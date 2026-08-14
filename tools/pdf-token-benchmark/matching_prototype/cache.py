from __future__ import annotations

import hashlib
import json
import os
import tempfile
from pathlib import Path
from typing import Any, Protocol

from .models import BuiltMatchingProfile, EmbeddingConfiguration


def embedding_fingerprint(
    profile: BuiltMatchingProfile,
    configuration: EmbeddingConfiguration,
) -> str:
    payload = {
        "matching_text": profile.matching_text,
        "embedding": configuration.to_dict(),
    }
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


class EmbeddingCache(Protocol):
    def get(self, mentor_id: str, fingerprint: str) -> list[float] | None: ...

    def put(
        self,
        mentor_id: str,
        fingerprint: str,
        vector: list[float],
        metadata: dict[str, Any],
    ) -> None: ...


class MemoryEmbeddingCache:
    def __init__(self) -> None:
        self.records: dict[str, dict[str, Any]] = {}

    def get(self, mentor_id: str, fingerprint: str) -> list[float] | None:
        record = self.records.get(mentor_id)
        if not record or record["fingerprint"] != fingerprint:
            return None
        return list(record["vector"])

    def put(
        self,
        mentor_id: str,
        fingerprint: str,
        vector: list[float],
        metadata: dict[str, Any],
    ) -> None:
        self.records[mentor_id] = {
            "fingerprint": fingerprint,
            "vector": list(vector),
            "metadata": metadata,
        }


class FileEmbeddingCache(MemoryEmbeddingCache):
    def __init__(self, path: Path) -> None:
        super().__init__()
        self.path = path.expanduser().resolve()
        if self.path.is_file():
            payload = json.loads(self.path.read_text(encoding="utf-8"))
            if payload.get("cache_version") == 1 and isinstance(payload.get("records"), dict):
                self.records = payload["records"]

    def put(
        self,
        mentor_id: str,
        fingerprint: str,
        vector: list[float],
        metadata: dict[str, Any],
    ) -> None:
        super().put(mentor_id, fingerprint, vector, metadata)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"cache_version": 1, "records": self.records}
        fd, temporary = tempfile.mkstemp(prefix=self.path.name, dir=self.path.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(payload, handle, ensure_ascii=False, indent=2)
                handle.write("\n")
            os.replace(temporary, self.path)
        except Exception:
            try:
                os.unlink(temporary)
            except FileNotFoundError:
                pass
            raise
