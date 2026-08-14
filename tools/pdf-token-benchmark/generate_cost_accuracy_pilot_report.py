#!/usr/bin/env python3
"""Judge and summarize the cost/accuracy pilot without rerunning parser calls."""

from __future__ import annotations

import argparse
import html
import json
import statistics
import time
from pathlib import Path
from typing import Any
from urllib.request import Request, urlopen

from dotenv import load_dotenv
from pydantic import BaseModel, Field

from accuracy_audit import deterministic_evidence, pdf_pages
from ai_provider import create_client, provider_settings
from benchmark import (
    BACKEND_DIR,
    SCRIPT_DIR,
    _openrouter_extra_body,
    _openrouter_response,
    _strict_json_schema,
    dump_model,
    dump_usage,
    estimate_cost,
    normalize_usage,
)


PARSER_RUNS = {
    "openai/gpt-5.6-luna": "luna",
    "google/gemini-2.5-flash-lite": "gemini-flash-lite",
    "google/gemma-4-26b-a4b-it:free": "gemma-free-fixed",
}
EMBEDDING_PRICES_PER_MILLION = {
    "baai/bge-m3": 0.01,
    "qwen/qwen3-embedding-8b": 0.01,
    "openai/text-embedding-3-small": 0.02,
}


class CandidateAccuracy(BaseModel):
    candidate_id: str
    factual_precision_score: int = Field(ge=0, le=100)
    completeness_score: int = Field(ge=0, le=100)
    evidence_quality_score: int = Field(ge=0, le=100)
    overall_accuracy_score: int = Field(ge=0, le=100)
    missing_information: list[str]
    hallucinated_information: list[str]
    notes: list[str]


class PilotAccuracyJudgement(BaseModel):
    candidates: list[CandidateAccuracy]
    source_limitations: list[str]


JUDGE_PROMPT = """
Audit structured mentor profiles against the supplied CV text. The CV text is the only
ground truth. Evaluate every candidate independently and conservatively.

Score factual precision, completeness, evidence quality, and overall accuracy from 0 to
100. Missing information means useful in-scope professional information present in the
CV but absent or materially wrong in that candidate. Hallucinated information means a
claim unsupported by the CV. Do not penalize excluded phone numbers, emails, addresses,
age, photographs, marital status, availability, capacity, or other facts outside the
profile schema. Do not reward plausible guesses. Return exactly one result for every
candidate_id supplied and do not rename candidate IDs. Keep each missing-information,
hallucination, and notes list to at most five concise, material items.
""".strip()


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def latest_files(root: Path, filename: str) -> list[Path]:
    return sorted(root.glob(f"**/{filename}"), key=lambda path: path.stat().st_mtime)


def load_parser_results(pilot_dir: Path) -> dict[str, dict[str, dict[str, Any]]]:
    collected: dict[str, dict[str, dict[str, Any]]] = {}
    for model, directory in PARSER_RUNS.items():
        model_dir = pilot_dir / "parsing" / directory
        by_pdf: dict[str, dict[str, Any]] = {}
        for path in latest_files(model_dir, "local_text.json"):
            by_pdf[path.parent.name] = read_json(path)
        collected[model] = by_pdf
    return collected


def load_source_text(pilot_dir: Path, pdf_stem: str, pdf_path: Path) -> str:
    candidates = latest_files(pilot_dir / "parsing" / "luna", "locally-extracted.txt")
    for path in reversed(candidates):
        if path.parent.name == pdf_stem:
            return path.read_text(encoding="utf-8")
    return "\n\n".join(
        f"[Page {index}]\n{text}" for index, text in enumerate(pdf_pages(pdf_path), start=1)
    )


def judge_candidates(
    *, client: Any, settings: Any, model: str, source_text: str,
    candidates: dict[str, dict[str, Any]], pdf_name: str, max_tokens: int = 4500,
) -> tuple[dict[str, Any], dict[str, Any]]:
    messages = [
        {"role": "system", "content": JUDGE_PROMPT},
        {"role": "user", "content": (
            f"CV filename: {pdf_name}\n\nCV TEXT:\n{source_text}\n\n"
            "CANDIDATE PROFILES:\n" + json.dumps(candidates, ensure_ascii=False)
        )},
    ]
    started = time.perf_counter()
    response = client.chat.completions.create(
        model=model,
        messages=messages,
        response_format=_strict_json_schema(PilotAccuracyJudgement),
        max_tokens=max_tokens,
        extra_body=_openrouter_extra_body(settings, direct_pdf=False),
    )
    parsed_response = _openrouter_response(response, PilotAccuracyJudgement)
    usage = normalize_usage(dump_usage(parsed_response))
    return dump_model(parsed_response.output_parsed), {
        "model": model,
        "elapsed_seconds": round(time.perf_counter() - started, 3),
        "usage": usage,
        "estimated_cost_usd": estimate_cost(model, usage),
    }


def parser_summary(
    model: str, results: dict[str, dict[str, Any]], pdfs: list[Path],
    judgements: dict[str, Any],
) -> dict[str, Any]:
    successful = [result for result in results.values() if not result.get("error")]
    usages = [result.get("usage", {}) for result in successful]
    costs = [float(result.get("estimated_cost_usd") or 0) for result in successful]
    latencies = [float(result.get("elapsed_seconds") or 0) for result in successful]
    accuracy_rows = []
    evidence_rows = []
    missing_fields = 0
    for pdf in pdfs:
        result = results.get(pdf.stem)
        if not result or result.get("error") or not result.get("profile"):
            continue
        profile = result["profile"]
        missing_fields += len(profile.get("missing_fields") or [])
        evidence_rows.append(deterministic_evidence(profile, pdf_pages(pdf)))
        candidate = judgements.get(pdf.stem, {}).get(model)
        if candidate:
            accuracy_rows.append(candidate)
    evidence_count = sum(row["evidence_count"] for row in evidence_rows)
    quote_supported = sum(row["quote_supported_count"] for row in evidence_rows)
    page_cited = sum(row["cited_evidence_count"] for row in evidence_rows)
    page_supported = sum(row["quote_on_cited_page_count"] for row in evidence_rows)
    return {
        "model": model,
        "documents_attempted": len(pdfs),
        "successful_profiles": len(successful),
        "success_rate_percent": round(len(successful) / len(pdfs) * 100, 1),
        "input_tokens": sum(row.get("input_tokens", 0) for row in usages),
        "output_tokens": sum(row.get("output_tokens", 0) for row in usages),
        "reasoning_tokens": sum(row.get("reasoning_tokens", 0) for row in usages),
        "total_tokens": sum(row.get("total_tokens", 0) for row in usages),
        "estimated_cost_usd": round(sum(costs), 8),
        "median_latency_seconds": round(statistics.median(latencies), 3) if latencies else None,
        "mean_accuracy_score": round(statistics.mean(row["overall_accuracy_score"] for row in accuracy_rows), 1) if accuracy_rows else None,
        "success_adjusted_accuracy_score": round(sum(row["overall_accuracy_score"] for row in accuracy_rows) / len(pdfs), 1) if accuracy_rows else None,
        "mean_factual_precision_score": round(statistics.mean(row["factual_precision_score"] for row in accuracy_rows), 1) if accuracy_rows else None,
        "mean_completeness_score": round(statistics.mean(row["completeness_score"] for row in accuracy_rows), 1) if accuracy_rows else None,
        "mean_evidence_quality_score": round(statistics.mean(row["evidence_quality_score"] for row in accuracy_rows), 1) if accuracy_rows else None,
        "quote_support_rate_percent": round(quote_supported / evidence_count * 100, 1) if evidence_count else None,
        "page_quote_support_rate_percent": round(page_supported / page_cited * 100, 1) if page_cited else None,
        "missing_fields_reported": missing_fields,
        "missing_information": sorted({item for row in accuracy_rows for item in row["missing_information"]}),
        "hallucinated_information": sorted({item for row in accuracy_rows for item in row["hallucinated_information"]}),
        "errors": [result.get("error") for result in results.values() if result.get("error")],
    }


def matching_summaries(pilot_dir: Path) -> list[dict[str, Any]]:
    summaries = []
    for path in sorted((pilot_dir / "matching").glob("*-usage.json")):
        payload = read_json(path)
        if not payload.get("results"):
            continue
        model = payload["results"][0]["embedding_model"]
        usage = payload.get("embedding_usage") or {}
        expected_top_1 = 0
        evaluated = 0
        for row in (payload.get("metrics") or {}).get("per_mentee", []):
            result = next(item for item in payload["results"] if item["mentee_id"] == row["mentee_id"])
            first = result["recommendations"][0]["mentor_id"] if result["recommendations"] else None
            evaluated += 1
            expected_top_1 += int(first in row["expected_mentor_ids"])
        input_tokens = int(usage.get("input_tokens") or 0)
        price = EMBEDDING_PRICES_PER_MILLION.get(model)
        summaries.append({
            "model": model,
            "input_tokens": input_tokens,
            "output_tokens": 0,
            "api_calls": int(usage.get("api_calls") or 0),
            "estimated_cost_usd": round(input_tokens * price / 1_000_000, 10) if price is not None else None,
            "precision_at_3": (payload.get("metrics") or {}).get("macro_precision_at_3"),
            "recall_at_3": (payload.get("metrics") or {}).get("macro_recall_at_3"),
            "top_1_accuracy": expected_top_1 / evaluated if evaluated else None,
        })
    unique = {row["model"]: row for row in summaries}
    return sorted(unique.values(), key=lambda row: row["model"])


def account_usage(settings: Any) -> dict[str, Any] | None:
    request = Request(
        f"{settings.base_url.rstrip('/')}/auth/key",
        headers={"Authorization": f"Bearer {settings.api_key}", "Accept": "application/json"},
    )
    try:
        with urlopen(request, timeout=12) as response:
            data = json.load(response).get("data") or {}
    except Exception:
        return None
    return {
        "usage_usd": data.get("usage"),
        "limit_usd": data.get("limit"),
        "limit_remaining_usd": data.get("limit_remaining"),
        "is_free_tier": data.get("is_free_tier"),
    }


def markdown_report(payload: dict[str, Any]) -> str:
    lines = [
        "# TeamTNT Cost–Accuracy Pilot Report", "",
        "## Executive decision", "",
        "Use `openai/gpt-5.6-luna` for local-text parsing for now. It was the only paid low-cost parser in this pilot to produce schema-valid profiles for all five CVs. Keep direct-PDF as a fallback, but it cannot be benchmarked with the current key until the OpenRouter account has at least $0.50 in purchased credits for file inputs.", "",
        "Use `baai/bge-m3` as the initial matching embedding model. All three embedding models found every expected mentor in the top three and ranked the expected mentor first, so BGE-M3 wins provisionally on multilingual suitability and the lowest tied list price. This matching dataset is too small for a final production claim.", "",
        f"OpenRouter reported **${float((payload.get('account') or {}).get('usage_usd') or 0):.8f}** total key usage after the pilot. This provider-reported value is authoritative and includes smoke tests and accuracy judging.", "",
        "## Parsing — local text", "",
        "| Model | Success | Input tokens | Output tokens | Cost | Median latency | Accuracy | Success-adjusted accuracy | Quote support |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in payload["parsing"]:
        def pct(value: Any) -> str:
            return "n/a" if value is None else f"{value:.1f}%"
        lines.append(
            f"| `{row['model']}` | {row['successful_profiles']}/{row['documents_attempted']} ({row['success_rate_percent']:.1f}%) | {row['input_tokens']:,} | {row['output_tokens']:,} | ${row['estimated_cost_usd']:.6f} | {row['median_latency_seconds'] if row['median_latency_seconds'] is not None else 'n/a'}s | {pct(row['mean_accuracy_score'])} | {pct(row['success_adjusted_accuracy_score'])} | {pct(row['quote_support_rate_percent'])} |"
        )
    lines += [
        "", "Accuracy is a structured Luna judge estimate against locally extracted CV text. Success-adjusted accuracy treats schema failures or unfinished requests as zero because they produce no usable profile. Quote support is a deterministic check that evidence quotes appear in the source; it does not prove every claim is semantically correct.", "",
        "## Direct-PDF approach", "",
        "The direct-PDF smoke request was rejected with HTTP 402: OpenRouter requires at least $0.50 of purchased balance for file inputs. The key reports a $20 usage limit and `is_free_tier=true`; a spending limit is not the same as funded credits. No repeated file requests were made after this was confirmed.", "",
        "Therefore direct-PDF token use, output tokens, accuracy, and cost are **not available in this pilot**. They must not be inferred from the local-text results.", "",
        "## Matching embeddings", "",
        "| Model | API calls | Input tokens | Output tokens | Estimated cost | Precision@3 | Recall@3 | Expected mentor ranked #1 |", "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in payload["matching"]:
        lines.append(
            f"| `{row['model']}` | {row['api_calls']} | {row['input_tokens']:,} | 0 | ${row['estimated_cost_usd']:.8f} | {row['precision_at_3']:.3f} | {row['recall_at_3']:.3f} | {row['top_1_accuracy']:.1%} |"
        )
    lines += [
        "", "Precision@3 is 0.333 because each test mentee has exactly one labeled relevant mentor and the metric denominator is three. It is not a compatibility percentage. Recall@3 is 1.0 because that one expected mentor appeared in the top three for every mentee.", "",
        "## Missing and inaccurate information", "",
    ]
    for row in payload["parsing"]:
        lines.append(f"### {row['model']}")
        lines.append("")
        lines.append(f"- Parser-declared missing fields: {row['missing_fields_reported']}")
        lines.append(f"- Judge-identified missing items: {len(row['missing_information'])}")
        lines.append(f"- Judge-identified unsupported/hallucinated items: {len(row['hallucinated_information'])}")
        if row["missing_information"]:
            lines.extend(f"  - Missing: {item}" for item in row["missing_information"])
        if row["hallucinated_information"]:
            lines.extend(f"  - Unsupported: {item}" for item in row["hallucinated_information"])
        if row["errors"]:
            lines.extend(f"  - Failure: {error}" for error in row["errors"])
        lines.append("")
    lines += [
        "## Limitations and next step", "",
        "- Five CVs are enough for a cost-controlled pilot, not a final production benchmark.",
        "- All five PDFs had machine-readable text; scanned and visually complex CVs remain untested.",
        "- Accuracy is model-judged and Luna is also one parser candidate, which can introduce correlated bias.",
        "- The matching evaluation has five mentors, three mentees, and one expected mentor per mentee.",
        "- OpenRouter prices and routing availability can change.",
        "- After adding the minimum funded balance, repeat the same five CVs with `--approach direct_pdf`, then expand the winning configuration to all 35 CVs.", "",
    ]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pilot-dir", type=Path, default=SCRIPT_DIR / "results" / "cost-accuracy-pilot")
    parser.add_argument("--pdf-dir", type=Path, default=SCRIPT_DIR / "test-cvs-35")
    parser.add_argument("--judge-model", default="openai/gpt-5.6-luna")
    parser.add_argument("--skip-judge", action="store_true")
    args = parser.parse_args()
    load_dotenv(BACKEND_DIR / ".env")
    pilot_dir = args.pilot_dir.expanduser().resolve()
    pdf_names = [
        "01_ACCOUNTANT_25867805.pdf", "14_DESIGNER_35990852.pdf",
        "19_HEALTHCARE_85421438.pdf", "21_INFORMATION-TECHNOLOGY_52246737.pdf",
        "34_BUSINESS-DEVELOPMENT_19738730.pdf",
    ]
    pdfs = [(args.pdf_dir / name).expanduser().resolve() for name in pdf_names]
    results = load_parser_results(pilot_dir)
    judgement_dir = pilot_dir / "accuracy-judgements"
    judgement_dir.mkdir(parents=True, exist_ok=True)
    judgements: dict[str, dict[str, Any]] = {}
    judge_usage = []
    if not args.skip_judge:
        settings = provider_settings()
        client = create_client(settings)
        for pdf in pdfs:
            destination = judgement_dir / f"{pdf.stem}.json"
            if destination.exists():
                saved = read_json(destination)
            else:
                candidates = {
                    model: rows[pdf.stem]["profile"] for model, rows in results.items()
                    if pdf.stem in rows and not rows[pdf.stem].get("error") and rows[pdf.stem].get("profile")
                }
                judgement, api = judge_candidates(
                    client=client, settings=settings, model=args.judge_model,
                    source_text=load_source_text(pilot_dir, pdf.stem, pdf),
                    candidates=candidates, pdf_name=pdf.name,
                )
                saved = {"pdf": pdf.name, "judgement": judgement, "api": api}
                destination.write_text(json.dumps(saved, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            judge_usage.append(saved.get("api") or {})
            judgements[pdf.stem] = {
                row["candidate_id"]: row for row in saved["judgement"]["candidates"]
            }
    else:
        for destination in judgement_dir.glob("*.json"):
            saved = read_json(destination)
            judge_usage.append(saved.get("api") or {})
            judgements[destination.stem] = {
                row["candidate_id"]: row for row in saved["judgement"]["candidates"]
            }
    payload = {
        "pilot_documents": [pdf.name for pdf in pdfs],
        "parsing": [parser_summary(model, rows, pdfs, judgements) for model, rows in results.items()],
        "matching": matching_summaries(pilot_dir),
        "accuracy_judge": {
            "model": args.judge_model,
            "api_calls": len(judge_usage),
            "estimated_cost_usd": round(sum(float(row.get("estimated_cost_usd") or 0) for row in judge_usage), 8),
        },
        "account": account_usage(provider_settings()),
    }
    markdown = markdown_report(payload)
    (pilot_dir / "report.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (pilot_dir / "report.md").write_text(markdown + "\n", encoding="utf-8")
    (pilot_dir / "report.html").write_text(
        "<!doctype html><meta charset='utf-8'><title>TeamTNT Cost–Accuracy Pilot</title>"
        "<style>body{max-width:1100px;margin:40px auto;padding:0 24px;font:16px/1.55 system-ui;color:#17213a}pre{white-space:pre-wrap}table{border-collapse:collapse}</style>"
        f"<pre>{html.escape(markdown)}</pre>", encoding="utf-8"
    )
    print(pilot_dir / "report.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
