# Hybrid direct mentor matching prototype

This prototype accepts `MentorProfile`, startup `MenteeProfile`, and
`IndividualMenteeProfile` parser JSON. It uses BM25 plus embeddings for candidate
discovery, then ranks candidates with explainable role, skills, goals, industry,
experience-gap, and preference fields. It returns up to three mentors and does not
create a mentorship or bypass coordinator review.

## Matching text

Mentor text can contain only:

- expertise (`expertise` plus current `professional_functions` adapter);
- industries;
- optional startup-stage experience;
- technologies;
- optional mentoring areas;
- professional experience claims from `employment_highlights`.

Mentee text can contain only:

- desired and current role;
- experience level and years;
- sector;
- industries;
- startup stage;
- needs;
- bottleneck claims;
- goal claims;
- technologies;
- projects and achievements;
- skills to develop and career goals;
- languages and meeting format from the follow-up form;
- desired-support claims from current `support_needs`.

The builder uses an allowlist. Names, startup names, contact data, evidence quotes and
pages, warnings, confidence values, availability, capacity, education, and unrelated
fields cannot enter matching text. Empty fields and missing headings are omitted.

## Providers and fixed configuration

`EmbeddingProvider` is the interface boundary. The live implementation uses the same
configuration for mentors and mentees:

```text
provider: Ollama
model: bge-m3
dimensions: 1024
encoding_format: float
matching_text_version: matching-text-v2
```

`DeterministicHashEmbeddingProvider` is a network-free lexical test double. Its scores
are useful only for tests and sample plumbing; do not use it to evaluate semantic
matching quality.

## Run tests

From `tools/pdf-token-benchmark` using the existing virtual environment:

```bash
.venv/bin/python -m unittest discover -s matching_prototype/tests -v
```

## Run the offline sample evaluation

```bash
.venv/bin/python -m matching_prototype.evaluate --provider deterministic
```

The command prints each mentee's top three, hybrid and component scores, matching
text, exact shared fields, skipped profiles, and evaluation metrics for supplied labels.

## Run the web UI

From `tools/pdf-token-benchmark`:

```bash
.venv/bin/python -m matching_prototype.web_app --port 8766
```

Open [http://127.0.0.1:8766](http://127.0.0.1:8766). The UI loads the included
sample mentor, mentee, and expected-match JSON automatically. You can replace any of
them by dropping a JSON file into the matching input area, add goals and support
preferences, select local BGE-M3 or another provider, then inspect:

- each mentee's ranked recommendations and component scores;
- the exact matching text sent to the embedding provider;
- deterministic shared fields;
- Precision@3 and Recall@3 when relevance labels are present;
- full downloadable evaluation JSON.

The OpenAI key remains in `Backend/.env` and is never exposed to browser JavaScript.
The matching UI uses port `8766` by default so it can run beside PDF Parse Lab on
port `8765`.

For the connected parsing, matching, and mentor-pool workflow, start all tools together:

```bash
.venv/bin/python integrated_app.py
```

In PDF Parse Lab, finish a mentor, startup mentee, or individual mentee batch and use **Add to matching
library**. Once both profile types have been added, choose **Open Mentor Match Lab**
or **Load parsed profiles**. Matching text remains privacy-filtered by the existing
allowlisted adapter; the parser JSON itself is not changed.

## Run Mentor Pool Lab

From `tools/pdf-token-benchmark`:

```bash
.venv/bin/python -m matching_prototype.pool_web_app --port 8767
```

Open [http://127.0.0.1:8767](http://127.0.0.1:8767). The lab loads mentors from
the shared parsed-profile library when available, otherwise it uses the included
sample mentors. It provides mentor readiness enrichment, problem intake,
taxonomy review, hybrid taxonomy/BM25/semantic retrieval, field-level scoring,
evidence-depth scoring, optional cross-encoder or experimental LLM reranking, diversity-aware pool
selection, coordinator labels, confidence warnings, feedback calibration metrics,
and downloadable run JSON. The offline provider exercises the complete workflow
without network access.

To use a local cross-encoder, expose a JSON reranking endpoint and configure:

```dotenv
RERANKER_URL=http://127.0.0.1:8081/rerank
RERANKER_MODEL=bge-reranker-v2-m3
```

The endpoint receives `model`, `query`, and `texts`, and returns either a numeric
score array or `{ "scores": [...] }`. If it is unavailable, the exported run records
the failure and uses the evidence-aware local fallback. Eligibility now hard-filters
inactive, capacity/load-constrained, conflicted, language/meeting mismatched,
excluded-domain, and under-experienced mentors. Missing required language or meeting
data is treated as unconfirmed rather than silently accepted.

### Experimental LLM reranking protocol

The **LLM judge (experimental)** option evaluates only mentors that already passed
hard eligibility. It receives the mentee problem, structured retrieval/component
scores, CV evidence, and a privacy-filtered profile excerpt. It returns a strict
JSON score, rationale, evidence quotes, and concerns for each submitted mentor.
Unsupported quotes are removed deterministically. Any configuration, API, schema,
or validation failure falls back to the evidence-aware reranker and is recorded in
the exported run.

Configure the test path in `Backend/.env`:

```dotenv
LLM_RERANKER_PROVIDER=openai
LLM_RERANKER_MODEL=gpt-5.6-luna
LLM_RERANKER_CANDIDATE_LIMIT=12
```

`LLM_RERANKER_PROVIDER` may also be `openrouter`; the corresponding provider API
key must be configured. The LLM score keeps the existing blend of 75% pre-rerank
score and 25% reranker score, so the experiment cannot replace eligibility or the
rest of the ranking protocol.

For a controlled comparison, run the same problem and mentor snapshot with
**Evidence-aware**, **Cross-encoder**, and **LLM judge**; collect coordinator labels;
then compare rank changes, inappropriate-mentor rate, ranking quality, latency, and
recorded token usage. Do not compare runs made from different profile or taxonomy
versions.

## Run with OpenAI embeddings

The existing `Backend/.env` supplies `OPENAI_API_KEY`:

```bash
.venv/bin/python -m matching_prototype.evaluate \
  --provider openai \
  --output results/matching/openai-evaluation.json
```

## Run with OpenRouter embeddings

Set `AI_PROVIDER=openrouter`, `OPENROUTER_API_KEY`, and optionally
`OPENROUTER_EMBEDDING_MODEL` in `Backend/.env`, then run:

```bash
.venv/bin/python -m matching_prototype.evaluate \
  --provider openrouter \
  --output results/matching/openrouter-evaluation.json
```

The default OpenRouter embedding model is `openai/text-embedding-3-small`. OpenAI
and OpenRouter cache entries remain distinct because the provider and full model slug
are part of the cache fingerprint.

The matching UI loads its OpenRouter picker from `/embeddings/models`. Selecting a
different embedding model is recorded in the evaluation output and creates a distinct
mentor-cache fingerprint. The API key remains in `Backend/.env` and is never returned
to browser JavaScript.

Mentor embeddings are cached at `results/matching/mentor_embeddings.json`. The cache
fingerprint includes matching text and the embedding provider/model/configuration. Cache
records also store profile and matching-text versions as metadata. A mentor embedding is
regenerated only when matching text or embedding provider/model/configuration changes. A
profile-version or matching-text-version change with identical text reuses the
mathematically identical vector, while the current versions are still returned in the
recommendation metadata.

Use different cache paths when comparing providers or evaluation runs if you want to
retain both files, although mismatched records are safely invalidated automatically.

## Evaluate custom profiles

Input files are JSON arrays. Each record wraps an existing parsed profile:

```json
{
  "mentor_id": "mentor-123",
  "profile_version": "mentor-profile-v1",
  "profile": {
    "industries": ["B2B SaaS"],
    "professional_functions": ["Enterprise sales"],
    "technologies": ["CRM"],
    "employment_highlights": [
      {
        "claim": "Built enterprise sales processes.",
        "page": 2,
        "quote": "Evidence is deliberately excluded from matching text.",
        "information_type": "explicit"
      }
    ]
  }
}
```

Run:

```bash
.venv/bin/python -m matching_prototype.evaluate \
  --provider openai \
  --mentors /path/to/mentors.json \
  --mentees /path/to/mentees.json \
  --expected /path/to/expected_matches.json \
  --output /path/to/evaluation.json
```

Expected matches use a mentee-to-relevant-mentor map:

```json
{
  "mentee-1": ["mentor-3", "mentor-9"]
}
```

Precision@3 uses a denominator of three. Recall@3 is the fraction of supplied expected
mentors appearing in the first three results. These metrics measure agreement with the
provided relevance labels, not mentorship success.

## Integration notes

- Pass the parser's `profile` object unchanged inside the record wrapper.
- Supply a stable participant ID outside the profile; identity is not embedded.
- Increment `profile_version` whenever confirmed profile content changes.
- Persist only recommendations; leave invitation and asynchronous acceptance to the
  existing application process.
- The hybrid score is ranking evidence, not a compatibility percentage or probability.
