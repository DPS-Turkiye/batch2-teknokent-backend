from __future__ import annotations

import argparse
import json
import mimetypes
import os
import tempfile
import uuid
from datetime import UTC, datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from dotenv import load_dotenv

from ai_provider import create_client, provider_settings
from model_catalog import available_models

from .cache import FileEmbeddingCache
from .evaluate import BACKEND_DIR, read_json
from .pool import (
    DEFAULT_WEIGHTS,
    PROBLEM_TAXONOMY,
    generate_pool,
    infer_problem_domains,
    taxonomy_terms,
)
from .profile_library import ProfileLibraryError, read_profile_library
from .providers import (
    DeterministicHashEmbeddingProvider,
    OllamaEmbeddingProvider,
    OpenAIEmbeddingProvider,
    OpenRouterEmbeddingProvider,
)
from .rerankers import EvidenceAwareReranker, HttpCrossEncoderReranker, LLMReranker


PACKAGE_DIR = Path(__file__).resolve().parent
STATIC_DIR = PACKAGE_DIR / "pool_static"
SAMPLES_DIR = PACKAGE_DIR / "samples"
RESULTS_DIR = PACKAGE_DIR.parent / "results" / "pool"
RUNS_DIR = RESULTS_DIR / "runs"
CACHE_PATH = PACKAGE_DIR.parent / "results" / "matching" / "mentor_embeddings.json"
MAX_REQUEST_BYTES = 10 * 1024 * 1024
MAX_MENTORS = 500


class RequestError(Exception):
    def __init__(self, status: HTTPStatus, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


def _atomic_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=path.name, dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.replace(temporary, path)
    except Exception:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def _provider(payload: dict[str, Any]):
    provider_name = str(payload.get("provider") or "deterministic")
    model = str(payload.get("model") or "").strip() or None
    if model and (len(model) > 160 or any(character.isspace() for character in model)):
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Invalid embedding model.")
    if provider_name == "deterministic":
        return DeterministicHashEmbeddingProvider()
    if provider_name == "openai":
        return OpenAIEmbeddingProvider(model=model)
    if provider_name == "openrouter":
        return OpenRouterEmbeddingProvider(model=model)
    if provider_name == "ollama":
        return OllamaEmbeddingProvider(model=model or "bge-m3")
    raise RequestError(
        HTTPStatus.UNPROCESSABLE_ENTITY,
        "Embedding provider must be 'deterministic', 'ollama', 'openai', or 'openrouter'.",
    )


def _llm_reranker() -> LLMReranker:
    environment = dict(os.environ)
    reranker_provider = environment.get(
        "LLM_RERANKER_PROVIDER", environment.get("AI_PROVIDER", "openai")
    ).strip().lower()
    environment["AI_PROVIDER"] = reranker_provider
    settings = provider_settings(environment)
    model = environment.get("LLM_RERANKER_MODEL", "").strip() or settings.parsing_model
    try:
        candidate_limit = int(environment.get("LLM_RERANKER_CANDIDATE_LIMIT", "12"))
    except ValueError as error:
        raise ValueError("LLM_RERANKER_CANDIDATE_LIMIT must be an integer.") from error
    return LLMReranker(
        create_client(settings),
        model=model,
        provider=settings.provider,
        candidate_limit=candidate_limit,
    )


def aggregate_feedback_metrics(runs_dir: Path = RUNS_DIR) -> dict[str, Any]:
    labels = {key: 0 for key in ("strong", "possible", "weak", "inappropriate", "unlabelled")}
    score_bands = {
        "high": {"labelled": 0, "approved": 0},
        "medium": {"labelled": 0, "approved": 0},
        "low": {"labelled": 0, "approved": 0},
    }
    decisions_total = approved_total = 0
    if runs_dir.is_dir():
        for review_path in runs_dir.glob("*/review.json"):
            pool_path = review_path.with_name("pool.json")
            try:
                review = json.loads(review_path.read_text(encoding="utf-8"))
                pool = json.loads(pool_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            scores = {row["mentor_id"]: float(row.get("final_score", 0)) for row in pool.get("candidates", [])}
            for decision in review.get("decisions", []):
                label = str(decision.get("label") or "unlabelled")
                if label not in labels:
                    continue
                labels[label] += 1
                if label == "unlabelled":
                    continue
                decisions_total += 1
                approved = bool(decision.get("approved"))
                approved_total += approved
                score = scores.get(decision.get("mentor_id"), 0.0)
                band = "high" if score >= 0.7 else "medium" if score >= 0.45 else "low"
                score_bands[band]["labelled"] += 1
                score_bands[band]["approved"] += approved
    for band in score_bands.values():
        band["approval_rate"] = round(band["approved"] / band["labelled"], 4) if band["labelled"] else None
    return {
        "labelled_decisions": decisions_total,
        "approved_decisions": approved_total,
        "approval_rate": round(approved_total / decisions_total, 4) if decisions_total else None,
        "labels": labels,
        "score_calibration": score_bands,
    }


def sample_enrichment(mentors: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    enrichment: dict[str, dict[str, Any]] = {}
    for index, mentor in enumerate(mentors):
        mentor_id = str(mentor.get("mentor_id") or mentor.get("id") or f"mentor-{index}")
        profile = mentor.get("profile", mentor)
        taxonomy_evidence = json.dumps(
            {
                key: profile.get(key)
                for key in (
                    "professional_functions",
                    "industries",
                    "technologies",
                    "mentoring_areas",
                    "employment_highlights",
                    "achievement_highlights",
                )
            },
            ensure_ascii=False,
        )
        preferred_domains = infer_problem_domains(
            taxonomy_evidence,
            profile.get("mentoring_areas", []),
        )
        enrichment[mentor_id] = {
            "active": True,
            "capacity": 2 if index < 3 else 1,
            "languages": ["English", "Turkish"],
            "meeting_formats": ["Online"],
            "preferred_problem_domains": preferred_domains,
            "excluded_problem_domains": [],
            "conflict_organizations": [],
            "current_load": 0,
            "maximum_load": 3,
            "recent_recommendation_count": 0,
        }
    return enrichment


def bootstrap_payload() -> dict[str, Any]:
    library = read_profile_library()
    parsed_mentors = library["mentors"]
    mentors = parsed_mentors or read_json(SAMPLES_DIR / "mentors.json")
    source = "parsed-profile-library" if parsed_mentors else "sample-library"
    settings = provider_settings()
    models, catalog_error = available_models(settings, "embedding")
    return {
        "mentors": mentors,
        "mentor_count": len(mentors),
        "mentor_source": source,
        "enrichment": sample_enrichment(mentors),
        "taxonomy": taxonomy_terms(),
        "taxonomy_aliases": {
            domain: list(aliases) for domain, aliases in PROBLEM_TAXONOMY.items()
        },
        "weights": DEFAULT_WEIGHTS,
        "sample_problem": {
            "mentee_id": "mentee-enterprise-pilots",
            "problem_statement": "We conduct successful enterprise pilots but cannot convert them into annual contracts.",
            "desired_outcomes": ["Improve pilot-to-contract conversion"],
            "problem_domains": ["Enterprise sales", "Pricing and negotiation"],
            "sectors": ["B2B SaaS", "Enterprise software"],
            "startup_stage": "Early revenue",
            "technologies": ["CRM"],
            "languages": ["English"],
            "meeting_formats": ["Online"],
            "organization": "Example Startup",
        },
        "provider": settings.provider,
        "embedding_model": settings.embedding_model,
        "configured": bool(settings.api_key),
        "key_environment_variable": settings.key_environment_variable,
        "models": models,
        "catalog_error": catalog_error,
        "feedback_metrics": aggregate_feedback_metrics(),
    }


def run_pool(payload: dict[str, Any]) -> dict[str, Any]:
    mentors = payload.get("mentors")
    if not isinstance(mentors, list) or not mentors or not all(isinstance(record, dict) for record in mentors):
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Mentors must be a non-empty JSON array.")
    if len(mentors) > MAX_MENTORS:
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, f"At most {MAX_MENTORS} mentors can be processed.")
    problem = payload.get("problem")
    if not isinstance(problem, dict):
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Problem must be a JSON object.")
    enrichment = payload.get("enrichment") or {}
    if not isinstance(enrichment, dict) or not all(isinstance(value, dict) for value in enrichment.values()):
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Enrichment must map mentor IDs to objects.")
    try:
        pool_size = int(payload.get("pool_size", 5))
    except (TypeError, ValueError) as error:
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Pool size must be an integer.") from error
    reranker_choice = str(payload.get("reranker") or "evidence")
    if reranker_choice not in {"evidence", "cross_encoder", "llm", "off"}:
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Invalid reranker choice.")
    reranker = None
    reranker_note = None
    if reranker_choice == "evidence":
        reranker = EvidenceAwareReranker()
    elif reranker_choice == "cross_encoder":
        endpoint = os.getenv("RERANKER_URL", "").strip()
        if endpoint:
            reranker = HttpCrossEncoderReranker(
                endpoint, model=os.getenv("RERANKER_MODEL", "bge-reranker-v2-m3")
            )
        else:
            reranker = EvidenceAwareReranker()
            reranker_note = "RERANKER_URL is not configured; used evidence-aware fallback."
    elif reranker_choice == "llm":
        try:
            reranker = _llm_reranker()
        except (ValueError, TypeError) as error:
            reranker = EvidenceAwareReranker()
            reranker_note = f"LLM reranker is not configured; used evidence-aware fallback. {error}"
    report = generate_pool(
        problem_input=problem,
        mentors=mentors,
        enrichment_by_mentor=enrichment,
        provider=_provider(payload),
        cache=FileEmbeddingCache(CACHE_PATH),
        pool_size=pool_size,
        weights=payload.get("weights"),
        reranker=reranker,
        enable_reranking=reranker_choice != "off",
    )
    report["reranker"]["requested"] = reranker_choice
    if reranker_note:
        report["reranker"]["fallback_reason"] = reranker_note
    report["feedback_metrics"] = aggregate_feedback_metrics()
    run_id = uuid.uuid4().hex
    created_at = datetime.now(UTC).isoformat()
    saved = {
        **report,
        "run_id": run_id,
        "created_at": created_at,
        "review_status": "pending",
    }
    _atomic_json(RUNS_DIR / run_id / "pool.json", saved)
    return {
        **saved,
        "download_url": f"/api/runs/{run_id}/pool.json",
        "review_url": f"/api/runs/{run_id}/review",
    }


def save_review(run_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    run_path = RUNS_DIR / run_id / "pool.json"
    if not run_path.is_file():
        raise RequestError(HTTPStatus.NOT_FOUND, "Pool run not found.")
    run = json.loads(run_path.read_text(encoding="utf-8"))
    allowed_ids = {candidate["mentor_id"] for candidate in run.get("candidates", [])}
    decisions = payload.get("decisions")
    if not isinstance(decisions, list) or not decisions:
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "At least one coordinator decision is required.")
    allowed_labels = {"strong", "possible", "weak", "inappropriate", "unlabelled"}
    normalized: list[dict[str, Any]] = []
    for decision in decisions:
        if not isinstance(decision, dict) or decision.get("mentor_id") not in allowed_ids:
            raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Review contains an unknown mentor ID.")
        label = str(decision.get("label") or "unlabelled")
        if label not in allowed_labels:
            raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Review contains an invalid label.")
        normalized.append(
            {
                "mentor_id": decision["mentor_id"],
                "approved": bool(decision.get("approved", label in {"strong", "possible"})),
                "label": label,
                "reason": str(decision.get("reason") or "")[:500],
                "notes": str(decision.get("notes") or "")[:1000],
                "display_position": int(decision.get("display_position") or 0),
            }
        )
    review = {
        "run_id": run_id,
        "coordinator": str(payload.get("coordinator") or "TeamTNT")[:160],
        "saved_at": datetime.now(UTC).isoformat(),
        "decisions": sorted(normalized, key=lambda value: (value["display_position"], value["mentor_id"])),
    }
    _atomic_json(RUNS_DIR / run_id / "review.json", review)
    run["review_status"] = "completed"
    _atomic_json(run_path, run)
    review["feedback_metrics"] = aggregate_feedback_metrics()
    return review


def _valid_run_id(value: str) -> bool:
    return len(value) == 32 and all(character in "0123456789abcdef" for character in value)


class PoolHandler(BaseHTTPRequestHandler):
    server_version = "MentorPoolLab/1.0"

    def send_bytes(
        self,
        payload: bytes,
        *,
        content_type: str,
        status: HTTPStatus = HTTPStatus.OK,
        disposition: str | None = None,
    ) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        if disposition:
            self.send_header("Content-Disposition", disposition)
        self.end_headers()
        self.wfile.write(payload)

    def send_json(self, payload: Any, status: HTTPStatus = HTTPStatus.OK) -> None:
        self.send_bytes(
            json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            content_type="application/json; charset=utf-8",
            status=status,
        )

    def send_static(self, path: Path) -> None:
        if not path.is_file():
            raise RequestError(HTTPStatus.NOT_FOUND, "Asset not found.")
        content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        if content_type.startswith("text/") or content_type == "application/javascript":
            content_type += "; charset=utf-8"
        self.send_bytes(path.read_bytes(), content_type=content_type)

    def _json_request(self) -> dict[str, Any]:
        if self.headers.get_content_type() != "application/json":
            raise RequestError(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "Expected a JSON request.")
        content_length = int(self.headers.get("Content-Length", "0"))
        if content_length <= 0 or content_length > MAX_REQUEST_BYTES:
            raise RequestError(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "Request is empty or too large.")
        try:
            payload = json.loads(self.rfile.read(content_length))
        except (json.JSONDecodeError, UnicodeDecodeError) as error:
            raise RequestError(HTTPStatus.BAD_REQUEST, "The request body is invalid JSON.") from error
        if not isinstance(payload, dict):
            raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "The request must be a JSON object.")
        return payload

    def do_GET(self) -> None:  # noqa: N802
        try:
            path = urlparse(self.path).path
            if path == "/":
                self.send_static(STATIC_DIR / "index.html")
                return
            if path.startswith("/static/"):
                name = path.removeprefix("/static/")
                if "/" in name or name not in {"styles.css", "app.js"}:
                    raise RequestError(HTTPStatus.NOT_FOUND, "Asset not found.")
                self.send_static(STATIC_DIR / name)
                return
            if path == "/api/bootstrap":
                self.send_json(bootstrap_payload())
                return
            if path.startswith("/api/runs/"):
                parts = path.strip("/").split("/")
                if len(parts) != 4 or parts[0:2] != ["api", "runs"] or parts[3] not in {"pool.json", "review.json"}:
                    raise RequestError(HTTPStatus.NOT_FOUND, "Run artifact not found.")
                run_id = parts[2]
                if not _valid_run_id(run_id):
                    raise RequestError(HTTPStatus.NOT_FOUND, "Run artifact not found.")
                artifact = RUNS_DIR / run_id / parts[3]
                if not artifact.is_file():
                    raise RequestError(HTTPStatus.NOT_FOUND, "Run artifact not found.")
                self.send_bytes(
                    artifact.read_bytes(),
                    content_type="application/json; charset=utf-8",
                    disposition=f'attachment; filename="mentor-pool-{run_id[:8]}-{parts[3]}"',
                )
                return
            raise RequestError(HTTPStatus.NOT_FOUND, "Page not found.")
        except (RequestError, ProfileLibraryError, ValueError) as error:
            request_error = error if isinstance(error, RequestError) else RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, str(error))
            self.send_json({"detail": request_error.message}, request_error.status)

    def do_POST(self) -> None:  # noqa: N802
        try:
            path = urlparse(self.path).path
            payload = self._json_request()
            if path == "/api/pool":
                self.send_json(run_pool(payload), HTTPStatus.CREATED)
                return
            parts = path.strip("/").split("/")
            if len(parts) == 4 and parts[0:2] == ["api", "runs"] and parts[3] == "review" and _valid_run_id(parts[2]):
                self.send_json(save_review(parts[2], payload), HTTPStatus.CREATED)
                return
            raise RequestError(HTTPStatus.NOT_FOUND, "Page not found.")
        except (RequestError, ProfileLibraryError, ValueError) as error:
            request_error = error if isinstance(error, RequestError) else RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, str(error))
            self.send_json({"detail": request_error.message}, request_error.status)
        except Exception as error:
            self.log_error("Unexpected error: %s", error)
            self.send_json(
                {"detail": "Unexpected server error. Check the terminal for details."},
                HTTPStatus.INTERNAL_SERVER_ERROR,
            )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the Mentor Pool Lab web UI.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8767)
    return parser.parse_args()


def main() -> None:
    load_dotenv(BACKEND_DIR / ".env")
    args = parse_args()
    server = ThreadingHTTPServer((args.host, args.port), PoolHandler)
    print(f"Mentor Pool Lab running at http://{args.host}:{args.port}")
    print("Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping Mentor Pool Lab.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
