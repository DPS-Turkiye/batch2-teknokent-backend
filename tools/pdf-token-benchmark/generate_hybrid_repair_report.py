#!/usr/bin/env python3
"""Build the reproducible Luna -> validation -> Haiku repair benchmark report."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from statistics import median
from typing import Any

from accuracy_audit import deterministic_evidence, pdf_pages


TOOL_DIR = Path(__file__).resolve().parent
ROOT = TOOL_DIR / "results" / "hybrid-repair"
BASELINE = ROOT / "baseline" / "20260803T150555Z" / "all-results.json"
REPAIRS = ROOT / "repairs" / "20260803T151130Z" / "all-results.json"
BASELINE_MATCHING = ROOT / "matching" / "baseline-ollama-bge.json"
HYBRID_MATCHING = ROOT / "matching" / "hybrid-ollama-bge.json"
REPORT_DIR = ROOT / "report"


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def evidence_summary(rows: list[dict[str, Any]], *, hybrid: bool) -> dict[str, Any]:
    evidence_count = quote_count = cited_count = page_count = 0
    for row in rows:
        profile = row["hybrid_profile"] if hybrid else row["baseline"]["profile"]
        evidence = deterministic_evidence(profile, pdf_pages(Path(row["pdf"])))
        evidence_count += int(evidence["evidence_count"])
        quote_count += int(evidence["quote_supported_count"])
        cited_count += int(evidence["cited_evidence_count"])
        page_count += int(evidence["quote_on_cited_page_count"])
    return {
        "evidence_count": evidence_count,
        "quote_supported_count": quote_count,
        "quote_support_rate": quote_count / evidence_count if evidence_count else None,
        "cited_evidence_count": cited_count,
        "page_quote_supported_count": page_count,
        "page_quote_support_rate": page_count / cited_count if cited_count else None,
    }


def matching_summary(payload: dict[str, Any]) -> dict[str, Any]:
    top1_hits = 0
    top3_rows = []
    for metric in payload["metrics"]["per_mentee"]:
        result = next(row for row in payload["results"] if row["mentee_id"] == metric["mentee_id"])
        mentors = [recommendation["mentor_id"] for recommendation in result["recommendations"]]
        top1_hits += int(bool(mentors) and mentors[0] in metric["expected_mentor_ids"])
        top3_rows.append({
            "mentee": metric["mentee_id"].removeprefix("mentee-").title(),
            "top_1": mentors[0] if mentors else "—",
            "top_2": mentors[1] if len(mentors) > 1 else "—",
            "top_3": mentors[2] if len(mentors) > 2 else "—",
            "hits_at_3": metric["hits_at_3"],
        })
    return {
        "precision_at_3": payload["metrics"]["macro_precision_at_3"],
        "recall_at_3": payload["metrics"]["macro_recall_at_3"],
        "top1_accuracy": top1_hits / payload["metrics"]["queries_evaluated"],
        "queries": payload["metrics"]["queries_evaluated"],
        "embedding_tokens": payload.get("embedding_usage", {}).get("input_tokens", 0),
        "top3": top3_rows,
    }


def build_metrics() -> dict[str, Any]:
    baseline_runs = read_json(BASELINE)
    repairs = read_json(REPAIRS)
    baseline_matching = matching_summary(read_json(BASELINE_MATCHING))
    hybrid_matching = matching_summary(read_json(HYBRID_MATCHING))

    baseline_results = [row["results"]["local_text"] for row in baseline_runs]
    baseline_cost = sum(float(row.get("estimated_cost_usd") or 0) for row in baseline_results)
    repair_apis = [row["repair_api"] for row in repairs if row.get("repair_api")]
    repair_cost = sum(float(row.get("estimated_cost_usd") or 0) for row in repair_apis)
    successful_repairs = sum(bool(row.get("applied_fields")) for row in repairs)
    attempted_repairs = sum(bool(row.get("repair_attempted")) for row in repairs)
    evidence_baseline = evidence_summary(repairs, hybrid=False)
    evidence_hybrid = evidence_summary(repairs, hybrid=True)

    field_names = ["industries", "professional_functions", "technologies", "employment_highlights", "missing_fields"]
    field_counts: dict[str, dict[str, int]] = {"baseline": {}, "hybrid": {}}
    for field in field_names:
        field_counts["baseline"][field] = sum(len((row["baseline"]["profile"].get(field) or [])) for row in repairs)
        field_counts["hybrid"][field] = sum(len((row["hybrid_profile"].get(field) or [])) for row in repairs)

    return {
        "cohort_size": len(repairs),
        "baseline": {
            "input_tokens": sum(int((row.get("usage") or {}).get("input_tokens") or 0) for row in baseline_results),
            "output_tokens": sum(int((row.get("usage") or {}).get("output_tokens") or 0) for row in baseline_results),
            "total_tokens": sum(int((row.get("usage") or {}).get("total_tokens") or 0) for row in baseline_results),
            "cost": baseline_cost,
            "median_latency": median(float(row.get("elapsed_seconds") or 0) for row in baseline_results),
            "evidence": evidence_baseline,
            "matching": baseline_matching,
            "field_counts": field_counts["baseline"],
        },
        "hybrid": {
            "cost": baseline_cost + repair_cost,
            "incremental_repair_cost": repair_cost,
            "repair_input_tokens": sum(int((row.get("usage") or {}).get("input_tokens") or 0) for row in repair_apis),
            "repair_output_tokens": sum(int((row.get("usage") or {}).get("output_tokens") or 0) for row in repair_apis),
            "repair_total_tokens": sum(int((row.get("usage") or {}).get("total_tokens") or 0) for row in repair_apis),
            "successful_repairs": successful_repairs,
            "attempted_repairs": attempted_repairs,
            "schema_failures": sum("ValidationError" in str(row.get("repair_error")) for row in repairs),
            "credit_blocked": sum("402" in str(row.get("repair_error")) for row in repairs),
            "no_repair_needed": sum(not row.get("repair_attempted") for row in repairs),
            "evidence": evidence_hybrid,
            "matching": hybrid_matching,
            "field_counts": field_counts["hybrid"],
        },
    }


def build_markdown(metrics: dict[str, Any]) -> str:
    baseline = metrics["baseline"]
    hybrid = metrics["hybrid"]
    multiplier = hybrid["cost"] / baseline["cost"]
    quote_delta_pp = (hybrid["evidence"]["quote_support_rate"] - baseline["evidence"]["quote_support_rate"]) * 100
    return f"""# Hybrid CV Parsing and Matching Test

## Technical summary

**Do not enable the tested Haiku repair policy by default.** On 10 new public Kaggle CVs, Luna-only and Luna-plus-targeted-Haiku produced the same matching result: Precision@3 **{baseline['matching']['precision_at_3']:.3f}**, Recall@3 **{baseline['matching']['recall_at_3']:.3f}**, and top-1 accuracy **{baseline['matching']['top1_accuracy']:.3f}** across five labeled queries. The hybrid path cost **${hybrid['cost']:.5f}**, {multiplier:.1f}× the **${baseline['cost']:.5f}** Luna-only cost.

The repairs also reduced exact source-quote support from **{baseline['evidence']['quote_support_rate']:.1%}** to **{hybrid['evidence']['quote_support_rate']:.1%}** ({quote_delta_pp:+.1f} percentage points). This is a deterministic fidelity check against the actual PDF text, not an LLM opinion. The result supports Luna local-first parsing plus local BGE-M3 matching, with repair reserved for narrower, high-confidence failure cases.

## Matching quality stayed flat while cost increased

Both pipelines retrieved 7 of the 10 expected category-matched mentors within the five top-three result sets. Repair changed some cosine scores and one within-top-three ordering, but it did not add a correct recommendation. The comparison chart shows total parsing cost for the same 10-CV cohort; local BGE-M3 added **$0 API cost** in both arms.

## The repair gate was too broad and only partly reliable

The validator flagged **{hybrid['attempted_repairs']} of {metrics['cohort_size']} CVs**. Haiku successfully changed fields for **{hybrid['successful_repairs']}**, one response failed schema validation, two calls were blocked after the OpenRouter key exhausted its funded allowance, and one CV required no repair. Baseline fallback kept all 10 profiles usable.

The hybrid path spent an additional **${hybrid['incremental_repair_cost']:.5f}** and {hybrid['repair_total_tokens']:,} Haiku tokens on successful repairs. Yet the aggregate matching metrics were unchanged and exact quote support declined. The broad triggers—especially short employment history and long technology/function lists—are therefore not selective enough for production use.

## Scope, data, and metric definitions

- **Cohort:** 10 machine-readable, publicly accessible Kaggle CV PDFs not used in the preceding model comparison; two each from Arts, Automobile, Banking, BPO, and Chef categories.
- **Parsing baseline:** local PDF text extraction followed by `openai/gpt-5.6-luna` structured mentor parsing.
- **Hybrid arm:** the same Luna profile, deterministic repair triggers, and `anthropic/claude-haiku-4.5` for only flagged matching fields. Failed repairs retain Luna output.
- **Matching:** deterministic matching text and cosine similarity using the same locally hosted `bge-m3` configuration for mentors and mentees.
- **Precision@3:** expected category matches among the three recommendations, divided by three, macro-averaged across five queries.
- **Recall@3:** expected category matches found in the top three, divided by two expected mentors per query, macro-averaged.
- **Exact quote support:** evidence quotes found verbatim in the PDF text, aggregated over all extracted evidence items.
- **Cost:** model-list-price estimate from returned token usage. Local PDF extraction and local BGE-M3 have no API charge; electricity and machine time are excluded.

## Validation design and observed outputs

The five mentee profiles are synthetic category-level probes, not real program participants. Expected matches come from the Kaggle category labels encoded in the test filenames. Both arms use identical mentees, labels, matching-text version, cosine implementation, top-three cutoff, and BGE-M3 model configuration. This isolates profile-repair effects within this small cohort.

The repair arm changed 6 profiles. Aggregate extracted field counts barely moved: employment highlights increased from {baseline['field_counts']['employment_highlights']} to {hybrid['field_counts']['employment_highlights']}; technologies fell from {baseline['field_counts']['technologies']} to {hybrid['field_counts']['technologies']}; professional functions fell from {baseline['field_counts']['professional_functions']} to {hybrid['field_counts']['professional_functions']}. Reported missing fields stayed at {baseline['field_counts']['missing_fields']} because the adapter intentionally does not rewrite parser metadata.

## Limitations and robustness checks

- Ten CVs and five queries are sufficient for a prototype failure signal, not a production-quality estimate.
- Category labels are coarse relevance labels. They do not measure real mentor–mentee fit or human acceptance.
- Direct-PDF parsing was not rerun because OpenRouter requires purchased credits for file inputs on this account.
- Two repair calls were credit-blocked and one failed schema validation; fallback preserves reliability, but this means only six profiles actually changed.
- Exact quote support measures evidence fidelity, not completeness of every semantic field. A blinded semantic judge was not run after the account began returning HTTP 402, so no unsupported semantic-accuracy claim is made.
- All PDFs had machine-readable text. Scanned, multilingual, table-heavy, and badly encoded CVs still need separate stress tests.

## Recommended next step

Keep **Luna local-first → deterministic validation → local BGE-M3** as the default. Do not pay for Haiku repair merely because lists are long or employment highlights are short. Redesign repair eligibility around hard failures: empty matching text, missing required sector/experience content, parser error, unusable evidence, or a low-cost deterministic source-support failure. Re-test that narrower gate on at least 50–100 CVs with real or coordinator-reviewed relevance labels before production rollout.

## Further questions

- Which missing fields actually change accepted mentor recommendations in real participant data?
- Can deterministic normalization replace most technology/function cleanup without any LLM call?
- What repair threshold maximizes corrected profiles per dollar while keeping quote support at or above the Luna baseline?
- How does local BGE-M3 perform on real multilingual mentee profiles and cross-language matches?
"""


def build_artifact(metrics: dict[str, Any], generated_at: str) -> dict[str, Any]:
    baseline = metrics["baseline"]
    hybrid = metrics["hybrid"]
    summary_rows = [
        {
            "pipeline": "Luna only",
            "parser_cost_usd": round(baseline["cost"], 8),
            "repair_cost_usd": 0.0,
            "total_cost_usd": round(baseline["cost"], 8),
            "precision_at_3": baseline["matching"]["precision_at_3"],
            "recall_at_3": baseline["matching"]["recall_at_3"],
            "top1_accuracy": baseline["matching"]["top1_accuracy"],
            "quote_support_rate": baseline["evidence"]["quote_support_rate"],
            "usable_profiles": metrics["cohort_size"],
        },
        {
            "pipeline": "Luna + targeted Haiku",
            "parser_cost_usd": round(baseline["cost"], 8),
            "repair_cost_usd": round(hybrid["incremental_repair_cost"], 8),
            "total_cost_usd": round(hybrid["cost"], 8),
            "precision_at_3": hybrid["matching"]["precision_at_3"],
            "recall_at_3": hybrid["matching"]["recall_at_3"],
            "top1_accuracy": hybrid["matching"]["top1_accuracy"],
            "quote_support_rate": hybrid["evidence"]["quote_support_rate"],
            "usable_profiles": metrics["cohort_size"],
        },
    ]
    repair_rows = []
    for row in read_json(REPAIRS):
        error = row.get("repair_error") or ""
        status = "not needed" if not row.get("repair_attempted") else "applied" if row.get("applied_fields") else "credit blocked" if "402" in error else "schema failure"
        repair_rows.append({
            "cv": Path(row["pdf"]).stem,
            "status": status,
            "fields_changed": ", ".join(row.get("applied_fields") or []) or "—",
            "repair_cost_usd": round(float((row.get("repair_api") or {}).get("estimated_cost_usd") or 0), 8),
        })
    ranking_rows = []
    for pipeline, matching in (("Luna only", baseline["matching"]), ("Hybrid", hybrid["matching"])):
        for row in matching["top3"]:
            for rank in range(1, 4):
                ranking_rows.append({
                    "pipeline": pipeline,
                    "mentee": row["mentee"],
                    "rank": rank,
                    "mentor_id": row[f"top_{rank}"],
                })

    source = {
        "id": "benchmark-files",
        "label": "Hybrid benchmark presentation extract",
        "path": "results/hybrid-repair/report/report_source.sql",
    }
    markdown = build_markdown(metrics)
    sections = markdown.split("\n## ")
    blocks = [{"id": "title", "type": "markdown", "body": sections[0].strip()}]
    section_ids = [
        "technical-summary", "matching-result", "repair-gate", "scope-definitions",
        "validation-design", "limitations", "recommendation", "further-questions",
    ]
    for section_id, body in zip(section_ids, sections[1:]):
        blocks.append({"id": section_id, "type": "markdown", "body": "## " + body.strip(), "sourceId": "benchmark-files"})
        if section_id == "technical-summary":
            blocks.append({"id": "headline-metrics", "type": "metric-strip", "cardIds": ["matching-recall", "hybrid-cost", "quote-support"]})
        if section_id == "matching-result":
            blocks.append({"id": "cost-chart-block", "type": "chart", "chartId": "pipeline-cost"})

    manifest = {
        "version": 1,
        "surface": "report",
        "title": "Hybrid CV Parsing and Matching Test",
        "description": "Technical comparison of Luna-only parsing versus deterministic validation and targeted Haiku repair.",
        "generatedAt": generated_at,
        "sources": [source],
        "cards": [
            {
                "id": "matching-recall", "dataset": "headline", "sourceId": "benchmark-files",
                "description": "Macro Recall@3 across five category-labeled mentee queries; unchanged by repair.",
                "metrics": [{"label": "Recall@3", "field": "recall_at_3", "format": "percent"}],
            },
            {
                "id": "hybrid-cost", "dataset": "headline", "sourceId": "benchmark-files",
                "description": "Estimated API cost for the full 10-CV hybrid pipeline.",
                "metrics": [{"label": "Hybrid cost", "field": "hybrid_cost", "format": "currency"}],
            },
            {
                "id": "quote-support", "dataset": "headline", "sourceId": "benchmark-files",
                "description": "Exact evidence-quote support after targeted repair.",
                "metrics": [{"label": "Hybrid quote support", "field": "hybrid_quote_support", "format": "percent"}],
            },
        ],
        "charts": [
            {
                "id": "pipeline-cost",
                "title": "Estimated parsing cost by pipeline",
                "subtitle": "Same 10 public CVs; local extraction and BGE-M3 embedding cost $0 via API",
                "type": "bar",
                "dataset": "pipeline_summary",
                "sourceId": "benchmark-files",
                "encodings": {
                    "x": {"field": "pipeline", "type": "nominal", "label": "Pipeline"},
                    "y": {"field": "total_cost_usd", "type": "quantitative", "format": "currency", "label": "Estimated cost"},
                    "tooltip": [
                        {"field": "precision_at_3", "type": "quantitative", "format": "percent", "label": "Precision@3"},
                        {"field": "recall_at_3", "type": "quantitative", "format": "percent", "label": "Recall@3"},
                        {"field": "quote_support_rate", "type": "quantitative", "format": "percent", "label": "Quote support"},
                    ],
                },
                "valueFormat": "currency",
                "layout": "full",
            }
        ],
        "tables": [],
        "blocks": blocks,
    }
    return {
        "surface": "report",
        "manifest": manifest,
        "snapshot": {
            "version": 1,
            "generatedAt": generated_at,
            "status": "ready",
            "datasets": {
                "headline": [{
                    "recall_at_3": baseline["matching"]["recall_at_3"],
                    "hybrid_cost": round(hybrid["cost"], 8),
                    "hybrid_quote_support": hybrid["evidence"]["quote_support_rate"],
                }],
                "pipeline_summary": summary_rows,
                "repair_outcomes": repair_rows,
                "rankings": ranking_rows,
            },
        },
        "sources": [source],
    }


def main() -> int:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    generated_at = datetime.now(UTC).isoformat()
    metrics = build_metrics()
    write_json(REPORT_DIR / "metrics.json", metrics)
    (REPORT_DIR / "report.md").write_text(build_markdown(metrics), encoding="utf-8")
    baseline = metrics["baseline"]
    hybrid = metrics["hybrid"]
    (REPORT_DIR / "report_source.sql").write_text(
        "-- Reproducible presentation extract generated from the saved benchmark JSON.\n"
        "-- The raw file identities and metric definitions are documented in report.md.\n"
        "WITH pipeline_summary(pipeline, total_cost_usd, precision_at_3, recall_at_3, "
        "top1_accuracy, quote_support_rate, usable_profiles) AS (VALUES\n"
        f"  ('Luna only', {baseline['cost']:.8f}, {baseline['matching']['precision_at_3']:.12f}, "
        f"{baseline['matching']['recall_at_3']:.12f}, {baseline['matching']['top1_accuracy']:.12f}, "
        f"{baseline['evidence']['quote_support_rate']:.12f}, {metrics['cohort_size']}),\n"
        f"  ('Luna + targeted Haiku', {hybrid['cost']:.8f}, {hybrid['matching']['precision_at_3']:.12f}, "
        f"{hybrid['matching']['recall_at_3']:.12f}, {hybrid['matching']['top1_accuracy']:.12f}, "
        f"{hybrid['evidence']['quote_support_rate']:.12f}, {metrics['cohort_size']})\n"
        ")\nSELECT * FROM pipeline_summary;\n",
        encoding="utf-8",
    )
    write_json(REPORT_DIR / "artifact.json", build_artifact(metrics, generated_at))
    print(REPORT_DIR)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
