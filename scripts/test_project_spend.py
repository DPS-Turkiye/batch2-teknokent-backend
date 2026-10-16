import argparse
import csv
import io
import json
import os
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

import project_spend as spend
from openrouter_usage import OpenRouterUsageError


class ProjectSpendTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def csv_file(self, name, rows):
        path = self.root / name
        with path.open('w', newline='') as out:
            writer = csv.DictWriter(out, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
        return path

    def call(self, **overrides):
        return dict(provider='openrouter', request_id='gen-1', timestamp='2026-07-25T00:00:00Z',
                    model='openai/gpt-4o-mini', cost_usd='0.10', prompt_tokens='100',
                    completion_tokens='20', **overrides)

    def ledger(self, **overrides):
        row = dict(provider='github', entry_id='invoice-1', date='2026-08-01', service='Actions',
                   amount='2.5', currency='USD', basis='cost', source='invoice.pdf')
        row.update(overrides)
        return row

    def aws_payload(self, groups=None, **overrides):
        if groups is None:
            groups = [(['Amazon EC2', 'Usage'], '12.10'), (['AWS Amplify', 'Usage'], '3.20'),
                      (['Amazon EC2', 'Credit'], '-5.00'), (['Tax', 'Tax'], '1.03')]
        period = dict(TimePeriod=dict(Start='2026-07-01', End='2026-08-01'), Estimated=False,
                      Groups=[dict(Keys=keys, Metrics={'UnblendedCost': dict(Amount=amount, Unit='USD')})
                              for keys, amount in groups])
        return dict(ResultsByTime=[period], **overrides)

    def test_numbers_keep_exact_cost_precision_and_reject_unknown(self):
        self.assertEqual(Decimal('0.3'), spend.number('0.1') + spend.number('0.2'))
        for value in [None, True, 'NaN', 'Infinity', 'oops']:
            with self.subTest(value=value), self.assertRaises(spend.SpendError):
                spend.number(value)
        for value in [-1, '1.5']:
            with self.assertRaises(spend.SpendError):
                spend.count(value)

    def test_env_is_not_executed_and_duplicate_keys_are_counted_once(self):
        marker = self.root / 'should-not-exist'
        a, b = self.root / 'a.env', self.root / 'b.env'
        a.write_text(f'OPENROUTER_API_KEY="shared-key" # comment\nUNRELATED=$(touch {marker})\n')
        b.write_text("export OPENROUTER_API_KEY='shared-key'\n")
        with patch.dict(os.environ, {}, clear=True):
            keys, management, detected = spend.load_credentials([a, b])
        self.assertEqual(1, len(keys))
        self.assertEqual(2, len(keys[0]['sources']))
        self.assertFalse(marker.exists())
        self.assertEqual([], management)
        self.assertEqual(['OPENROUTER_API_KEY'], detected)

    def test_discovery_skips_dependencies_examples_and_symlinks(self):
        (self.root / '.env').write_text('')
        (self.root / '.env.example').write_text('')
        (self.root / 'node_modules').mkdir()
        (self.root / 'node_modules/.env').write_text('')
        (self.root / 'another').mkdir()
        (self.root / 'another/.env').symlink_to(self.root / '.env')
        self.assertEqual([self.root / '.env'], spend.discover_env_files([self.root]))

    @patch('project_spend.fetch_key_usage')
    def test_lifetime_uses_usage_not_monthly_limit_or_byok(self, fetch):
        fetch.return_value = dict(usage=1.11039921, usage_monthly=0.06295495,
                                 limit=20, limit_remaining=19.93704505, byok_usage=3)
        report = spend.collect_openrouter([dict(token='secret-key', id='abc', sources=['file'])], [], 5)
        self.assertEqual('1.11039921', report['lifetime_usd'])
        self.assertNotIn('secret-key', json.dumps(report))
        self.assertEqual('management_key_missing', report['keys'][0]['activity_status'])

    @patch('project_spend.fetch_key_usage')
    def test_provider_failure_is_unknown_and_does_not_leak_key(self, fetch):
        fetch.side_effect = OpenRouterUsageError('Invalid secret-key')
        report = spend.collect_openrouter([dict(token='secret-key', id='abc', sources=[])], [], 5)
        self.assertIsNone(report['lifetime_usd'])
        self.assertNotIn('secret-key', json.dumps(report))

    def test_aws_sums_services_with_signed_credits_and_taxes_once(self):
        rows = spend.aws_rows(self.aws_payload(), '2026-07-01', '2026-10-17')
        self.assertEqual(Decimal('11.33'), sum((spend.number(r['amount_usd']) for r in rows), Decimal(0)))
        self.assertEqual(4, len(rows))

    def test_aws_rejects_partial_export_and_outside_window(self):
        with self.assertRaisesRegex(spend.SpendError, 'NextPageToken'):
            spend.aws_rows(self.aws_payload(NextPageToken='page2'), '2026-07-01', '2026-10-17')
        with self.assertRaisesRegex(spend.SpendError, 'fit'):
            spend.aws_rows(self.aws_payload(), '2026-07-25', '2026-10-17')
        with self.assertRaisesRegex(spend.SpendError, 'unknown'):
            spend.aws_rows(dict(ResultsByTime=[]), '2026-07-01', '2026-10-17')

    def test_aws_rejects_wrong_group_order_and_ungrouped_charges(self):
        payload = self.aws_payload(GroupDefinitions=[dict(Key='RECORD_TYPE'), dict(Key='SERVICE')])
        with self.assertRaisesRegex(spend.SpendError, 'group'):
            spend.aws_rows(payload, '2026-07-01', '2026-10-17')
        payload = self.aws_payload(groups=[])
        payload['ResultsByTime'][0]['Total'] = {'UnblendedCost': {'Amount':'99'}}
        with self.assertRaisesRegex(spend.SpendError, 'zero total'):
            spend.aws_rows(payload, '2026-07-01', '2026-10-17')

    @patch('project_spend.subprocess.run')
    def test_aws_reads_all_pages_and_never_puts_credentials_in_arguments(self, run):
        env = self.root / 'aws.env'
        env.write_text('AWS_ACCESS_KEY_ID=access-secret\nAWS_SECRET_ACCESS_KEY=secret-secret\n')
        page1 = self.aws_payload(NextPageToken='next')
        page2 = self.aws_payload(groups=[(['Amazon S3', 'Usage'], '0.01')])
        run.side_effect = [argparse.Namespace(returncode=0, stdout=json.dumps(page1)),
                           argparse.Namespace(returncode=0, stdout=json.dumps(page2))]
        raw = spend.fetch_aws('2026-07-01', '2026-10-17', env, None, None, 5)
        rows = spend.aws_rows(raw, '2026-07-01', '2026-10-17')
        self.assertEqual(5, len(rows))
        self.assertIn('--next-page-token', run.call_args.args[0])
        self.assertNotIn('secret-secret', str(run.call_args.args[0]))
        self.assertEqual('secret-secret', run.call_args.kwargs['env']['AWS_SECRET_ACCESS_KEY'])

    @patch('project_spend.subprocess.run')
    def test_aws_denied_sanitizes_identity_and_credentials(self, run):
        env = self.root / 'aws.env'
        env.write_text('AWS_ACCESS_KEY_ID=access-secret\nAWS_SECRET_ACCESS_KEY=secret-secret\n')
        run.return_value = argparse.Namespace(returncode=1, stderr='AccessDenied secret-secret arn:aws:iam::123:user/test')
        with self.assertRaises(spend.SpendError) as error:
            spend.fetch_aws('2026-07-01', '2026-10-17', env, None, None, 5)
        self.assertNotIn('secret-secret', str(error.exception))
        self.assertNotIn('arn:aws', str(error.exception))

    def test_calls_dedup_by_request_and_filter_by_utc(self):
        one = self.call()
        two = dict(one, request_id='gen-2', timestamp='2026-07-01T00:30:00+03:00')
        path = self.csv_file('calls.csv', [one, two])
        rows = spend.load_calls([path, path], '2026-07-01', '2026-10-17')
        self.assertEqual(1, len(rows))
        self.assertEqual(1, spend.call_summary(rows)[0]['requests'])
        self.assertEqual('0.10', spend.call_summary(rows)[0]['cost_usd'])

    def test_calls_reject_conflicting_duplicates_or_naive_times(self):
        path = self.csv_file('calls.csv', [self.call(), dict(self.call(), cost_usd='0.2')])
        with self.assertRaisesRegex(spend.SpendError, 'Conflicting'):
            spend.load_calls([path], '2026-07-01', '2026-10-17')
        path = self.csv_file('naive.csv', [dict(self.call(), timestamp='2026-07-25T00:00:00')])
        with self.assertRaisesRegex(spend.SpendError, 'timezone'):
            spend.load_calls([path], '2026-07-01', '2026-10-17')

    def test_reconciliation_separates_lifetime_costs_payments_and_currencies(self):
        router = dict(lifetime_usd='1.11039921')
        aws = dict(status='ok', project_scoped=False, rows=[dict(amount_usd='999')])
        path = self.csv_file('ledger.csv', [self.ledger(), self.ledger(entry_id='invoice-2', basis='payment', amount='20'),
                                                self.ledger(entry_id='invoice-3', currency='TRY', amount='100')])
        ledger = spend.load_ledger([path, path], '2026-07-01', '2026-10-17')
        totals = spend.reconcile(router, aws, [], ledger)
        self.assertEqual({'TRY':'100', 'USD':'2.5'}, totals['documented_costs_in_period'])
        self.assertEqual({'USD':'20'}, totals['documented_payments_in_period'])
        self.assertEqual('1.11039921', totals['openrouter_lifetime_usd'])
        self.assertIsNone(totals['project_total'])

    def test_overlapping_cost_sources_are_rejected(self):
        aws = dict(status='ok', project_scoped=True, rows=[dict(amount_usd='9')])
        with self.assertRaisesRegex(spend.SpendError, 'overlaps'):
            spend.reconcile(dict(lifetime_usd=None), aws, [], [self.ledger(provider='aws')])
        path = self.csv_file('calls.csv', [self.call()])
        calls = spend.load_calls([path], '2026-07-01', '2026-10-17')
        with self.assertRaisesRegex(spend.SpendError, 'overlaps'):
            spend.reconcile(dict(lifetime_usd='1'), dict(status='unavailable'), calls, [self.ledger(provider='openrouter')])

    @patch('project_spend.fetch_aws', side_effect=spend.SpendError('AccessDenied: ce:GetCostAndUsage'))
    @patch('project_spend.fetch_key_usage', return_value=dict(usage=1.11039921))
    def test_partial_report_survives_aws_denial_and_is_saved_without_secrets(self, key, aws):
        env = self.root / '.env'
        env.write_text('OPENROUTER_API_KEY=never-print-this-secret\n')
        output = self.root / 'reports'
        with patch.dict(os.environ, {}, clear=True), patch('sys.stdout', new_callable=io.StringIO) as stdout:
            code = spend.main(['--start','2026-07-01','--end','2026-10-17', '--env-file',str(env), '--output-dir',str(output)])
        self.assertEqual(3, code)
        data = json.loads((output / 'project-spend.json').read_text())
        self.assertEqual('1.11039921', data['totals']['openrouter_lifetime_usd'])
        self.assertEqual('unavailable', data['aws']['status'])
        self.assertEqual({}, data['totals']['documented_costs_in_period'])
        self.assertNotIn('never-print-this-secret', stdout.getvalue())
        self.assertNotIn('never-print-this-secret', json.dumps(data))
        self.assertEqual(0o600, (output / 'project-spend.json').stat().st_mode & 0o777)
        self.assertTrue((output / 'aws-monthly.csv').is_file())

    def test_missing_sources_are_not_zero_and_offline_import_works(self):
        aws_file = self.root / 'aws.json'
        aws_file.write_text(json.dumps(self.aws_payload()))
        with patch.dict(os.environ, {}, clear=True):
            args = spend.parse_args(['--start','2026-07-01','--end','2026-10-17','--skip-openrouter',
                                     '--aws-cost-file',str(aws_file),'--aws-dedicated-account',
                                     '--output-dir',str(self.root / 'out')])
            report = spend.build_report(args)
        self.assertIsNone(report['openrouter']['lifetime_usd'])
        self.assertEqual({'USD':'11.33'}, report['totals']['documented_costs_in_period'])
        self.assertIsNone(report['totals']['project_total'])


if __name__ == '__main__':
    unittest.main()
