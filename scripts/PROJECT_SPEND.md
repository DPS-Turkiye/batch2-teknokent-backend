# Project API usage and spending

`project_spend.py` extends the original `openrouter_usage.py` checker. It is a
read-only, standard-library Python CLI. AWS collection also needs the AWS CLI.
It makes usage/billing requests, never model inference requests or deployments.

## Run against the local DPS repositories

From this worktree (`Versions/.worktrees/openrouter-usage-backend`):

```bash
python3 scripts/project_spend.py \
  --start 2026-07-01 \
  --discover-env-root ../../.. \
  --aws-env-file ../../v1.3.0/Backend/.env \
  --output-dir ../../v1.3.0/Reports/project-spend/latest
```

July 2026 is an initial billing window inferred from the earliest backend commit
(25 July 2026). It is not proof that no setup costs predate July. Change `--start`
when earlier billing evidence is available. `--end` is exclusive and defaults to
tomorrow UTC so the current, possibly incomplete day is included.

Generated files:

- `project-spend.md`: readable findings, source statuses and coverage gaps.
- `project-spend.json`: normalized evidence, decimal amounts, provenance and gaps.
- `aws-monthly.csv`: month/service/charge-type costs, including signed credits,
  refunds and taxes returned by Cost Explorer.
- `calls-by-model.csv`: deduplicated imported calls, token counts and cost.

Use a dated output directory to preserve snapshots. Snapshots contain financial
metadata, not credentials, and should stay outside Git. They are not transactions:
never add repeated lifetime snapshots together. Exit code **3** means the files
were created but coverage is incomplete. Exit 1 means an invalid input/output;
exit 2 means invalid CLI arguments. The tool deliberately does not certify a
complete project total from a handful of connected sources.

## OpenRouter coverage

The tool finds nonempty `OPENROUTER_API_KEY` assignments in explicit `--env-file`
arguments, the process environment, and optionally project `.env` files under
`--discover-env-root`. Duplicate copies are hashed and queried once. Env files
are parsed as text, never executed. Dependencies, `.git`, build directories and
env examples are excluded. Key values are never report fields or command-line
arguments. Credentials go only to the provider's fixed API URL in auth headers.

`GET /key` supplies **lifetime credits consumed** and rolling daily/weekly/monthly
counters. The current key's `limit` is monthly, so `limit - remaining` must not be
used as the lifetime total. API credits consumed are separate from the cash paid
to buy credits, fees and taxes. External BYOK figures are informational; reconcile
those against the external provider's actual bill rather than adding estimates.

With exactly one `OPENROUTER_MANAGEMENT_KEY` in the input sources, the tool also
uses the original checker's key-filtered Activity API. Its daily rows appear in
JSON under each key's `recent_activity` and cover **30 completed UTC days**.
They are not added to lifetime spend or presented as lifetime request counts.
Multiple management keys require explicit input files/environment to select the
correct account; the script does not spray unrelated account credentials.

For an older/full project breakdown, OpenRouter's current
[Analytics API](https://openrouter.ai/docs/api/api-reference/analytics/query-analytics-data)
and [Activity exports](https://openrouter.ai/docs/cookbook/administration/activity-export)
can cover a selected historical range. The old 30-day endpoint is not an all-time
history endpoint. Management access or account-owner exports are required. This
CLI does not implement the new Analytics query protocol; request-level exports
must be mapped into the explicit CSV schema below. Do not assume aggregated
Activity rows contain one row per call.

Deleted, rotated, deployment-only and other developers' keys are not recoverable
from local env discovery alone. A shared key's full lifetime may include other
projects. Reconcile ownership and dates before treating a counter as project-only.

## AWS collection and attribution

Use `--aws-env-file PATH`, `--aws-profile PROFILE`, or the AWS CLI's normal
credential chain. Env-file and profile selection are mutually exclusive. Existing
app permissions (S3/EC2 access) do not imply billing permissions. A billing admin
can review `spend-examples/aws-billing-read-policy.json`; this tool does not apply
policies or change access.

The billing query is monthly `UnblendedCost`, grouped by `SERVICE` then
`RECORD_TYPE`, across all regions. It includes EC2/Vexa hosting, EBS/IP/network,
Amplify, S3, SES, Route 53 and other billed AWS services when present. Do not add
Vexa's AWS hosting a second time as a separate transcription charge. Cost Explorer
can lag and mark current periods as estimated. These are accrued billing charges,
not a bank-payment ledger. Cost Explorer API requests themselves may incur AWS's
normal billing-query charge; the collector does not issue retries automatically.

**An unfiltered AWS account is not assumed to belong to this project.** Such
figures are shown as account-wide context and excluded from documented project
costs. If a cost-allocation tag/filter is already known, supply a nonempty JSON
Cost Explorer expression:

```bash
python3 scripts/project_spend.py \
  --start 2026-07-01 --env-file ../../v1.3.0/Backend/.env \
  --aws-env-file ../../v1.3.0/Backend/.env \
  --aws-filter /path/to/verified-project-filter.json \
  --output-dir /path/to/private/reports
```

For example, a filter could be `{"Tags":{"Key":"Project","Values":["teknokent"]}}`
**only if that tag exists and covers the historical resources**. Tags may miss
older or untagged charges and account-wide costs. Do not invent a filter to force
an attractive project total. Use `--aws-dedicated-account` only when the entire
billing account/export is known to belong to the project.

If local billing access is denied, a billing owner can export the same query:

```bash
aws ce get-cost-and-usage \
  --time-period Start=2026-07-01,End=2026-10-17 \
  --granularity MONTHLY --metrics UnblendedCost \
  --group-by Type=DIMENSION,Key=SERVICE Type=DIMENSION,Key=RECORD_TYPE \
  --region us-east-1 --output json > aws-costs.json
```

Supply `--aws-cost-file aws-costs.json` to work offline. Exports must contain all
pages; a remaining `NextPageToken` is rejected. The imported dates must fit the
requested window; the tool never prorates monthly charges. A live `--aws-filter`
is not applied retroactively to an imported file and cannot be combined with it.
For a shared account, import verified project allocations through the cost ledger
instead of asserting the account/export is dedicated.

## Historical API calls

Repeat `--calls-csv` for normalized, **project-filtered** request-level exports.
The header is in `spend-examples/calls.csv`:

```csv
provider,request_id,timestamp,model,cost_usd,prompt_tokens,completion_tokens
```

Each row represents one provider request and its actual reported charge. Timestamps
must be ISO 8601 with a timezone. Dates are filtered in UTC. Duplicate
`(provider, request_id)` rows are counted once; conflicting duplicates fail.
Prompt and completion tokens are summed. Reasoning tokens are already included
in completion tokens and should not be added again. Missing costs/counts fail
validation instead of silently becoming zero. Request exports may include free
requests with an explicitly recorded zero cost.

Imports produce a period subtotal; they do not increase OpenRouter's lifetime
counter. Without full request exports/analytics, lifetime call counts remain
unknown even when the lifetime spending counter is available. This does not
reconstruct every historical HTTP call to your app or every AWS API request.

## Other services and actual cash paid

Use `--ledger-csv` for project-allocated Firebase/Google Cloud bills, GitHub
Actions/plans, external domains, developer tools, historical direct-provider
charges, hosting allocations, purchase fees or actual payments. The header is in
`spend-examples/ledger.csv`:

```csv
provider,entry_id,date,service,amount,currency,basis,source
```

- `basis=cost`: an accrued charge/allocation supported by an invoice or usage report.
- `basis=payment`: actual cash paid, supported by a receipt/payment record.
- `date`: the invoice/charge date for costs, or payment date for payments.
- `source`: a local evidence filename or reference, never credentials/card details.
- Negative amounts represent credits/refunds. Use ISO three-letter currencies;
  different currencies remain separate and are not converted automatically.

Repeated identical `(provider, entry_id)` rows are deduplicated. Conflicts fail.
Ledger costs for a provider already counted in call exports or project-scoped AWS
are rejected to prevent double counting; combine that provider's evidence in one
source first. Payment rows remain separate from costs even for the same provider.
Credit top-ups are payments, not an additional model-usage cost.

## Tests

```bash
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

No backend runtime or database is involved. Live billing reads are separately
verified with the project's existing credentials. Provider failures are retained
as gaps; they do not stop successful sources from producing a usable report.
