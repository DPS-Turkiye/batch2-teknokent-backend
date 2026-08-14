from __future__ import annotations

import hashlib
import json
import os
import tempfile
from pathlib import Path
from typing import Any


TOOL_DIR = Path(__file__).resolve().parent.parent
PROFILE_LIBRARY_PATH = TOOL_DIR / "results" / "matching" / "parsed-profile-library.json"
PROFILE_TYPES = {
    "mentor": "mentors",
    "mentee": "mentees",
    "individual_mentee": "mentees",
}
PROFILE_ID_KEYS = {
    "mentor": "mentor_id",
    "mentee": "mentee_id",
    "individual_mentee": "mentee_id",
}
PROFILE_KINDS = {
    "mentor": "mentor",
    "mentee": "startup",
    "individual_mentee": "individual",
}
PARSING_APPROACHES = {"local_text", "direct_pdf"}
MAX_IMPORT_RECORDS = 100


class ProfileLibraryError(ValueError):
    pass


def empty_profile_library() -> dict[str, Any]:
    return {"library_version": 1, "mentors": [], "mentees": []}


def read_profile_library(path: Path = PROFILE_LIBRARY_PATH) -> dict[str, Any]:
    if not path.is_file():
        return empty_profile_library()
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        raise ProfileLibraryError("The parsed-profile library could not be read.") from error
    if payload.get("library_version") != 1:
        raise ProfileLibraryError("The parsed-profile library version is unsupported.")
    for key in ("mentors", "mentees"):
        if not isinstance(payload.get(key), list) or not all(
            isinstance(record, dict) for record in payload[key]
        ):
            raise ProfileLibraryError("The parsed-profile library is malformed.")
    return payload


def _write_profile_library(path: Path, payload: dict[str, Any]) -> None:
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


def _canonical_digest(value: Any) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def import_parsed_profiles(
    payload: dict[str, Any], path: Path = PROFILE_LIBRARY_PATH
) -> dict[str, Any]:
    profile_type = payload.get("profile_type")
    approach = payload.get("approach")
    entries = payload.get("entries")
    if profile_type not in PROFILE_TYPES:
        raise ProfileLibraryError(
            "Profile type must be mentor, mentee, or individual_mentee."
        )
    if approach not in PARSING_APPROACHES:
        raise ProfileLibraryError("Parsing approach must be local_text or direct_pdf.")
    if not isinstance(entries, list) or not 1 <= len(entries) <= MAX_IMPORT_RECORDS:
        raise ProfileLibraryError(
            f"Import between 1 and {MAX_IMPORT_RECORDS} parsed profiles at once."
        )

    id_key = PROFILE_ID_KEYS[profile_type]
    id_prefix = "mentor" if profile_type == "mentor" else "mentee"
    collection_key = PROFILE_TYPES[profile_type]
    imported: list[dict[str, Any]] = []
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("profile"), dict):
            raise ProfileLibraryError("Every imported entry requires a profile object.")
        source_name = Path(str(entry.get("source_name") or "profile.pdf")).name[:180]
        profile = entry["profile"]
        source_digest = hashlib.sha256(source_name.encode("utf-8")).hexdigest()[:12]
        profile_digest = _canonical_digest(profile)
        imported.append(
            {
                id_key: f"{id_prefix}-{source_digest}",
                "profile_kind": PROFILE_KINDS[profile_type],
                "profile_version": f"parser-{profile_digest[:16]}",
                "profile": profile,
                "source": {
                    "filename": source_name,
                    "parsing_approach": approach,
                },
            }
        )

    library = read_profile_library(path)
    by_id = {
        str(record.get(id_key)): record
        for record in library[collection_key]
        if record.get(id_key)
    }
    for record in imported:
        by_id[record[id_key]] = record
    library[collection_key] = sorted(by_id.values(), key=lambda record: record[id_key])
    _write_profile_library(path, library)
    return {
        "imported": len(imported),
        "profile_type": profile_type,
        "approach": approach,
        "mentor_count": len(library["mentors"]),
        "mentee_count": len(library["mentees"]),
    }
