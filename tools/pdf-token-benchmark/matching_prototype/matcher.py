from __future__ import annotations

import math
from collections.abc import Sequence
from typing import Any

from .cache import EmbeddingCache, MemoryEmbeddingCache, embedding_fingerprint
from .models import MATCHING_TEXT_VERSION, MatchResult, Recommendation, UnusableProfileError
from .providers import EmbeddingProvider
from .text_builder import (
    build_mentee_matching_profile,
    build_mentor_matching_profile,
    deterministic_shared_fields,
)


def cosine_similarity(left: Sequence[float], right: Sequence[float]) -> float:
    if len(left) != len(right):
        raise ValueError("embedding vectors must have the same dimensions")
    if not left:
        raise ValueError("embedding vectors must not be empty")
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0 or right_norm == 0:
        raise ValueError("cosine similarity is undefined for a zero vector")
    return sum(a * b for a, b in zip(left, right)) / (left_norm * right_norm)


class EmbeddingMatcher:
    def __init__(
        self,
        provider: EmbeddingProvider,
        cache: EmbeddingCache | None = None,
        *,
        top_k: int = 3,
        matching_text_version: str = MATCHING_TEXT_VERSION,
    ) -> None:
        if top_k <= 0:
            raise ValueError("top_k must be positive")
        self.provider = provider
        self.cache = cache or MemoryEmbeddingCache()
        self.top_k = top_k
        self.matching_text_version = matching_text_version

    def match(
        self, mentee_record: dict[str, Any], mentor_records: Sequence[dict[str, Any]]
    ) -> MatchResult:
        mentee = build_mentee_matching_profile(mentee_record)
        mentors = []
        skipped: list[str] = []
        for index, record in enumerate(mentor_records):
            fallback_id = str(record.get("mentor_id") or record.get("profile_id") or record.get("id") or f"index-{index}")
            try:
                mentors.append(build_mentor_matching_profile(record))
            except UnusableProfileError:
                skipped.append(fallback_id)

        if not mentors:
            return MatchResult(
                mentee_id=mentee.profile_id,
                recommendations=(),
                embedding_model=self.provider.configuration.model,
                embedding_configuration=self.provider.configuration.to_dict(),
                matching_text_version=self.matching_text_version,
                mentee_profile_version=mentee.profile_version,
                skipped_mentor_ids=tuple(sorted(skipped)),
            )

        vectors: dict[str, list[float]] = {}
        missing_profiles = []
        missing_fingerprints: dict[str, str] = {}
        for mentor in mentors:
            fingerprint = embedding_fingerprint(
                mentor, self.provider.configuration
            )
            cached = self.cache.get(mentor.profile_id, fingerprint)
            if cached is not None:
                vectors[mentor.profile_id] = cached
            else:
                missing_profiles.append(mentor)
                missing_fingerprints[mentor.profile_id] = fingerprint

        if missing_profiles:
            generated = self.provider.embed([profile.matching_text for profile in missing_profiles])
            if len(generated) != len(missing_profiles):
                raise RuntimeError("embedding provider returned an unexpected vector count")
            for profile, vector in zip(missing_profiles, generated):
                vectors[profile.profile_id] = vector
                self.cache.put(
                    profile.profile_id,
                    missing_fingerprints[profile.profile_id],
                    vector,
                    {
                        "embedding": self.provider.configuration.to_dict(),
                        "matching_text_version": self.matching_text_version,
                        "profile_version": profile.profile_version,
                    },
                )

        mentee_vector = self.provider.embed([mentee.matching_text])[0]
        scored = [
            (cosine_similarity(mentee_vector, vectors[mentor.profile_id]), mentor)
            for mentor in mentors
        ]
        scored.sort(key=lambda item: (-item[0], item[1].profile_id))

        recommendations = tuple(
            Recommendation(
                mentor_id=mentor.profile_id,
                cosine_similarity=score,
                ranking_position=rank,
                mentor_matching_text=mentor.matching_text,
                mentee_matching_text=mentee.matching_text,
                shared_fields=deterministic_shared_fields(mentor, mentee),
                embedding_model=self.provider.configuration.model,
                embedding_configuration=self.provider.configuration.to_dict(),
                matching_text_version=self.matching_text_version,
                mentor_profile_version=mentor.profile_version,
                mentee_profile_version=mentee.profile_version,
            )
            for rank, (score, mentor) in enumerate(scored[: self.top_k], start=1)
        )
        return MatchResult(
            mentee_id=mentee.profile_id,
            recommendations=recommendations,
            embedding_model=self.provider.configuration.model,
            embedding_configuration=self.provider.configuration.to_dict(),
            matching_text_version=self.matching_text_version,
            mentee_profile_version=mentee.profile_version,
            skipped_mentor_ids=tuple(sorted(skipped)),
        )
