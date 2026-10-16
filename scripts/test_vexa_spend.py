import csv
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from project_spend import SpendError
import vexa_spend as vexa


class VexaSpendTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def save(self, name, value):
        path = self.root / name
        path.write_text(json.dumps(value))
        return path

    def ledger(self, **changes):
        row = dict(provider='vexa', entry_id='stt-1', date='2026-09-25', service='hosted STT',
                   amount='0.20', currency='USD', basis='cost', source='vexa-invoice.pdf', component='transcription')
        row.update(changes)
        return row

    def csv(self, name, rows):
        path = self.root / name
        with path.open('w', newline='') as out:
            writer = csv.DictWriter(out, fieldnames=list(rows[0]))
            writer.writeheader(); writer.writerows(rows)
        return path

    def meeting(self, **changes):
        row = dict(id=1, status='completed', start_time='2026-09-25T10:00:00Z',
                   end_time='2026-09-25T11:00:00Z', created_at='2026-09-25T09:59:00Z')
        row.update(changes)
        return row

    def test_separates_hosting_stt_reporting_and_payments(self):
        aws = dict(status='ok', rows=[dict(amount_usd='10.00'), dict(amount_usd='-2.00')])
        ledger = [self.ledger(), self.ledger(entry_id='payment', amount='20', basis='payment')]
        calls = [dict(provider='openrouter', cost_usd='0.05')]
        result = vexa.summarize(aws, calls, ledger)
        self.assertEqual({'USD':'8.25'}, result['documented_cost_subtotal'])
        self.assertEqual({'USD':'0.20'}, result['costs_by_component']['transcription'])
        self.assertEqual({'USD':'20'}, result['payments_by_component']['transcription'])
        self.assertIsNone(result['full_vexa_total'])
        self.assertIn('do not add', result['rollup'])

    def test_missing_components_stay_unknown_not_zero(self):
        result = vexa.summarize(dict(status='unavailable'), [], [])
        self.assertEqual({}, result['documented_cost_subtotal'])
        self.assertEqual({}, result['costs_by_component']['hosting'])
        self.assertIsNone(result['full_vexa_total'])

    def test_empty_vexa_billing_export_is_unknown_but_explicit_zero_group_is_known(self):
        for groups, status, costs in [([], 'no_matching_billing_data', {}),
                ([{'Keys': ['EC2', 'Usage'], 'Metrics': {'UnblendedCost': {'Amount': '0', 'Unit': 'USD'}}}],
                 'ok', {'USD': '0'})]:
            with self.subTest(status=status):
                file = self.save('aws.json', {'ResultsByTime': [{'TimePeriod':
                    {'Start': '2026-07-01', 'End': '2026-08-01'}, 'Groups': groups}]})
                args = vexa.parse_args(['--start', '2026-07-01', '--end', '2026-10-17',
                    '--aws-cost-file', str(file), '--aws-export-is-vexa-only', '--output-dir', str(self.root)])
                report = vexa.build_report(args)
                self.assertEqual(status, report['aws']['status'])
                self.assertEqual(costs, report['totals']['costs_by_component']['hosting'])
                if status != 'ok':
                    self.assertIn('| Hosting | Unknown |', vexa.render(report))

    def test_overlapping_sources_rejected_but_component_allocation_allowed(self):
        aws = dict(status='ok', rows=[dict(amount_usd='10')])
        with self.assertRaisesRegex(SpendError, 'overlaps'):
            vexa.summarize(aws, [], [self.ledger(provider='aws', component='hosting')])
        with self.assertRaisesRegex(SpendError, 'overlaps'):
            vexa.summarize(dict(status='unavailable'), [dict(provider='openrouter',cost_usd='1')],
                           [self.ledger(provider='openrouter', component='reporting')])
        result = vexa.summarize(aws, [], [self.ledger(provider='aws',component='transcription')])
        self.assertEqual({'USD':'10.20'}, result['documented_cost_subtotal'])

    def test_ledger_deduplicates_and_checks_components(self):
        path = self.csv('invoice.csv', [self.ledger()])
        rows = vexa.load_vexa_ledger([path,path], '2026-07-01','2026-10-17')
        self.assertEqual(1, len(rows))
        self.assertEqual('transcription',rows[0]['component'])
        path = self.csv('bad.csv', [self.ledger(component='other-project')])
        with self.assertRaisesRegex(SpendError, 'Component'):
            vexa.load_vexa_ledger([path], '2026-07-01','2026-10-17')

    def test_duplicate_invoice_with_different_component_is_rejected(self):
        a = self.csv('a.csv', [self.ledger()])
        b = self.csv('b.csv', [self.ledger(component='hosting')])
        with self.assertRaisesRegex(SpendError, 'conflicting'):
            vexa.load_vexa_ledger([a,b], '2026-07-01','2026-10-17')

    def test_meetings_deduplicate_internal_ids_but_not_recurring_links(self):
        a = self.meeting(native_meeting_id='recurring-link', data={'participants':['private name']})
        b = self.meeting(id=2,native_meeting_id='recurring-link',start_time='2026-09-26T10:00:00Z',end_time='2026-09-26T10:30:00Z')
        path = self.save('meetings.json',dict(meetings=[a,b],has_more=False))
        result = vexa.meeting_stats([path,path],'2026-09-01','2026-10-01')
        self.assertEqual(2,result['observed_meetings_in_window'])
        self.assertEqual('90.0',result['observed_session_minutes'])
        self.assertNotIn('private name',json.dumps(result))
        self.assertNotIn('recurring-link',json.dumps(result))

    def test_usage_clips_to_window_and_does_not_treat_active_session_as_zero(self):
        before = self.meeting(start_time='2026-08-31T23:30:00Z',end_time='2026-09-01T00:30:00Z')
        active = self.meeting(id=2,status='active',start_time='2026-09-01T00:00:00Z',end_time=None)
        zero = self.meeting(id=3,start_time='2026-09-02T10:00:00Z',end_time='2026-09-02T10:00:00Z')
        result = vexa.meeting_stats([self.save('m.json',[before,active,zero])],'2026-09-01','2026-10-01')
        self.assertEqual(3,result['observed_meetings_in_window'])
        self.assertEqual('30.0',result['observed_session_minutes'])
        self.assertEqual(1,result['records_without_final_duration'])

    def test_incomplete_exports_naive_times_and_negative_duration_are_rejected(self):
        partial = self.save('partial.json',dict(meetings=[],has_more=True))
        with self.assertRaisesRegex(SpendError,'paginated'):
            vexa.meeting_stats([partial],'2026-09-01','2026-10-01')
        naive = self.save('naive.json',[self.meeting(start_time='2026-09-25T10:00:00')])
        with self.assertRaisesRegex(SpendError,'Naive'):
            vexa.meeting_stats([naive],'2026-09-01','2026-10-01')
        self.assertEqual('60.0',vexa.meeting_stats([naive],'2026-09-01','2026-10-01',True)['observed_session_minutes'])
        bad = self.save('bad.json',[self.meeting(end_time='2026-09-25T09:00:00Z')])
        with self.assertRaisesRegex(SpendError,'before'):
            vexa.meeting_stats([bad],'2026-09-01','2026-10-01')

    @patch('vexa_spend.fetch_aws')
    def test_no_account_wide_billing_fallback(self, fetch):
        args = vexa.parse_args(['--start','2026-07-01','--output-dir',str(self.root)])
        report = vexa.build_report(args)
        fetch.assert_not_called()
        self.assertEqual('unavailable',report['aws']['status'])
        self.assertIsNone(report['meeting_usage']['observed_session_minutes'])

    def test_offline_aws_import_requires_explicit_vexa_scope(self):
        with patch('sys.stderr',new_callable=io.StringIO),self.assertRaises(SystemExit):
            vexa.parse_args(['--start','2026-07-01','--aws-cost-file','cost.json','--output-dir',str(self.root)])

    @patch('vexa_spend.fetch_aws',side_effect=SpendError('AccessDenied ce:GetCostAndUsage'))
    def test_report_survives_billing_denial_and_saves_no_meeting_pii(self, fetch):
        filt = self.save('filter.json',{'Tags':{'Key':'Purpose','Values':['vexa']}})
        meetings = self.save('meetings.json',[self.meeting(data={'transcript':'private secret transcript'})])
        args=['--start','2026-07-01','--end','2026-10-17','--aws-filter',str(filt),
              '--meetings-file',str(meetings),'--output-dir',str(self.root/'out')]
        with patch('sys.stdout',new_callable=io.StringIO):
            self.assertEqual(3,vexa.main(args))
        text=(self.root/'out/vexa-spend.json').read_text()
        self.assertNotIn('private secret',text)
        report=json.loads(text)
        self.assertEqual('unavailable',report['aws']['status'])
        self.assertIsNone(report['totals']['full_vexa_total'])
        self.assertEqual(0o600,(self.root/'out/vexa-spend.md').stat().st_mode & 0o777)


if __name__=='__main__':
    unittest.main()
