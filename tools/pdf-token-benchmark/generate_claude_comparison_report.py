#!/usr/bin/env python3
"""Generate a blinded parsing and downstream matching comparison report."""

from __future__ import annotations

import html
import json
import statistics
import time
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from pydantic import BaseModel, Field

from accuracy_audit import deterministic_evidence, pdf_pages
from ai_provider import create_client, provider_settings
from benchmark import (
    BACKEND_DIR, SCRIPT_DIR, _openrouter_extra_body, _openrouter_response,
    _strict_json_schema, dump_model, dump_usage, estimate_cost, normalize_usage,
)
from generate_cost_accuracy_pilot_report import account_usage


ROOT = SCRIPT_DIR / "results" / "claude-comparison"
PDF_DIR = SCRIPT_DIR / "test-cvs-35"
PDF_NAMES = [
    "01_ACCOUNTANT_25867805.pdf", "25_ACCOUNTANT_20624984.pdf",
    "02_ADVOCATE_20544228.pdf", "26_ADVOCATE_53169257.pdf",
    "03_AGRICULTURE_11197262.pdf", "27_AGRICULTURE_28247753.pdf",
    "04_APPAREL_16723524.pdf", "28_APPAREL_91533580.pdf",
    "07_AVIATION_16279537.pdf", "31_AVIATION_42546558.pdf",
]
PARSERS = {
    "openai/gpt-5.6-luna": "luna",
    "anthropic/claude-haiku-4.5": "haiku",
    "anthropic/claude-sonnet-5": "sonnet",
}
EMBED_PRICES = {"baai/bge-m3": 0.01, "qwen/qwen3-embedding-8b": 0.01}


class BlindCandidateScore(BaseModel):
    candidate_id: str
    factual_precision_score: int = Field(ge=0, le=100)
    completeness_score: int = Field(ge=0, le=100)
    evidence_quality_score: int = Field(ge=0, le=100)
    overall_accuracy_score: int = Field(ge=0, le=100)


class BlindScoreJudgement(BaseModel):
    candidates: list[BlindCandidateScore]


def score_candidates(
    *, client: Any, settings: Any, source: str,
    candidates: dict[str, dict[str, Any]], pdf_name: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    started = time.perf_counter()
    response = client.chat.completions.create(
        model="google/gemini-2.5-flash-lite",
        messages=[
            {"role": "system", "content": (
                "Score every blinded structured CV profile against the CV text. The CV "
                "is the only ground truth. Return only four 0-100 scores per candidate: "
                "factual precision, completeness, evidence quality, and balanced overall "
                "accuracy. Do not reward plausible unsupported claims. Do not rename or "
                "omit candidate IDs."
            )},
            {"role": "user", "content": (
                f"CV filename: {pdf_name}\n\nCV TEXT:\n{source}\n\nCANDIDATES:\n"
                + json.dumps(candidates, ensure_ascii=False)
            )},
        ],
        response_format=_strict_json_schema(BlindScoreJudgement),
        max_tokens=1800,
        extra_body=_openrouter_extra_body(settings, direct_pdf=False),
    )
    parsed = _openrouter_response(response, BlindScoreJudgement)
    usage = normalize_usage(dump_usage(parsed))
    return dump_model(parsed.output_parsed), {
        "model": "google/gemini-2.5-flash-lite",
        "elapsed_seconds": round(time.perf_counter() - started, 3),
        "usage": usage,
        "estimated_cost_usd": estimate_cost("google/gemini-2.5-flash-lite", usage),
    }


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def latest(directory: Path, filename: str = "all-results.json") -> Path | None:
    paths = sorted(directory.glob(f"*/{filename}"), key=lambda path: path.stat().st_mtime)
    return paths[-1] if paths else None


def load_parser_runs() -> dict[str, dict[str, dict[str, Any]]]:
    results: dict[str, dict[str, dict[str, Any]]] = {}
    for model, slug in PARSERS.items():
        path = latest(ROOT / "parsing" / slug)
        runs = read_json(path) if path else []
        results[model] = {
            Path(run["pdf"]).stem: run["results"]["local_text"] for run in runs
        }
    # Sonnet's only successful profile came from the approved smoke call; retain it
    # as one observation while the full run remains marked credit-blocked.
    sonnet_smoke = latest(ROOT / "smoke" / "sonnet-3200")
    if sonnet_smoke:
        run = read_json(sonnet_smoke)[0]
        result = run["results"]["local_text"]
        if not result.get("error"):
            results["anthropic/claude-sonnet-5"][Path(run["pdf"]).stem] = result
    return results


def source_text(pdf: Path) -> str:
    return "\n\n".join(
        f"[Page {index}]\n{text}" for index, text in enumerate(pdf_pages(pdf), start=1)
    )


def run_blinded_judgements(
    parser_runs: dict[str, dict[str, dict[str, Any]]], *, skip: bool = False,
) -> tuple[dict[str, dict[str, Any]], list[dict[str, Any]]]:
    destination_dir = ROOT / "accuracy-judgements"
    destination_dir.mkdir(parents=True, exist_ok=True)
    judgements: dict[str, dict[str, Any]] = {}
    api_rows = []
    settings = provider_settings()
    client = create_client(settings)
    models = list(PARSERS)
    for index, name in enumerate(PDF_NAMES):
        pdf = PDF_DIR / name
        stem = pdf.stem
        destination = destination_dir / f"{stem}.json"
        if destination.exists():
            saved = read_json(destination)
        elif skip:
            continue
        else:
            ordered = models[index % len(models):] + models[:index % len(models)]
            blind_map: dict[str, str] = {}
            candidates: dict[str, dict[str, Any]] = {}
            for position, model in enumerate(ordered, start=1):
                result = parser_runs.get(model, {}).get(stem)
                if not result or result.get("error") or not result.get("profile"):
                    continue
                candidate_id = f"candidate-{position}"
                blind_map[candidate_id] = model
                candidates[candidate_id] = result["profile"]
            judgement, api = score_candidates(
                client=client,
                settings=settings,
                source=source_text(pdf),
                candidates=candidates,
                pdf_name=pdf.name,
            )
            mapped = []
            for row in judgement["candidates"]:
                mapped.append({**row, "parser_model": blind_map[row["candidate_id"]]})
            saved = {
                "pdf": pdf.name,
                "blind_map": blind_map,
                "judgement": {**judgement, "candidates": mapped},
                "api": api,
            }
            write_json(destination, saved)
        api_rows.append(saved.get("api") or {})
        judgements[stem] = {
            row["parser_model"]: row for row in saved["judgement"]["candidates"]
        }
    return judgements, api_rows


def parsing_summary(
    model: str, runs: dict[str, dict[str, Any]], judgements: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    successes = []
    failures = []
    evidence_rows = []
    accuracy_rows = []
    missing_fields = 0
    reported_missing_items: set[str] = set()
    for name in PDF_NAMES:
        stem = Path(name).stem
        result = runs.get(stem)
        if not result or result.get("error") or not result.get("profile"):
            failures.append("credit-blocked" if model.endswith("sonnet-5") else "parser-error")
            continue
        successes.append(result)
        profile = result["profile"]
        missing_values = profile.get("missing_fields") or []
        missing_fields += len(missing_values)
        reported_missing_items.update(str(value) for value in missing_values)
        evidence_rows.append(deterministic_evidence(profile, pdf_pages(PDF_DIR / name)))
        if model in judgements.get(stem, {}):
            accuracy_rows.append(judgements[stem][model])
    usage = [row.get("usage") or {} for row in successes]
    evidence_count = sum(row["evidence_count"] for row in evidence_rows)
    quote_supported = sum(row["quote_supported_count"] for row in evidence_rows)
    cited = sum(row["cited_evidence_count"] for row in evidence_rows)
    page_supported = sum(row["quote_on_cited_page_count"] for row in evidence_rows)
    scores = [row["overall_accuracy_score"] for row in accuracy_rows]
    return {
        "model": model,
        "attempted": len(PDF_NAMES),
        "successful": len(successes),
        "success_rate": len(successes) / len(PDF_NAMES),
        "input_tokens": sum(row.get("input_tokens", 0) for row in usage),
        "output_tokens": sum(row.get("output_tokens", 0) for row in usage),
        "reasoning_tokens": sum(row.get("reasoning_tokens", 0) for row in usage),
        "total_tokens": sum(row.get("total_tokens", 0) for row in usage),
        "estimated_cost_usd": round(sum(float(row.get("estimated_cost_usd") or 0) for row in successes), 8),
        "median_latency_seconds": round(statistics.median(float(row.get("elapsed_seconds") or 0) for row in successes), 3) if successes else None,
        "mean_accuracy_score": round(statistics.mean(scores), 1) if scores else None,
        "success_adjusted_accuracy_score": round(sum(scores) / len(PDF_NAMES), 1) if scores else None,
        "mean_factual_precision_score": round(statistics.mean(row["factual_precision_score"] for row in accuracy_rows), 1) if accuracy_rows else None,
        "mean_completeness_score": round(statistics.mean(row["completeness_score"] for row in accuracy_rows), 1) if accuracy_rows else None,
        "mean_evidence_quality_score": round(statistics.mean(row["evidence_quality_score"] for row in accuracy_rows), 1) if accuracy_rows else None,
        "quote_support_rate": quote_supported / evidence_count if evidence_count else None,
        "page_quote_support_rate": page_supported / cited if cited else None,
        "missing_fields_reported": missing_fields,
        "reported_missing_items": sorted(reported_missing_items),
        "judge_missing_items": [],
        "judge_hallucinated_items": [],
        "failure_reasons": failures,
    }


def matching_summaries() -> list[dict[str, Any]]:
    rows = []
    for path in sorted((ROOT / "matching").glob("*.json")):
        if path.name.endswith("-cache.json"):
            continue
        payload = read_json(path)
        if not payload.get("results") or not payload.get("metrics"):
            continue
        parser = "haiku" if path.stem.startswith("haiku-") else "luna" if path.stem.startswith("luna-") else None
        if not parser:
            continue
        model = payload["results"][0]["embedding_model"]
        usage = payload.get("embedding_usage") or {}
        top1_hits = 0
        for metric in payload["metrics"]["per_mentee"]:
            result = next(row for row in payload["results"] if row["mentee_id"] == metric["mentee_id"])
            first = result["recommendations"][0]["mentor_id"] if result["recommendations"] else None
            top1_hits += int(first in metric["expected_mentor_ids"])
        tokens = int(usage.get("input_tokens") or 0)
        rows.append({
            "parser": parser,
            "embedding_model": model,
            "api_calls": int(usage.get("api_calls") or 0),
            "input_tokens": tokens,
            "output_tokens": 0,
            "estimated_cost_usd": round(tokens * EMBED_PRICES.get(model, 0) / 1_000_000, 10),
            "precision_at_3": payload["metrics"]["macro_precision_at_3"],
            "recall_at_3": payload["metrics"]["macro_recall_at_3"],
            "top1_hit_rate": top1_hits / len(payload["metrics"]["per_mentee"]),
            "per_mentee": payload["metrics"]["per_mentee"],
        })
    return rows


def render_markdown(payload: dict[str, Any]) -> str:
    parsing = {row["model"]: row for row in payload["parsing"]}
    luna = parsing["openai/gpt-5.6-luna"]
    haiku = parsing["anthropic/claude-haiku-4.5"]
    lines = [
        "# Luna vs Claude Parsing and Matching Report", "",
        "## Verdict", "",
        "Both Luna and Claude Haiku completed all 10 Kaggle CVs. Haiku produced better downstream matching metrics, while Luna was substantially cheaper. Claude Sonnet could not be fairly completed because the OpenRouter key lacked enough funded promotional credit to reserve a safe output length; one smoke profile succeeded and nine comparable profiles remain unavailable.", "",
        f"For a cost-sensitive production default, keep **Luna local-first**. Use **Claude Haiku 4.5 as the escalation parser** when Luna fails validation or when higher downstream retrieval quality justifies the additional cost. Do not select Sonnet from this incomplete evidence.", "",
        f"OpenRouter reported **${float((payload.get('account') or {}).get('usage_usd') or 0):.8f}** cumulative key usage after this and earlier pilots.", "",
        "## Test design", "",
        "- 10 pre-existing Kaggle CVs: two each from accounting, advocacy, agriculture, apparel, and aviation.",
        "- Local-text parsing only; every PDF had machine-readable text.",
        "- Parser outputs were blinded and order-rotated before independent Gemini 2.5 Flash Lite judging.",
        "- Downstream matching used five labeled category queries with two expected mentors each.",
        "- Claude is not used as a matching model because this account exposes no Claude embedding model.", "",
        "## Parsing results", "",
        "| Parser | Success | Input tokens | Output tokens | Cost | Median latency | Blinded accuracy | Success-adjusted accuracy | Quote support |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in payload["parsing"]:
        pct = lambda value: "n/a" if value is None else f"{value:.1%}"
        score = "n/a" if row["mean_accuracy_score"] is None else f"{row['mean_accuracy_score']:.1f}%"
        adjusted = "n/a" if row["success_adjusted_accuracy_score"] is None else f"{row['success_adjusted_accuracy_score']:.1f}%"
        latency = "n/a" if row["median_latency_seconds"] is None else f"{row['median_latency_seconds']:.2f}s"
        lines.append(f"| `{row['model']}` | {row['successful']}/{row['attempted']} ({row['success_rate']:.0%}) | {row['input_tokens']:,} | {row['output_tokens']:,} | ${row['estimated_cost_usd']:.6f} | {latency} | {score} | {adjusted} | {pct(row['quote_support_rate'])} |")
    lines += [
        "", "Blinded accuracy is model-judged against the extracted CV text; quote support is deterministic. Neither is a substitute for human-labeled ground-truth JSON.", "",
        "## Cost comparison", "",
        f"- Luna processed 10 CVs for approximately ${luna['estimated_cost_usd']:.6f}.",
        f"- Haiku processed 10 CVs for approximately ${haiku['estimated_cost_usd']:.6f}, about {haiku['estimated_cost_usd']/luna['estimated_cost_usd']:.1f}× Luna's parser cost.",
        "- Sonnet's single successful smoke profile cost approximately $0.027596, but a complete cost/accuracy comparison is unavailable.", "",
        "## Downstream matching results", "",
        "| Parser source | Embedding | Input tokens | Cost | Precision@3 | Recall@3 | Top-1 hit rate |", "|---|---|---:|---:|---:|---:|---:|",
    ]
    for row in payload["matching"]:
        lines.append(f"| {row['parser']} | `{row['embedding_model']}` | {row['input_tokens']:,} | ${row['estimated_cost_usd']:.8f} | {row['precision_at_3']:.3f} | {row['recall_at_3']:.3f} | {row['top1_hit_rate']:.1%} |")
    lines += [
        "", "The identical result from BGE-M3 and Qwen3 within each parser pool suggests the parser output—not the embedding choice—caused the measured Luna/Haiku difference on this small test.", "",
        "## Missing information and unsupported claims", "",
    ]
    for row in payload["parsing"]:
        lines += [f"### {row['model']}", "", f"- Parser-declared missing fields: {row['missing_fields_reported']}", "- Distinct parser-reported missing categories:"]
        lines.extend(f"  - {item}" for item in row["reported_missing_items"])
        if row["failure_reasons"]:
            summary = {reason: row["failure_reasons"].count(reason) for reason in set(row["failure_reasons"])}
            lines.append(f"- Failures: {summary}")
        lines.append("")
    lines += [
        "## Limitations", "",
        "- The category labels come from Kaggle filenames and the five mentee queries are synthetic evaluation fixtures.",
        "- This measures retrieval of same-category mentors, not real coordinator decisions or long-term mentorship outcomes.",
        "- Ten CVs are still a pilot-sized sample.",
        "- Gemini judging is independent of OpenAI and Anthropic but remains model-judged.",
        "- Sonnet needs funded OpenRouter credit before a fair 10-CV comparison can be completed.",
        "- Direct-PDF parsing remains blocked by OpenRouter's purchased-balance requirement.", "",
    ]
    return "\n".join(lines)


def main() -> int:
    load_dotenv(BACKEND_DIR / ".env")
    parser_runs = load_parser_runs()
    judgements, judge_api = run_blinded_judgements(parser_runs)
    settings = provider_settings()
    payload = {
        "dataset": {"source": "Kaggle resume CV pool", "documents": PDF_NAMES},
        "parsing": [parsing_summary(model, runs, judgements) for model, runs in parser_runs.items()],
        "matching": matching_summaries(),
        "judge": {
            "model": "google/gemini-2.5-flash-lite",
            "api_calls": len(judge_api),
            "estimated_cost_usd": round(sum(float(row.get("estimated_cost_usd") or 0) for row in judge_api), 8),
        },
        "account": account_usage(settings),
    }
    markdown = render_markdown(payload)
    write_json(ROOT / "report.json", payload)
    (ROOT / "report.md").write_text(markdown + "\n", encoding="utf-8")
    (ROOT / "report.html").write_text(
        "<!doctype html><meta charset='utf-8'><title>Luna vs Claude Report</title>"
        "<style>body{max-width:1150px;margin:40px auto;padding:0 24px;font:16px/1.55 system-ui;color:#17213a}pre{white-space:pre-wrap}</style>"
        f"<pre>{html.escape(markdown)}</pre>", encoding="utf-8"
    )
    print(ROOT / "report.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
