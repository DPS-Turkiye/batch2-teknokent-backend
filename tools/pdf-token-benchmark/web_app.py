#!/usr/bin/env python3
"""Dependency-free local web server for the PDF token benchmark UI."""

from __future__ import annotations

import argparse
import base64
import csv
import html
import json
import mimetypes
import os
import shutil
import statistics
import subprocess
import sys
import uuid
from datetime import UTC, datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import StringIO
from pathlib import Path
from urllib.parse import urlparse

from dotenv import load_dotenv

from ai_provider import provider_settings
from model_catalog import available_models

from matching_prototype.profile_library import (
    PROFILE_LIBRARY_PATH,
    ProfileLibraryError,
    import_parsed_profiles,
    read_profile_library,
)


SCRIPT_DIR = Path(__file__).resolve().parent
BACKEND_DIR = SCRIPT_DIR.parents[1]
STATIC_DIR = SCRIPT_DIR / "static"
RESULTS_DIR = SCRIPT_DIR / "results" / "web"
REPORTS_DIR = SCRIPT_DIR / "results" / "web-reports"
BENCHMARK_SCRIPT = SCRIPT_DIR / "benchmark.py"
MAX_UPLOAD_BYTES = 50 * 1024 * 1024
MAX_REQUEST_BYTES = 70 * 1024 * 1024
MAX_REPORT_RUNS = 50
ALLOWED_PROFILE_TYPES = {"mentor", "mentee", "individual_mentee"}
OPENAI_MODELS = {
    "gpt-5.6-luna",
    "gpt-5.6-terra",
    "gpt-5.6-sol",
    "gpt-5.6",
}
ALLOWED_DETAILS = {"low", "high", "auto"}
ALLOWED_ORDERS = {"local-first", "direct-first"}
ALLOWED_PRIVACY_MODES = {"standard", "local-redacted"}
ALLOWED_OCR_MODES = {"off", "auto", "force"}

load_dotenv(BACKEND_DIR / ".env")


class RequestError(Exception):
    def __init__(self, status: HTTPStatus, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


def require_choice(payload: dict, key: str, allowed: set[str], label: str) -> str:
    value = str(payload.get(key, ""))
    if value not in allowed:
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, f"Invalid {label}.")
    return value


def result_file(job_id: str, filename: str) -> Path:
    if not job_id.isalnum() or filename not in {"summary.csv", "all-results.json"}:
        raise RequestError(HTTPStatus.NOT_FOUND, "Result not found.")
    candidates = list((RESULTS_DIR / job_id).glob(f"*/{filename}"))
    if len(candidates) != 1 or not candidates[0].is_file():
        raise RequestError(HTTPStatus.NOT_FOUND, "Result not found.")
    return candidates[0]


def report_file(report_id: str, filename: str) -> Path:
    allowed = {"report.html", "batch-summary.csv", "batch-results.json"}
    if not report_id.isalnum() or filename not in allowed:
        raise RequestError(HTTPStatus.NOT_FOUND, "Report not found.")
    path = REPORTS_DIR / report_id / filename
    if not path.is_file():
        raise RequestError(HTTPStatus.NOT_FOUND, "Report not found.")
    return path


def import_matching_profiles(
    payload: dict, library_path: Path = PROFILE_LIBRARY_PATH
) -> dict:
    if "entries" in payload:
        return import_parsed_profiles(payload, library_path)
    sources = payload.get("sources")
    approach = payload.get("approach")
    if not isinstance(sources, list) or not 1 <= len(sources) <= MAX_REPORT_RUNS:
        raise ProfileLibraryError("No saved parser results were supplied.")
    entries = []
    for source in sources:
        if not isinstance(source, dict):
            raise ProfileLibraryError("A saved parser result is malformed.")
        job_id = str(source.get("job_id", ""))
        runs = json.loads(result_file(job_id, "all-results.json").read_text(encoding="utf-8"))
        if not isinstance(runs, list) or not runs:
            raise ProfileLibraryError("A saved parser result is empty.")
        result = runs[0].get("results", {}).get(approach, {})
        profile = result.get("profile") if isinstance(result, dict) else None
        if not isinstance(profile, dict):
            raise ProfileLibraryError(
                f"Saved result {job_id[:8]} has no usable {approach} profile."
            )
        entries.append(
            {
                "source_name": Path(str(source.get("source_name", "profile.pdf"))).name,
                "profile": profile,
            }
        )
    return import_parsed_profiles({**payload, "entries": entries}, library_path)


def finite_number(value: object, *, default: float = 0.0) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    if number != number or number in {float("inf"), float("-inf")}:
        return default
    return number


def median(values: list[float]) -> float:
    return round(statistics.median(values), 6) if values else 0.0


def normalize_report_row(raw: object) -> dict:
    if not isinstance(raw, dict):
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Invalid report row.")
    name = Path(str(raw.get("pdf", "unnamed.pdf"))).name[:180]
    status = str(raw.get("status", "failed"))
    if status not in {"completed", "failed"}:
        status = "failed"
    row = {
        "source_job_id": str(raw.get("source_job_id", ""))[:64],
        "pdf": name,
        "status": status,
        "error": str(raw.get("error", ""))[:1000],
        "pages": max(0, int(finite_number(raw.get("pages")))),
        "bytes": max(0, int(finite_number(raw.get("bytes")))),
        "extracted_characters": max(
            0, int(finite_number(raw.get("extracted_characters")))
        ),
        "local_total_tokens": max(
            0, int(finite_number(raw.get("local_total_tokens")))
        ),
        "direct_total_tokens": max(
            0, int(finite_number(raw.get("direct_total_tokens")))
        ),
        "local_input_tokens": max(
            0, int(finite_number(raw.get("local_input_tokens")))
        ),
        "direct_input_tokens": max(
            0, int(finite_number(raw.get("direct_input_tokens")))
        ),
        "local_output_tokens": max(
            0, int(finite_number(raw.get("local_output_tokens")))
        ),
        "direct_output_tokens": max(
            0, int(finite_number(raw.get("direct_output_tokens")))
        ),
        "local_cost_usd": max(0.0, finite_number(raw.get("local_cost_usd"))),
        "direct_cost_usd": max(0.0, finite_number(raw.get("direct_cost_usd"))),
        "local_latency_seconds": max(
            0.0, finite_number(raw.get("local_latency_seconds"))
        ),
        "direct_latency_seconds": max(
            0.0, finite_number(raw.get("direct_latency_seconds"))
        ),
        "local_warning_count": max(
            0, int(finite_number(raw.get("local_warning_count")))
        ),
        "direct_warning_count": max(
            0, int(finite_number(raw.get("direct_warning_count")))
        ),
        "extraction_warning_count": max(
            0, int(finite_number(raw.get("extraction_warning_count")))
        ),
    }
    row["token_difference"] = (
        row["local_total_tokens"] - row["direct_total_tokens"]
    )
    row["cost_difference_usd"] = round(
        row["local_cost_usd"] - row["direct_cost_usd"], 8
    )
    row["latency_difference_seconds"] = round(
        row["local_latency_seconds"] - row["direct_latency_seconds"], 3
    )
    direct_total = row["direct_total_tokens"]
    row["token_difference_percent"] = (
        round(row["token_difference"] / direct_total * 100, 2)
        if direct_total
        else 0.0
    )
    return row


def summarize_report(rows: list[dict]) -> dict:
    completed = [row for row in rows if row["status"] == "completed"]
    failed = [row for row in rows if row["status"] == "failed"]
    total_cost = sum(
        row["local_cost_usd"] + row["direct_cost_usd"] for row in completed
    )
    token_differences = [float(row["token_difference"]) for row in completed]
    token_percentages = [row["token_difference_percent"] for row in completed]
    cost_differences = [row["cost_difference_usd"] for row in completed]
    latency_differences = [
        row["latency_difference_seconds"] for row in completed
    ]
    local_total = sum(row["local_total_tokens"] for row in completed)
    direct_total = sum(row["direct_total_tokens"] for row in completed)
    return {
        "files_total": len(rows),
        "completed": len(completed),
        "failed": len(failed),
        "success_rate_percent": round(len(completed) / len(rows) * 100, 1),
        "api_calls_attempted": len(rows) * 2,
        "total_api_cost_usd": round(total_cost, 8),
        "local_total_tokens": local_total,
        "direct_total_tokens": direct_total,
        "total_token_difference": local_total - direct_total,
        "median_token_difference": median(token_differences),
        "median_token_difference_percent": median(token_percentages),
        "median_cost_difference_usd": median(cost_differences),
        "median_latency_difference_seconds": median(latency_differences),
        "total_warning_count": sum(
            row["local_warning_count"]
            + row["direct_warning_count"]
            + row["extraction_warning_count"]
            for row in completed
        ),
    }


def technical_summary(summary: dict) -> str:
    if not summary["completed"]:
        return "No CV completed both parsing approaches, so no comparison is available."
    token_diff = summary["median_token_difference"]
    latency_diff = summary["median_latency_difference_seconds"]
    token_winner = "Local text" if token_diff < 0 else "Direct PDF" if token_diff > 0 else "Neither approach"
    latency_winner = "local text" if latency_diff < 0 else "direct PDF" if latency_diff > 0 else "neither approach"
    return (
        f"Across {summary['completed']} successfully processed CVs, {token_winner} "
        f"used a median of {abs(token_diff):,.1f} fewer total tokens per CV. "
        f"Median end-to-end latency favored {latency_winner} by "
        f"{abs(latency_diff):.2f} seconds. These are descriptive results for this "
        "batch and call order, not a universal performance guarantee."
    )


def create_batch_csv(rows: list[dict]) -> str:
    csv_rows: list[dict] = []
    for row in rows:
        flat = {key: value for key, value in row.items() if key != "accuracy"}
        accuracy = row.get("accuracy")
        if isinstance(accuracy, dict):
            for approach in ("local_text", "direct_pdf"):
                judged = accuracy.get(approach, {})
                deterministic = accuracy.get("deterministic", {}).get(approach, {})
                for field in (
                    "overall_accuracy_score",
                    "factual_precision_score",
                    "completeness_score",
                    "evidence_quality_score",
                    "supported_claims_count",
                ):
                    flat[f"{approach}_{field}"] = judged.get(field)
                for field in (
                    "missing_information",
                    "unsupported_claims",
                    "hallucinated_information",
                    "incorrectly_marked_missing",
                ):
                    flat[f"{approach}_{field}"] = json.dumps(
                        judged.get(field, []), ensure_ascii=False
                    )
                flat[f"{approach}_quote_support_rate_percent"] = deterministic.get(
                    "quote_support_rate_percent"
                )
                flat[f"{approach}_page_quote_support_rate_percent"] = deterministic.get(
                    "page_quote_support_rate_percent"
                )
        csv_rows.append(flat)
    output = StringIO()
    fields = list(csv_rows[0].keys()) if csv_rows else ["pdf", "status", "error"]
    writer = csv.DictWriter(output, fieldnames=fields)
    writer.writeheader()
    writer.writerows(csv_rows)
    return output.getvalue()


def create_report_table_row(row: dict) -> str:
    name = html.escape(row["pdf"])
    if row["status"] != "completed":
        return (
            f"<tr><td>{name}</td><td>—</td><td>—</td><td>—</td>"
            "<td>—</td><td>—</td><td>Failed</td></tr>"
        )
    token_class = "saving" if row["token_difference"] < 0 else ""
    cost_class = "saving" if row["cost_difference_usd"] < 0 else ""
    latency_class = "saving" if row["latency_difference_seconds"] < 0 else ""
    return f'''<tr><td>{name}</td><td>{row['local_total_tokens']:,}</td><td>{row['direct_total_tokens']:,}</td>
        <td class="{token_class}">{row['token_difference']:+,} ({row['token_difference_percent']:+.1f}%)</td>
        <td class="{cost_class}">{row['cost_difference_usd']:+.6f}</td><td class="{latency_class}">{row['latency_difference_seconds']:+.2f}s</td>
        <td>Completed</td></tr>'''


def html_list(values: list[object], empty: str = "None identified.") -> str:
    if not values:
        return f'<li class="muted">{html.escape(empty)}</li>'
    return "".join(f"<li>{html.escape(str(value))}</li>" for value in values)


def create_accuracy_html(report: dict) -> str:
    accuracy = report.get("accuracy")
    if not isinstance(accuracy, dict) or accuracy.get("status") != "complete":
        return ""
    local = accuracy["local_text"]
    direct = accuracy["direct_pdf"]
    winners = accuracy["winner_counts"]
    rows: list[str] = []
    details: list[str] = []
    for document in accuracy.get("documents", []):
        name = html.escape(str(document.get("pdf", "")))
        local_judge = document["judge"]["local_text"]
        direct_judge = document["judge"]["direct_pdf"]
        local_det = document["deterministic"]["local_text"]
        direct_det = document["deterministic"]["direct_pdf"]
        local_quote = local_det["quote_support_rate_percent"]
        direct_quote = direct_det["quote_support_rate_percent"]
        rows.append(
            f"<tr><td>{name}</td><td>{local_judge['overall_accuracy_score']}</td>"
            f"<td>{direct_judge['overall_accuracy_score']}</td>"
            f"<td>{local_judge['completeness_score']}</td><td>{direct_judge['completeness_score']}</td>"
            f"<td>{str(local_quote) + '%' if local_quote is not None else '—'}</td>"
            f"<td>{str(direct_quote) + '%' if direct_quote is not None else '—'}</td></tr>"
        )
        local_missing = local_judge.get("missing_information", [])
        direct_missing = direct_judge.get("missing_information", [])
        local_bad = local_judge.get("unsupported_claims", []) + local_judge.get("hallucinated_information", [])
        direct_bad = direct_judge.get("unsupported_claims", []) + direct_judge.get("hallucinated_information", [])
        limitations = document["judge"].get("document_limitations", [])
        details.append(
            f'''<details><summary><strong>{name}</strong> · Local missing {len(local_missing)} · Direct missing {len(direct_missing)}</summary>
            <div class="missing-grid"><section><h3>Local text — missing from JSON</h3><ul>{html_list(local_missing)}</ul>
            <h3>Local text — unsupported or hallucinated</h3><ul>{html_list(local_bad)}</ul></section>
            <section><h3>Direct PDF — missing from JSON</h3><ul>{html_list(direct_missing)}</ul>
            <h3>Direct PDF — unsupported or hallucinated</h3><ul>{html_list(direct_bad)}</ul></section></div>
            <p class="muted"><strong>Source/document limitations:</strong> {html.escape('; '.join(limitations) or 'None noted.')}</p></details>'''
        )
    method_limitations = accuracy.get("methodology", {}).get("limitations", [])
    return f'''
<h2>Accuracy against the actual CVs</h2>
<p class="summary">The original PDF was treated as ground truth. Accuracy combines a structured model review with deterministic checks that evidence quotes occur in the document and, where cited, on the stated page.</p>
<div class="metrics"><div class="metric"><span>Median overall accuracy · local</span><strong>{local['median_overall_accuracy_score']:.1f}</strong></div>
<div class="metric"><span>Median overall accuracy · direct</span><strong>{direct['median_overall_accuracy_score']:.1f}</strong></div>
<div class="metric"><span>Missing facts · local / direct</span><strong>{local['missing_information_count']} / {direct['missing_information_count']}</strong></div>
<div class="metric"><span>CV wins · local / direct / tie</span><strong>{winners['local_text']} / {winners['direct_pdf']} / {winners['tie']}</strong></div></div>
<div class="table-wrap"><table><thead><tr><th>CV file</th><th>Local overall</th><th>Direct overall</th><th>Local completeness</th><th>Direct completeness</th><th>Local quote support</th><th>Direct quote support</th></tr></thead><tbody>{''.join(rows)}</tbody></table></div>
<h2>Missing parts and unsupported information</h2>
<p>“Missing from JSON” means useful in-scope information was present in the actual CV but absent or materially incorrect in that parser output. Information absent from the CV is a source limitation and is not counted as a parser miss.</p>
<div class="missing-list">{''.join(details)}</div>
<h2>Accuracy methodology and limitations</h2><ul>{html_list(method_limitations)}</ul>
<p class="muted">Judge: {html.escape(str(accuracy.get('judge_model', '')))} · {accuracy.get('judge_api_calls', 0)} calls · estimated audit cost ${accuracy.get('judge_estimated_cost_usd', 0):.6f}. API billing remains authoritative.</p>'''


def create_report_html(report: dict) -> str:
    summary = report["summary"]
    rows = report["rows"]
    completed = [row for row in rows if row["status"] == "completed"]
    max_tokens = max(
        [1]
        + [
            max(row["local_total_tokens"], row["direct_total_tokens"])
            for row in completed
        ]
    )
    chart_rows = "".join(
        f'''<div class="chart-row"><div class="chart-label">{html.escape(row["pdf"])}</div>
        <div class="bars"><div class="bar-line"><span>Local</span><i class="local" style="width:{row['local_total_tokens'] / max_tokens * 100:.2f}%"></i><b>{row['local_total_tokens']:,}</b></div>
        <div class="bar-line"><span>Direct</span><i class="direct" style="width:{row['direct_total_tokens'] / max_tokens * 100:.2f}%"></i><b>{row['direct_total_tokens']:,}</b></div></div></div>'''
        for row in completed
    ) or '<p class="muted">No completed comparisons to chart.</p>'
    table_rows = "".join(create_report_table_row(row) for row in rows)
    failures = "".join(
        f"<li><strong>{html.escape(row['pdf'])}</strong>: {html.escape(row['error'] or 'Benchmark did not complete.')}</li>"
        for row in rows if row["status"] == "failed"
    ) or "<li>No failed CVs in this batch.</li>"
    generated = html.escape(report["generated_at"])
    settings = report["settings"]
    accuracy_html = create_accuracy_html(report)
    return f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light"><title>PDF Parsing Batch Benchmark Report</title>
<style>:root{{--ink:#0b1535;--muted:#53607a;--line:#cad3e2;--surface:#f7f9fc;--blue:#175ce6;--green:#078553}}*{{box-sizing:border-box}}body{{margin:0;color:var(--ink);font:15px/1.55 Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}}main{{max-width:1120px;margin:auto;padding:48px 32px 72px}}h1{{font-size:38px;letter-spacing:-.04em;margin:0}}h2{{font-size:23px;margin:42px 0 12px}}h3{{font-size:17px;margin:26px 0 8px}}.meta,.muted{{color:var(--muted)}}.summary{{font-size:18px;max-width:920px}}.metrics{{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:26px 0}}.metric{{border:1px solid var(--line);padding:18px}}.metric span{{color:var(--muted);display:block;font-size:13px}}.metric strong{{display:block;font-size:25px;margin-top:4px}}.chart{{border-top:1px solid var(--line);padding-top:10px}}.chart-row{{display:grid;grid-template-columns:190px 1fr;gap:16px;margin:15px 0}}.chart-label{{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}}.bar-line{{align-items:center;display:grid;grid-template-columns:46px minmax(0,1fr) 70px;gap:8px;font-size:12px}}.bar-line i{{display:block;height:9px;min-width:2px}}.bar-line b{{font-family:ui-monospace,monospace;font-weight:600;text-align:right}}.local{{background:var(--green)}}.direct{{background:var(--blue)}}.table-wrap{{border:1px solid var(--line);overflow:auto}}table{{border-collapse:collapse;min-width:920px;width:100%}}th,td{{border-bottom:1px solid var(--line);padding:12px;text-align:right}}th{{background:var(--surface);font-size:12px}}th:first-child,td:first-child{{text-align:left}}tr:last-child td{{border-bottom:0}}td:not(:first-child){{font-family:ui-monospace,monospace;font-size:12px}}.saving{{color:var(--green)}}.notes,.missing-grid{{display:grid;grid-template-columns:1fr 1fr;gap:36px}}.missing-list details{{border:1px solid var(--line);margin:10px 0;padding:14px 16px}}.missing-list summary{{cursor:pointer}}ul{{padding-left:20px}}footer{{border-top:1px solid var(--line);color:var(--muted);font-size:12px;margin-top:44px;padding-top:18px}}@media(max-width:760px){{main{{padding:30px 18px}}.metrics,.notes,.missing-grid{{grid-template-columns:1fr}}.chart-row{{grid-template-columns:1fr}}h1{{font-size:31px}}}}@media print{{main{{max-width:none;padding:18px}}}}</style></head>
<body><main><h1>PDF Parsing Batch Benchmark Report</h1><p class="meta">Generated {generated} · Model {html.escape(str(settings.get('model', '')))} · {html.escape(str(settings.get('profile_type', '')))} profile · {html.escape(str(settings.get('pdf_detail', '')))} PDF detail · {html.escape(str(settings.get('order', '')))} call order</p>
<h2>Technical summary</h2><p class="summary">{html.escape(report['technical_summary'])}</p>
<div class="metrics"><div class="metric"><span>CVs completed</span><strong>{summary['completed']} / {summary['files_total']}</strong></div><div class="metric"><span>Total API cost</span><strong>${summary['total_api_cost_usd']:.6f}</strong></div><div class="metric"><span>Median token difference</span><strong>{summary['median_token_difference']:+,.1f}</strong></div><div class="metric"><span>Median latency difference</span><strong>{summary['median_latency_difference_seconds']:+.2f}s</strong></div></div>
<h2>Total tokens varied by CV</h2><p>The paired horizontal bars compare total input plus output tokens for each completed CV. Bars share a zero baseline and a common scale; exact values are listed at right.</p><div class="chart">{chart_rows}</div>
<h2>Per-CV results</h2><p>Negative differences mean the local-text path used fewer tokens, cost less, or completed faster than direct PDF for that CV.</p><div class="table-wrap"><table><thead><tr><th>CV file</th><th>Local tokens</th><th>Direct tokens</th><th>Token difference</th><th>Cost difference ($)</th><th>Latency difference</th><th>Status</th></tr></thead><tbody>{table_rows}</tbody></table></div>
{accuracy_html}
<div class="notes"><section><h2>Scope and metric definitions</h2><ul><li><strong>Cohort:</strong> PDFs selected for this batch; summaries exclude failed comparisons.</li><li><strong>Tokens:</strong> total input plus output tokens reported by the API.</li><li><strong>Cost:</strong> estimate using the rates embedded in the benchmark script.</li><li><strong>Latency:</strong> end-to-end time from submission to final response.</li><li><strong>Comparison:</strong> local text minus direct PDF; negative favors local text.</li></ul></section><section><h2>Limitations and robustness</h2><ul><li>Results depend on CV length, visual structure, scan quality, model behavior, and network conditions.</li><li>Prompt caching and fixed call order can affect usage. Repeat with reversed order before generalizing.</li><li>The accuracy audit is model-judged and should be supplemented with human review before production decisions.</li><li>Small batches describe only the tested documents.</li></ul></section></div>
<h2>Failures and warnings</h2><ul>{failures}</ul>
<h2>Recommended next steps</h2><ol><li>Review profile fields and evidence for extraction accuracy, not only token usage.</li><li>Repeat the same batch with reversed call order to assess cache sensitivity.</li><li>Separate scanned and digitally generated CVs before choosing a default production path.</li></ol>
<h2>Further questions</h2><ul><li>Does one approach omit high-value profile fields more often?</li><li>How do results change for scanned PDFs or CVs with complex multi-column layouts?</li><li>Is the token difference large enough to matter at expected production volume?</li></ul>
<footer>Source: PDF Parse Lab batch results. API usage and provider billing remain authoritative.</footer></main></body></html>'''


def create_batch_report(payload: dict) -> dict:
    raw_rows = payload.get("rows")
    if not isinstance(raw_rows, list) or not 1 <= len(raw_rows) <= MAX_REPORT_RUNS:
        raise RequestError(
            HTTPStatus.UNPROCESSABLE_ENTITY,
            f"Report must contain between 1 and {MAX_REPORT_RUNS} CV rows.",
        )
    settings = payload.get("settings")
    if not isinstance(settings, dict):
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Report settings are missing.")
    rows = [normalize_report_row(row) for row in raw_rows]
    summary = summarize_report(rows)
    report_id = uuid.uuid4().hex
    report_dir = REPORTS_DIR / report_id
    report_dir.mkdir(parents=True, exist_ok=False)
    report = {
        "report_id": report_id,
        "generated_at": datetime.now(UTC).strftime("%B %d, %Y at %H:%M UTC"),
        "settings": {
            "profile_type": str(settings.get("profile_type", ""))[:40],
            "model": str(settings.get("model", ""))[:80],
            "pdf_detail": str(settings.get("pdf_detail", ""))[:40],
            "order": str(settings.get("order", ""))[:40],
        },
        "technical_summary": technical_summary(summary),
        "summary": summary,
        "rows": rows,
    }
    (report_dir / "batch-results.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (report_dir / "batch-summary.csv").write_text(
        create_batch_csv(rows), encoding="utf-8"
    )
    (report_dir / "report.html").write_text(
        create_report_html(report), encoding="utf-8"
    )
    report["report_url"] = f"/api/reports/{report_id}/report.html"
    report["csv_url"] = f"/api/reports/{report_id}/batch-summary.csv"
    report["json_url"] = f"/api/reports/{report_id}/batch-results.json"
    return report


def run_uploaded_benchmark(payload: dict) -> dict:
    api_settings = provider_settings()
    if not api_settings.api_key:
        raise RequestError(
            HTTPStatus.SERVICE_UNAVAILABLE,
            f"{api_settings.key_environment_variable} is empty in {BACKEND_DIR / '.env'}",
        )

    profile_type = require_choice(
        payload, "profile_type", ALLOWED_PROFILE_TYPES, "profile type"
    )
    if api_settings.provider == "openai":
        model = require_choice(payload, "model", OPENAI_MODELS, "model")
    else:
        model = str(payload.get("model", "")).strip()
        if not model or len(model) > 160 or any(char.isspace() for char in model):
            raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Invalid model.")
    pdf_detail = require_choice(payload, "pdf_detail", ALLOWED_DETAILS, "PDF detail")
    order = require_choice(payload, "order", ALLOWED_ORDERS, "call order")
    privacy_mode = str(payload.get("privacy_mode", "standard"))
    if privacy_mode not in ALLOWED_PRIVACY_MODES:
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Invalid privacy mode.")
    ocr_mode = str(payload.get("ocr_mode", "off"))
    if ocr_mode not in ALLOWED_OCR_MODES:
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Invalid OCR mode.")

    original_name = Path(str(payload.get("filename", "upload.pdf"))).name
    if Path(original_name).suffix.lower() != ".pdf":
        raise RequestError(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "Please upload a PDF.")

    encoded_pdf = payload.get("pdf_base64")
    if not isinstance(encoded_pdf, str) or not encoded_pdf:
        raise RequestError(HTTPStatus.BAD_REQUEST, "The PDF content is missing.")
    try:
        content = base64.b64decode(encoded_pdf, validate=True)
    except (ValueError, TypeError) as error:
        raise RequestError(HTTPStatus.BAD_REQUEST, "The PDF data is invalid.") from error

    if len(content) > MAX_UPLOAD_BYTES:
        raise RequestError(
            HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
            "PDF must be smaller than 50 MB.",
        )
    if content[:5] != b"%PDF-":
        raise RequestError(
            HTTPStatus.UNSUPPORTED_MEDIA_TYPE,
            "The file is not a valid PDF.",
        )

    job_id = uuid.uuid4().hex
    job_dir = RESULTS_DIR / job_id
    upload_dir = job_dir / "upload"
    upload_dir.mkdir(parents=True, exist_ok=False)
    upload_path = upload_dir / "source.pdf"
    upload_path.write_bytes(content)

    command = [
        sys.executable,
        str(BENCHMARK_SCRIPT),
        "--profile-type",
        profile_type,
        "--model",
        model,
        "--pdf-detail",
        pdf_detail,
        "--order",
        order,
        "--ocr-mode",
        ocr_mode,
        "--results-dir",
        str(job_dir),
        str(upload_path),
    ]
    if privacy_mode == "local-redacted":
        command[2:2] = ["--approach", "local_text", "--skip-verification"]

    try:
        completed = subprocess.run(
            command,
            cwd=SCRIPT_DIR,
            capture_output=True,
            check=False,
            text=True,
            timeout=360,
        )
    except subprocess.TimeoutExpired as error:
        raise RequestError(
            HTTPStatus.GATEWAY_TIMEOUT,
            "The benchmark exceeded the six-minute timeout.",
        ) from error
    finally:
        shutil.rmtree(upload_dir, ignore_errors=True)

    process_output = "\n".join(
        part.strip() for part in (completed.stdout, completed.stderr) if part.strip()
    )
    result_candidates = list(job_dir.glob("*/all-results.json"))
    if not result_candidates:
        shutil.rmtree(job_dir, ignore_errors=True)
        raise RequestError(
            HTTPStatus.BAD_GATEWAY,
            process_output or "The benchmark did not produce a result.",
        )

    runs = json.loads(result_candidates[0].read_text(encoding="utf-8"))
    if not runs:
        raise RequestError(HTTPStatus.BAD_GATEWAY, "The result was empty.")

    run = runs[0]
    run["pdf"] = original_name
    run["job_id"] = job_id
    run["summary_csv_url"] = f"/api/results/{job_id}/summary.csv"
    run["result_json_url"] = f"/api/results/{job_id}/all-results.json"
    run["process_exit_code"] = completed.returncode
    run["process_output"] = process_output
    return run


class BenchmarkHandler(BaseHTTPRequestHandler):
    server_version = "PDFParseLab/1.0"

    def log_message(self, format_string: str, *args: object) -> None:
        sys.stderr.write(
            f"[pdf-parse-lab] {self.address_string()} "
            f"{format_string % args}\n"
        )

    def send_bytes(
        self,
        payload: bytes,
        *,
        content_type: str,
        status: HTTPStatus = HTTPStatus.OK,
        disposition: str | None = None,
    ) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        if disposition:
            self.send_header("Content-Disposition", disposition)
        self.end_headers()
        self.wfile.write(payload)

    def send_json(
        self, payload: dict | list, status: HTTPStatus = HTTPStatus.OK
    ) -> None:
        self.send_bytes(
            json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            content_type="application/json; charset=utf-8",
            status=status,
        )

    def send_error_json(self, error: RequestError) -> None:
        self.send_json({"detail": error.message}, error.status)

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        try:
            path = urlparse(self.path).path
            if path == "/":
                self.send_static_file(STATIC_DIR / "index.html")
                return
            if path.startswith("/static/"):
                relative = path.removeprefix("/static/")
                if "/" in relative or relative not in {"styles.css", "app.js"}:
                    raise RequestError(HTTPStatus.NOT_FOUND, "Asset not found.")
                self.send_static_file(STATIC_DIR / relative)
                return
            if path.startswith("/api/results/"):
                parts = path.strip("/").split("/")
                if len(parts) != 4:
                    raise RequestError(HTTPStatus.NOT_FOUND, "Result not found.")
                _, _, job_id, filename = parts
                result_path = result_file(job_id, filename)
                content_type = (
                    "text/csv; charset=utf-8"
                    if filename.endswith(".csv")
                    else "application/json; charset=utf-8"
                )
                self.send_bytes(
                    result_path.read_bytes(),
                    content_type=content_type,
                    disposition=f'attachment; filename="pdf-benchmark-{job_id[:8]}.{filename.rsplit(".", 1)[-1]}"',
                )
                return
            if path.startswith("/api/reports/"):
                parts = path.strip("/").split("/")
                if len(parts) != 4:
                    raise RequestError(HTTPStatus.NOT_FOUND, "Report not found.")
                _, _, report_id, filename = parts
                path_to_report = report_file(report_id, filename)
                content_type = {
                    "report.html": "text/html; charset=utf-8",
                    "batch-summary.csv": "text/csv; charset=utf-8",
                    "batch-results.json": "application/json; charset=utf-8",
                }[filename]
                disposition = None
                if filename != "report.html":
                    disposition = f'attachment; filename="pdf-batch-{report_id[:8]}.{filename.rsplit(".", 1)[-1]}"'
                self.send_bytes(
                    path_to_report.read_bytes(),
                    content_type=content_type,
                    disposition=disposition,
                )
                return
            if path == "/api/matching-library":
                library = read_profile_library()
                self.send_json(
                    {
                        "mentor_count": len(library["mentors"]),
                        "mentee_count": len(library["mentees"]),
                    }
                )
                return
            if path == "/api/config":
                settings = provider_settings()
                if settings.provider == "openai":
                    models = [
                        {"id": model, "name": model, "context_length": None}
                        for model in sorted(OPENAI_MODELS)
                    ]
                    catalog_error = None
                else:
                    models, catalog_error = available_models(settings, "parsing")
                self.send_json(
                    {
                        "provider": settings.provider,
                        "default_model": settings.parsing_model,
                        "models": models,
                        "catalog_error": catalog_error,
                        "configured": bool(settings.api_key),
                        "key_environment_variable": settings.key_environment_variable,
                    }
                )
                return
            raise RequestError(HTTPStatus.NOT_FOUND, "Page not found.")
        except RequestError as error:
            self.send_error_json(error)

    def send_static_file(self, path: Path) -> None:
        if not path.is_file():
            raise RequestError(HTTPStatus.NOT_FOUND, "Asset not found.")
        content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        if content_type.startswith("text/") or content_type == "application/javascript":
            content_type += "; charset=utf-8"
        self.send_bytes(path.read_bytes(), content_type=content_type)

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        try:
            path = urlparse(self.path).path
            if path not in {"/api/benchmark", "/api/report", "/api/matching-library"}:
                raise RequestError(HTTPStatus.NOT_FOUND, "Page not found.")
            if self.headers.get_content_type() != "application/json":
                raise RequestError(
                    HTTPStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Expected a JSON request.",
                )
            content_length = int(self.headers.get("Content-Length", "0"))
            if content_length <= 0 or content_length > MAX_REQUEST_BYTES:
                raise RequestError(
                    HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                    "Request is empty or too large.",
                )
            try:
                payload = json.loads(self.rfile.read(content_length))
            except (json.JSONDecodeError, UnicodeDecodeError) as error:
                raise RequestError(
                    HTTPStatus.BAD_REQUEST,
                    "The request body is invalid.",
                ) from error
            if path == "/api/benchmark":
                self.send_json(run_uploaded_benchmark(payload))
            elif path == "/api/report":
                self.send_json(create_batch_report(payload), HTTPStatus.CREATED)
            else:
                self.send_json(import_matching_profiles(payload), HTTPStatus.CREATED)
        except ProfileLibraryError as error:
            self.send_error_json(
                RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, str(error))
            )
        except RequestError as error:
            self.send_error_json(error)
        except Exception as error:  # Keep unexpected server errors out of the browser.
            self.log_error("Unexpected error: %s", error)
            self.send_error_json(
                RequestError(
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                    "Unexpected server error. Check the terminal for details.",
                )
            )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the PDF Parse Lab web UI.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    server = ThreadingHTTPServer((args.host, args.port), BenchmarkHandler)
    print(f"PDF Parse Lab running at http://{args.host}:{args.port}")
    print("Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping PDF Parse Lab.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
