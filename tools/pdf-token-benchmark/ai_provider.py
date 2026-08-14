"""Shared AI-provider configuration for parsing and matching prototypes."""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Mapping

from openai import OpenAI


OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
DEFAULT_OPENAI_MODEL = "gpt-5.6-luna"
DEFAULT_OPENROUTER_MODEL = "openai/gpt-4o-mini"
DEFAULT_OPENAI_EMBEDDING_MODEL = "text-embedding-3-small"
DEFAULT_OPENROUTER_EMBEDDING_MODEL = "openai/text-embedding-3-small"


@dataclass(frozen=True)
class AIProviderSettings:
    provider: str
    api_key: str
    base_url: str | None
    parsing_model: str
    embedding_model: str
    pdf_engine: str | None = None
    default_headers: dict[str, str] | None = None

    @property
    def key_environment_variable(self) -> str:
        return "OPENROUTER_API_KEY" if self.provider == "openrouter" else "OPENAI_API_KEY"


def provider_settings(environment: Mapping[str, str] | None = None) -> AIProviderSettings:
    env = environment if environment is not None else os.environ
    provider = env.get("AI_PROVIDER", "openai").strip().lower() or "openai"
    if provider not in {"openai", "openrouter"}:
        raise ValueError("AI_PROVIDER must be 'openai' or 'openrouter'.")

    if provider == "openrouter":
        headers = {}
        if env.get("OPENROUTER_SITE_URL", "").strip():
            headers["HTTP-Referer"] = env["OPENROUTER_SITE_URL"].strip()
        if env.get("OPENROUTER_APP_NAME", "").strip():
            headers["X-OpenRouter-Title"] = env["OPENROUTER_APP_NAME"].strip()
        return AIProviderSettings(
            provider=provider,
            api_key=env.get("OPENROUTER_API_KEY", "").strip(),
            base_url=env.get("OPENROUTER_BASE_URL", OPENROUTER_BASE_URL).strip(),
            parsing_model=env.get("OPENROUTER_MODEL", DEFAULT_OPENROUTER_MODEL).strip(),
            embedding_model=env.get(
                "OPENROUTER_EMBEDDING_MODEL", DEFAULT_OPENROUTER_EMBEDDING_MODEL
            ).strip(),
            pdf_engine=env.get("OPENROUTER_PDF_ENGINE", "").strip() or None,
            default_headers=headers or None,
        )

    return AIProviderSettings(
        provider=provider,
        api_key=env.get("OPENAI_API_KEY", "").strip(),
        base_url=None,
        parsing_model=env.get("OPENAI_MODEL", DEFAULT_OPENAI_MODEL).strip(),
        embedding_model=env.get(
            "OPENAI_EMBEDDING_MODEL", DEFAULT_OPENAI_EMBEDDING_MODEL
        ).strip(),
    )


def create_client(settings: AIProviderSettings) -> OpenAI:
    if not settings.api_key:
        raise ValueError(f"{settings.key_environment_variable} is empty.")
    options = {"api_key": settings.api_key}
    if settings.base_url:
        options["base_url"] = settings.base_url
    if settings.default_headers:
        options["default_headers"] = settings.default_headers
    return OpenAI(**options)
