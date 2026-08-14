"""Server-side model discovery with short-lived caching and safe fallbacks."""

from __future__ import annotations

import json
import time
from hashlib import sha256
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from ai_provider import AIProviderSettings


CATALOG_TTL_SECONDS = 300
_CACHE: dict[tuple[str, str, str], tuple[float, list[dict[str, Any]]]] = {}


class ModelCatalogError(RuntimeError):
    pass


def _fallback(settings: AIProviderSettings, kind: str) -> list[dict[str, Any]]:
    model = settings.parsing_model if kind == "parsing" else settings.embedding_model
    return [{"id": model, "name": model, "context_length": None}]


def _catalog_url(settings: AIProviderSettings, kind: str) -> str:
    base = (settings.base_url or "").rstrip("/")
    return f"{base}/models/user" if kind == "parsing" else f"{base}/embeddings/models"


def _parse_models(payload: Any, kind: str) -> list[dict[str, Any]]:
    records = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(records, list):
        raise ModelCatalogError("The provider returned an invalid model catalog.")
    models = []
    for record in records:
        if not isinstance(record, dict) or not isinstance(record.get("id"), str):
            continue
        architecture = record.get("architecture") if isinstance(record.get("architecture"), dict) else {}
        output_modalities = architecture.get("output_modalities") or []
        supported = set(record.get("supported_parameters") or [])
        if kind == "parsing":
            if "text" not in output_modalities:
                continue
            if not {"structured_outputs", "response_format"}.intersection(supported):
                continue
        elif "embeddings" not in output_modalities:
            continue
        models.append(
            {
                "id": record["id"],
                "name": str(record.get("name") or record["id"]),
                "context_length": record.get("context_length"),
            }
        )
    models.sort(key=lambda item: (item["name"].casefold(), item["id"]))
    return models


def available_models(
    settings: AIProviderSettings,
    kind: str,
    *,
    opener: Callable[..., Any] = urlopen,
    force_refresh: bool = False,
) -> tuple[list[dict[str, Any]], str | None]:
    if kind not in {"parsing", "embedding"}:
        raise ValueError("kind must be 'parsing' or 'embedding'")
    if settings.provider != "openrouter":
        return _fallback(settings, kind), None
    if not settings.api_key:
        return _fallback(settings, kind), f"{settings.key_environment_variable} is not configured."

    key_fingerprint = sha256(settings.api_key.encode("utf-8")).hexdigest()[:16]
    cache_key = (settings.base_url or "", kind, key_fingerprint)
    cached = _CACHE.get(cache_key)
    if not force_refresh and cached and time.monotonic() - cached[0] < CATALOG_TTL_SECONDS:
        return cached[1], None

    request = Request(
        _catalog_url(settings, kind),
        headers={"Authorization": f"Bearer {settings.api_key}", "Accept": "application/json"},
    )
    try:
        with opener(request, timeout=12) as response:
            models = _parse_models(json.load(response), kind)
    except HTTPError as error:
        return _fallback(settings, kind), f"Model catalog request failed with HTTP {error.code}."
    except (URLError, TimeoutError, OSError, json.JSONDecodeError, ModelCatalogError) as error:
        return _fallback(settings, kind), f"Model catalog unavailable: {error}"

    configured = settings.parsing_model if kind == "parsing" else settings.embedding_model
    if not any(item["id"] == configured for item in models):
        models.insert(0, {"id": configured, "name": f"{configured} (configured)", "context_length": None})
    if not models:
        return _fallback(settings, kind), "No compatible models were returned; using the configured fallback."
    _CACHE[cache_key] = (time.monotonic(), models)
    return models, None
