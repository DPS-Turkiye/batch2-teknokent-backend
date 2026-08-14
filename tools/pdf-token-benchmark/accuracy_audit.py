#!/usr/bin/env python3
"""Audit saved profile JSON against the original CV PDFs and enrich a batch report."""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import statistics
import sys
import time
import unicodedata
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from openai import OpenAI
from pydantic import BaseModel, Field
from pypdf import PdfReader

from benchmark import dump_model, dump_usage, estimate_cost, normalize_usage
from web_app import create_batch_csv, create_report_html
from evidence_verification import verify_evidence_quote


SCRIPT_DIR = Path(__file__).resolve().parent
BACKEND_DIR = SCRIPT_DIR.parents[1]
DEFAULT_REPORT_ID = "7c32ad2e82224dbfa9a27b48e684020f"


class ApproachAccuracy(BaseModel):
    factual_precision_score: int = Field(ge=0, le=100)
    completeness_score: int = Field(ge=0, le=100)
    evidence_quality_score: int = Field(ge=0, le=100)
    overall_accuracy_score: int = Field(ge=0, le=100)
    supported_claims_count: int = Field(ge=0)
    unsupported_claims: list[str]
    missing_information: list[str]
    correctly_marked_missing: list[str]
    incorrectly_marked_missing: list[str]
    hallucinated_information: list[str]
    notes: list[str]


class AccuracyJudgement(BaseModel):
    local_text: ApproachAccuracy
    direct_pdf: ApproachAccuracy
    comparative_summary: str
    document_limitations: list[str]


JUDGE_INSTRUCTIONS = """
You are auditing two structured CV profiles against the attached original PDF.
The PDF is the only ground truth. Evaluate each profile independently and conservatively.

Scoring definitions:
- factual_precision_score: how often populated facts are supported by the PDF.
- completeness_score: coverage of useful, in-scope professional facts present in the PDF.
- evidence_quality_score: whether evidence claims, short quotes, and page references support the claim.
- overall_accuracy_score: balanced assessment of precision, completeness, and evidence quality.

Important rules:
- A fact absent from the PDF is source-missing, not a parser omission.
- Placeholder text such as "Company Name" is not a real employer or person name.
- Do not penalize omission of excluded sensitive data such as phone, email, address, age, photo,
  marital status, or other facts outside the profile schema.
- missing_information must list useful in-scope facts present in the PDF but missing or materially
  incorrect in that profile. Be specific and concise.
- correctly_marked_missing lists profile missing_fields entries that truly are absent from the PDF.
- incorrectly_marked_missing lists profile missing_fields entries where the PDF does contain the fact.
- hallucinated_information is for claims not supported by the PDF. Unsupported inferences belong here.
- Do not reward plausible guesses. Do not invent a ground-truth value when the PDF is ambiguous.
- Keep every list concise and deduplicated. Return [] where there are no findings.
""".strip()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-id", default=DEFAULT_REPORT_ID)
    parser.add_argument("--pdf-dir", type=Path, default=SCRIPT_DIR / "test-cvs-35")
    parser.add_argument("--model", default="gpt-5.6-luna")
    parser.add_argument("--pdf-detail", choices=("low", "auto", "high"), default="low")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--limit", type=int, default=None)
    return parser.parse_args()


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalized(value: str) -> str:
    value = unicodedata.normalize("NFKD", value).casefold()
    value = "".join(character for character in value if not unicodedata.combining(character))
    return re.sub(r"[^a-z0-9]+", " ", value).strip()


def pdf_pages(path: Path) -> list[str]:
    return [(page.extract_text() or "") for page in PdfReader(str(path)).pages]


def evidence_items(value: Any, path: str = "profile") -> list[dict[str, Any]]:
    found: list[dict[str, Any]] = []
    if isinstance(value, dict):
        if {"claim", "page", "quote", "information_type"}.issubset(value):
            found.append({"path": path, **value})
        for key, child in value.items():
            found.extend(evidence_items(child, f"{path}.{key}"))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            found.extend(evidence_items(child, f"{path}[{index}]"))
    return found


def deterministic_evidence(profile: dict[str, Any], pages: list[str]) -> dict[str, Any]:
    page_text = [normalized(page) for page in pages]
    whole_text = " ".join(page_text)
    findings: list[dict[str, Any]] = []
    for item in evidence_items(profile):
        quote = str(item.get("quote") or "")
        page = item.get("page")
        valid_page = isinstance(page, int) and 1 <= page <= len(pages)
        document_match = verify_evidence_quote(quote, whole_text)
        page_match = verify_evidence_quote(quote, page_text[page - 1]) if valid_page else {
            "supported": False, "similarity": 0.0, "method": "invalid-page"
        }
        quote_in_document = document_match["supported"]
        quote_on_page = valid_page and page_match["supported"]
        findings.append({
            "path": item["path"],
            "claim": item.get("claim"),
            "page": page,
            "quote": item.get("quote"),
            "page_valid": valid_page,
            "quote_in_document": quote_in_document,
            "quote_on_cited_page": quote_on_page,
            "document_similarity": document_match["similarity"],
            "document_match_method": document_match["method"],
            "page_similarity": page_match["similarity"],
            "page_match_method": page_match["method"],
        })
    count = len(findings)
    cited = [finding for finding in findings if finding["page"] is not None]
    return {
        "evidence_count": count,
        "quote_supported_count": sum(finding["quote_in_document"] for finding in findings),
        "quote_support_rate_percent": round(sum(finding["quote_in_document"] for finding in findings) / count * 100, 1) if count else None,
        "cited_evidence_count": len(cited),
        "valid_page_count": sum(finding["page_valid"] for finding in cited),
        "quote_on_cited_page_count": sum(finding["quote_on_cited_page"] for finding in cited),
        "page_quote_support_rate_percent": round(sum(finding["quote_on_cited_page"] for finding in cited) / len(cited) * 100, 1) if cited else None,
        "findings": findings,
    }


def locate_result_window(report_rows: list[dict[str, Any]]) -> list[Path]:
    candidates = sorted((SCRIPT_DIR / "results" / "web").glob("*/*/all-results.json"), key=lambda path: path.stat().st_mtime)
    expected = [(row.get("local_total_tokens"), row.get("direct_total_tokens")) for row in report_rows]
    matches: list[list[Path]] = []
    for start in range(0, len(candidates) - len(expected) + 1):
        window = candidates[start : start + len(expected)]
        actual: list[tuple[Any, Any]] = []
        valid = True
        for path in window:
            runs = read_json(path)
            if not isinstance(runs, list) or len(runs) != 1:
                valid = False
                break
            results = runs[0].get("results", {})
            actual.append((results.get("local_text", {}).get("usage", {}).get("total_tokens"), results.get("direct_pdf", {}).get("usage", {}).get("total_tokens")))
        if valid and actual == expected:
            matches.append(window)
    if len(matches) != 1:
        raise RuntimeError(f"Expected one exact result mapping; found {len(matches)}.")
    return matches[0]


def judge_one(client: OpenAI, *, pdf: Path, local_profile: dict[str, Any], direct_profile: dict[str, Any], model: str, detail: str) -> tuple[dict[str, Any], dict[str, Any]]:
    encoded = base64.b64encode(pdf.read_bytes()).decode("ascii")
    started = time.perf_counter()
    response = client.responses.parse(
        model=model,
        reasoning={"effort": "none"},
        max_output_tokens=4000,
        store=False,
        input=[
            {"role": "system", "content": JUDGE_INSTRUCTIONS},
            {"role": "user", "content": [
                {"type": "input_file", "filename": pdf.name, "file_data": f"data:application/pdf;base64,{encoded}", "detail": detail},
                {"type": "input_text", "text": "LOCAL_TEXT PROFILE JSON:\n" + json.dumps(local_profile, ensure_ascii=False) + "\n\nDIRECT_PDF PROFILE JSON:\n" + json.dumps(direct_profile, ensure_ascii=False)},
            ]},
        ],
        text_format=AccuracyJudgement,
    )
    raw_usage = dump_usage(response)
    usage = normalize_usage(raw_usage)
    metadata = {
        "response_id": getattr(response, "id", None),
        "model": getattr(response, "model", model),
        "elapsed_seconds": round(time.perf_counter() - started, 3),
        "usage": usage,
        "estimated_cost_usd": estimate_cost(model, usage),
    }
    return dump_model(getattr(response, "output_parsed", None)), metadata


def approach_totals(items: list[dict[str, Any]], approach: str) -> dict[str, Any]:
    judgements = [item["judge"][approach] for item in items]
    deterministic = [item["deterministic"][approach] for item in items]
    score_fields = ("overall_accuracy_score", "factual_precision_score", "completeness_score", "evidence_quality_score")
    totals: dict[str, Any] = {}
    for field in score_fields:
        values = [entry[field] for entry in judgements]
        totals[f"mean_{field}"] = round(statistics.mean(values), 1)
        totals[f"median_{field}"] = round(statistics.median(values), 1)
    quote_denominator = sum(entry["evidence_count"] for entry in deterministic)
    page_denominator = sum(entry["cited_evidence_count"] for entry in deterministic)
    totals.update({
        "missing_information_count": sum(len(entry["missing_information"]) for entry in judgements),
        "hallucinated_information_count": sum(len(entry["hallucinated_information"]) for entry in judgements),
        "incorrectly_marked_missing_count": sum(len(entry["incorrectly_marked_missing"]) for entry in judgements),
        "evidence_count": quote_denominator,
        "quote_support_rate_percent": round(sum(entry["quote_supported_count"] for entry in deterministic) / quote_denominator * 100, 1) if quote_denominator else None,
        "page_quote_support_rate_percent": round(sum(entry["quote_on_cited_page_count"] for entry in deterministic) / page_denominator * 100, 1) if page_denominator else None,
    })
    return totals


def normalize_judge_score_scale(item: dict[str, Any]) -> list[str]:
    """Normalize evident 5–10 judge output to the report's requested 0–100 scale."""
    score_fields = (
        "factual_precision_score",
        "completeness_score",
        "evidence_quality_score",
        "overall_accuracy_score",
    )
    adjusted: list[str] = []
    for approach in ("local_text", "direct_pdf"):
        scores = item["judge"][approach]
        values = [scores[field] for field in score_fields]
        if min(values) >= 5 and max(values) <= 10:
            for field in score_fields:
                scores[field] *= 10
            adjusted.append(approach)
    if adjusted:
        item.setdefault("quality_controls", {})[
            "score_scale_normalized_from_10"
        ] = adjusted
    return adjusted


def aggregate(items: list[dict[str, Any]], model: str) -> dict[str, Any]:
    local_wins = sum(item["judge"]["local_text"]["overall_accuracy_score"] > item["judge"]["direct_pdf"]["overall_accuracy_score"] for item in items)
    direct_wins = sum(item["judge"]["direct_pdf"]["overall_accuracy_score"] > item["judge"]["local_text"]["overall_accuracy_score"] for item in items)
    audit_cost = sum(float(item.get("api", {}).get("estimated_cost_usd") or 0) for item in items)
    return {
        "status": "complete",
        "audited_at": datetime.now(UTC).strftime("%B %d, %Y at %H:%M UTC"),
        "documents_audited": len(items),
        "judge_model": model,
        "judge_api_calls": len(items),
        "judge_estimated_cost_usd": round(audit_cost, 8),
        "winner_counts": {"local_text": local_wins, "direct_pdf": direct_wins, "tie": len(items) - local_wins - direct_wins},
        "local_text": approach_totals(items, "local_text"),
        "direct_pdf": approach_totals(items, "direct_pdf"),
        "methodology": {
            "ground_truth": "Original CV PDF",
            "deterministic_checks": "Normalized evidence quote presence in the PDF and on the cited page.",
            "model_judgement": "One structured comparison call per CV, evaluating both saved profiles against the same original PDF.",
            "score_normalization": "When all four judge scores for an approach were returned on an evident 5–10 scale, each was multiplied by 10 to match the requested 0–100 scale. Findings and counts were not altered.",
            "limitations": [
                "Scores are model-judged estimates, not human-labeled ground truth.",
                "The judge is from the same model family as the parser, which may introduce correlated bias.",
                "Deterministic quote support verifies citation presence, not whether every claim is semantically correct.",
                "A source-absent field is separated from information present in the PDF but missed by a parser.",
            ],
        },
        "documents": items,
    }


def main() -> int:
    args = parse_args()
    load_dotenv(BACKEND_DIR / ".env")
    if not os.getenv("OPENAI_API_KEY", "").strip():
        print(f"error: OPENAI_API_KEY is empty in {BACKEND_DIR / '.env'}", file=sys.stderr)
        return 2
    report_dir = SCRIPT_DIR / "results" / "web-reports" / args.report_id
    report_path = report_dir / "batch-results.json"
    report = read_json(report_path)
    rows = report["rows"]
    pdfs = sorted(args.pdf_dir.expanduser().resolve().glob("*.pdf"))
    if len(pdfs) != len(rows):
        raise RuntimeError(f"Found {len(pdfs)} PDFs for {len(rows)} report rows.")
    result_paths = locate_result_window(rows)
    audit_dir = report_dir / "accuracy-audits"
    audit_dir.mkdir(exist_ok=True)
    client = OpenAI()
    items: list[dict[str, Any]] = []
    maximum = min(len(rows), args.limit) if args.limit else len(rows)
    for index, (row, pdf, result_path) in enumerate(zip(rows, pdfs, result_paths), start=1):
        if index > maximum:
            break
        if row["pdf"] != pdf.name:
            raise RuntimeError(f"PDF order mismatch: {row['pdf']} != {pdf.name}")
        destination = audit_dir / f"{pdf.stem}.json"
        if destination.exists() and not args.force:
            item = read_json(destination)
            print(f"[{index}/{maximum}] reused {pdf.name}", flush=True)
        else:
            run = read_json(result_path)[0]
            local_profile = run["results"]["local_text"]["profile"]
            direct_profile = run["results"]["direct_pdf"]["profile"]
            pages = pdf_pages(pdf)
            judgement, api = judge_one(client, pdf=pdf, local_profile=local_profile, direct_profile=direct_profile, model=args.model, detail=args.pdf_detail)
            item = {
                "pdf": pdf.name,
                "source_job_id": result_path.parents[1].name,
                "judge": judgement,
                "deterministic": {
                    "local_text": deterministic_evidence(local_profile, pages),
                    "direct_pdf": deterministic_evidence(direct_profile, pages),
                },
                "api": api,
            }
            write_json(destination, item)
            print(f"[{index}/{maximum}] audited {pdf.name} ({api['elapsed_seconds']:.1f}s)", flush=True)
        adjusted = normalize_judge_score_scale(item)
        if adjusted:
            write_json(destination, item)
        items.append(item)
    if len(items) != len(rows):
        print(f"Partial audit saved ({len(items)}/{len(rows)}). Re-run without --limit to finish.")
        return 0
    accuracy = aggregate(items, args.model)
    report["accuracy"] = accuracy
    for row, item in zip(rows, items):
        row["source_job_id"] = item["source_job_id"]
        row["accuracy"] = {
            "local_text": item["judge"]["local_text"],
            "direct_pdf": item["judge"]["direct_pdf"],
            "deterministic": item["deterministic"],
            "comparative_summary": item["judge"]["comparative_summary"],
            "document_limitations": item["judge"]["document_limitations"],
        }
    write_json(report_path, report)
    (report_dir / "batch-summary.csv").write_text(create_batch_csv(rows), encoding="utf-8")
    (report_dir / "report.html").write_text(create_report_html(report), encoding="utf-8")
    print(f"Accuracy report updated: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
