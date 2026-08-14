# PDF parsing token benchmark

This test runs the same profile extraction schema and instructions through two
provider API paths. OpenAI uses the Responses API; OpenRouter uses its
OpenAI-compatible Chat Completions API with strict JSON Schema output:

1. `local_text`: extract page-delimited text with `pypdf`, then send the text.
2. `direct_pdf`: send the original PDF as an `input_file`.

The output includes the API's raw usage object and normalized input, cached input,
cache-write, output, reasoning, and total token counts. It also writes an estimated
cost for supported GPT-5.6 models.

## Setup

From this directory:

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

Put the manually created key in `Backend/.env`:

```dotenv
OPENAI_API_KEY=your-key-here
OPENAI_MODEL=gpt-5.6-luna
```

To use an OpenRouter key for both parsing and matching instead:

```dotenv
AI_PROVIDER=openrouter
OPENROUTER_API_KEY=sk-or-v1-your-key-here
OPENROUTER_BASE_URL=https://openrouter.ai/api/v1
OPENROUTER_MODEL=openai/gpt-4o-mini
OPENROUTER_EMBEDDING_MODEL=openai/text-embedding-3-small
```

`OPENROUTER_PDF_ENGINE` is optional. Leave it empty to let OpenRouter choose its
default PDF handling, or set `native`, `cloudflare-ai`, or `mistral-ocr`. The UI
reads the active provider and model from the server; keys never enter browser code.

When OpenRouter is active, the parser model picker queries the account-scoped
`/models/user` catalog and shows models that advertise structured JSON output. The
catalog is cached locally for five minutes, and the configured `OPENROUTER_MODEL`
remains available as a fallback when the catalog cannot be reached.

Do not commit `Backend/.env`; the backend repository already ignores it.

## Validate without API usage

```bash
.venv/bin/python benchmark.py --dry-run --profile-type mentor /path/to/cv.pdf
```

## Run both approaches

```bash
.venv/bin/python benchmark.py --profile-type mentor /path/to/cv.pdf
```

## Run the web UI

Start the local server from this directory:

```bash
.venv/bin/python web_app.py --port 8765
```

Then open [http://127.0.0.1:8765](http://127.0.0.1:8765). The API key is read by
the local server from `Backend/.env` and is never sent to browser JavaScript.

### Local OCR

The application can run OCR before PII redaction and profile parsing. Install
OCRmyPDF, Tesseract, and the English/Turkish language data on the host. The
application supports `off`, `auto`, and `force` OCR modes; `force` is useful for
scanned or visually complex multi-column CVs. OCR output remains local, and only
the redacted extracted text is sent to the configured AI provider.

The web UI lets you upload as many as 50 CV PDFs at once. It runs them
sequentially, with two API calls per CV, so progress and failures stay visible.
The completed batch includes:

- aggregate token, estimated-cost, latency, success, and warning metrics;
- a same-scale local-text versus direct-PDF token chart;
- exact per-CV metrics and retryable failures;
- per-CV input/output usage and warning counts;
- downloadable self-contained HTML, CSV, and JSON reports.

Failed CVs remain in the audit table but are excluded from aggregate medians and
totals. The HTML report records metric definitions, call-order sensitivity,
limitations, recommended next steps, and follow-up questions.

Each CV uses two API calls. Start with a small batch to confirm model pricing and
output quality before running a large folder.

### Send parsed profiles into mentor matching

After a batch finishes, use **Use these profiles for matching** at the bottom of
the report. Select the parser output to keep—**Local text** is the recommended
default—then choose **Add to matching library**. Repeat once for a mentor batch and
once for a mentee/startup or individual-mentee batch. **Open Mentor Match Lab** loads both collections
from the shared local library without manually downloading or uploading JSON.

To start all three connected tools with one process:

```bash
.venv/bin/python integrated_app.py
```

- PDF Parse Lab: [http://127.0.0.1:8765](http://127.0.0.1:8765)
- Mentor Match Lab: [http://127.0.0.1:8766](http://127.0.0.1:8766)
- Mentor Pool Lab: [http://127.0.0.1:8767](http://127.0.0.1:8767)

Mentor Pool Lab reuses the parsed mentor library and embedding cache. It combines
hard eligibility rules, the controlled problem taxonomy, BM25 lexical retrieval,
semantic retrieval, field-aware scoring, evidence-aware/cross-encoder/experimental
LLM reranking, and coordinator labels. Pool runs and reviews are stored under
`results/pool/runs/`.

Imported profiles are stored locally at
`results/matching/parsed-profile-library.json`. Re-importing the same source PDF
updates its profile instead of creating a duplicate.

For a startup or pitch deck:

```bash
.venv/bin/python benchmark.py --profile-type mentee /path/to/deck.pdf
```

For an intern or other individual mentee CV:

```bash
.venv/bin/python benchmark.py --profile-type individual_mentee /path/to/cv.pdf
```

Useful controls:

```bash
# Compare a higher-detail direct PDF pass.
.venv/bin/python benchmark.py --pdf-detail high /path/to/cv.pdf

# Reverse call order to expose any prompt-caching effect.
.venv/bin/python benchmark.py --order direct-first /path/to/cv.pdf

# Run only one approach (useful when file inputs are unavailable or when testing fallback cost).
.venv/bin/python benchmark.py --approach local_text /path/to/cv.pdf
.venv/bin/python benchmark.py --approach direct_pdf /path/to/cv.pdf

# Test multiple PDFs in one run.
.venv/bin/python benchmark.py /path/to/one.pdf /path/to/two.pdf
```

### Generate the cost–accuracy pilot report

After collecting the pilot parser and matching runs under
`results/cost-accuracy-pilot`, generate the model-judged accuracy audit and combined
Markdown, JSON, and HTML reports with:

```bash
.venv/bin/python generate_cost_accuracy_pilot_report.py
```

The report records parser input/output/reasoning tokens, estimated model cost,
schema-valid success rate, deterministic evidence support, model-judged accuracy and
missing information, plus embedding tokens, Precision@3, Recall@3, and top-1 accuracy.

For the 10-CV Luna/Claude comparison, first build the labeled downstream matching
fixtures, run the desired embedding evaluations, and then generate the blinded report:

```bash
.venv/bin/python prepare_claude_matching_inputs.py
.venv/bin/python generate_claude_comparison_report.py
```

The Claude comparison uses two CVs from each of five Kaggle categories, rotates and
blinds parser identities before cross-provider judging, and reports how each parser's
profiles affect downstream matching quality.

Each run creates a timestamped directory under `results/` containing:

- the inspectable locally extracted text;
- each approach's profile, raw API usage, and normalized token counts;
- a per-PDF comparison JSON;
- an aggregate JSON and CSV summary.

## Interpretation notes

- Compare `input_tokens` for the main parsing-cost difference.
- Review `cached_input_tokens` separately; call order can affect cache reads.
- `reasoning_tokens` are already part of output usage and must not be added twice.
- Direct PDF processing includes extracted text and page images. `--pdf-detail low`
  reduces image tokens; `high` preserves more visual detail.
- The local path does not include OCR. If the script warns that little text was
  extracted, use an OCR-capable local parser before drawing conclusions.
- Estimated costs are convenience estimates using the rates embedded in
  `benchmark.py`. The API usage object and the Platform billing dashboard remain
  authoritative.

## Add an accuracy audit to a saved batch report

The accuracy audit compares both saved profile JSON outputs with each original CV PDF,
adds deterministic evidence/page checks, and records missing or unsupported information
per CV. It uses one additional API call per CV and saves each result so interrupted runs
can resume.

```bash
.venv/bin/python accuracy_audit.py \
  --report-id YOUR_REPORT_ID \
  --pdf-dir /absolute/path/to/the/same-cv-folder
```

PDF filenames and order must match the saved report. The script updates that report's
HTML, JSON, and CSV files in place. Accuracy scores are model-judged estimates; the
original PDF is treated as ground truth, and the report labels the limitations.

## Evaluate mentor–mentee embedding matching

The matching prototype consumes the parser's existing mentor and mentee profile JSON;
it does not change PDF parsing or participant acceptance behavior. See
[`matching_prototype/README.md`](matching_prototype/README.md) for its schema adapter,
privacy rules, tests, cache behavior, sample evaluation, and OpenAI run commands.

Quick offline evaluation:

```bash
.venv/bin/python -m matching_prototype.evaluate --provider deterministic
```

Live multilingual embedding evaluation:

```bash
.venv/bin/python -m matching_prototype.evaluate \
  --provider openai \
  --output results/matching/openai-evaluation.json
```

OpenRouter embedding evaluation:

```bash
.venv/bin/python -m matching_prototype.evaluate \
  --provider openrouter \
  --output results/matching/openrouter-evaluation.json
```

Local multilingual embedding evaluation (no API charge):

```bash
ollama pull bge-m3
OLLAMA_EMBEDDING_MODEL=bge-m3 .venv/bin/python -m matching_prototype.evaluate \
  --provider ollama \
  --output results/matching/local-bge-m3-evaluation.json
```

### Evaluate targeted field repair

The hybrid experiment keeps Luna local-first parsing as the baseline, applies
deterministic validation, asks Haiku to repair only flagged matching fields, and keeps
the Luna profile whenever repair fails:

```bash
.venv/bin/python hybrid_repair_benchmark.py
.venv/bin/python prepare_hybrid_matching_inputs.py
```

Run the baseline and hybrid mentor pools through the same local BGE-M3 configuration,
then generate the reproducible technical report:

```bash
OLLAMA_EMBEDDING_MODEL=bge-m3 .venv/bin/python -m matching_prototype.evaluate \
  --provider ollama \
  --mentors results/hybrid-repair/matching/inputs/baseline-mentors.json \
  --mentees results/hybrid-repair/matching/inputs/mentees.json \
  --expected results/hybrid-repair/matching/inputs/expected_matches.json \
  --cache results/hybrid-repair/matching/baseline-ollama-bge-cache.json \
  --output results/hybrid-repair/matching/baseline-ollama-bge.json

OLLAMA_EMBEDDING_MODEL=bge-m3 .venv/bin/python -m matching_prototype.evaluate \
  --provider ollama \
  --mentors results/hybrid-repair/matching/inputs/hybrid-mentors.json \
  --mentees results/hybrid-repair/matching/inputs/mentees.json \
  --expected results/hybrid-repair/matching/inputs/expected_matches.json \
  --cache results/hybrid-repair/matching/hybrid-ollama-bge-cache.json \
  --output results/hybrid-repair/matching/hybrid-ollama-bge.json

.venv/bin/python generate_hybrid_repair_report.py
```

The report is written under `results/hybrid-repair/report/`. It distinguishes exact
PDF quote support from semantic completeness and records any schema or credit failures.

Matching web UI:

```bash
.venv/bin/python -m matching_prototype.web_app --port 8766
```

Then open [http://127.0.0.1:8766](http://127.0.0.1:8766). It can run beside the
PDF parsing UI on port `8765`.
