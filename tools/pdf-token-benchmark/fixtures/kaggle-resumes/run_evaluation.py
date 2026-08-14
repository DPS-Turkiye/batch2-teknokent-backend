#!/usr/bin/env python3
"""Import parsed Kaggle fixtures and evaluate matching plus mentor-pool ranking."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

TOOL_DIR = Path(__file__).resolve().parents[2]
if str(TOOL_DIR) not in sys.path:
    sys.path.insert(0, str(TOOL_DIR))

from matching_prototype.cache import MemoryEmbeddingCache
from matching_prototype.hybrid_matcher import HybridDirectMatcher
from matching_prototype.models import UnusableProfileError
from matching_prototype.pool import generate_pool
from matching_prototype.profile_library import (
    PROFILE_LIBRARY_PATH,
    import_parsed_profiles,
    read_profile_library,
)
from matching_prototype.providers import OllamaEmbeddingProvider
from matching_prototype.text_builder import build_mentee_matching_profile


ROOT = Path(__file__).resolve().parent
MANIFEST_PATH = ROOT / "cohort-manifest.json"
RESULT_PATH = ROOT / "results" / "kaggle-evaluation.json"
ISOLATED_LIBRARY_PATH = ROOT / "results" / "kaggle-profile-library.json"

ROLE_CONFIG = {
    "Software Engineer": {
        "domain": "Technology architecture",
        "problem": (
            "I am an early-career software engineer and need guidance designing "
            "reliable software architecture and deploying production services."
        ),
        "outcomes": ["Build production-ready backend and cloud systems"],
    },
    "Data Scientist": {
        "domain": "AI product strategy",
        "problem": (
            "I am an early-career data scientist and need guidance turning machine "
            "learning prototypes into a reliable production AI product."
        ),
        "outcomes": ["Move a machine-learning prototype into production"],
    },
    "Financial Analyst": {
        "domain": "Financial management",
        "problem": (
            "I am an early-career financial analyst and need guidance with financial "
            "planning, forecasting, and financial modeling."
        ),
        "outcomes": ["Improve forecasting and financial-modeling practice"],
    },
}


def latest_results(name: str) -> Path:
    matches = sorted((ROOT / "results" / name).glob("*/all-results.json"))
    if not matches:
        raise FileNotFoundError(f"No parser results found for {name}.")
    return matches[-1]


def successful_entries(path: Path) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for run in json.loads(path.read_text(encoding="utf-8")):
        result = run["results"]["local_text"]
        if result.get("error") or not isinstance(result.get("profile"), dict):
            continue
        entries.append(
            {
                "source_name": Path(run["pdf"]).name,
                "profile": result["profile"],
            }
        )
    return entries


def role_by_source(manifest: dict[str, Any], cohort: str) -> dict[str, str]:
    return {
        Path(item["pdf"]).name: item["job_role"]
        for item in manifest["cohorts"][cohort]
    }


def manifest_by_source(manifest: dict[str, Any], cohort: str) -> dict[str, dict[str, Any]]:
    return {
        Path(item["pdf"]).name: item
        for item in manifest["cohorts"][cohort]
    }


def record_source(record: dict[str, Any]) -> str:
    return str(record.get("source", {}).get("filename") or "")


def normalized(values: list[str]) -> set[str]:
    return {str(value).strip().casefold() for value in values if str(value).strip()}


def parser_quality(
    mentors: list[dict[str, Any]],
    mentees: list[dict[str, Any]],
    manifest: dict[str, Any],
) -> dict[str, Any]:
    mentor_manifest = manifest_by_source(manifest, "mentors")
    mentee_manifest = manifest_by_source(manifest, "mentees")
    mentor_rows: list[dict[str, Any]] = []
    for record in mentors:
        source = record_source(record)
        truth = mentor_manifest[source]
        profile = record["profile"]
        expected_skills = normalized(truth["skills"])
        parsed_skills = normalized(profile.get("technologies", []))
        functions = " ".join(profile.get("professional_functions", [])).casefold()
        mentor_rows.append(
            {
                "source": source,
                "role": truth["job_role"],
                "years_exact": profile.get("years_of_experience") == truth["years_experience"],
                "role_found": truth["job_role"].casefold() in functions,
                "skill_recall": round(
                    (
                        len(expected_skills & parsed_skills) / len(expected_skills)
                        if expected_skills
                        else 1.0
                    ),
                    4,
                ),
                "warnings": profile.get("warnings", []),
            }
        )

    mentee_rows: list[dict[str, Any]] = []
    for record in mentees:
        source = record_source(record)
        truth = mentee_manifest[source]
        profile = record["profile"]
        expected_skills = normalized(truth["skills"])
        parsed_skills = normalized(profile.get("technologies", []))
        try:
            build_mentee_matching_profile(record)
            usable = True
        except UnusableProfileError:
            usable = False
        mentee_rows.append(
            {
                "source": source,
                "role": truth["job_role"],
                "matching_usable": usable,
                "skill_recall": round(
                    (
                        len(expected_skills & parsed_skills) / len(expected_skills)
                        if expected_skills
                        else 1.0
                    ),
                    4,
                ),
                "startup_fields_present": sum(
                    bool(profile.get(key))
                    for key in (
                        "startup_name",
                        "startup_summary",
                        "stage",
                        "business_model",
                        "sectors",
                        "target_markets",
                        "current_bottlenecks",
                        "goals",
                        "support_needs",
                    )
                ),
                "warnings": profile.get("warnings", []),
            }
        )

    return {
        "mentors": mentor_rows,
        "mentees": mentee_rows,
        "mentor_years_accuracy": round(sum(row["years_exact"] for row in mentor_rows) / len(mentor_rows), 4),
        "mentor_role_accuracy": round(sum(row["role_found"] for row in mentor_rows) / len(mentor_rows), 4),
        "mentor_mean_skill_recall": round(sum(row["skill_recall"] for row in mentor_rows) / len(mentor_rows), 4),
        "mentee_matching_usable_rate": round(sum(row["matching_usable"] for row in mentee_rows) / len(mentee_rows), 4),
        "mentee_mean_skill_recall": round(sum(row["skill_recall"] for row in mentee_rows) / len(mentee_rows), 4),
    }


def matching_evaluation(
    provider: OllamaEmbeddingProvider,
    cache: MemoryEmbeddingCache,
    mentors: list[dict[str, Any]],
    mentees: list[dict[str, Any]],
    manifest: dict[str, Any],
) -> dict[str, Any]:
    mentor_roles = role_by_source(manifest, "mentors")
    mentee_roles = role_by_source(manifest, "mentees")
    role_by_mentor_id = {
        record["mentor_id"]: mentor_roles[record_source(record)] for record in mentors
    }
    expected_by_role = {
        role: sorted(
            mentor_id for mentor_id, mentor_role in role_by_mentor_id.items() if mentor_role == role
        )
        for role in ROLE_CONFIG
    }
    matcher = HybridDirectMatcher(provider, cache, top_k=3)
    rows: list[dict[str, Any]] = []
    for mentee in mentees:
        source = record_source(mentee)
        role = mentee_roles[source]
        try:
            truth = manifest_by_source(manifest, "mentees")[source]
            prepared = {
                **mentee,
                "matching_preferences": {
                    "desired_role": role,
                    "skills_to_develop": truth["skills"],
                    "career_goals": [f"Develop into a strong {role}"],
                    "preferred_industry": ["Technology" if role != "Financial Analyst" else "Finance"],
                },
            }
            result = matcher.match(prepared, mentors)
        except UnusableProfileError as error:
            rows.append(
                {
                    "mentee_id": mentee["mentee_id"],
                    "source": source,
                    "role": role,
                    "status": "unusable",
                    "error": str(error),
                }
            )
            continue
        recommendations = result["recommendations"]
        recommended_ids = [item["mentor_id"] for item in recommendations]
        hits = len(set(recommended_ids) & set(expected_by_role[role]))
        rows.append(
            {
                "mentee_id": mentee["mentee_id"],
                "source": source,
                "role": role,
                "status": "evaluated",
                "top_1_same_role": bool(recommended_ids and role_by_mentor_id[recommended_ids[0]] == role),
                "hits_at_3": hits,
                "precision_at_3": round(hits / 3, 4),
                "recall_at_3": round(hits / len(expected_by_role[role]), 4),
                "recommendations": [
                    {
                        "mentor_id": item["mentor_id"],
                        "mentor_role": role_by_mentor_id[item["mentor_id"]],
                        "final_score": round(item["final_score"], 6),
                        "semantic_score": round(item["semantic_score"], 6),
                        "lexical_score": round(item["lexical_score"], 6),
                        "components": item["components"],
                        "shared_fields": item["shared_fields"],
                    }
                    for item in recommendations
                ],
            }
        )
    evaluated = [row for row in rows if row["status"] == "evaluated"]
    return {
        "rows": rows,
        "queries_total": len(rows),
        "queries_evaluated": len(evaluated),
        "queries_unusable": len(rows) - len(evaluated),
        "top_1_role_accuracy": round(sum(row["top_1_same_role"] for row in evaluated) / len(evaluated), 4)
        if evaluated
        else None,
        "macro_precision_at_3": round(sum(row["precision_at_3"] for row in evaluated) / len(evaluated), 4)
        if evaluated
        else None,
        "macro_recall_at_3": round(sum(row["recall_at_3"] for row in evaluated) / len(evaluated), 4)
        if evaluated
        else None,
    }


def pool_evaluation(
    provider: OllamaEmbeddingProvider,
    cache: MemoryEmbeddingCache,
    mentors: list[dict[str, Any]],
    manifest: dict[str, Any],
) -> dict[str, Any]:
    mentor_roles = role_by_source(manifest, "mentors")
    role_by_mentor_id = {
        record["mentor_id"]: mentor_roles[record_source(record)] for record in mentors
    }
    enrichment = {
        mentor_id: {
            "active": True,
            "capacity": 2,
            "preferred_problem_domains": [ROLE_CONFIG[role]["domain"]],
            "excluded_problem_domains": [],
            "conflict_organizations": [],
        }
        for mentor_id, role in role_by_mentor_id.items()
    }
    mentee_manifest = manifest_by_source(manifest, "mentees")
    rows: list[dict[str, Any]] = []
    for role, config in ROLE_CONFIG.items():
        exemplar = next(item for item in mentee_manifest.values() if item["job_role"] == role)
        report = generate_pool(
            problem_input={
                "mentee_id": f"pool-{role.lower().replace(' ', '-')}",
                "problem_statement": config["problem"],
                "desired_outcomes": config["outcomes"],
                "problem_domains": [config["domain"]],
                "technologies": exemplar["skills"],
                "languages": [],
                "meeting_formats": [],
            },
            mentors=mentors,
            enrichment_by_mentor=enrichment,
            provider=provider,
            cache=cache,
            pool_size=3,
        )
        suggestions = report["suggested_pool"]
        rows.append(
            {
                "role": role,
                "problem_domain": config["domain"],
                "top_1_same_role": bool(suggestions and role_by_mentor_id[suggestions[0]["mentor_id"]] == role),
                "same_role_in_top_3": sum(
                    role_by_mentor_id[item["mentor_id"]] == role for item in suggestions
                ),
                "suggestions": [
                    {
                        "mentor_id": item["mentor_id"],
                        "mentor_role": role_by_mentor_id[item["mentor_id"]],
                        "final_score": item["final_score"],
                        "taxonomy": item["retrieval"]["taxonomy"],
                        "lexical": item["retrieval"]["lexical"],
                        "semantic": item["retrieval"]["semantic"],
                    }
                    for item in suggestions
                ],
            }
        )
    return {
        "rows": rows,
        "top_1_role_accuracy": round(sum(row["top_1_same_role"] for row in rows) / len(rows), 4),
        "mean_same_role_in_top_3": round(sum(row["same_role_in_top_3"] for row in rows) / len(rows), 4),
    }


def main() -> None:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    mentee_result_path = latest_results("parse-individual-mentees")
    mentor_result_path = latest_results("parse-mentors")
    mentee_entries = successful_entries(mentee_result_path)
    mentor_entries = successful_entries(mentor_result_path)

    for library_path in (ISOLATED_LIBRARY_PATH, PROFILE_LIBRARY_PATH):
        import_parsed_profiles(
            {"profile_type": "mentor", "approach": "local_text", "entries": mentor_entries},
            library_path,
        )
        import_parsed_profiles(
            {"profile_type": "individual_mentee", "approach": "local_text", "entries": mentee_entries},
            library_path,
        )

    cohort_library = read_profile_library(ISOLATED_LIBRARY_PATH)
    mentors = cohort_library["mentors"]
    mentees = cohort_library["mentees"]
    provider = OllamaEmbeddingProvider(model="bge-m3", dimensions=1024)
    cache = MemoryEmbeddingCache()
    report = {
        "dataset": manifest["dataset"],
        "selection": manifest["selection"],
        "parser": {
            "mentee_results": str(mentee_result_path.relative_to(ROOT)),
            "mentor_results": str(mentor_result_path.relative_to(ROOT)),
            "mentee_successes": len(mentee_entries),
            "mentor_successes": len(mentor_entries),
        },
        "parser_quality": parser_quality(mentors, mentees, manifest),
        "matching": matching_evaluation(provider, cache, mentors, mentees, manifest),
        "pool": pool_evaluation(provider, cache, mentors, manifest),
        "embedding": {
            **provider.configuration.to_dict(),
            "api_calls": len(provider.usage_calls),
            "input_tokens": sum(call.get("input_tokens", 0) for call in provider.usage_calls),
        },
        "shared_library": str(PROFILE_LIBRARY_PATH),
    }
    RESULT_PATH.parent.mkdir(parents=True, exist_ok=True)
    RESULT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
