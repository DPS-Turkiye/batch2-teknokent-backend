#!/usr/bin/env python3
"""Export a PDF Parse Lab batch-results.json file as readable Markdown."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def md(value: object) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ").strip()


def bullets(values: list[object], empty: str = "None identified.") -> list[str]:
    return [f"- {md(value)}" for value in values] if values else [f"- {empty}"]


def accuracy_document(document: dict[str, Any]) -> list[str]:
    local = document["judge"]["local_text"]
    direct = document["judge"]["direct_pdf"]
    lines = [
        f"### {md(document['pdf'])}",
        "",
        "| Metric | Local text | Direct PDF |",
        "|---|---:|---:|",
        f"| Overall accuracy | {local['overall_accuracy_score']} | {direct['overall_accuracy_score']} |",
        f"| Factual precision | {local['factual_precision_score']} | {direct['factual_precision_score']} |",
        f"| Completeness | {local['completeness_score']} | {direct['completeness_score']} |",
        f"| Evidence quality | {local['evidence_quality_score']} | {direct['evidence_quality_score']} |",
        "",
        f"Comparison: {md(document['judge'].get('comparative_summary', ''))}",
        "",
        "#### Local text — missing from JSON",
        "",
        *bullets(local.get("missing_information", [])),
        "",
        "#### Direct PDF — missing from JSON",
        "",
        *bullets(direct.get("missing_information", [])),
        "",
        "#### Local text — unsupported or hallucinated",
        "",
        *bullets(local.get("unsupported_claims", []) + local.get("hallucinated_information", [])),
        "",
        "#### Direct PDF — unsupported or hallucinated",
        "",
        *bullets(direct.get("unsupported_claims", []) + direct.get("hallucinated_information", [])),
        "",
        "#### Fields incorrectly marked missing",
        "",
        "Local text:",
        "",
        *bullets(local.get("incorrectly_marked_missing", [])),
        "",
        "Direct PDF:",
        "",
        *bullets(direct.get("incorrectly_marked_missing", [])),
        "",
        "#### Source/document limitations",
        "",
        *bullets(document["judge"].get("document_limitations", [])),
        "",
    ]
    return lines


def render(report: dict[str, Any]) -> str:
    summary = report["summary"]
    settings = report["settings"]
    lines = [
        "# PDF Parsing Batch Benchmark Report",
        "",
        f"Generated {md(report['generated_at'])} · Model {md(settings.get('model', ''))} · {md(settings.get('profile_type', ''))} profile · {md(settings.get('pdf_detail', ''))} PDF detail · {md(settings.get('order', ''))} call order",
        "",
        "## Technical summary",
        "",
        md(report["technical_summary"]),
        "",
        "| Benchmark metric | Value |",
        "|---|---:|",
        f"| CVs completed | {summary['completed']} / {summary['files_total']} |",
        f"| Total API cost | ${summary['total_api_cost_usd']:.6f} |",
        f"| Local total tokens | {summary['local_total_tokens']:,} |",
        f"| Direct total tokens | {summary['direct_total_tokens']:,} |",
        f"| Median token difference | {summary['median_token_difference']:+,.1f} |",
        f"| Median latency difference | {summary['median_latency_difference_seconds']:+.2f}s |",
        f"| Total warnings | {summary['total_warning_count']:,} |",
        "",
        "## Per-CV benchmark results",
        "",
        "Negative differences favor local text.",
        "",
        "| CV file | Local tokens | Direct tokens | Token difference | Cost difference | Latency difference | Status |",
        "|---|---:|---:|---:|---:|---:|---|",
    ]
    for row in report["rows"]:
        if row["status"] != "completed":
            lines.append(f"| {md(row['pdf'])} | — | — | — | — | — | Failed |")
            continue
        lines.append(
            f"| {md(row['pdf'])} | {row['local_total_tokens']:,} | {row['direct_total_tokens']:,} | "
            f"{row['token_difference']:+,} ({row['token_difference_percent']:+.1f}%) | "
            f"${row['cost_difference_usd']:+.6f} | {row['latency_difference_seconds']:+.2f}s | Completed |"
        )

    accuracy = report.get("accuracy")
    if isinstance(accuracy, dict) and accuracy.get("status") == "complete":
        local = accuracy["local_text"]
        direct = accuracy["direct_pdf"]
        wins = accuracy["winner_counts"]
        lines.extend([
            "",
            "## Accuracy against the actual CVs",
            "",
            "The original PDF was treated as ground truth. Scores combine structured model review with deterministic evidence-quote and page checks.",
            "",
            "| Accuracy metric | Local text | Direct PDF |",
            "|---|---:|---:|",
            f"| Median overall accuracy | {local['median_overall_accuracy_score']:.1f} | {direct['median_overall_accuracy_score']:.1f} |",
            f"| Median factual precision | {local['median_factual_precision_score']:.1f} | {direct['median_factual_precision_score']:.1f} |",
            f"| Median completeness | {local['median_completeness_score']:.1f} | {direct['median_completeness_score']:.1f} |",
            f"| Median evidence quality | {local['median_evidence_quality_score']:.1f} | {direct['median_evidence_quality_score']:.1f} |",
            f"| Missing-information findings | {local['missing_information_count']} | {direct['missing_information_count']} |",
            f"| Hallucination findings | {local['hallucinated_information_count']} | {direct['hallucinated_information_count']} |",
            f"| Incorrectly marked missing | {local['incorrectly_marked_missing_count']} | {direct['incorrectly_marked_missing_count']} |",
            f"| Evidence quote support | {local['quote_support_rate_percent']:.1f}% | {direct['quote_support_rate_percent']:.1f}% |",
            f"| Quote on cited page | {local['page_quote_support_rate_percent']:.1f}% | {direct['page_quote_support_rate_percent']:.1f}% |",
            "",
            f"CV-level wins: local text {wins['local_text']}, direct PDF {wins['direct_pdf']}, ties {wins['tie']}.",
            "",
            "## Missing parts and unsupported information",
            "",
            "“Missing from JSON” means useful in-scope information was present in the actual CV but absent or materially incorrect in that parser output. Information absent from the CV is a source limitation, not a parser miss.",
            "",
        ])
        for document in accuracy.get("documents", []):
            lines.extend(accuracy_document(document))
        methodology = accuracy.get("methodology", {})
        lines.extend([
            "## Accuracy methodology and limitations",
            "",
            f"- Ground truth: {md(methodology.get('ground_truth', 'Original CV PDF'))}",
            f"- Deterministic checks: {md(methodology.get('deterministic_checks', ''))}",
            f"- Model judgement: {md(methodology.get('model_judgement', ''))}",
            f"- Score normalization: {md(methodology.get('score_normalization', 'None'))}",
            *bullets(methodology.get("limitations", [])),
            "",
            f"Judge: {md(accuracy.get('judge_model', ''))} · {accuracy.get('judge_api_calls', 0)} calls · estimated audit cost ${accuracy.get('judge_estimated_cost_usd', 0):.6f}. API billing remains authoritative.",
            "",
        ])

    failures = [row for row in report["rows"] if row["status"] == "failed"]
    lines.extend(["## Failures", ""])
    if failures:
        lines.extend(f"- **{md(row['pdf'])}:** {md(row.get('error') or 'Benchmark did not complete.')}" for row in failures)
    else:
        lines.append("- No failed CVs in this batch.")
    lines.extend([
        "",
        "## General limitations",
        "",
        "- Results depend on CV length, visual structure, scan quality, model behavior, and network conditions.",
        "- Prompt caching and fixed call order can affect usage.",
        "- Accuracy scores are model-judged estimates and should be supplemented with human review before production decisions.",
        "- This report describes only the tested document cohort.",
        "",
        "Source: PDF Parse Lab batch results. API usage and provider billing remain authoritative.",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report_json", type=Path)
    parser.add_argument("--output", "-o", type=Path)
    args = parser.parse_args()
    source = args.report_json.expanduser().resolve()
    output = args.output.expanduser().resolve() if args.output else source.with_name("report.md")
    report = json.loads(source.read_text(encoding="utf-8"))
    output.write_text(render(report), encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
