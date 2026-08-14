from __future__ import annotations

import argparse
import json
import mimetypes
import os
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from dotenv import load_dotenv

from ai_provider import provider_settings
from model_catalog import available_models

from .cache import FileEmbeddingCache
from .evaluate import BACKEND_DIR, evaluate_hybrid, read_json
from .hybrid_matcher import HybridDirectMatcher
from .models import UnusableProfileError
from .providers import (
    DeterministicHashEmbeddingProvider,
    OllamaEmbeddingProvider,
    OpenAIEmbeddingProvider,
    OpenRouterEmbeddingProvider,
)
from .profile_library import ProfileLibraryError, read_profile_library


PACKAGE_DIR = Path(__file__).resolve().parent
STATIC_DIR = PACKAGE_DIR / "static"
SAMPLES_DIR = PACKAGE_DIR / "samples"
RESULTS_DIR = PACKAGE_DIR.parent / "results" / "matching"
CACHE_PATH = RESULTS_DIR / "mentor_embeddings.json"
REPORTS_DIR = RESULTS_DIR / "web-reports"
MAX_REQUEST_BYTES = 10 * 1024 * 1024
MAX_MENTORS = 500
MAX_MENTEES = 100


class RequestError(Exception):
    def __init__(self, status: HTTPStatus, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


def _profile_records(
    payload: dict[str, Any], key: str, *, maximum: int
) -> list[dict[str, Any]]:
    records = payload.get(key)
    if not isinstance(records, list) or not records:
        raise RequestError(
            HTTPStatus.UNPROCESSABLE_ENTITY,
            f"{key.capitalize()} must be a non-empty JSON array.",
        )
    if len(records) > maximum:
        raise RequestError(
            HTTPStatus.UNPROCESSABLE_ENTITY,
            f"At most {maximum} {key} can be evaluated at once.",
        )
    if not all(isinstance(record, dict) for record in records):
        raise RequestError(
            HTTPStatus.UNPROCESSABLE_ENTITY,
            f"Every {key[:-1]} record must be a JSON object.",
        )
    return records


def _expected_matches(payload: dict[str, Any]) -> dict[str, list[str]] | None:
    expected = payload.get("expected")
    if expected is None:
        return None
    if not isinstance(expected, dict) or not all(
        isinstance(key, str)
        and isinstance(value, list)
        and all(isinstance(item, str) for item in value)
        for key, value in expected.items()
    ):
        raise RequestError(
            HTTPStatus.UNPROCESSABLE_ENTITY,
            "Expected matches must map mentee IDs to arrays of mentor IDs.",
        )
    return expected


def sample_payload() -> dict[str, Any]:
    return {
        "mentors": read_json(SAMPLES_DIR / "mentors.json"),
        "mentees": read_json(SAMPLES_DIR / "mentees.json"),
        "expected": read_json(SAMPLES_DIR / "expected_matches.json"),
    }


def run_matching(payload: dict[str, Any]) -> dict[str, Any]:
    mentors = _profile_records(payload, "mentors", maximum=MAX_MENTORS)
    mentees = _profile_records(payload, "mentees", maximum=MAX_MENTEES)
    expected = _expected_matches(payload)
    provider_name = payload.get("provider", "openai")
    model = str(payload.get("model", "")).strip() or None
    if model and (len(model) > 160 or any(char.isspace() for char in model)):
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Invalid embedding model.")
    if provider_name == "openai":
        provider = OpenAIEmbeddingProvider(model=model)
    elif provider_name == "openrouter":
        provider = OpenRouterEmbeddingProvider(model=model)
    elif provider_name == "ollama":
        provider = OllamaEmbeddingProvider(model=model or "bge-m3")
    elif provider_name == "deterministic":
        provider = DeterministicHashEmbeddingProvider()
    else:
        raise RequestError(
            HTTPStatus.UNPROCESSABLE_ENTITY,
            "Embedding provider must be 'ollama', 'openai', 'openrouter', or 'deterministic'.",
        )

    cache = FileEmbeddingCache(CACHE_PATH)
    result = evaluate_hybrid(
        mentors, mentees, expected, HybridDirectMatcher(provider, cache)
    )
    report_id = uuid.uuid4().hex
    report_dir = REPORTS_DIR / report_id
    report_dir.mkdir(parents=True, exist_ok=False)
    report = {
        **result,
        "report_id": report_id,
        "provider": provider.configuration.to_dict(),
        "mentor_count": len(mentors),
        "mentee_count": len(mentees),
        "cache_record_count": len(cache.records),
    }
    (report_dir / "evaluation.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    report["download_url"] = f"/api/reports/{report_id}/evaluation.json"
    return report


def _embedding_provider(payload: dict[str, Any]):
    provider_name = str(payload.get("provider") or "openai")
    model = str(payload.get("model") or "").strip() or None
    if model and (len(model) > 160 or any(char.isspace() for char in model)):
        raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Invalid embedding model.")
    if provider_name == "openai":
        return OpenAIEmbeddingProvider(model=model)
    if provider_name == "openrouter":
        return OpenRouterEmbeddingProvider(model=model)
    if provider_name == "ollama":
        return OllamaEmbeddingProvider(model=model or "bge-m3")
    if provider_name == "deterministic":
        return DeterministicHashEmbeddingProvider()
    raise RequestError(
        HTTPStatus.UNPROCESSABLE_ENTITY,
        "Embedding provider must be 'ollama', 'openai', 'openrouter', or 'deterministic'.",
    )


def run_embeddings(payload: dict[str, Any]) -> dict[str, Any]:
    texts = payload.get("texts")
    if (
        not isinstance(texts, list)
        or not texts
        or len(texts) > 500
        or not all(isinstance(text, str) and text.strip() and len(text) <= 20_000 for text in texts)
    ):
        raise RequestError(
            HTTPStatus.UNPROCESSABLE_ENTITY,
            "Texts must be a non-empty array of at most 500 non-empty strings (20,000 characters each).",
        )
    provider = _embedding_provider(payload)
    vectors = provider.embed(texts)
    return {
        "provider": provider.configuration.provider,
        "model": provider.configuration.model,
        "dimensions": len(vectors[0]) if vectors else 0,
        "embeddings": vectors,
    }


class MatchingHandler(BaseHTTPRequestHandler):
    server_version = "MentorMatchLab/1.0"

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

    def send_json(
        self, payload: dict[str, Any], status: HTTPStatus = HTTPStatus.OK
    ) -> None:
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
            if path == "/api/samples":
                self.send_json(sample_payload())
                return
            if path == "/api/config":
                settings = provider_settings()
                self.send_json(
                    {
                        "provider": "ollama",
                        "embedding_model": "bge-m3",
                        "configured": True,
                        "key_environment_variable": settings.key_environment_variable,
                        "models": [{"id": "bge-m3", "name": "BGE-M3 (local Ollama)"}],
                        "catalog_error": None,
                    }
                )
                return
            if path == "/api/models":
                requested = parse_qs(urlparse(self.path).query).get("provider", ["openrouter"])[0]
                if requested not in {"openai", "openrouter"}:
                    raise RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, "Invalid provider.")
                environment = {**os.environ, "AI_PROVIDER": requested}
                settings = provider_settings(environment)
                models, catalog_error = available_models(settings, "embedding")
                self.send_json(
                    {
                        "provider": requested,
                        "default_model": settings.embedding_model,
                        "models": models,
                        "catalog_error": catalog_error,
                    }
                )
                return
            if path == "/api/parsed-profiles":
                library = read_profile_library()
                self.send_json(
                    {
                        "mentors": library["mentors"],
                        "mentees": library["mentees"],
                        "mentor_count": len(library["mentors"]),
                        "mentee_count": len(library["mentees"]),
                    }
                )
                return
            if path.startswith("/api/reports/"):
                parts = path.strip("/").split("/")
                if len(parts) != 4 or parts[3] != "evaluation.json":
                    raise RequestError(HTTPStatus.NOT_FOUND, "Report not found.")
                report_id = parts[2]
                if len(report_id) != 32 or not all(char in "0123456789abcdef" for char in report_id):
                    raise RequestError(HTTPStatus.NOT_FOUND, "Report not found.")
                report_path = REPORTS_DIR / report_id / "evaluation.json"
                if not report_path.is_file():
                    raise RequestError(HTTPStatus.NOT_FOUND, "Report not found.")
                self.send_bytes(
                    report_path.read_bytes(),
                    content_type="application/json; charset=utf-8",
                    disposition=f'attachment; filename="mentor-matching-{report_id[:8]}.json"',
                )
                return
            raise RequestError(HTTPStatus.NOT_FOUND, "Page not found.")
        except (RequestError, ProfileLibraryError) as error:
            request_error = (
                error
                if isinstance(error, RequestError)
                else RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, str(error))
            )
            self.send_json({"detail": request_error.message}, request_error.status)

    def do_POST(self) -> None:  # noqa: N802
        try:
            path = urlparse(self.path).path
            if path not in {"/api/match", "/api/embeddings"}:
                raise RequestError(HTTPStatus.NOT_FOUND, "Page not found.")
            if self.headers.get_content_type() != "application/json":
                raise RequestError(
                    HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "Expected a JSON request."
                )
            content_length = int(self.headers.get("Content-Length", "0"))
            if content_length <= 0 or content_length > MAX_REQUEST_BYTES:
                raise RequestError(
                    HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                    "Request is empty or too large.",
                )
            try:
                payload = json.loads(self.rfile.read(content_length))
            except (json.JSONDecodeError, UnicodeDecodeError) as error:
                raise RequestError(
                    HTTPStatus.BAD_REQUEST, "The request body is invalid JSON."
                ) from error
            if not isinstance(payload, dict):
                raise RequestError(
                    HTTPStatus.UNPROCESSABLE_ENTITY, "The request must be a JSON object."
                )
            if path == "/api/embeddings":
                self.send_json(run_embeddings(payload))
            else:
                self.send_json(run_matching(payload), HTTPStatus.CREATED)
        except (RequestError, UnusableProfileError, ValueError) as error:
            if isinstance(error, RequestError):
                request_error = error
            else:
                request_error = RequestError(HTTPStatus.UNPROCESSABLE_ENTITY, str(error))
            self.send_json({"detail": request_error.message}, request_error.status)
        except Exception as error:
            self.log_error("Unexpected error: %s", error)
            self.send_json(
                {"detail": "Unexpected server error. Check the terminal for details."},
                HTTPStatus.INTERNAL_SERVER_ERROR,
            )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the Mentor Match Lab web UI.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8766)
    return parser.parse_args()


def main() -> None:
    load_dotenv(BACKEND_DIR / ".env")
    args = parse_args()
    server = ThreadingHTTPServer((args.host, args.port), MatchingHandler)
    print(f"Mentor Match Lab running at http://{args.host}:{args.port}")
    print("Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping Mentor Match Lab.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
