from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any


MATCHING_TEXT_VERSION = "matching-text-v2"


class UnusableProfileError(ValueError):
    """Raised when no matching-relevant content can be built from a profile."""


@dataclass(frozen=True)
class BuiltMatchingProfile:
    profile_id: str
    profile_version: str
    matching_text: str
    fields: dict[str, tuple[str, ...]]


@dataclass(frozen=True)
class EmbeddingConfiguration:
    provider: str
    model: str
    dimensions: int
    encoding_format: str = "float"

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class Recommendation:
    mentor_id: str
    cosine_similarity: float
    ranking_position: int
    mentor_matching_text: str
    mentee_matching_text: str
    shared_fields: dict[str, list[str]]
    embedding_model: str
    embedding_configuration: dict[str, Any]
    matching_text_version: str
    mentor_profile_version: str
    mentee_profile_version: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class MatchResult:
    mentee_id: str
    recommendations: tuple[Recommendation, ...]
    embedding_model: str
    embedding_configuration: dict[str, Any]
    matching_text_version: str
    mentee_profile_version: str
    skipped_mentor_ids: tuple[str, ...] = field(default_factory=tuple)

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["recommendations"] = [item.to_dict() for item in self.recommendations]
        value["skipped_mentor_ids"] = list(self.skipped_mentor_ids)
        return value
