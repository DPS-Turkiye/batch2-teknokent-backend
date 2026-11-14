# AI features

Every AI call goes from Spring Boot straight to the configured provider (`AI_PROVIDER`: `openai` or `openrouter`;
any other value stops the app at start-up). No Python service or extra port is involved. Personal data is
redacted locally (`LocalPiiRedactionService`) before any text leaves the server.

Each feature has a switch, all on when unset. A switched-off feature answers 403 with code `AI_FEATURE_DISABLED`,
and the frontend offers the manual path instead.

| Feature | Switch | Without it |
|---|---|---|
| CV and pitch-deck parsing | `AI_PROFILE_PARSING_ENABLED` | Manual profile entry |
| Syllabus and weekly-program reading | `AI_SYLLABUS_ENABLED` | Weeks and phases typed in |
| Mentor matching (embeddings) | `AI_MATCHING_ENABLED` | Deterministic keyword and evidence ranking |
| Meeting report and transcript cleanup | `AI_MEETING_REPORT_ENABLED` | Raw transcript only |
| Task handover when someone leaves a team | `AI_TASK_HANDOVER_ENABLED` | Tasks go to the team lead |

## Profile parsing

PDFBox extracts the text; pages without a text layer go through Tesseract when OCR is `auto` or `force` (at most 10
pages). The text is redacted, then sent with a strict JSON schema (`util/JsonSchema`); the answer is read leniently
(`util/ModelOutput`) and checked for technologies implied by the evidence. Nothing is saved until the user reviews
and confirms the result; a preview before sign-up stores nothing. Processing consent is recorded first.

## Mentor matching

Confirmed mentor profiles are embedded and cached in PostgreSQL (pgvector, `mentor_profile_embedding`), refreshed
when a profile changes. A request is embedded, a cosine shortlist is taken, and the Java ranking layer orders it
with reasons and evidence; eligibility is applied before ranking. If pgvector or the embedding API is unavailable,
the deterministic ranking keeps the flow working. The ranking and the chosen mentor are saved with the request as
an auditable snapshot.

## Meeting assistant

A bot from the meeting provider joins recorded meetings near their start. The watchdog stops it when the meeting
ends, cleans the transcript with the AI (misheard words only; unsure lines keep the recognizer's text) and writes a
report: summary, decisions, action items, risks and next steps. The report always uses the configured model; a
caller cannot choose one.

## Errors

A provider outage, a refused key or an unreadable answer is a 502 with a translated message; the provider's own
message is logged, never returned. Only a schema rejection is retried (with a looser format); network failures and
refused keys are not.
