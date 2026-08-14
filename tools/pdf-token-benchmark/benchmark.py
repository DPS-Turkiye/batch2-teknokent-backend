#!/usr/bin/env python3
"""Compare local-text and direct-PDF OpenAI profile extraction token usage."""

from __future__ import annotations

import argparse
import base64
import csv
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Literal

from dotenv import load_dotenv
from openai import OpenAI
from pydantic import BaseModel
from pypdf import PdfReader

from ai_provider import AIProviderSettings, create_client, provider_settings


SCRIPT_DIR = Path(__file__).resolve().parent
BACKEND_DIR = SCRIPT_DIR.parents[1]
DEFAULT_RESULTS_DIR = SCRIPT_DIR / "results"

_EMAIL_PATTERN = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
_TURKISH_ID_PATTERN = re.compile(r"(?<!\d)[1-9]\d{10}(?!\d)")
_PHONE_PATTERN = re.compile(
    r"(?<!\w)(?:\+?90[\s().-]*)?(?:0?[2-5]\d{2})[\s().-]*\d{3}[\s.-]*\d{2}[\s.-]*\d{2}(?!\w)"
)

# USD per 1M tokens. Override the model or rates in code if pricing changes.
MODEL_PRICES: dict[str, dict[str, float]] = {
    "gpt-5.6": {"input": 5.00, "cached_input": 0.50, "output": 30.00},
    "gpt-5.6-sol": {"input": 5.00, "cached_input": 0.50, "output": 30.00},
    "gpt-5.6-terra": {"input": 2.50, "cached_input": 0.25, "output": 15.00},
    "gpt-5.6-luna": {"input": 1.00, "cached_input": 0.10, "output": 6.00},
    # OpenRouter account-catalog rates captured for the cost-accuracy pilot.
    "openai/gpt-5.6-luna": {"input": 0.10, "cached_input": 0.10, "output": 0.60},
    "openai/gpt-4o-mini": {"input": 0.15, "cached_input": 0.15, "output": 0.60},
    "google/gemini-2.5-flash-lite": {"input": 0.10, "cached_input": 0.10, "output": 0.40},
    "google/gemini-2.5-flash": {"input": 0.30, "cached_input": 0.30, "output": 2.50},
    "google/gemma-4-26b-a4b-it:free": {"input": 0.0, "cached_input": 0.0, "output": 0.0},
    "qwen/qwen3.7-flash": {"input": 0.03, "cached_input": 0.03, "output": 0.13},
    "anthropic/claude-haiku-4.5": {"input": 1.00, "cached_input": 1.00, "output": 5.00},
    "anthropic/claude-sonnet-5": {"input": 2.00, "cached_input": 2.00, "output": 10.00},
}

COMMON_INSTRUCTIONS = """
Extract a structured profile from the supplied source.

Rules:
- Extract only information supported by the source.
- Never invent experience, preferences, availability, achievements, or startup facts.
- Use null for unavailable scalar values and [] for unavailable lists.
- Keep evidence quotes short and verbatim.
- For important claims, include the source page when it can be determined.
- Distinguish explicit facts from reasonable inferences.
- Do not extract age, photographs, marital status, home address, phone numbers, or
  personal email addresses.
- Put information that should be collected in a follow-up form in missing_fields.
- Add document quality or extraction limitations to warnings.
""".strip()

VERIFICATION_INSTRUCTIONS = """
You are performing a quality-control pass over a previously extracted profile.
Read the original PDF again and return a corrected version of the same schema.
Keep only claims supported by the source. Restore important facts that the candidate
missed when they are clearly present. Correct wrong fields, preserve page evidence,
and use null or [] rather than guessing. Do not describe the review; return only the
structured profile.
""".strip()

MENTOR_INSTRUCTIONS = """
Create a mentor profile. Focus on employment, education, professional functions,
industries, technologies, projects, achievements, years of experience, and languages.
Do not infer mentoring availability, capacity, style, meeting preference, motivation,
or conflicts of interest when they are not explicitly present.
""".strip()

MENTEE_INSTRUCTIONS = """
Create a mentee/startup profile. Focus on startup name, stage, business model, sector,
target market, team size, technologies, current bottlenecks, goals, and support needs.
Do not infer objective company facts that are not explicitly present.
""".strip()

INDIVIDUAL_MENTEE_INSTRUCTIONS = """
Create an individual mentee profile for career mentoring. Focus on current and desired
roles, professional functions, industries, experience level, years of experience,
technologies, projects, achievements, languages, career goals, skills to develop, and
support needs. Do not infer career goals, mentoring preferences, or availability when
they are not explicitly present; collect those later through the matching form.
""".strip()


class Evidence(BaseModel):
    claim: str
    page: int | None
    quote: str
    information_type: Literal["explicit", "inferred"]


class MentorProfile(BaseModel):
    full_name: str | None
    professional_summary: str | None
    industries: list[str]
    professional_functions: list[str]
    technologies: list[str]
    languages: list[str]
    years_of_experience: float | None
    employment_highlights: list[Evidence]
    education_highlights: list[Evidence]
    achievement_highlights: list[Evidence]
    missing_fields: list[str]
    warnings: list[str]


class MenteeProfile(BaseModel):
    startup_name: str | None
    startup_summary: str | None
    stage: str | None
    business_model: str | None
    sectors: list[str]
    target_markets: list[str]
    team_size: int | None
    technologies: list[str]
    current_bottlenecks: list[Evidence]
    goals: list[Evidence]
    support_needs: list[Evidence]
    missing_fields: list[str]
    warnings: list[str]


class IndividualMenteeProfile(BaseModel):
    full_name: str | None
    professional_summary: str | None
    current_role: str | None
    desired_role: str | None
    professional_functions: list[str]
    industries: list[str]
    experience_level: str | None
    years_of_experience: float | None
    technologies: list[str]
    projects: list[Evidence]
    achievement_highlights: list[Evidence]
    languages: list[str]
    career_goals: list[Evidence]
    skills_to_develop: list[str]
    support_needs: list[Evidence]
    missing_fields: list[str]
    warnings: list[str]


ProfileModel = type[MentorProfile] | type[MenteeProfile] | type[IndividualMenteeProfile]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run the same structured profile extraction twice: once with locally "
            "extracted PDF text and once with the original PDF as a multimodal input."
        )
    )
    parser.add_argument("pdfs", nargs="+", type=Path, help="One or more PDF files.")
    parser.add_argument(
        "--approach",
        choices=("both", "local_text", "direct_pdf"),
        default="both",
        help="Run both approaches or only one (default: both).",
    )
    parser.add_argument(
        "--profile-type",
        choices=("mentor", "mentee", "individual_mentee"),
        default="mentor",
        help="Structured profile schema to use for both approaches (default: mentor).",
    )
    parser.add_argument(
        "--model",
        default=None,
        help="Provider model. Defaults to OPENAI_MODEL or OPENROUTER_MODEL.",
    )
    parser.add_argument(
        "--pdf-detail",
        choices=("low", "high", "auto"),
        default="low",
        help="Direct-PDF page-image detail (default: low, fewer input tokens).",
    )
    parser.add_argument(
        "--max-output-tokens",
        type=int,
        default=2500,
        help="Per-request output-token ceiling (default: 2500).",
    )
    parser.add_argument(
        "--order",
        choices=("local-first", "direct-first"),
        default="local-first",
        help="Call order. Cached tokens are reported separately (default: local-first).",
    )
    parser.add_argument(
        "--results-dir",
        type=Path,
        default=DEFAULT_RESULTS_DIR,
        help=f"Output directory (default: {DEFAULT_RESULTS_DIR}).",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Extract local text and show the plan without calling OpenAI.",
    )
    parser.add_argument(
        "--skip-verification",
        action="store_true",
        help="Do not send the original PDF for a direct-PDF verification pass.",
    )
    parser.add_argument(
        "--ocr-mode",
        choices=("off", "auto", "force"),
        default="off",
        help="Apply local OCR never, when extraction is weak, or always.",
    )
    parser.add_argument(
        "--ocr-languages",
        default="eng+tur",
        help="Preferred local Tesseract languages (default: eng+tur).",
    )
    return parser.parse_args()


def profile_config(profile_type: str) -> tuple[ProfileModel, str]:
    if profile_type == "mentor":
        return MentorProfile, MENTOR_INSTRUCTIONS
    if profile_type == "individual_mentee":
        return IndividualMenteeProfile, INDIVIDUAL_MENTEE_INSTRUCTIONS
    return MenteeProfile, MENTEE_INSTRUCTIONS


def validate_pdf(path: Path) -> Path:
    resolved = path.expanduser().resolve()
    if not resolved.is_file():
        raise ValueError(f"PDF not found: {resolved}")
    if resolved.suffix.lower() != ".pdf":
        raise ValueError(f"Expected a .pdf file: {resolved}")
    if resolved.stat().st_size >= 50 * 1024 * 1024:
        raise ValueError(f"PDF must be smaller than 50 MB: {resolved}")
    if resolved.read_bytes()[:5] != b"%PDF-":
        raise ValueError(f"File does not have a PDF signature: {resolved}")
    return resolved


def extract_text(path: Path) -> tuple[str, int, list[str]]:
    """Extract page-delimited text so Option 1 preserves page evidence."""
    reader = PdfReader(str(path))
    pages: list[str] = []
    warnings: list[str] = []
    for page_number, page in enumerate(reader.pages, start=1):
        page_text = (page.extract_text() or "").strip()
        if not page_text:
            warnings.append(f"Page {page_number} produced no machine-readable text.")
        pages.append(f"[Page {page_number}]\n{page_text}")

    text = "\n\n".join(pages).strip()
    visible_chars = sum(len(page.split("\n", 1)[-1].strip()) for page in pages)
    if visible_chars < 100:
        warnings.append(
            "Very little text was extracted. The PDF may be scanned; add OCR before "
            "treating the local-text result as representative."
        )
    return text, len(reader.pages), warnings


def _visible_character_count(text: str) -> int:
    return sum(character.isalnum() for character in text)


def _needs_ocr(text: str, page_count: int, warnings: list[str]) -> bool:
    visible_characters = _visible_character_count(text)
    empty_pages = sum("produced no machine-readable text" in warning for warning in warnings)
    return visible_characters < max(350, page_count * 180) or empty_pages > 0


def _installed_ocr_languages() -> set[str]:
    tesseract = shutil.which("tesseract")
    if not tesseract:
        return set()
    completed = subprocess.run(
        [tesseract, "--list-langs"],
        capture_output=True,
        check=False,
        text=True,
        timeout=15,
    )
    return {
        line.strip()
        for line in completed.stdout.splitlines()
        if line.strip() and not line.startswith("List of available")
    }


def extract_text_with_ocr(
    path: Path, *, mode: str, preferred_languages: str
) -> tuple[str, int, list[str], dict[str, Any]]:
    """Extract text locally and optionally improve weak/complex PDFs with OCR."""
    text, page_count, warnings = extract_text(path)
    should_run = mode == "force" or (
        mode == "auto" and _needs_ocr(text, page_count, warnings)
    )
    metadata: dict[str, Any] = {
        "mode": mode,
        "applied": False,
        "language": None,
        "reason": "not-needed" if mode == "auto" else "disabled",
    }
    if not should_run:
        return text, page_count, warnings, metadata

    ocrmypdf = shutil.which("ocrmypdf")
    installed_languages = _installed_ocr_languages()
    requested_languages = [
        language.strip() for language in preferred_languages.split("+") if language.strip()
    ]
    usable_languages = [
        language for language in requested_languages if language in installed_languages
    ]
    if not ocrmypdf or not usable_languages:
        warnings.append(
            "Local OCR was requested but OCRmyPDF or a requested Tesseract language is unavailable."
        )
        metadata["reason"] = "runtime-unavailable"
        return text, page_count, warnings, metadata

    language_value = "+".join(usable_languages)
    if len(usable_languages) != len(requested_languages):
        missing = sorted(set(requested_languages) - set(usable_languages))
        warnings.append(
            "Local OCR language data is missing for: " + ", ".join(missing) + "."
        )

    try:
        with tempfile.TemporaryDirectory(prefix="teknokent-ocr-") as directory:
            output_path = Path(directory) / "ocr.pdf"
            completed = subprocess.run(
                [
                    ocrmypdf,
                    "--force-ocr",
                    "--deskew",
                    "--rotate-pages",
                    "--optimize",
                    "0",
                    "--jobs",
                    "1",
                    "--quiet",
                    "--language",
                    language_value,
                    str(path),
                    str(output_path),
                ],
                capture_output=True,
                check=False,
                text=True,
                timeout=180,
            )
            if completed.returncode != 0 or not output_path.is_file():
                detail = completed.stderr.strip().splitlines()
                raise RuntimeError(detail[-1] if detail else "OCRmyPDF failed.")
            ocr_text, _, ocr_warnings = extract_text(output_path)

        if _visible_character_count(ocr_text) < 100:
            warnings.append("Local OCR completed but did not produce usable text.")
            metadata["reason"] = "unusable-output"
            return text, page_count, warnings, metadata

        metadata.update(
            {
                "applied": True,
                "language": language_value,
                "reason": "forced" if mode == "force" else "weak-extraction",
            }
        )
        return (
            ocr_text,
            page_count,
            warnings + ocr_warnings + ["Local OCR was applied before PII redaction."],
            metadata,
        )
    except (OSError, subprocess.TimeoutExpired, RuntimeError) as error:
        warnings.append(f"Local OCR failed; original extraction was used: {error}")
        metadata["reason"] = "failed"
        return text, page_count, warnings, metadata


def redact_pii(text: str) -> tuple[str, dict[str, int]]:
    """Remove common contact and Turkish identity data before any model call."""
    redacted, email_count = _EMAIL_PATTERN.subn("[REDACTED_EMAIL]", text)
    redacted, identity_count = _TURKISH_ID_PATTERN.subn(
        "[REDACTED_ID]", redacted
    )
    redacted, phone_count = _PHONE_PATTERN.subn("[REDACTED_PHONE]", redacted)
    return redacted, {
        "emails": email_count,
        "phone_numbers": phone_count,
        "identity_numbers": identity_count,
        "total": email_count + phone_count + identity_count,
    }


def dump_model(value: Any) -> dict[str, Any]:
    if value is None:
        raise RuntimeError("The API response did not contain a parsed profile.")
    if hasattr(value, "model_dump"):
        return value.model_dump(mode="json")
    if isinstance(value, dict):
        return value
    raise TypeError(f"Unexpected parsed output type: {type(value).__name__}")


def dump_usage(response: Any) -> dict[str, Any]:
    usage = getattr(response, "usage", None)
    if usage is None:
        return {}
    if hasattr(usage, "model_dump"):
        return usage.model_dump(mode="json", exclude_none=False)
    if isinstance(usage, dict):
        return usage
    return {"raw": str(usage)}


def nested_int(data: dict[str, Any], *path: str) -> int:
    current: Any = data
    for key in path:
        if not isinstance(current, dict):
            return 0
        current = current.get(key)
    return int(current or 0)


def normalize_usage(usage: dict[str, Any]) -> dict[str, int]:
    input_tokens = nested_int(usage, "input_tokens") or nested_int(usage, "prompt_tokens")
    output_tokens = nested_int(usage, "output_tokens") or nested_int(usage, "completion_tokens")
    total_tokens = nested_int(usage, "total_tokens") or input_tokens + output_tokens
    input_details = "input_tokens_details" if "input_tokens_details" in usage else "prompt_tokens_details"
    output_details = "output_tokens_details" if "output_tokens_details" in usage else "completion_tokens_details"
    return {
        "input_tokens": input_tokens,
        "cached_input_tokens": nested_int(
            usage, input_details, "cached_tokens"
        ),
        "cache_write_tokens": nested_int(
            usage, input_details, "cache_write_tokens"
        ),
        "output_tokens": output_tokens,
        "reasoning_tokens": nested_int(
            usage, output_details, "reasoning_tokens"
        ),
        "total_tokens": total_tokens,
    }


def estimate_cost(model: str, tokens: dict[str, int]) -> float | None:
    rates = MODEL_PRICES.get(model)
    if rates is None:
        return None

    cached = tokens["cached_input_tokens"]
    cache_write = tokens["cache_write_tokens"]
    uncached = max(tokens["input_tokens"] - cached - cache_write, 0)
    cost = (
        uncached * rates["input"]
        + cached * rates["cached_input"]
        + cache_write * rates["input"] * 1.25
        + tokens["output_tokens"] * rates["output"]
    ) / 1_000_000
    return round(cost, 8)


def request_options(model: str, max_output_tokens: int) -> dict[str, Any]:
    options: dict[str, Any] = {
        "model": model,
        "max_output_tokens": max_output_tokens,
        "store": False,
    }
    if model.startswith("gpt-5"):
        options["reasoning"] = {"effort": "none"}
    return options


def _strict_json_schema(profile_model: ProfileModel) -> dict[str, Any]:
    schema = profile_model.model_json_schema()

    def make_strict(value: Any) -> None:
        if isinstance(value, dict):
            if value.get("type") == "object" or "properties" in value:
                value["additionalProperties"] = False
            for child in value.values():
                make_strict(child)
        elif isinstance(value, list):
            for child in value:
                make_strict(child)

    make_strict(schema)
    return {
        "type": "json_schema",
        "json_schema": {
            "name": profile_model.__name__,
            "strict": True,
            "schema": schema,
        },
    }


def _openrouter_response(response: Any, profile_model: ProfileModel) -> Any:
    if not response.choices:
        raise RuntimeError("OpenRouter returned no completion choices.")
    content = response.choices[0].message.content
    if not isinstance(content, str) or not content.strip():
        raise RuntimeError("OpenRouter returned no structured profile content.")
    stripped = content.strip()
    if stripped.startswith("```") and stripped.endswith("```"):
        first_newline = stripped.find("\n")
        if first_newline != -1:
            stripped = stripped[first_newline + 1 : -3].strip()
    parsed = profile_model.model_validate_json(stripped)
    return SimpleNamespace(
        id=getattr(response, "id", None),
        model=getattr(response, "model", None),
        usage=getattr(response, "usage", None),
        output_parsed=parsed,
    )


def _openrouter_extra_body(settings: AIProviderSettings, *, direct_pdf: bool) -> dict[str, Any]:
    body: dict[str, Any] = {"provider": {"require_parameters": True}}
    if direct_pdf and settings.pdf_engine:
        body["plugins"] = [
            {"id": "file-parser", "pdf": {"engine": settings.pdf_engine}}
        ]
    return body


def run_local_text(
    client: OpenAI,
    *,
    path: Path,
    extracted_text: str,
    model: str,
    profile_model: ProfileModel,
    profile_instructions: str,
    max_output_tokens: int,
    provider: AIProviderSettings | None = None,
) -> tuple[Any, float]:
    started = time.perf_counter()
    if provider and provider.provider == "openrouter":
        response = client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": f"{COMMON_INSTRUCTIONS}\n\n{profile_instructions}"},
                {
                    "role": "user",
                    "content": (
                        f"Source filename: {path.name}\n"
                        "The following text was extracted locally from the PDF. Page "
                        "markers are authoritative for page references.\n\n"
                        f"{extracted_text}"
                    ),
                },
            ],
            response_format=_strict_json_schema(profile_model),
            max_tokens=max_output_tokens,
            extra_body=_openrouter_extra_body(provider, direct_pdf=False),
        )
        return _openrouter_response(response, profile_model), time.perf_counter() - started
    response = client.responses.parse(
        **request_options(model, max_output_tokens),
        input=[
            {
                "role": "system",
                "content": f"{COMMON_INSTRUCTIONS}\n\n{profile_instructions}",
            },
            {
                "role": "user",
                "content": (
                    f"Source filename: {path.name}\n"
                    "The following text was extracted locally from the PDF. Page "
                    "markers are authoritative for page references.\n\n"
                    f"{extracted_text}"
                ),
            },
        ],
        text_format=profile_model,
    )
    return response, time.perf_counter() - started


def run_direct_pdf(
    client: OpenAI,
    *,
    path: Path,
    model: str,
    profile_model: ProfileModel,
    profile_instructions: str,
    pdf_detail: str,
    max_output_tokens: int,
    provider: AIProviderSettings | None = None,
    candidate_profile: dict[str, Any] | None = None,
) -> tuple[Any, float]:
    encoded_pdf = base64.b64encode(path.read_bytes()).decode("ascii")
    instructions = f"{COMMON_INSTRUCTIONS}\n\n{profile_instructions}"
    prompt = (
        "Read the original PDF, including its visual layout, and extract the requested "
        "profile. Use PDF page numbers for evidence."
    )
    if candidate_profile is not None:
        instructions = f"{instructions}\n\n{VERIFICATION_INSTRUCTIONS}"
        prompt = (
            "Review this candidate extraction against the original PDF, then return the "
            "corrected profile. Candidate JSON:\n"
            f"{json.dumps(candidate_profile, ensure_ascii=False)}"
        )
    started = time.perf_counter()
    if provider and provider.provider == "openrouter":
        response = client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": instructions},
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": prompt,
                        },
                        {
                            "type": "file",
                            "file": {
                                "filename": path.name,
                                "file_data": f"data:application/pdf;base64,{encoded_pdf}",
                            },
                        },
                    ],
                },
            ],
            response_format=_strict_json_schema(profile_model),
            max_tokens=max_output_tokens,
            extra_body=_openrouter_extra_body(provider, direct_pdf=True),
        )
        return _openrouter_response(response, profile_model), time.perf_counter() - started
    response = client.responses.parse(
        **request_options(model, max_output_tokens),
        input=[
            {
                "role": "system",
                "content": instructions,
            },
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_file",
                        "filename": path.name,
                        "file_data": f"data:application/pdf;base64,{encoded_pdf}",
                        "detail": pdf_detail,
                    },
                    {
                        "type": "input_text",
                        "text": prompt,
                    },
                ],
            },
        ],
        text_format=profile_model,
    )
    return response, time.perf_counter() - started


def result_from_response(
    approach: str, response: Any, elapsed_seconds: float, model: str
) -> dict[str, Any]:
    raw_usage = dump_usage(response)
    tokens = normalize_usage(raw_usage)
    return {
        "approach": approach,
        "response_id": getattr(response, "id", None),
        "response_model": getattr(response, "model", model),
        "elapsed_seconds": round(elapsed_seconds, 3),
        "usage": tokens,
        "raw_usage": raw_usage,
        "estimated_cost_usd": estimate_cost(model, tokens),
        "profile": dump_model(getattr(response, "output_parsed", None)),
        "error": None,
    }


def error_result(approach: str, error: Exception) -> dict[str, Any]:
    return {
        "approach": approach,
        "response_id": None,
        "response_model": None,
        "elapsed_seconds": None,
        "usage": {},
        "raw_usage": {},
        "estimated_cost_usd": None,
        "profile": None,
        "error": f"{type(error).__name__}: {error}",
    }


def comparison_for(results: dict[str, dict[str, Any]]) -> dict[str, Any]:
    if "local_text" not in results or "direct_pdf" not in results:
        return {"available": False, "reason": "Only one approach was requested."}
    local = results["local_text"]
    direct = results["direct_pdf"]
    if local["error"] or direct["error"]:
        return {"available": False, "reason": "Both approaches must succeed."}

    local_usage = local["usage"]
    direct_usage = direct["usage"]
    metrics = (
        "input_tokens",
        "cached_input_tokens",
        "cache_write_tokens",
        "output_tokens",
        "reasoning_tokens",
        "total_tokens",
    )
    differences = {
        metric: local_usage[metric] - direct_usage[metric] for metric in metrics
    }
    direct_input = direct_usage["input_tokens"]
    return {
        "available": True,
        "local_minus_direct": differences,
        "local_to_direct_input_ratio": (
            round(local_usage["input_tokens"] / direct_input, 4)
            if direct_input
            else None
        ),
        "lower_input_token_approach": (
            "local_text"
            if local_usage["input_tokens"] < direct_usage["input_tokens"]
            else "direct_pdf"
            if direct_usage["input_tokens"] < local_usage["input_tokens"]
            else "tie"
        ),
    }


def write_json(path: Path, payload: Any) -> None:
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def write_summary_csv(path: Path, runs: list[dict[str, Any]]) -> None:
    fields = [
        "pdf",
        "approach",
        "model",
        "pdf_pages",
        "pdf_bytes",
        "extracted_characters",
        "input_tokens",
        "cached_input_tokens",
        "cache_write_tokens",
        "output_tokens",
        "reasoning_tokens",
        "total_tokens",
        "estimated_cost_usd",
        "elapsed_seconds",
        "error",
    ]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for run in runs:
            for result in run["results"].values():
                usage = result.get("usage", {})
                writer.writerow(
                    {
                        "pdf": run["pdf"],
                        "approach": result["approach"],
                        "model": run["model"],
                        "pdf_pages": run["pdf_pages"],
                        "pdf_bytes": run["pdf_bytes"],
                        "extracted_characters": run["extracted_characters"],
                        "input_tokens": usage.get("input_tokens"),
                        "cached_input_tokens": usage.get("cached_input_tokens"),
                        "cache_write_tokens": usage.get("cache_write_tokens"),
                        "output_tokens": usage.get("output_tokens"),
                        "reasoning_tokens": usage.get("reasoning_tokens"),
                        "total_tokens": usage.get("total_tokens"),
                        "estimated_cost_usd": result.get("estimated_cost_usd"),
                        "elapsed_seconds": result.get("elapsed_seconds"),
                        "error": result.get("error"),
                    }
                )


def print_result(run: dict[str, Any]) -> None:
    print(f"\n{run['pdf']}")
    for approach in ("local_text", "direct_pdf"):
        if approach not in run["results"]:
            continue
        result = run["results"][approach]
        if result["error"]:
            print(f"  {approach}: ERROR - {result['error']}")
            continue
        usage = result["usage"]
        cost = result["estimated_cost_usd"]
        cost_text = f"${cost:.6f}" if cost is not None else "n/a"
        print(
            f"  {approach}: input={usage['input_tokens']}, "
            f"cached={usage['cached_input_tokens']}, "
            f"output={usage['output_tokens']}, "
            f"reasoning={usage['reasoning_tokens']}, "
            f"total={usage['total_tokens']}, cost~={cost_text}"
        )
    comparison = run["comparison"]
    if comparison["available"]:
        print(
            "  lower input-token approach: "
            f"{comparison['lower_input_token_approach']}"
        )


def main() -> int:
    args = parse_args()
    load_dotenv(BACKEND_DIR / ".env")
    api_settings = provider_settings()
    model = args.model or api_settings.parsing_model
    profile_model, profile_instructions = profile_config(args.profile_type)

    try:
        pdfs = [validate_pdf(path) for path in args.pdfs]
    except ValueError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    extracted: dict[Path, tuple[str, int, list[str], dict[str, Any]]] = {}
    for pdf in pdfs:
        try:
            extracted[pdf] = extract_text_with_ocr(
                pdf,
                mode=args.ocr_mode,
                preferred_languages=args.ocr_languages,
            )
        except Exception as error:
            print(f"error: local extraction failed for {pdf}: {error}", file=sys.stderr)
            return 2

    if args.dry_run:
        print(f"Model: {model}")
        print(f"Profile type: {args.profile_type}")
        print(f"Direct-PDF detail: {args.pdf_detail}")
        print("No provider API calls were made.")
        for pdf, (text, page_count, warnings, ocr_metadata) in extracted.items():
            print(
                f"- {pdf}: pages={page_count}, bytes={pdf.stat().st_size}, "
                f"extracted_characters={len(text)}, warnings={len(warnings)}, "
                f"ocr_applied={ocr_metadata['applied']}"
            )
        return 0

    if not api_settings.api_key:
        print(
            f"error: {api_settings.key_environment_variable} is empty in {BACKEND_DIR / '.env'}",
            file=sys.stderr,
        )
        return 2

    client = create_client(api_settings)
    timestamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    output_dir = args.results_dir.expanduser().resolve() / timestamp
    output_dir.mkdir(parents=True, exist_ok=False)
    all_runs: list[dict[str, Any]] = []
    any_error = False

    if args.approach == "local_text":
        approach_order = ("local_text",)
    elif args.approach == "direct_pdf":
        approach_order = ("direct_pdf",)
    else:
        approach_order = (
            ("local_text", "direct_pdf")
            if args.order == "local-first"
            else ("direct_pdf", "local_text")
        )

    for pdf in pdfs:
        text, page_count, extraction_warnings, ocr_metadata = extracted[pdf]
        redacted_text, pii_redaction = redact_pii(text)
        pdf_output_dir = output_dir / pdf.stem
        pdf_output_dir.mkdir(parents=True, exist_ok=True)
        (pdf_output_dir / "locally-extracted.txt").write_text(
            redacted_text + "\n", encoding="utf-8"
        )

        results: dict[str, dict[str, Any]] = {}
        for approach in approach_order:
            try:
                if approach == "local_text":
                    response, elapsed = run_local_text(
                        client,
                        path=pdf,
                        extracted_text=redacted_text,
                        model=model,
                        profile_model=profile_model,
                        profile_instructions=profile_instructions,
                        max_output_tokens=args.max_output_tokens,
                        provider=api_settings,
                    )
                else:
                    response, elapsed = run_direct_pdf(
                        client,
                        path=pdf,
                        model=model,
                        profile_model=profile_model,
                        profile_instructions=profile_instructions,
                        pdf_detail=args.pdf_detail,
                        max_output_tokens=args.max_output_tokens,
                        provider=api_settings,
                    )
                results[approach] = result_from_response(
                    approach, response, elapsed, model
                )
            except Exception as error:
                any_error = True
                results[approach] = error_result(approach, error)
            write_json(pdf_output_dir / f"{approach}.json", results[approach])

        candidate = next(
            (
                result.get("profile")
                for result in (results.get("direct_pdf"), results.get("local_text"))
                if result
                and not result.get("error")
                and isinstance(result.get("profile"), dict)
            ),
            None,
        )
        if candidate is not None and not args.skip_verification:
            try:
                response, elapsed = run_direct_pdf(
                    client,
                    path=pdf,
                    model=model,
                    profile_model=profile_model,
                    profile_instructions=profile_instructions,
                    pdf_detail=args.pdf_detail,
                    max_output_tokens=args.max_output_tokens,
                    provider=api_settings,
                    candidate_profile=candidate,
                )
                results["verified"] = result_from_response(
                    "verified", response, elapsed, model
                )
            except Exception as error:
                results["verified"] = error_result("verified", error)
            write_json(pdf_output_dir / "verified.json", results["verified"])

        run = {
            "pdf": str(pdf),
            "model": model,
            "profile_type": args.profile_type,
            "pdf_detail": args.pdf_detail,
            "call_order": list(approach_order),
            "pdf_pages": page_count,
            "pdf_bytes": pdf.stat().st_size,
            "extracted_characters": len(text),
            "local_extraction_warnings": extraction_warnings,
            "ocr": ocr_metadata,
            "pii_redaction": pii_redaction,
            "results": results,
            "comparison": comparison_for(results),
        }
        write_json(pdf_output_dir / "comparison.json", run)
        all_runs.append(run)
        print_result(run)

    write_json(output_dir / "all-results.json", all_runs)
    write_summary_csv(output_dir / "summary.csv", all_runs)
    print(f"\nResults written to: {output_dir}")
    return 1 if any_error else 0


if __name__ == "__main__":
    raise SystemExit(main())
