from __future__ import annotations

import re
from collections.abc import Sequence
from typing import Any

from .cache import EmbeddingCache, MemoryEmbeddingCache
from .matcher import EmbeddingMatcher
from .pool import BM25Retriever
from .providers import EmbeddingProvider
from .text_builder import (
    build_mentee_matching_profile,
    build_mentor_matching_profile,
    deterministic_shared_fields,
)


DIRECT_MATCH_WEIGHTS = {
    "desired_role": 0.25,
    "skills": 0.25,
    "goals": 0.20,
    "industry": 0.10,
    "experience_gap": 0.10,
    "preferences": 0.10,
}
_TOKEN = re.compile(r"[^\W_]+", re.UNICODE)


def _tokens(values: Sequence[str]) -> set[str]:
    return {
        token.casefold()
        for value in values
        for token in _TOKEN.findall(value.replace("-", " "))
        if len(token) > 1
    }


def _overlap(left: Sequence[str], right: Sequence[str]) -> float:
    wanted = _tokens(left)
    offered = _tokens(right)
    return len(wanted & offered) / len(wanted) if wanted else 0.0


def _values(profile: Any, *headings: str) -> tuple[str, ...]:
    return tuple(
        value
        for heading in headings
        for value in profile.fields.get(heading, ())
    )


def _number(values: Sequence[str]) -> float | None:
    for value in values:
        try:
            return float(value)
        except (TypeError, ValueError):
            continue
    return None


class HybridDirectMatcher:
    """Hybrid discovery plus field-aware ranking for direct mentor matching."""

    def __init__(
        self,
        provider: EmbeddingProvider,
        cache: EmbeddingCache | None = None,
        *,
        top_k: int = 3,
        candidate_k: int = 30,
    ) -> None:
        self.provider = provider
        self.cache = cache or MemoryEmbeddingCache()
        self.top_k = top_k
        self.candidate_k = candidate_k

    def match(
        self, mentee_record: dict[str, Any], mentor_records: Sequence[dict[str, Any]]
    ) -> dict[str, Any]:
        mentee = build_mentee_matching_profile(mentee_record)
        usable_records: list[dict[str, Any]] = []
        mentor_profiles: dict[str, Any] = {}
        skipped: list[str] = []
        for index, record in enumerate(mentor_records):
            fallback = str(
                record.get("mentor_id") or record.get("profile_id") or record.get("id") or f"index-{index}"
            )
            try:
                built = build_mentor_matching_profile(record)
            except (ValueError, TypeError):
                skipped.append(fallback)
                continue
            usable_records.append(record)
            mentor_profiles[built.profile_id] = built

        if not usable_records:
            return {
                "mentee_id": mentee.profile_id,
                "recommendations": [],
                "skipped_mentor_ids": sorted(skipped),
                "mentee_profile_version": mentee.profile_version,
            }

        semantic_matcher = EmbeddingMatcher(
            self.provider, self.cache, top_k=len(usable_records)
        )
        semantic_result = semantic_matcher.match(mentee_record, usable_records)
        semantic = {
            row.mentor_id: max(0.0, min(1.0, (row.cosine_similarity + 1.0) / 2.0))
            for row in semantic_result.recommendations
        }
        lexical = BM25Retriever(
            {key: profile.matching_text for key, profile in mentor_profiles.items()}
        ).score(mentee.matching_text)
        discovery = sorted(
            mentor_profiles,
            key=lambda key: (-(0.65 * semantic.get(key, 0.0) + 0.35 * lexical.get(key, 0.0)), key),
        )[: self.candidate_k]

        scored: list[tuple[float, str, dict[str, Any]]] = []
        for mentor_id in discovery:
            mentor = mentor_profiles[mentor_id]
            desired_roles = _values(mentee, "Desired role", "Current role")
            mentor_roles = _values(mentor, "Expertise", "Professional experience")
            desired_skills = _values(mentee, "Skills to develop", "Technologies")
            mentor_skills = _values(mentor, "Technologies", "Expertise", "Mentoring areas")
            goals = _values(mentee, "Career goals", "Goals", "Desired support", "Needs", "Bottlenecks")
            industries = _values(mentee, "Industries", "Sector")
            mentor_industries = _values(mentor, "Industries")
            role = _overlap(desired_roles, mentor_roles)
            skills = _overlap(desired_skills, mentor_skills)
            goal_overlap = _overlap(goals, mentor_roles + mentor_skills)
            goal = min(1.0, 0.65 * goal_overlap + 0.35 * semantic.get(mentor_id, 0.0))
            industry = _overlap(industries, mentor_industries)
            mentee_years = _number(_values(mentee, "Experience years"))
            mentor_years = _number(_values(mentor, "Experience years"))
            if mentee_years is None or mentor_years is None:
                experience = 0.5
            elif mentor_years >= mentee_years + 3:
                experience = 1.0
            elif mentor_years >= mentee_years + 1:
                experience = 0.6
            else:
                experience = 0.2
            languages = _values(mentee, "Languages")
            preferences = _overlap(languages, _values(mentor, "Languages")) if languages else 0.5
            components = {
                "desired_role": role,
                "skills": skills,
                "goals": goal,
                "industry": industry,
                "experience_gap": experience,
                "preferences": preferences,
            }
            field_score = sum(DIRECT_MATCH_WEIGHTS[key] * value for key, value in components.items())
            final = min(1.0, 0.75 * field_score + 0.15 * semantic.get(mentor_id, 0.0) + 0.10 * lexical.get(mentor_id, 0.0))
            reasons = [
                label
                for label, value in sorted(components.items(), key=lambda item: -item[1])
                if value >= 0.5
            ][:3]
            scored.append((final, mentor_id, {
                "mentor_id": mentor_id,
                "final_score": final,
                "cosine_similarity": semantic.get(mentor_id, 0.0) * 2.0 - 1.0,
                "semantic_score": semantic.get(mentor_id, 0.0),
                "lexical_score": lexical.get(mentor_id, 0.0),
                "field_score": field_score,
                "components": components,
                "reasons": reasons,
                "mentor_matching_text": mentor.matching_text,
                "mentee_matching_text": mentee.matching_text,
                "shared_fields": deterministic_shared_fields(mentor, mentee),
                "mentor_profile_version": mentor.profile_version,
                "mentee_profile_version": mentee.profile_version,
            }))

        scored.sort(key=lambda item: (-item[0], item[1]))
        recommendations = []
        for rank, (_, __, recommendation) in enumerate(scored[: self.top_k], start=1):
            recommendation["ranking_position"] = rank
            recommendations.append(recommendation)
        return {
            "mentee_id": mentee.profile_id,
            "recommendations": recommendations,
            "skipped_mentor_ids": sorted(set(skipped + list(semantic_result.skipped_mentor_ids))),
            "embedding_model": self.provider.configuration.model,
            "embedding_configuration": self.provider.configuration.to_dict(),
            "mentee_profile_version": mentee.profile_version,
        }
