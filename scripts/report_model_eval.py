#!/usr/bin/env python3
"""Compare meeting-report models on real transcripts, through the real code path.

Replays stored transcripts from S3 against POST /api/assistant/bots/{p}/{id}/report
with a per-request model override, so every model sees byte-identical input and
production settings are never touched. Going through the backend rather than
calling OpenRouter directly means PII redaction, the JSON schema and the response
mapping are all exercised as the product runs them.

Writes two files:
  results.json  every report, timed and measured, with the model recorded
  scoring.md    anonymised sheet for blind human scoring; the label -> model key
                lives in results.json only, so the sheet can be read without
                learning which model wrote what

The three mechanical rubric axes are filled in automatically. The three that need
a person (Turkish quality, decisions captured, action-item correctness) are left
blank. See Reports/2026-09-18-meeting-report-model-evaluation.md for the rubric.

Usage:
  python3 scripts/report_model_eval.py --meetings <id> <id> --out ./eval
  python3 scripts/report_model_eval.py --meetings <id> --out ./eval --dry-run
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import random
import re
import string
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# The 09-18 shortlist. The baseline is first so it runs before the others, which
# keeps it distinguishable in OpenRouter's per-model activity: production only
# ever uses this one for reports, so spend on the rest is unambiguously the eval.
DEFAULT_MODELS = [
    "google/gemini-3-flash-preview",  # baseline, what production uses today
    "openai/gpt-5-mini",
    "google/gemini-3.8-flash",
    "anthropic/claude-haiku-4.5",
    "anthropic/claude-sonnet-5",
    "openai/gpt-5.6-sol",
]

REQUIRED_FIELDS = {
    "title": str,
    "summary": str,
    "topicsDiscussed": list,
    "decisions": list,
    "actionItems": list,
    "risksOrBlockers": list,
    "nextSteps": list,
}

# A runaway loop on a shared production key would eat the whole budget and stop
# production generating reports at all, so the run is capped and never retries.
MAX_REQUESTS = 60


def load_transcripts(bucket: str, ids: list[str], workdir: Path) -> dict[str, list[dict]]:
    """Pull transcript.json for each meeting id. Content stays on disk."""
    out: dict[str, list[dict]] = {}
    for mid in ids:
        dest = workdir / f"{mid}.json"
        if not dest.exists():
            key = f"s3://{bucket}/meetings/{mid}/transcript.json"
            fetched = subprocess.run(["aws", "s3", "cp", key, str(dest), "--quiet"])
            if fetched.returncode != 0:
                raise SystemExit(
                    f"no transcript at {key}\n"
                    f"  ids must be full UUIDs. To list them:\n"
                    f"  aws s3 ls s3://{bucket}/meetings/ --recursive "
                    f"| grep transcript.json"
                )
        raw = json.loads(dest.read_text(encoding="utf-8"))
        segments = raw.get("segments") if isinstance(raw, dict) else raw
        out[mid] = segments or []
    return out


def transcript_text(segments: list[dict]) -> str:
    return " ".join((s.get("text") or "") for s in segments if isinstance(s, dict))


def schema_valid(report: dict) -> tuple[bool, list[str]]:
    """Did the model return the shape the schema asked for, first try?"""
    problems = []
    for field, kind in REQUIRED_FIELDS.items():
        if field not in report:
            problems.append(f"missing {field}")
        elif not isinstance(report[field], kind):
            problems.append(f"{field} is {type(report[field]).__name__}, want {kind.__name__}")
    for i, item in enumerate(report.get("actionItems") or []):
        if not isinstance(item, dict) or not item.get("task"):
            problems.append(f"actionItems[{i}] has no task")
    return (not problems), problems


TOKEN_RE = re.compile(r"[0-9]+(?:[.,][0-9]+)?|[A-ZÇĞİÖŞÜ][a-zçğıöşü]{2,}")


def ungrounded_terms(report: dict, transcript: str) -> list[str]:
    """Capitalised words and numbers in the report that never appear in the transcript.

    A blunt proxy for the rubric's faithfulness axis, and the one that catches the
    failure that matters: an invented name, date or figure presented as fact. It
    over-reports (the model may legitimately rephrase), so treat it as a pointer to
    where a human should look, not a score.
    """
    haystack = transcript.lower()
    fields = [report.get("title") or "", report.get("summary") or ""]
    for key in ("topicsDiscussed", "decisions", "risksOrBlockers", "nextSteps"):
        fields.extend(str(x) for x in (report.get(key) or []))
    for item in report.get("actionItems") or []:
        if isinstance(item, dict):
            fields.extend(str(item.get(k) or "") for k in ("owner", "task", "due"))

    missing, seen = [], set()
    for field in fields:
        for term in TOKEN_RE.findall(field):
            low = term.lower()
            if low in seen or len(low) < 3:
                continue
            seen.add(low)
            if low not in haystack:
                missing.append(term)
    return missing


def post_report(base_url: str, model: str, segments: list[dict], timeout: int) -> tuple[dict, float]:
    payload = json.dumps({"model": model, "segments": segments}).encode("utf-8")
    url = f"{base_url}/api/assistant/bots/eval/transcript-replay/report"
    req = urllib.request.Request(
        url, data=payload, headers={"Content-Type": "application/json"}, method="POST"
    )
    started = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    return body, time.monotonic() - started


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--bucket", default=os.environ.get("AWS_S3_BUCKET"))
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--meetings", nargs="+", required=True,
                        help="meeting ids under meetings/<id>/transcript.json")
    parser.add_argument("--models", nargs="+", default=DEFAULT_MODELS)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--dry-run", action="store_true",
                        help="fetch and measure inputs, call no model")
    args = parser.parse_args()

    if not args.bucket:
        print("no bucket: pass --bucket or set AWS_S3_BUCKET", file=sys.stderr)
        return 2

    planned = len(args.meetings) * len(args.models)
    if planned > MAX_REQUESTS:
        print(f"refusing to run {planned} requests; cap is {MAX_REQUESTS}", file=sys.stderr)
        return 2

    args.out.mkdir(parents=True, exist_ok=True)
    workdir = args.out / "transcripts"
    workdir.mkdir(exist_ok=True)

    transcripts = load_transcripts(args.bucket, args.meetings, workdir)
    for mid, segs in transcripts.items():
        speakers = {(s.get("speaker") or "").strip() for s in segs} - {""}
        words = len(transcript_text(segs).split())
        print(f"  {mid[:8]}  {len(segs):>3} segments  {len(speakers)} speaker(s)  {words:>5} words")
    if args.dry_run:
        print("\ndry run: no model was called")
        return 0

    print(f"\n{planned} requests, no retries\n")
    results, skipped, aborted = [], {}, False
    for model in args.models:
        for mid, segments in transcripts.items():
            if not segments:
                continue
            try:
                report, seconds = post_report(args.base_url, model, segments, args.timeout)
            except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError,
                    ConnectionError, http.client.HTTPException) as exc:
                # A dropped connection is not an HTTPError and used to escape as a
                # traceback, losing every result gathered so far.
                status = getattr(exc, "code", None)
                detail = exc.read().decode("utf-8", "replace")[:200] if hasattr(exc, "read") else str(exc)
                # 401/402/429 mean the key itself is in trouble — stop everything
                # rather than keep spending someone else's budget. Anything else is
                # this model disagreeing with our request, so skip it and carry on.
                if status in (401, 402, 429):
                    print(f"ABORTING on {status}: {detail}", file=sys.stderr)
                    aborted = True
                    break
                print(f"  -- {model:34} skipped: {detail}", file=sys.stderr)
                skipped[model] = detail
                break
            ok, problems = schema_valid(report)
            ungrounded = ungrounded_terms(report, transcript_text(segments))
            results.append({
                "model": model,
                "meeting": mid,
                "seconds": round(seconds, 2),
                "schemaValid": ok,
                "schemaProblems": problems,
                "ungroundedTerms": ungrounded,
                "summaryWords": len((report.get("summary") or "").split()),
                "counts": {k: len(report.get(k) or []) for k in
                           ("topicsDiscussed", "decisions", "actionItems",
                            "risksOrBlockers", "nextSteps")},
                "actionItemsWithOwner": sum(
                    1 for i in (report.get("actionItems") or [])
                    if isinstance(i, dict) and i.get("owner")),
                "report": report,
            })
            flag = "ok " if ok else "BAD"
            print(f"  {flag} {model:34} {mid[:8]}  {seconds:5.1f}s  {len(ungrounded):>2} ungrounded")
        if aborted:
            break

    if not results:
        print("nothing to write", file=sys.stderr)
        return 1

    # Anonymise for blind scoring; the key stays in results.json only.
    labels = list(string.ascii_uppercase)
    random.shuffle(labels)
    by_model = {m: labels[i] for i, m in enumerate(sorted({r["model"] for r in results}))}

    (args.out / "results.json").write_text(
        json.dumps({"labelToModel": {v: k for k, v in by_model.items()},
                    "skipped": skipped, "runs": results},
                   ensure_ascii=False, indent=2), encoding="utf-8")

    lines = [
        "# Meeting report model comparison — blind scoring sheet", "",
        "Score 1-5 in the blank columns. Do not open `results.json` until you are done;",
        "it carries the label -> model key. Rubric:",
        "Reports/2026-09-18-meeting-report-model-evaluation.md",
        "",
        "`Ungrounded` lists capitalised words and numbers that never appear in the",
        "transcript — a pointer to where invented facts would be, not a score. Check",
        "them before judging faithfulness.",
        "",
    ]
    for mid in args.meetings:
        runs = [r for r in results if r["meeting"] == mid]
        if not runs:
            continue
        lines += [
            f"## Transcript {mid[:8]}", "",
            "| Model | JSON | Secs | Summary words | Decisions | Actions (with owner) "
            "| Ungrounded | Turkish 1-5 | Decisions 1-5 | Actions 1-5 |",
            "|---|---|---|---|---|---|---|---|---|---|",
        ]
        for r in sorted(runs, key=lambda x: by_model[x["model"]]):
            u = ", ".join(r["ungroundedTerms"][:6]) or "—"
            lines.append(
                f"| {by_model[r['model']]} | {'ok' if r['schemaValid'] else 'BAD'} | {r['seconds']} | "
                f"{r['summaryWords']} | {r['counts']['decisions']} | "
                f"{r['counts']['actionItems']} ({r['actionItemsWithOwner']}) | {u} |  |  |  |")
        lines.append("")
    (args.out / "scoring.md").write_text("\n".join(lines), encoding="utf-8")

    print(f"\nwrote {args.out/'results.json'} and {args.out/'scoring.md'}")
    if skipped:
        print(f"{len(skipped)} model(s) skipped; sheet covers the rest", file=sys.stderr)
        for model, why in skipped.items():
            print(f"  {model}: {why}", file=sys.stderr)
    return 1 if aborted else 0


if __name__ == "__main__":
    raise SystemExit(main())
