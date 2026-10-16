# Vexa bot spending checker

`vexa_spend.py` provides a Vexa-specific view alongside `project_spend.py`.
It separates three components:

- **Hosting**: the bot/runtime server plus attributable storage, public IPs,
  snapshots, network, logs and other infrastructure.
- **Transcription**: actual hosted Vexa/STT service charges or allocations.
- **Reporting**: project backend AI calls for meeting reports and transcript cleanup.

The local Vexa repo supports both self-hosted transcription and a remote STT
service. Deployment notes from 22 September identify `transcription.vexa.ai` in
the earlier production configuration. A self-hosted bot can therefore have both
AWS hosting costs and separately billed hosted transcription. The current STT
account, plan, token and bill have not been verified by this checker.

## Run

From `Versions/.worktrees/openrouter-usage-backend`:

```bash
python3 scripts/vexa_spend.py \
  --start 2026-07-01 \
  --aws-env-file ../../v1.3.0/Backend/.env \
  --aws-filter scripts/spend-examples/vexa-aws-filter.json \
  --output-dir ../../v1.3.0/Reports/vexa-spend/latest
```

The example filter uses `Purpose=vexa`, observed on the current `teknokent-vexa`
EC2 instance on 16 October 2026. **Resource tags must be activated for cost
allocation and historically cover the intended resources.** The filter may miss
old/replaced hosts, untagged EBS/public IP/logging/network charges or charges
before tag activation. Zero filtered costs would not establish zero Vexa spend.
No tags, IAM policies, billing settings or cloud resources are changed. If the
query returns no grouped service records, hosting remains **unknown**, even if
AWS returned a zero total. This commonly needs billing-tag activation/backfill;
the empty result does not establish that Vexa hosting was free.

AWS requires `ce:GetCostAndUsage`. Existing app credentials were denied that
permission during the parent project spending check. Use `--aws-profile` instead
of `--aws-env-file` with a billing-authorized profile, or have a billing owner
supply a verified Vexa-only AWS export:

```bash
python3 scripts/vexa_spend.py \
  --start 2026-07-01 --end 2026-10-17 \
  --aws-cost-file /path/to/vexa-only-aws-costs.json \
  --aws-export-is-vexa-only \
  --output-dir /path/to/private/vexa-report
```

The AWS export follows the parent checker's month / SERVICE / RECORD_TYPE
`UnblendedCost` schema. All pages must be included, with no remaining
`NextPageToken`. Whole-account costs require an actual Vexa allocation before
importing. A shared-account fallback is never queried. For older resource-level
costs, use existing CUR/billing exports or verified historical cost-allocation
tags; Cost Explorer's optional daily resource view covers only the past 14 days.

## Transcription invoices and other allocations

Use `--ledger-csv`, repeating the flag for more than one file. The empty template
is `spend-examples/vexa-ledger.csv`:

```csv
provider,entry_id,date,service,amount,currency,basis,source,component
```

`component` must be `hosting`, `transcription` or `reporting`. Use `basis=cost`
for an actual charge/allocation and `basis=payment` for cash paid, such as a credit
top-up. Costs and payments remain separate. Signed credits/refunds are supported;
currencies are never silently converted. The date is the charge/invoice date or
payment date. Keep the invoice/reference in `source`, excluding credentials and
payment-card details. Every row must be Vexa-only or an explicitly supported
Vexa allocation. Export mapping is explicit; arbitrary provider CSV formats are
not guessed or silently accepted.

The tool deduplicates provider + entry ID. It rejects ledger costs that overlap
an already collected source for the same component/provider. For example, use a
single reconciled source for Vexa AWS hosting rather than adding an AWS invoice
again on top of the scoped Cost Explorer rows.

## Report and cleanup AI calls

Supply `--report-calls-csv` with Vexa-related meeting report/cleanup requests in
the parent tool's normalized request export schema. Include only actual,
project-filtered calls. Never use the entire OpenRouter key's lifetime amount:
that key also funds CV/profile parsing and mentor embeddings. The script does
not infer the feature solely from a model name. Historical feature attribution
needs the original call IDs/logs/exports or an explicit invoice allocation.

## Meeting counts and observed duration

Optionally pass `--meetings-file` with a complete JSON list of Vexa bot/meeting
records, or an object with `meetings` and `has_more=false`. Pagination/cursors
must be fully resolved before importing. The current local Vexa `GET /bots`
list uses `limit`/`offset` and `has_more`; gather every page and flatten it. Older
meeting exports are also supported when they have the same basic record fields:
`id`, `status`, `start_time`, `end_time`, `created_at`.

Records deduplicate by the internal meeting ID, so repeated meetings using the
same Google Meet link remain separate. Use exports from one deployment/account;
internal IDs can collide across deployments. Duration is clipped to the UTC
report window. Active/failed records without final timestamps remain unknown.
If the source emits timezone-naive UTC timestamps, verify that convention and
pass `--naive-times-are-utc`; it is never assumed silently.

The output saves aggregated counts/durations, not meeting titles, participant
names, links, transcripts or arbitrary metadata. Meeting duration does not equal
STT billing: retries and overlapping audio windows can differ. Idle servers can
continue to incur hosting costs without a meeting. The checker therefore does
not multiply meeting minutes by a current public price to invent historical spend.

## Output and integration

Outputs are `vexa-spend.md`, `vexa-spend.json` and
`vexa-cost-components.csv`. An optional `--resource-inventory` can attach a
read-only inventory export with a `status: "ok"` and `instances` array; only
instance ID/name/type/state/launch time and volume IDs are retained. Inventory
proves current identity, not historical uptime or spending.

The report exposes a partial documented cost subtotal when evidence exists. It
leaves the full lifetime total unavailable until all historical components are
reconciled. Missing components are **unknown**, not zero. Exit code 3 means a
report was saved with incomplete lifetime evidence; 1 means invalid inputs or
output failure, and 2 means invalid arguments. Financial output files are mode
0600 and should remain outside Git. Repeated snapshots are not additive.

**Vexa is part of the project total.** Its AWS/AI/provider charges are a breakdown
of costs already included by corresponding project sources. Do not add the Vexa
report a second time to the project report.

## Validation

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Sources: [Vexa STT options](https://docs.vexa.ai/how-to/custom-stt),
[AWS cost-allocation tags](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/custom-tags.html),
[AWS resource history](https://docs.aws.amazon.com/cost-management/latest/userguide/ce-resource-daily.html).
