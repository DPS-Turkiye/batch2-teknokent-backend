from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from dotenv import load_dotenv

from .cache import FileEmbeddingCache
from .matcher import EmbeddingMatcher
from .hybrid_matcher import HybridDirectMatcher, DIRECT_MATCH_WEIGHTS
from .models import UnusableProfileError
from .providers import (
    DeterministicHashEmbeddingProvider,
    OllamaEmbeddingProvider,
    OpenAIEmbeddingProvider,
    OpenRouterEmbeddingProvider,
)


PACKAGE_DIR = Path(__file__).resolve().parent
TOOL_DIR = PACKAGE_DIR.parent
BACKEND_DIR = TOOL_DIR.parents[1]


def read_json(path: Path) -> Any:
    return json.loads(path.expanduser().resolve().read_text(encoding="utf-8"))


def evaluate(
    mentors: list[dict[str, Any]],
    mentees: list[dict[str, Any]],
    expected: dict[str, list[str]] | None,
    matcher: EmbeddingMatcher,
) -> dict[str, Any]:
    results = [matcher.match(mentee, mentors).to_dict() for mentee in mentees]
    evaluated = []
    for result in results:
        relevant = set((expected or {}).get(result["mentee_id"], []))
        if not relevant:
            continue
        recommended = [item["mentor_id"] for item in result["recommendations"]]
        hits = len(relevant.intersection(recommended))
        evaluated.append(
            {
                "mentee_id": result["mentee_id"],
                "expected_mentor_ids": sorted(relevant),
                "hits_at_3": hits,
                "precision_at_3": hits / 3,
                "recall_at_3": hits / len(relevant),
            }
        )
    metrics = None
    if evaluated:
        metrics = {
            "queries_evaluated": len(evaluated),
            "macro_precision_at_3": sum(row["precision_at_3"] for row in evaluated) / len(evaluated),
            "macro_recall_at_3": sum(row["recall_at_3"] for row in evaluated) / len(evaluated),
            "per_mentee": evaluated,
        }
    return {"results": results, "metrics": metrics}


def evaluate_hybrid(
    mentors: list[dict[str, Any]],
    mentees: list[dict[str, Any]],
    expected: dict[str, list[str]] | None,
    matcher: HybridDirectMatcher,
) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    skipped_mentees: list[dict[str, str]] = []
    for index, mentee in enumerate(mentees):
        fallback = str(
            mentee.get("mentee_id") or mentee.get("profile_id") or mentee.get("id") or f"index-{index}"
        )
        try:
            results.append(matcher.match(mentee, mentors))
        except (UnusableProfileError, ValueError, TypeError) as error:
            skipped_mentees.append({"mentee_id": fallback, "reason": str(error)})

    evaluated = []
    for result in results:
        relevant = set((expected or {}).get(result["mentee_id"], []))
        if not relevant:
            continue
        recommended = [item["mentor_id"] for item in result["recommendations"]]
        hits = len(relevant.intersection(recommended))
        evaluated.append({
            "mentee_id": result["mentee_id"],
            "expected_mentor_ids": sorted(relevant),
            "hits_at_3": hits,
            "top_1_hit": bool(recommended and recommended[0] in relevant),
            "precision_at_3": hits / 3,
            "recall_at_3": hits / len(relevant),
        })
    metrics = None
    if evaluated:
        metrics = {
            "queries_evaluated": len(evaluated),
            "top_1_accuracy": sum(row["top_1_hit"] for row in evaluated) / len(evaluated),
            "macro_precision_at_3": sum(row["precision_at_3"] for row in evaluated) / len(evaluated),
            "macro_recall_at_3": sum(row["recall_at_3"] for row in evaluated) / len(evaluated),
            "per_mentee": evaluated,
        }
    return {
        "results": results,
        "metrics": metrics,
        "skipped_mentees": skipped_mentees,
        "methodology": {
            "discovery": "BM25 lexical retrieval + embedding similarity",
            "ranking": "field-aware weighted scoring with semantic and lexical signals",
            "field_weights": DIRECT_MATCH_WEIGHTS,
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate prototype mentor–mentee embedding matching.")
    parser.add_argument("--mentors", type=Path, default=PACKAGE_DIR / "samples" / "mentors.json")
    parser.add_argument("--mentees", type=Path, default=PACKAGE_DIR / "samples" / "mentees.json")
    parser.add_argument("--expected", type=Path, default=PACKAGE_DIR / "samples" / "expected_matches.json")
    parser.add_argument(
        "--provider",
        choices=("deterministic", "ollama", "openai", "openrouter"),
        default="deterministic",
    )
    parser.add_argument("--cache", type=Path, default=TOOL_DIR / "results" / "matching" / "mentor_embeddings.json")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    load_dotenv(BACKEND_DIR / ".env")
    providers = {
        "openai": OpenAIEmbeddingProvider,
        "openrouter": OpenRouterEmbeddingProvider,
        "ollama": OllamaEmbeddingProvider,
        "deterministic": DeterministicHashEmbeddingProvider,
    }
    provider = providers[args.provider]()
    matcher = HybridDirectMatcher(provider, FileEmbeddingCache(args.cache))
    payload = evaluate_hybrid(
        read_json(args.mentors),
        read_json(args.mentees),
        read_json(args.expected) if args.expected else None,
        matcher,
    )
    usage_calls = getattr(provider, "usage_calls", [])
    if usage_calls:
        payload["embedding_usage"] = {
            "api_calls": len(usage_calls),
            "input_tokens": sum(call.get("input_tokens", 0) for call in usage_calls),
            "total_tokens": sum(call.get("total_tokens", 0) for call in usage_calls),
            "output_tokens": 0,
            "calls": usage_calls,
        }
    rendered = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        output = args.output.expanduser().resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")


if __name__ == "__main__":
    main()
