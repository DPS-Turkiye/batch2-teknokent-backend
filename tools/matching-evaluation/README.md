# Matching evaluation

This set contains six labelled, request-specific scenarios spanning AI, growth, fundraising,
technology law, industrial operations, and climate impact. It evaluates the live Spring Boot
endpoint rather than a separate demo matcher.

With the backend and matching tool running:

```bash
python3 tools/matching-evaluation/evaluate.py
```

If `.env` uses non-default backend credentials, pass them explicitly with
`--username` and `--password`.

The report prints hit rate at 3, mean reciprocal rank, the matching method, and the number of
confirmed CV evidence snippets shown for the top three mentors. Treat the labels as an MVP
regression set; add reviewed real requests as they become available.
