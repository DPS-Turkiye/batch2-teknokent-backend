#!/usr/bin/env python3
"""Report Vexa-specific hosting, transcription and meeting-AI spending evidence."""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

from project_spend import (
    AWS_URL, SpendError, aws_rows, fetch_aws, load_calls, load_ledger,
    number, parse_args as parse_project_args, read_env, redact_report,
)

COMPONENTS = ('hosting', 'transcription', 'reporting')


def load_vexa_ledger(paths, start, end):
    entries = load_ledger(paths, start, end)
    components = {}
    for path in paths:
        with path.open(newline='') as source:
            reader = csv.DictReader(source)
            if 'component' not in (reader.fieldnames or []):
                raise SpendError('Vexa ledgers require a component column.')
            for row in reader:
                if not start <= row['date'].strip() < end:
                    continue
                component = row['component'].strip()
                if component not in COMPONENTS:
                    raise SpendError('Component must be hosting, transcription or reporting.')
                key = (row['provider'].strip().lower(), row['entry_id'].strip())
                if key in components and components[key] != component:
                    raise SpendError('Duplicate invoice has conflicting Vexa components.')
                components[key] = component
    return [dict(row, component=components[(row['provider'], row['entry_id'])]) for row in entries]


def parse_time(value, assume_utc):
    if not value:
        return None
    if not isinstance(value, str):
        raise SpendError('Meeting timestamps must be ISO 8601 strings.')
    stamp = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if stamp.tzinfo is None:
        if not assume_utc:
            raise SpendError('Naive Vexa timestamps require --naive-times-are-utc after verifying the source timezone.')
        stamp = stamp.replace(tzinfo=timezone.utc)
    return stamp.astimezone(timezone.utc)


def meeting_stats(paths, start, end, assume_utc=False):
    lower = datetime.fromisoformat(start).replace(tzinfo=timezone.utc)
    upper = datetime.fromisoformat(end).replace(tzinfo=timezone.utc)
    meetings = {}
    for path in paths:
        payload = json.loads(path.read_text())
        if isinstance(payload, dict):
            if payload.get('has_more') or payload.get('next_cursor') or payload.get('next_page_token'):
                raise SpendError('Meeting export is paginated; supply a complete flattened export.')
            payload = payload.get('meetings')
        if not isinstance(payload, list):
            raise SpendError('Meeting export must be a list or a meetings object.')
        for raw in payload:
            if not isinstance(raw, dict) or raw.get('id') is None:
                raise SpendError('Every meeting needs its stable internal id, not only a recurring Meet link.')
            # Never retain transcript, names, participants, native meeting URLs or arbitrary data blobs.
            row = {k: raw.get(k) for k in ('id', 'status', 'start_time', 'end_time', 'created_at')}
            key = str(row['id'])
            if key in meetings and meetings[key] != row:
                raise SpendError('Conflicting duplicate meeting snapshots; use one complete export per deployment/account.')
            meetings[key] = row
    statuses = Counter()
    seconds = Decimal(0)
    timed = missing = undated = 0
    for row in meetings.values():
        a = parse_time(row['start_time'], assume_utc)
        b = parse_time(row['end_time'], assume_utc)
        created = parse_time(row['created_at'], assume_utc)
        if a and b and b < a:
            raise SpendError('Meeting ends before it starts.')
        if a and b:
            duration = min(b, upper) - max(a, lower)
            if a == b:
                if not lower <= a < upper:
                    continue
            elif duration.total_seconds() <= 0:
                continue
            statuses[row['status'] or 'unknown'] += 1
            seconds += number(str(duration.total_seconds()))
            timed += 1
        else:
            anchor = a or created
            if anchor is None:
                undated += 1
                continue
            if anchor >= upper or (b is not None and b <= lower):
                continue
            if anchor < lower and b is not None:
                continue
            # Ongoing sessions that started before the window can still consume
            # resources in it, but cannot be assigned a final duration.
            if anchor < lower and row['status'] not in {'active', 'joining', 'awaiting_admission'}:
                continue
            statuses[row['status'] or 'unknown'] += 1
            missing += 1
    return {
        'status': 'imported' if paths else 'unavailable',
        'exported_unique_records': len(meetings) if paths else None,
        'observed_meetings_in_window': sum(statuses.values()) if paths else None,
        'statuses': dict(sorted(statuses.items())),
        'timed_sessions': timed if paths else None,
        'observed_session_seconds': str(seconds) if paths else None,
        'observed_session_minutes': str(seconds / 60) if paths else None,
        'records_without_final_duration': missing if paths else None,
        'records_without_usable_dates': undated if paths else None,
        'naive_timestamps_assumed_utc': assume_utc,
        'basis': 'Observed meeting intervals, clipped to the window. Not provider-billed audio minutes or STT requests.',
    }


def summarize(aws, calls, ledger):
    costs = {component: defaultdict(Decimal) for component in COMPONENTS}
    payments = {component: defaultdict(Decimal) for component in COMPONENTS}
    sources = set()
    if aws['status'] == 'ok':
        costs['hosting']['USD'] += sum((number(row['amount_usd']) for row in aws['rows']), Decimal(0))
        sources.add(('hosting', 'aws'))
    for row in calls:
        costs['reporting']['USD'] += number(row['cost_usd'])
        sources.add(('reporting', row['provider']))
    for row in ledger:
        component = row['component']
        if row['basis'] == 'cost' and (component, row['provider']) in sources:
            raise SpendError('Vexa ledger overlaps a collected cost source; reconcile that component/provider in one source.')
        target = payments if row['basis'] == 'payment' else costs
        target[component][row['currency']] += number(row['amount'])
    subtotal = defaultdict(Decimal)
    for amounts in costs.values():
        for currency, amount in amounts.items():
            subtotal[currency] += amount
    convert = lambda data: {k: {c: str(v) for c, v in sorted(amounts.items())} for k, amounts in data.items()}
    return dict(full_vexa_total=None, coverage='incomplete',
                costs_by_component=convert(costs), payments_by_component=convert(payments),
                documented_cost_subtotal={k: str(v) for k, v in sorted(subtotal.items())},
                reporting_requests_in_exports=len(calls) if calls else None,
                rollup='Vexa is a project subset: do not add these costs again to the project spending report.')


def render(report):
    total = report['totals']
    lines = ['# Vexa bot spending', '', f"As of: {report['as_of_utc']}", '',
             f"Window (UTC): {report['start']} to {report['end_exclusive']} (end exclusive).", '',
             '**Full lifetime Vexa spend: unavailable — billing coverage is incomplete.**', '',
             '| Component | Documented cost | Documented cash payments |', '|---|---|---|']
    for component in COMPONENTS:
        fmt = lambda values: ', '.join(f'{currency} {amount}' for currency, amount in values.items()) or 'Unknown'
        lines.append(f"| {component.title()} | {fmt(total['costs_by_component'][component])} | {fmt(total['payments_by_component'][component])} |")
    lines.extend(['', 'Documented cost subtotal (partial): ' + (fmt(total['documented_cost_subtotal'])), '',
                  total['rollup'], '', '## Bot usage', ''])
    stats = report['meeting_usage']
    lines.extend([f"- Observed meetings in window: {stats['observed_meetings_in_window'] if stats['status'] == 'imported' else 'Unknown'}",
                  f"- Observed session minutes: {stats['observed_session_minutes'] if stats['status'] == 'imported' else 'Unknown'}",
                  '- ' + stats['basis'], '', '## Billing coverage', ''])
    lines.extend('- ' + gap for gap in report['gaps'])
    if report.get('resources'):
        lines.extend(['', '## Resource inventory', ''])
        for item in report['resources']:
            lines.append(f"- `{item['instance_id']}` ({item['name']}): {item['instance_type']}, {item['state']}; instance launch time {item['launch_time']}.")
            lines.append('- Attached volume IDs: ' + ', '.join(f'`{v}`' for v in item['volumes']))
        lines.append('Launch time is inventory metadata; it does not prove uninterrupted uptime or lifetime cost.')
    if report['aws'].get('error'):
        lines.extend(['', 'AWS result: ' + report['aws']['error']])
    if report['aws'].get('rows'):
        lines.extend(['', '## AWS charges by month and service', '',
                      '| Start | End exclusive | Service | Charge type | USD | Estimated |',
                      '|---|---|---|---|---:|---|'])
        for row in report['aws']['rows']:
            service = str(row['service']).replace('|', '/')
            lines.append(f"| {row['start']} | {row['end_exclusive']} | {service} | {row['record_type']} | {row['amount_usd']} | {row['estimated']} |")
    lines.extend(['', '## Sources', '',
                  '- [Vexa speech-to-text deployment options](https://docs.vexa.ai/how-to/custom-stt)',
                  '- [AWS cost-allocation tags](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/custom-tags.html)',
                  f'- [AWS Cost Explorer]({AWS_URL})', ''])
    return '\n'.join(lines)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--start', required=True)
    parser.add_argument('--end')
    parser.add_argument('--aws-env-file', type=Path)
    parser.add_argument('--aws-profile')
    parser.add_argument('--aws-filter', type=Path, help='verified Vexa-only Cost Explorer filter')
    parser.add_argument('--aws-cost-file', type=Path)
    parser.add_argument('--aws-export-is-vexa-only', action='store_true', help='assert imported AWS file is already Vexa-scoped')
    parser.add_argument('--skip-aws', action='store_true')
    parser.add_argument('--ledger-csv', action='append', type=Path, default=[])
    parser.add_argument('--report-calls-csv', action='append', type=Path, default=[], help='Vexa-related report/cleanup calls only')
    parser.add_argument('--meetings-file', action='append', type=Path, default=[])
    parser.add_argument('--naive-times-are-utc', action='store_true')
    parser.add_argument('--resource-inventory', type=Path)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--timeout', type=float, default=15)
    args = parser.parse_args(argv)
    # Reuse the base collector's date, timeout and credential-source validation.
    base = ['--start', args.start, '--timeout', str(args.timeout), '--output-dir', str(args.output_dir)]
    if args.end:
        base.extend(['--end', args.end])
    if args.aws_env_file:
        base.extend(['--aws-env-file', str(args.aws_env_file)])
    if args.aws_profile:
        base.extend(['--aws-profile', args.aws_profile])
    validated = parse_project_args(base)
    args.end = validated.end
    if args.aws_cost_file and (args.aws_filter or args.skip_aws):
        parser.error('AWS file cannot be combined with --aws-filter or --skip-aws')
    if args.aws_cost_file and not args.aws_export_is_vexa_only:
        parser.error('Imported AWS costs require --aws-export-is-vexa-only after verifying attribution')
    return args


def build_report(args):
    aws = dict(status='unavailable', scope='Vexa hosting only; no account-wide fallback')
    if args.skip_aws:
        aws['error'] = 'AWS collection skipped.'
    elif args.aws_cost_file:
        try:
            aws['rows'] = aws_rows(json.loads(args.aws_cost_file.read_text()), args.start, args.end)
            aws.update(status='ok', source=str(args.aws_cost_file.resolve()))
        except (ValueError, KeyError, TypeError) as error:
            raise SpendError('Invalid/incomplete Vexa AWS export.') from error
    elif args.aws_filter:
        cost_filter = json.loads(args.aws_filter.read_text())
        if not isinstance(cost_filter, dict) or not cost_filter:
            raise SpendError('A nonempty Vexa-only cost filter is required.')
        aws['filter'] = cost_filter
        try:
            raw = fetch_aws(args.start, args.end, args.aws_env_file, args.aws_profile, cost_filter, args.timeout)
            aws['rows'] = aws_rows(raw, args.start, args.end)
            aws.update(status='ok', source=AWS_URL)
        except (OSError, ValueError, KeyError, TypeError) as error:
            aws['error'] = str(error)
    else:
        aws['error'] = 'A Vexa-only AWS filter or verified scoped export is required; account totals are not queried.'
    ledger = load_vexa_ledger(args.ledger_csv, args.start, args.end)
    calls = load_calls(args.report_calls_csv, args.start, args.end)
    usage = meeting_stats(args.meetings_file, args.start, args.end, args.naive_times_are_utc)
    resources = []
    if args.resource_inventory:
        inventory = json.loads(args.resource_inventory.read_text())
        if inventory.get('status') != 'ok':
            raise SpendError('Resource inventory is unavailable.')
        for item in inventory['instances']:
            resources.append(dict(instance_id=item['InstanceId'], name=item.get('Name'),
                                  instance_type=item['InstanceType'], launch_time=item['LaunchTime'],
                                  state=item['State'], volumes=item.get('Volumes', [])))
    report = dict(schema_version=1, as_of_utc=datetime.now(timezone.utc).isoformat(),
                  start=args.start, end_exclusive=args.end, aws=aws, resources=resources,
                  meeting_usage=usage, ledger=ledger, totals=summarize(aws, calls, ledger),
                  input_files=dict(ledgers=[str(p.resolve()) for p in args.ledger_csv],
                                   reporting_calls=[str(p.resolve()) for p in args.report_calls_csv],
                                   meeting_exports=[str(p.resolve()) for p in args.meetings_file],
                                   resource_inventory=str(args.resource_inventory.resolve()) if args.resource_inventory else None),
                  gaps=[
                      'AWS billing must be scoped to Vexa. A tag on the instance does not prove it is active for historical billing or applied to EBS, public IPs, snapshots, logs and network charges.',
                      'Current inventory excludes terminated/replaced hosts and earlier non-AWS hosting; no lifetime amount is inferred from launch time or current public prices.',
                      'Hosted transcription requires actual Vexa/provider usage bills or invoices. A self-hosted bot can use separately billed remote STT; an OpenAI-compatible URL/model name does not identify the billing provider.',
                      'Reporting/cleanup costs require Vexa-specific call exports or invoice allocations; the whole OpenRouter key balance also includes profile analysis and matching.',
                      'Bot session duration is not billable audio duration: retries, overlapping audio windows, failed joins and idle hosting can differ. No dollar amount is calculated from meeting minutes.',
                      'Invoices/payment receipts and deleted/other-account meeting records remain unverified. Missing evidence is unknown, not zero.',
                  ])
    if aws['status'] != 'ok':
        report['gaps'].append('AWS hosting costs unavailable: ' + aws['error'])
    secrets = [value for name, value in os.environ.items() if name in {'AWS_ACCESS_KEY_ID','AWS_SECRET_ACCESS_KEY','AWS_SESSION_TOKEN'}]
    if args.aws_env_file:
        secrets.extend(read_env(args.aws_env_file).values())
    return json.loads(redact_report(json.dumps(report), secrets))


def write_report(report, directory):
    directory.mkdir(parents=True, exist_ok=True)
    for name, content in [('vexa-spend.json', json.dumps(report, indent=2) + '\n'), ('vexa-spend.md', render(report))]:
        path = directory / name
        path.write_text(content)
        path.chmod(0o600)
    path = directory / 'vexa-cost-components.csv'
    with path.open('w', newline='') as output:
        writer = csv.writer(output)
        writer.writerow(['component', 'basis', 'currency', 'amount'])
        for basis, key in [('cost','costs_by_component'), ('payment','payments_by_component')]:
            for component, amounts in report['totals'][key].items():
                for currency, amount in amounts.items():
                    writer.writerow([component, basis, currency, amount])
    path.chmod(0o600)


def main(argv=None):
    args = parse_args(argv)
    try:
        report = build_report(args)
        write_report(report, args.output_dir)
    except (OSError, ValueError, KeyError, TypeError):
        print('Could not build Vexa report. Check input schema, dates and Vexa-only attribution.', file=sys.stderr)
        return 1
    print(render(report))
    print(f'Report files: {args.output_dir.resolve()}')
    return 3  # Successful report, but incomplete lifetime billing evidence.


if __name__ == '__main__':
    raise SystemExit(main())
