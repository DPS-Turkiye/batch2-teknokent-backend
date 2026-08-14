"""Prototype mentor–mentee embedding matcher for parsed profile JSON."""

from .matcher import EmbeddingMatcher, cosine_similarity
from .models import MatchResult, Recommendation, UnusableProfileError
from .providers import DeterministicHashEmbeddingProvider, OpenAIEmbeddingProvider

__all__ = [
    "DeterministicHashEmbeddingProvider",
    "EmbeddingMatcher",
    "MatchResult",
    "OpenAIEmbeddingProvider",
    "Recommendation",
    "UnusableProfileError",
    "cosine_similarity",
]
