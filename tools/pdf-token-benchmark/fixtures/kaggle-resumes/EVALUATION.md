# Kaggle CV parsing, matching, and mentor-pool evaluation

## Dataset and cohort

- Source: [Resume Dataset for NLP and Recruitment Analytics](https://www.kaggle.com/datasets/souptikghosh/resume-dataset)
- License: MIT
- Downloaded rows: 815
- Mentees: 6 synthetic records with 0 years of experience
- Mentors: 6 synthetic records with 8 years of experience
- Roles: Software Engineer, Data Scientist, and Financial Analyst
- Privacy handling: email and phone lines were removed before PDF generation
- Deduplication: exact resume-text hashes and names were unique within each cohort

The source contains 764 unique resume texts across 815 rows, so 51 rows are exact
text duplicates. The generated cohort should be treated as a smoke/regression fixture,
not a representative production benchmark.

## Test configuration

- Parser: OpenRouter `openai/gpt-4o-mini`, local-text PDF extraction with the
  `individual_mentee` schema for interns
- Matching discovery: BM25 lexical retrieval + local Ollama `bge-m3`, 1,024 dimensions
- Direct ranking: field-aware role, skills, goals, industry, experience-gap, and
  preference scoring, blended with semantic and lexical signals
- Pool retrieval: controlled taxonomy + BM25 lexical retrieval + BGE-M3 dense retrieval
- Pool ranking: evidence-depth weighted scoring, evidence-aware pair reranking, and
  diversity-aware maximal-marginal-relevance selection

## Results

| Area | Result |
|---|---:|
| Parser completion | 12 / 12 |
| Mentor years-of-experience accuracy | 100% |
| Mentor role extraction accuracy | 83.33% |
| Mentor mean skill recall | 96.67% |
| Intern profiles usable by direct matcher | 100% (6 / 6) |
| Intern mean skill recall | 83.33% |
| Hybrid direct matching top-1 role accuracy | 100% (6 / 6) |
| Hybrid direct matching macro recall@3 | 100% |
| Hybrid direct matching macro precision@3 | 66.67% |
| Hybrid mentor-pool top-1 role accuracy | 100% across 3 problem spaces |
| Correct-role mentors in each pool top 3 | 2 of 3 |

The combined matching and pool evaluation used 10 local BGE-M3 embedding calls and
reported 1,059 input tokens.

## Findings

1. The mentor parser is suitable for this basic smoke test. It recovered all six
   experience values and nearly all listed skills.
2. The individual-mentee schema made all six intern profiles usable, including the
   Financial Analyst CV that the former startup schema could not match.
3. The direct matcher ranked a same-role senior first for all six interns. Each top
   three contained both same-role mentors, giving 100% recall@3.
4. The goals/support form is important evaluation context: CV facts supplied skills
   and experience, while the controlled form supplied desired role and career goal.
5. This is still a small synthetic smoke test. It verifies the workflow and catches
   regressions; it does not establish production fairness or real-world match quality.

## Recommended product changes

1. Collect coordinator labels on accepted, rejected, and successful matches so the
   fixed field weights can be calibrated against real decisions.
2. Add larger, multilingual, and cross-role evaluation sets before production use.
3. Keep coordinator review: ranking evidence should support a decision, not automate
   mentorship assignment.

## Reproduction

From `Backend/tools/pdf-token-benchmark`:

```bash
/Users/aryazaeri/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 \
  fixtures/kaggle-resumes/build_cohort.py

.venv/bin/python benchmark.py --approach local_text --profile-type individual_mentee \
  --results-dir fixtures/kaggle-resumes/results/parse-individual-mentees \
  fixtures/kaggle-resumes/pdfs/mentees/*.pdf

.venv/bin/python benchmark.py --approach local_text --profile-type mentor \
  --results-dir fixtures/kaggle-resumes/results/parse-mentors \
  fixtures/kaggle-resumes/pdfs/mentors/*.pdf

.venv/bin/python fixtures/kaggle-resumes/run_evaluation.py
```

The machine-readable result is in `results/kaggle-evaluation.json`. The six mentor
and six mentee profiles are also imported into the shared parsing/matching library.
