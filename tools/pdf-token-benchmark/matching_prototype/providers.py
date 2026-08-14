from __future__ import annotations

import hashlib
import json
import math
import re
import os
import urllib.request
from abc import ABC, abstractmethod
from collections.abc import Sequence

from .models import EmbeddingConfiguration
from ai_provider import create_client, provider_settings


class EmbeddingProvider(ABC):
    @property
    @abstractmethod
    def configuration(self) -> EmbeddingConfiguration:
        raise NotImplementedError

    @abstractmethod
    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        raise NotImplementedError


class OpenAIEmbeddingProvider(EmbeddingProvider):
    """OpenAI-backed provider using one fixed configuration for both profile types."""

    def __init__(self, client=None, *, model: str | None = None) -> None:
        if client is None:
            settings = provider_settings({**os.environ, "AI_PROVIDER": "openai"})
            client = create_client(settings)
        self._client = client
        self._usage_calls: list[dict[str, int]] = []
        self._configuration = EmbeddingConfiguration(
            provider="openai",
            model=model or os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small"),
            dimensions=1536,
            encoding_format="float",
        )

    @property
    def configuration(self) -> EmbeddingConfiguration:
        return self._configuration

    @property
    def usage_calls(self) -> list[dict[str, int]]:
        return list(self._usage_calls)

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        if not texts:
            return []
        response = self._client.embeddings.create(
            model=self.configuration.model,
            input=list(texts),
            dimensions=self.configuration.dimensions,
            encoding_format=self.configuration.encoding_format,
        )
        usage = getattr(response, "usage", None)
        self._usage_calls.append({
            "input_tokens": int(getattr(usage, "prompt_tokens", 0) or 0),
            "total_tokens": int(getattr(usage, "total_tokens", 0) or 0),
        })
        ordered = sorted(response.data, key=lambda item: item.index)
        return [list(item.embedding) for item in ordered]


class OpenRouterEmbeddingProvider(OpenAIEmbeddingProvider):
    """OpenRouter embedding endpoint using the same fixed config for all profiles."""

    def __init__(self, client=None, *, model: str | None = None) -> None:
        settings = provider_settings({**os.environ, "AI_PROVIDER": "openrouter"})
        if client is None:
            client = create_client(settings)
        super().__init__(client=client, model=model or settings.embedding_model)
        selected_model = model or settings.embedding_model
        known_dimensions = {
            "openai/text-embedding-3-small": 1536,
            "openai/text-embedding-3-large": 3072,
            "openai/text-embedding-ada-002": 1536,
        }
        self._configuration = EmbeddingConfiguration(
            provider="openrouter",
            model=selected_model,
            dimensions=known_dimensions.get(selected_model, 0),
            encoding_format="float",
        )

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        if not texts:
            return []
        response = self._client.embeddings.create(
            model=self.configuration.model,
            input=list(texts),
        )
        usage = getattr(response, "usage", None)
        self._usage_calls.append({
            "input_tokens": int(getattr(usage, "prompt_tokens", 0) or 0),
            "total_tokens": int(getattr(usage, "total_tokens", 0) or 0),
        })
        ordered = sorted(response.data, key=lambda item: item.index)
        return [list(item.embedding) for item in ordered]


class OllamaEmbeddingProvider(EmbeddingProvider):
    """Local Ollama embedding provider with a fixed, cacheable configuration."""

    def __init__(
        self,
        *,
        model: str | None = None,
        base_url: str | None = None,
        dimensions: int = 1024,
    ) -> None:
        self._base_url = (base_url or os.getenv("OLLAMA_BASE_URL", "http://127.0.0.1:11434")).rstrip("/")
        self._usage_calls: list[dict[str, int]] = []
        self._configuration = EmbeddingConfiguration(
            provider="ollama",
            model=model or os.getenv("OLLAMA_EMBEDDING_MODEL", "bge-m3"),
            dimensions=dimensions,
            encoding_format="float",
        )

    @property
    def configuration(self) -> EmbeddingConfiguration:
        return self._configuration

    @property
    def usage_calls(self) -> list[dict[str, int]]:
        return list(self._usage_calls)

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        if not texts:
            return []
        body = json.dumps(
            {
                "model": self.configuration.model,
                "input": list(texts),
                "truncate": False,
            }
        ).encode("utf-8")
        request = urllib.request.Request(
            f"{self._base_url}/api/embed",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=300) as response:
            payload = json.loads(response.read().decode("utf-8"))
        vectors = [[float(value) for value in vector] for vector in payload.get("embeddings", [])]
        if len(vectors) != len(texts):
            raise RuntimeError(f"Ollama returned {len(vectors)} embeddings for {len(texts)} texts")
        if vectors and len(vectors[0]) != self.configuration.dimensions:
            raise RuntimeError(
                f"Expected {self.configuration.dimensions} dimensions, got {len(vectors[0])}"
            )
        prompt_tokens = int(payload.get("prompt_eval_count", 0) or 0)
        self._usage_calls.append({"input_tokens": prompt_tokens, "total_tokens": prompt_tokens})
        return vectors


class DeterministicHashEmbeddingProvider(EmbeddingProvider):
    """Network-free lexical embedding used only for tests and sample evaluation."""

    _TOKENS = re.compile(r"[^\W_]+", re.UNICODE)

    def __init__(self, dimensions: int = 256) -> None:
        if dimensions <= 0:
            raise ValueError("dimensions must be positive")
        self._configuration = EmbeddingConfiguration(
            provider="deterministic-test",
            model="deterministic-hash-v1",
            dimensions=dimensions,
        )

    @property
    def configuration(self) -> EmbeddingConfiguration:
        return self._configuration

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        vectors: list[list[float]] = []
        for text in texts:
            vector = [0.0] * self.configuration.dimensions
            tokens = [token.casefold() for token in self._TOKENS.findall(text)]
            for token in tokens:
                digest = hashlib.sha256(token.encode("utf-8")).digest()
                index = int.from_bytes(digest[:8], "big") % len(vector)
                sign = 1.0 if digest[8] & 1 else -1.0
                vector[index] += sign
            norm = math.sqrt(sum(value * value for value in vector))
            if norm:
                vector = [value / norm for value in vector]
            vectors.append(vector)
        return vectors
