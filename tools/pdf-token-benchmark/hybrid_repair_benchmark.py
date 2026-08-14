#!/usr/bin/env python3
"""Validate Luna mentor profiles and repair only flagged matching fields with Haiku."""

from __future__ import annotations

import json
import re
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from pydantic import BaseModel

from accuracy_audit import deterministic_evidence, pdf_pages
from ai_provider import create_client, provider_settings
from benchmark import (
    BACKEND_DIR, Evidence, SCRIPT_DIR, _openrouter_extra_body, _openrouter_response,
    _strict_json_schema, dump_model, dump_usage, estimate_cost, normalize_usage,
)


DEFAULT_ROOT = SCRIPT_DIR / "results" / "hybrid-repair"
HAIKU_MODEL = "anthropic/claude-haiku-4.5"


class MentorFieldRepair(BaseModel):
    industries: list[str]
    professional_functions: list[str]
    technologies: list[str]
    employment_highlights: list[Evidence]
    repair_notes: list[str]


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def latest_baseline(root: Path) -> Path:
    candidates = sorted((root / "baseline").glob("*/all-results.json"), key=lambda path: path.stat().st_mtime)
    if not candidates:
        raise RuntimeError("No Luna baseline results were found.")
    return candidates[-1]


def repair_triggers(profile: dict[str, Any], pdf: Path) -> dict[str, list[str]]:
    triggers: dict[str, list[str]] = {}

    def flag(field: str, reason: str) -> None:
        triggers.setdefault(field, []).append(reason)

    industries = profile.get("industries") or []
    functions = profile.get("professional_functions") or []
    technologies = profile.get("technologies") or []
    employment = profile.get("employment_highlights") or []
    missing = " ".join(str(value) for value in profile.get("missing_fields") or []).casefold()
    warnings = " ".join(str(value) for value in profile.get("warnings") or []).casefold()

    if not industries:
        flag("industries", "No industry was extracted.")
    if not functions:
        flag("professional_functions", "No professional function was extracted.")
    elif len(functions) > 16:
        flag("professional_functions", "Function list is unusually long and may contain noisy task fragments.")
    if not technologies:
        flag("technologies", "No technology was extracted.")
    elif len(technologies) > 20:
        flag("technologies", "Technology list is unusually long and may contain non-technology noise.")
    if re.search(r"technolog|software|hardware|database|office-system", missing):
        flag("technologies", "The parser explicitly reported missing technology details.")
    if len(employment) < 4:
        flag("employment_highlights", "Fewer than four employment highlights were extracted.")
    if re.search(r"project|accomplishment|outcome|current role|responsibilit", missing):
        flag("employment_highlights", "The parser explicitly reported missing project or employment detail.")

    evidence = deterministic_evidence(profile, pdf_pages(pdf))
    quote_rate = evidence.get("quote_support_rate_percent")
    if quote_rate is not None and quote_rate < 90:
        flag("employment_highlights", f"Evidence quote support was only {quote_rate:.1f}%.")
    if "extraction" in warnings or "truncat" in warnings:
        flag("employment_highlights", "The baseline parser reported an extraction or truncation warning.")
    return triggers


def request_repair(
    client: Any, settings: Any, *, pdf: Path, source_text: str,
    profile: dict[str, Any], triggers: dict[str, list[str]],
) -> tuple[dict[str, Any], dict[str, Any]]:
    requested_fields = sorted(triggers)
    prompt = {
        "source_filename": pdf.name,
        "requested_fields": requested_fields,
        "repair_reasons": triggers,
        "baseline_profile": profile,
        "source_text": source_text,
    }
    started = time.perf_counter()
    response = client.chat.completions.create(
        model=HAIKU_MODEL,
        messages=[
            {"role": "system", "content": (
                "Repair only the requested matching-relevant fields in a mentor CV profile. "
                "Use only facts supported by the source text. Keep evidence quotes short and "
                "verbatim with correct page numbers. Normalize industries, professional "
                "functions, and technologies into concise deduplicated concepts. Return [] for "
                "every non-requested field. Do not include names, contacts, availability, "
                "capacity, confidence, warnings, or unrelated personal details."
            )},
            {"role": "user", "content": json.dumps(prompt, ensure_ascii=False)},
        ],
        response_format=_strict_json_schema(MentorFieldRepair),
        max_tokens=1400,
        extra_body=_openrouter_extra_body(settings, direct_pdf=False),
    )
    parsed = _openrouter_response(response, MentorFieldRepair)
    usage = normalize_usage(dump_usage(parsed))
    return dump_model(parsed.output_parsed), {
        "model": HAIKU_MODEL,
        "elapsed_seconds": round(time.perf_counter() - started, 3),
        "usage": usage,
        "estimated_cost_usd": estimate_cost(HAIKU_MODEL, usage),
    }


def merge_profile(
    baseline: dict[str, Any], repair: dict[str, Any], fields: list[str],
) -> tuple[dict[str, Any], list[str]]:
    merged = json.loads(json.dumps(baseline))
    applied = []
    for field in fields:
        value = repair.get(field)
        if isinstance(value, list) and value:
            merged[field] = value
            applied.append(field)
    return merged, applied


def main() -> int:
    load_dotenv(BACKEND_DIR / ".env")
    settings = provider_settings()
    client = create_client(settings)
    root = DEFAULT_ROOT
    baseline_path = latest_baseline(root)
    baseline_runs = read_json(baseline_path)
    timestamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    output_dir = root / "repairs" / timestamp
    output_dir.mkdir(parents=True, exist_ok=False)
    output_rows = []
    for index, run in enumerate(baseline_runs, start=1):
        pdf = Path(run["pdf"])
        baseline_result = run["results"]["local_text"]
        baseline_profile = baseline_result.get("profile")
        if baseline_result.get("error") or not baseline_profile:
            output_rows.append({
                "pdf": str(pdf), "baseline": baseline_result, "triggers": {},
                "repair_attempted": False, "repair": None,
                "repair_api": None, "applied_fields": [], "hybrid_profile": None,
            })
            continue
        triggers = repair_triggers(baseline_profile, pdf)
        repair = None
        repair_api = None
        applied_fields: list[str] = []
        hybrid_profile = baseline_profile
        error = None
        if triggers:
            source_path = baseline_path.parent / pdf.stem / "locally-extracted.txt"
            try:
                repair, repair_api = request_repair(
                    client, settings, pdf=pdf,
                    source_text=source_path.read_text(encoding="utf-8"),
                    profile=baseline_profile, triggers=triggers,
                )
                hybrid_profile, applied_fields = merge_profile(
                    baseline_profile, repair, sorted(triggers)
                )
            except Exception as exc:
                raw_error = str(exc)
                error = (
                    "OpenRouter HTTP 402: insufficient funded credit for the requested repair output reservation."
                    if "402" in raw_error
                    else f"{type(exc).__name__}: {raw_error.splitlines()[0][:500]}"
                )
        row = {
            "pdf": str(pdf),
            "baseline": baseline_result,
            "triggers": triggers,
            "repair_attempted": bool(triggers),
            "repair": repair,
            "repair_api": repair_api,
            "repair_error": error,
            "applied_fields": applied_fields,
            "hybrid_profile": hybrid_profile,
        }
        output_rows.append(row)
        write_json(output_dir / f"{pdf.stem}.json", row)
        status = "no repair needed" if not triggers else f"repaired {', '.join(applied_fields) or 'none'}"
        print(f"[{index}/{len(baseline_runs)}] {pdf.name}: {status}", flush=True)
    write_json(output_dir / "all-results.json", output_rows)
    print(output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
