#!/usr/bin/env python3
"""Collect project billing evidence without conflating missing data with zero cost."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

from openrouter_usage import fetch_key_usage, fetch_model_activity

ENV_NAMES = {'.env', '.env.local', '.env.production', '.env.prod', '.env.development',
             '.env.production.local', '.env.development.local'}
SKIP_DIRS = {'.git', 'node_modules', 'target', '.next', '.venv', 'graphify-out', '__pycache__'}
CREDENTIAL_NAMES = {
    'OPENROUTER_API_KEY', 'OPENROUTER_MANAGEMENT_KEY', 'OPENAI_API_KEY',
    'ANTHROPIC_API_KEY', 'DEEPGRAM_API_KEY', 'ASSEMBLYAI_API_KEY',
    'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN',
}
AWS_URL = 'https://docs.aws.amazon.com/cli/latest/reference/ce/get-cost-and-usage.html'
ROUTER_URL = 'https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key'


class SpendError(ValueError):
    pass


def number(value: Any) -> Decimal:
    if value is None or isinstance(value, bool):
        raise SpendError('Missing or invalid numeric value in billing evidence.')
    try:
        result = Decimal(str(value))
    except InvalidOperation:
        raise SpendError('Invalid numeric value in billing evidence.') from None
    if not result.is_finite():
        raise SpendError('Non-finite numeric value in billing evidence.')
    return result


def count(value: Any) -> int:
    result = number(value)
    if result < 0 or result != result.to_integral_value():
        raise SpendError('Request/token counts must be nonnegative integers.')
    return int(result)


def read_env(path: Path) -> dict[str, str]:
    """Read credential assignments as data; never source/execute an env file."""
    result = {}
    for line in path.read_text().splitlines():
        match = re.match(r'^\s*(?:export\s+)?([A-Z_]+)\s*=(.*)$', line)
        if not match or match[1] not in CREDENTIAL_NAMES:
            continue
        try:
            parts = shlex.split(match[2], comments=True)
        except ValueError:
            raise SpendError(f'Invalid credential quoting in {path}.') from None
        if len(parts) > 1:
            raise SpendError(f'Invalid credential assignment in {path}.')
        if parts and parts[0] and not parts[0].startswith('${'):
            result[match[1]] = parts[0]
    return result


def discover_env_files(roots: list[Path]) -> list[Path]:
    found = set()
    for root in roots:
        if not root.is_dir():
            raise SpendError(f'Env discovery directory does not exist: {root}')
        for directory, dirs, files in os.walk(root, followlinks=False):
            dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
            for name in ENV_NAMES.intersection(files):
                path = Path(directory) / name
                if not path.is_symlink():
                    found.add(path.resolve())
    return sorted(found)


def redact(text: str, secrets: list[str]) -> str:
    for secret in sorted(set(secrets), key=len, reverse=True):
        if secret:
            text = text.replace(secret, '[REDACTED]')
    text = re.sub(r'arn:aws:[^\s]+', '[AWS identity]', text)
    text = re.sub(r'sk-(?:or-v1-|proj-)?[\w-]{12,}', '[REDACTED]', text)
    return text[:1500]


def load_credentials(files: list[Path]) -> tuple[list[dict], list[str], list[str]]:
    sources = [(str(path), read_env(path)) for path in sorted(set(files))]
    sources.append(('process environment', {k: os.environ[k] for k in CREDENTIAL_NAMES if os.environ.get(k)}))
    keys: dict[str, dict] = {}
    management = set()
    detected = set()
    for source, values in sources:
        detected.update(values)
        token = values.get('OPENROUTER_API_KEY')
        if token:
            key_hash = hashlib.sha256(token.encode()).hexdigest()
            key = keys.setdefault(key_hash, {'token': token, 'id': key_hash[:12], 'sources': []})
            key['sources'].append(source)
        if values.get('OPENROUTER_MANAGEMENT_KEY'):
            management.add(values['OPENROUTER_MANAGEMENT_KEY'])
    return list(keys.values()), sorted(management), sorted(detected)


def collect_openrouter(keys: list[dict], management: list[str], timeout: float) -> dict:
    rows = []
    # A management key belongs to an account; do not try unrelated accounts' keys.
    manager = management[0] if len(management) == 1 else None
    for key in keys:
        row = {'key_id': key['id'], 'sources': key['sources'], 'status': 'unavailable'}
        try:
            raw = fetch_key_usage(key['token'], timeout=timeout)
            row.update(status='ok', lifetime_usd=str(number(raw.get('usage'))))
            for name in ('usage_daily', 'usage_weekly', 'usage_monthly', 'byok_usage'):
                row[name] = str(number(raw[name])) if raw.get(name) is not None else None
        except Exception as error:
            row['error'] = redact(str(error), [key['token'], *management])
        if manager:
            try:
                row['recent_activity'] = fetch_model_activity(manager, key['token'], timeout=timeout)
                row['activity_status'] = 'last_30_completed_utc_days'
            except Exception as error:
                row['activity_status'] = 'unavailable'
                row['activity_error'] = redact(str(error), [key['token'], *management])
        else:
            row['activity_status'] = 'management_key_missing' if not management else 'multiple_management_keys'
        rows.append(row)
    amounts = [number(row['lifetime_usd']) for row in rows if row['status'] == 'ok']
    return {
        'source': ROUTER_URL, 'keys': rows,
        'lifetime_usd': str(sum(amounts, Decimal(0))) if amounts else None,
        'coverage': 'Lifetime of discovered keys only; deleted, rotated, remote-only or shared keys need reconciliation.',
        'cost_basis': 'OpenRouter credits consumed; not credit purchases, purchase fees, taxes, or external BYOK bills.',
    }


def fetch_aws(start: str, end: str, env_file: Path | None, profile: str | None,
              cost_filter: dict | None, timeout: float) -> dict:
    env = os.environ.copy()
    if env_file:
        values = read_env(env_file)
        if not values.get('AWS_ACCESS_KEY_ID') or not values.get('AWS_SECRET_ACCESS_KEY'):
            raise SpendError('The AWS env file must contain both access key ID and secret access key.')
        for name in ('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN'):
            env.pop(name, None)
            if values.get(name):
                env[name] = values[name]
    elif profile:
        for name in ('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN'):
            env.pop(name, None)
    env.update(AWS_PAGER='', AWS_EC2_METADATA_DISABLED='true', AWS_MAX_ATTEMPTS='1')
    pages, seen, token = [], set(), None
    for _ in range(100):
        command = ['aws', 'ce', 'get-cost-and-usage', '--time-period', f'Start={start},End={end}',
                   '--granularity', 'MONTHLY', '--metrics', 'UnblendedCost', '--group-by',
                   'Type=DIMENSION,Key=SERVICE', 'Type=DIMENSION,Key=RECORD_TYPE',
                   '--region', 'us-east-1', '--output', 'json', '--no-paginate',
                   '--cli-connect-timeout', str(max(1, int(timeout))),
                   '--cli-read-timeout', str(max(1, int(timeout)))]
        if profile:
            command.extend(['--profile', profile])
        if cost_filter:
            command.extend(['--filter', json.dumps(cost_filter)])
        if token:
            command.extend(['--next-page-token', token])
        try:
            response = subprocess.run(command, env=env, capture_output=True, text=True, timeout=timeout + 5)
        except FileNotFoundError:
            raise SpendError('AWS CLI is not installed; use --aws-cost-file with an owner export.') from None
        except subprocess.TimeoutExpired:
            raise SpendError('AWS Cost Explorer request timed out.') from None
        if response.returncode:
            secrets = [env.get(k, '') for k in CREDENTIAL_NAMES]
            raise SpendError(redact(response.stderr.strip(), secrets))
        payload = json.loads(response.stdout)
        pages.extend(payload['ResultsByTime'])
        token = payload.get('NextPageToken')
        if not token:
            return {'ResultsByTime': pages}
        if token in seen:
            raise SpendError('AWS repeated a pagination token; refusing a partial total.')
        seen.add(token)
    raise SpendError('AWS pagination exceeded 100 pages; narrow the billing window.')


def aws_rows(payload: dict, start: str, end: str) -> list[dict]:
    if payload.get('NextPageToken'):
        raise SpendError('AWS export contains NextPageToken; export all pages before importing.')
    periods = payload.get('ResultsByTime')
    if not isinstance(periods, list) or not periods:
        raise SpendError('AWS response has no billing periods; cost is unknown.')
    definitions = payload.get('GroupDefinitions')
    if definitions is not None and [item.get('Key') for item in definitions] != ['SERVICE', 'RECORD_TYPE']:
        raise SpendError('AWS export must group by SERVICE then RECORD_TYPE.')
    combined: dict[tuple, Decimal] = defaultdict(Decimal)
    estimates = {}
    for period in periods:
        a, b = period['TimePeriod']['Start'], period['TimePeriod']['End']
        date.fromisoformat(a); date.fromisoformat(b)
        if a >= b or a < start or b > end:
            raise SpendError('AWS export periods must fit the requested start/end, without proration.')
        groups = period.get('Groups')
        if not isinstance(groups, list):
            raise SpendError('AWS export must group by SERVICE then RECORD_TYPE.')
        for group in groups:
            if len(group['Keys']) != 2:
                raise SpendError('AWS export must group by SERVICE then RECORD_TYPE.')
            service, record = group['Keys']
            metric = group['Metrics']['UnblendedCost']
            unit = metric['Unit']
            if unit != 'USD':
                raise SpendError('AWS export must use USD; currencies are never silently converted.')
            key = (a, b, service, record)
            combined[key] += number(metric['Amount'])
            estimates[key] = estimates.get(key, False) or bool(period.get('Estimated'))
        if not groups:
            total = period.get('Total', {}).get('UnblendedCost', {}).get('Amount')
            if total is not None and number(total) != 0:
                raise SpendError('AWS export has charges without service groups; refusing a zero total.')
            # No charge is different from a missing source.
            combined[(a, b, '(no charges)', '(none)')] += Decimal(0)
            estimates[(a, b, '(no charges)', '(none)')] = bool(period.get('Estimated'))
    return [dict(start=k[0], end_exclusive=k[1], service=k[2], record_type=k[3],
                 amount_usd=str(v), estimated=estimates.get(k, False)) for k, v in sorted(combined.items())]


def aws_cost_breakdown(rows: list[dict]) -> dict:
    """Keep charges visible when credits offset the bill; preserve signed corrections."""
    def amounts(items):
        charges = credits = refunds = Decimal(0)
        for row in items:
            amount = number(row['amount_usd'])
            if row['record_type'].lower() == 'credit':
                credits += amount
            elif row['record_type'].lower() == 'refund':
                refunds += amount
            else:
                charges += amount
        return dict(charges_before_credits_usd=str(charges), credits_usd=str(credits),
                    refunds_usd=str(refunds), net_usd=str(charges + credits + refunds))
    periods = defaultdict(list)
    for row in rows:
        periods[(row['start'], row['end_exclusive'])].append(row)
    monthly = [dict(start=a, end_exclusive=b, estimated=any(r['estimated'] for r in items),
                    **amounts(items)) for (a, b), items in sorted(periods.items())]
    return dict(**amounts(rows), monthly=monthly)


def has_grouped_aws_charges(rows: list[dict]) -> bool:
    # Empty groups from a nonmatching/unactivated tag do not establish free hosting.
    return any(row['record_type'] != '(none)' for row in rows)


def load_calls(paths: list[Path], start: str, end: str) -> list[dict]:
    """Import normalized request exports, deduplicating provider + request ID."""
    required = {'provider', 'request_id', 'timestamp', 'model', 'cost_usd', 'prompt_tokens', 'completion_tokens'}
    calls = {}
    for path in paths:
        with path.open(newline='') as source:
            reader = csv.DictReader(source)
            if not required.issubset(reader.fieldnames or []):
                raise SpendError(f'Call CSV lacks required columns: {path}')
            for row in reader:
                stamp = datetime.fromisoformat(row['timestamp'].replace('Z', '+00:00'))
                if stamp.tzinfo is None:
                    raise SpendError('Call timestamps must specify a timezone.')
                day = stamp.astimezone(timezone.utc).date().isoformat()
                if not start <= day < end:
                    continue
                provider = row['provider'].strip().lower()
                request_id = row['request_id'].strip()
                if not provider or not request_id:
                    raise SpendError('Call exports require nonempty provider and request_id.')
                cost = number(row['cost_usd'])
                if cost < 0:
                    raise SpendError('Call costs cannot be negative; put refunds in the ledger.')
                item = dict(provider=provider, request_id=request_id,
                            timestamp=stamp.astimezone(timezone.utc).isoformat(), model=row['model'],
                            cost_usd=str(cost), prompt_tokens=count(row['prompt_tokens']),
                            completion_tokens=count(row['completion_tokens']))
                key = (provider, request_id)
                if key in calls and calls[key] != item:
                    raise SpendError('Conflicting duplicate request in call exports.')
                calls[key] = item
    return sorted(calls.values(), key=lambda row: (row['timestamp'], row['provider'], row['request_id']))


def load_ledger(paths: list[Path], start: str, end: str) -> list[dict]:
    """Read project allocations and payment evidence with explicit cost basis."""
    required = {'provider', 'entry_id', 'date', 'service', 'amount', 'currency', 'basis', 'source'}
    entries = {}
    for path in paths:
        with path.open(newline='') as source:
            reader = csv.DictReader(source)
            if not required.issubset(reader.fieldnames or []):
                raise SpendError(f'Ledger CSV lacks required columns: {path}')
            for raw in reader:
                row = {k: raw[k].strip() for k in required}
                day = date.fromisoformat(row['date']).isoformat()
                if not start <= day < end:
                    continue
                row['provider'] = row['provider'].lower()
                if any(not row[k] for k in required):
                    raise SpendError('Ledger evidence fields must not be blank.')
                if row['basis'] not in {'cost', 'payment'}:
                    raise SpendError('Ledger basis must be cost or payment.')
                row['currency'] = row['currency'].upper()
                if not re.fullmatch('[A-Z]{3}', row['currency']):
                    raise SpendError('Ledger currency must be a three-letter code.')
                row['amount'] = str(number(row['amount']))
                key = (row['provider'], row['entry_id'])
                if key in entries and entries[key] != row:
                    raise SpendError('Conflicting duplicate ledger entry.')
                entries[key] = row
    return sorted(entries.values(), key=lambda row: (row['date'], row['provider'], row['entry_id']))


def call_summary(calls: list[dict]) -> list[dict]:
    groups = {}
    for call in calls:
        key = (call['provider'], call['model'])
        row = groups.setdefault(key, dict(provider=key[0], model=key[1], requests=0,
                                         prompt_tokens=0, completion_tokens=0, cost_usd=Decimal(0)))
        row['requests'] += 1
        row['prompt_tokens'] += call['prompt_tokens']
        row['completion_tokens'] += call['completion_tokens']
        row['cost_usd'] += number(call['cost_usd'])
    return [{**row, 'cost_usd': str(row['cost_usd'])} for _, row in sorted(groups.items())]


def reconcile(router: dict, aws: dict, calls: list[dict], ledger: list[dict]) -> dict:
    costs, payments = defaultdict(Decimal), defaultdict(Decimal)
    cost_sources = set()
    # Lifetime key counters do not have the report's date grain; do not add them
    # to a period-specific total or count the same calls twice.
    for row in calls:
        costs['USD'] += number(row['cost_usd'])
        cost_sources.add(row['provider'])
    if aws.get('status') == 'ok' and aws['project_scoped']:
        costs['USD'] += sum((number(row['amount_usd']) for row in aws['rows']), Decimal(0))
        cost_sources.add('aws')
    for row in ledger:
        if row['basis'] == 'payment':
            payments[row['currency']] += number(row['amount'])
        else:
            if row['provider'] in cost_sources:
                raise SpendError(f"Ledger cost overlaps collected {row['provider']} costs; use one source per provider.")
            costs[row['currency']] += number(row['amount'])
    return {
        'project_total': None, 'coverage_status': 'incomplete',
        'openrouter_lifetime_usd': router['lifetime_usd'],
        'documented_costs_in_period': {k: str(v) for k, v in sorted(costs.items())},
        'documented_payments_in_period': {k: str(v) for k, v in sorted(payments.items())},
        'note': 'Partial evidence only. Costs and cash payments are separate, never added together. '
                'OpenRouter lifetime counters are separate from period costs; API call exports are never added on top of them.',
    }


def markdown(report: dict) -> str:
    totals = report['totals']
    lines = ['# Project API usage and spending', '',
             f"As of: {report['as_of_utc']}", '',
             f"Billing/export window (UTC): {report['start']} to {report['end_exclusive']} (end exclusive).", '',
             '**Full project total: unavailable — coverage is incomplete.**', '',
             f"OpenRouter lifetime usage for discovered keys: {money(totals['openrouter_lifetime_usd'])}.", '',
             '| Source | Coverage | Result |', '|---|---|---|']
    for row in report['openrouter']['keys']:
        lines.append(f"| OpenRouter key `{row['key_id']}` | Key lifetime | {money(row.get('lifetime_usd'))} |")
    aws = report['aws']
    lines.append(f"| AWS Cost Explorer | {aws['scope']} | {money(aws.get('net_usd')) if aws['status'] == 'ok' else 'Unavailable'} |")
    if aws['status'] == 'ok' and aws.get('cost_breakdown'):
        breakdown = aws['cost_breakdown']
        lines.extend(['', 'AWS accrued charges and offsets (not cash payments):', '',
                      f"- Charges before credits/refunds: {money(breakdown['charges_before_credits_usd'])}",
                      f"- Credits: {money(breakdown['credits_usd'])}",
                      f"- Refunds: {money(breakdown['refunds_usd'])}",
                      f"- Net after credits/refunds: {money(breakdown['net_usd'])}", '',
                      '| Month start | Charges before credits/refunds (USD) | Credits (USD) | Refunds (USD) | Net (USD) | Estimated |',
                      '|---|---:|---:|---:|---:|---|'])
        for row in breakdown['monthly']:
            lines.append(f"| {row['start']} | {row['charges_before_credits_usd']} | {row['credits_usd']} | {row['refunds_usd']} | {row['net_usd']} | {row['estimated']} |")
    lines.extend(['', '## Documented costs and payments', '', totals['note'], ''])
    for title, field in [('Costs in report window', 'documented_costs_in_period'),
                         ('Cash payments in report window', 'documented_payments_in_period')]:
        amounts = totals[field]
        lines.append(f"- {title}: " + (', '.join(f'{k} {v}' for k, v in amounts.items()) if amounts else 'unavailable'))
    lines.extend(['', '## Coverage gaps', ''])
    lines.extend(f'- {gap}' for gap in report['gaps'])
    lines.extend(['', '## Imported API calls', '',
                  'Exports are deduplicated by provider and request ID. Their coverage must be confirmed with the provider.', '',
                  '| Provider | Model | Requests | Prompt tokens | Completion tokens | USD |',
                  '|---|---|---:|---:|---:|---:|'])
    for row in report['calls_by_model']:
        lines.append(f"| {row['provider']} | {row['model']} | {row['requests']} | {row['prompt_tokens']} | {row['completion_tokens']} | {row['cost_usd']} |")
    if not report['calls_by_model']:
        lines.append('| — | No historical call export supplied | Unknown | Unknown | Unknown | Unknown |')
    lines.extend(['', '## AWS monthly charges', '',
                  'UnblendedCost including signed credits/refunds/tax record types; current periods may be estimated. '
                  'Shared-account figures are excluded from project costs until allocated.', '',
                  '| Start | End exclusive | Service | Charge type | USD | Estimated |',
                  '|---|---|---|---|---:|---|'])
    for row in aws.get('rows', []):
        if aws['status'] == 'no_matching_billing_data':
            continue
        service = str(row['service']).replace('|', '/')
        lines.append(f"| {row['start']} | {row['end_exclusive']} | {service} | {row['record_type']} | {row['amount_usd']} | {row['estimated']} |")
    if aws.get('error'):
        lines.extend(['', 'AWS access result: ' + aws['error']])
    lines.extend(['', '## Sources', '', f'- [OpenRouter key usage]({ROUTER_URL})',
                  f'- [AWS Cost Explorer]({AWS_URL})', '',
                  'Provider response numbers are billing evidence, not estimates from current model prices. '
                  'Vexa hosted on EC2 belongs within AWS costs; do not count its hosting twice.', ''])
    return '\n'.join(lines)


def money(value: str | None) -> str:
    return f'${number(value):,.8f}' if value is not None else 'unavailable'


def write_outputs(report: dict, directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    outputs = {'project-spend.json': json.dumps(report, indent=2) + '\n',
               'project-spend.md': markdown(report)}
    for name, content in outputs.items():
        path = directory / name
        path.write_text(content)
        path.chmod(0o600)
    for name, rows, fields in [
        ('aws-monthly.csv', report['aws'].get('rows', []),
         ['start', 'end_exclusive', 'service', 'record_type', 'amount_usd', 'estimated']),
        ('calls-by-model.csv', report['calls_by_model'],
         ['provider', 'model', 'requests', 'prompt_tokens', 'completion_tokens', 'cost_usd']),
    ]:
        path = directory / name
        with path.open('w', newline='') as out:
            writer = csv.DictWriter(out, fieldnames=fields)
            writer.writeheader(); writer.writerows(rows)
        path.chmod(0o600)


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--start', required=True, help='first billing/export date, inclusive YYYY-MM-DD')
    parser.add_argument('--end', default=(datetime.now(timezone.utc).date() + timedelta(days=1)).isoformat(),
                        help='billing/export end date, exclusive (default: tomorrow UTC)')
    parser.add_argument('--env-file', type=Path, action='append', default=[], help='project credentials; may repeat')
    parser.add_argument('--discover-env-root', type=Path, action='append', default=[], help='scan local project env files; may repeat')
    parser.add_argument('--aws-env-file', type=Path, help='AWS credentials; never printed or written to reports')
    parser.add_argument('--aws-profile')
    parser.add_argument('--aws-filter', type=Path, help='Cost Explorer project filter JSON')
    parser.add_argument('--aws-dedicated-account', action='store_true', help='assert the entire AWS account belongs to this project')
    parser.add_argument('--aws-cost-file', type=Path, help='offline JSON export of get-cost-and-usage (same grouping)')
    parser.add_argument('--skip-aws', action='store_true')
    parser.add_argument('--skip-openrouter', action='store_true')
    parser.add_argument('--calls-csv', type=Path, action='append', default=[], help='normalized historical API call exports')
    parser.add_argument('--ledger-csv', type=Path, action='append', default=[], help='other project costs/payments with evidence')
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--timeout', type=float, default=15)
    args = parser.parse_args(argv)
    try:
        if not all(re.fullmatch(r'\d{4}-\d{2}-\d{2}', value) for value in (args.start, args.end)):
            parser.error('dates must use YYYY-MM-DD')
        if date.fromisoformat(args.start) >= date.fromisoformat(args.end):
            parser.error('--start must precede --end')
    except ValueError:
        parser.error('dates must use YYYY-MM-DD')
    if not 0 < args.timeout <= 55:
        parser.error('--timeout must be greater than zero and no more than 55 seconds')
    if args.aws_profile and args.aws_env_file:
        parser.error('choose --aws-profile or --aws-env-file')
    if args.skip_aws and args.aws_cost_file:
        parser.error('--skip-aws cannot be combined with --aws-cost-file')
    if args.aws_cost_file and args.aws_filter:
        parser.error('--aws-filter applies only to live queries, not imported AWS exports')
    return args


def build_report(args: argparse.Namespace) -> dict:
    files = args.env_file + discover_env_files(args.discover_env_root)
    keys, management, detected = load_credentials(files)
    router = collect_openrouter([] if args.skip_openrouter else keys, management, args.timeout)
    if args.aws_filter:
        cost_filter = json.loads(args.aws_filter.read_text())
        if not isinstance(cost_filter, dict) or not cost_filter:
            raise SpendError('--aws-filter must contain a nonempty Cost Explorer expression.')
    else:
        cost_filter = None
    aws = dict(status='unavailable', project_scoped=bool(cost_filter or args.aws_dedicated_account),
               scope='project filter' if cost_filter else ('dedicated project account' if args.aws_dedicated_account else 'account-wide, attribution unverified'),
               source=AWS_URL)
    if not args.skip_aws:
        try:
            payload = (json.loads(args.aws_cost_file.read_text()) if args.aws_cost_file else
                       fetch_aws(args.start, args.end, args.aws_env_file, args.aws_profile, cost_filter, args.timeout))
            aws['rows'] = aws_rows(payload, args.start, args.end)
            aws.update(status='ok', net_usd=str(sum((number(row['amount_usd']) for row in aws['rows']), Decimal(0))))
            aws['cost_breakdown'] = aws_cost_breakdown(aws['rows'])
            if cost_filter and not has_grouped_aws_charges(aws['rows']):
                aws.update(status='no_matching_billing_data', net_usd=None, cost_breakdown=None,
                           error='The billing filter returned no service charge records. Verify historical tag activation and coverage; scoped spending remains unknown.')
        except (OSError, ValueError, KeyError, TypeError) as error:
            aws['error'] = redact(str(error), [])
    else:
        aws['error'] = 'AWS collection skipped.'
    calls = load_calls(args.calls_csv, args.start, args.end)
    ledger = load_ledger(args.ledger_csv, args.start, args.end)
    gaps = [
        'No claim of a complete project total: remote/deleted keys, shared accounts and external invoices require reconciliation.',
        'OpenRouter lifetime counters have no date breakdown and are not added to period costs. Historical request counts need call exports or management analytics.',
        'OpenRouter credit purchases/fees/taxes are cash-payment evidence, separate from API credits consumed.',
        'Firebase/Google Cloud, GitHub Actions/plans, domains outside AWS, and developer subscriptions require project-allocated invoice/usage evidence; missing evidence is not zero.',
        'Detailed API-call exports do not reconstruct every HTTP request to the application or AWS. No complete historical request ledger was present in this tool branch.',
    ]
    if aws['status'] != 'ok':
        gaps.append('AWS billing unavailable: ' + aws.get('error', 'unknown error'))
    elif not aws['project_scoped']:
        gaps.append('AWS account-wide costs are shown separately and excluded from project costs; use a project billing filter or verified allocation.')
    if not router['keys']:
        gaps.append('No OpenRouter keys were queried.')
    for row in router['keys']:
        if row['status'] != 'ok':
            gaps.append(f"OpenRouter key {row['key_id']} unavailable: {row.get('error', 'unknown error')}")
        if row['activity_status'] != 'last_30_completed_utc_days':
            gaps.append(f"OpenRouter key {row['key_id']} model/request history: {row['activity_status']}.")
    report = dict(schema_version=1, as_of_utc=datetime.now(timezone.utc).isoformat(),
                  start=args.start, end_exclusive=args.end, openrouter=router, aws=aws,
                  detected_credential_types=detected, calls_by_model=call_summary(calls),
                  imported_call_count=len(calls), ledger=ledger, gaps=gaps)
    if args.aws_cost_file:
        report['aws']['imported_from'] = str(args.aws_cost_file.resolve())
    if cost_filter:
        report['aws']['filter'] = cost_filter
    report['input_files'] = {
        'call_exports': [str(path.resolve()) for path in args.calls_csv],
        'ledgers': [str(path.resolve()) for path in args.ledger_csv],
    }
    report['totals'] = reconcile(router, aws, calls, ledger)
    # Allowlist + redaction across the whole output, including upstream messages.
    secrets = [key['token'] for key in keys] + management
    for path in files + ([args.aws_env_file] if args.aws_env_file else []):
        secrets.extend(read_env(path).values())
    return json.loads(redact_report(json.dumps(report), secrets))


def redact_report(serialized: str, secrets: list[str]) -> str:
    # Unlike error messages, report content must not be truncated.
    for secret in sorted(set(secrets), key=len, reverse=True):
        if secret:
            serialized = serialized.replace(secret, '[REDACTED]')
    return serialized


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        report = build_report(args)
        write_outputs(report, args.output_dir)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f'Could not build spending report: {type(error).__name__}. Check input files/schema; no credentials are printed.', file=sys.stderr)
        return 1
    print(markdown(report))
    print(f'Report files: {args.output_dir.resolve()}')
    # Incomplete coverage remains machine-visible even when some sources succeed.
    return 3


if __name__ == '__main__':
    raise SystemExit(main())
