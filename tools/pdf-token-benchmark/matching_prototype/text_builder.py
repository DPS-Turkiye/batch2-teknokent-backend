from __future__ import annotations

import re
from collections.abc import Iterable
from typing import Any

from .models import BuiltMatchingProfile, UnusableProfileError


_WHITESPACE = re.compile(r"\s+")
_EMAIL = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE)


def _clean(value: Any) -> str | None:
    if value is None or isinstance(value, bool):
        return None
    text = _WHITESPACE.sub(" ", str(value)).strip()
    return text or None


def _items(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, dict):
        claim = _clean(value.get("claim"))
        return [claim] if claim else []
    if isinstance(value, str):
        cleaned = _clean(value)
        return [cleaned] if cleaned else []
    if isinstance(value, Iterable):
        result: list[str] = []
        for item in value:
            result.extend(_items(item))
        return result
    cleaned = _clean(value)
    return [cleaned] if cleaned else []


def _without_personal_data(value: str, profile: dict[str, Any]) -> str | None:
    redacted = _EMAIL.sub("", value)
    for key in (
        "full_name",
        "name",
        "startup_name",
        "email",
        "phone",
        "phone_number",
        "contact_information",
    ):
        for personal_value in _items(profile.get(key)):
            redacted = re.sub(re.escape(personal_value), "", redacted, flags=re.IGNORECASE)
    redacted = re.sub(r"\s+([,.;:])", r"\1", redacted)
    return _clean(redacted.strip(" -–—,;:"))


def _values(profile: dict[str, Any], *keys: str) -> tuple[str, ...]:
    unique: dict[str, str] = {}
    for key in keys:
        for value in _items(profile.get(key)):
            safe_value = _without_personal_data(value, profile)
            if safe_value:
                unique.setdefault(safe_value.casefold(), safe_value)
    return tuple(sorted(unique.values(), key=lambda value: (value.casefold(), value)))


def _profile_version(record: dict[str, Any], profile: dict[str, Any]) -> str:
    metadata = record.get("metadata") or record.get("_metadata") or {}
    candidates = (
        record.get("profile_version"),
        record.get("schema_version"),
        profile.get("profile_version"),
        profile.get("schema_version"),
        metadata.get("profile_version") if isinstance(metadata, dict) else None,
    )
    return next((_clean(value) for value in candidates if _clean(value)), "unversioned")


def _unwrap(record: dict[str, Any], id_keys: tuple[str, ...]) -> tuple[str, str, dict[str, Any]]:
    profile = record.get("profile", record)
    if not isinstance(profile, dict):
        raise UnusableProfileError("profile must be a JSON object")
    profile_id = next((_clean(record.get(key)) for key in id_keys if _clean(record.get(key))), None)
    if not profile_id:
        raise ValueError(f"profile record requires one of: {', '.join(id_keys)}")
    return profile_id, _profile_version(record, profile), profile


def _render(profile_id: str, profile_version: str, fields: dict[str, tuple[str, ...]]) -> BuiltMatchingProfile:
    sections = [
        f"{heading}:\n" + "\n".join(f"- {value}" for value in values)
        for heading, values in fields.items()
        if values
    ]
    if not sections:
        raise UnusableProfileError(f"profile {profile_id!r} has no matching-relevant fields")
    return BuiltMatchingProfile(profile_id, profile_version, "\n\n".join(sections), fields)


def build_mentor_matching_profile(record: dict[str, Any]) -> BuiltMatchingProfile:
    """Adapt the current MentorProfile JSON without changing its parser schema."""
    profile_id, version, profile = _unwrap(record, ("mentor_id", "profile_id", "id"))
    fields = {
        "Expertise": _values(profile, "expertise", "professional_functions"),
        "Industries": _values(profile, "industries"),
        "Startup-stage experience": _values(
            profile, "startup_stage_experience", "startup_stages"
        ),
        "Technologies": _values(profile, "technologies"),
        "Mentoring areas": _values(profile, "mentoring_areas"),
        "Professional experience": _values(
            profile, "professional_experience", "employment_highlights"
        ),
        "Languages": _values(profile, "languages"),
        "Experience years": _values(profile, "years_of_experience"),
    }
    return _render(profile_id, version, fields)


def build_mentee_matching_profile(record: dict[str, Any]) -> BuiltMatchingProfile:
    """Adapt the current MenteeProfile JSON without embedding identity or metadata."""
    profile_id, version, profile = _unwrap(record, ("mentee_id", "profile_id", "id"))
    preferences = record.get("matching_preferences") or {}
    if not isinstance(preferences, dict):
        preferences = {}
    fields = {
        "Desired role": tuple(dict.fromkeys(
            (*_values(preferences, "desired_role"), *_values(profile, "desired_role"))
        )),
        "Current role": _values(profile, "current_role", "professional_functions"),
        "Experience level": _values(profile, "experience_level"),
        "Experience years": _values(profile, "years_of_experience"),
        "Sector": _values(profile, "sector", "sectors"),
        "Industries": tuple(dict.fromkeys(
            (*_values(preferences, "preferred_industry"), *_values(profile, "industries"))
        )),
        "Startup stage": _values(profile, "stage"),
        "Needs": _values(profile, "needs"),
        "Bottlenecks": _values(profile, "current_bottlenecks", "bottlenecks"),
        "Goals": _values(profile, "goals"),
        "Technologies": _values(profile, "technologies"),
        "Projects": _values(profile, "projects", "achievement_highlights"),
        "Skills to develop": tuple(dict.fromkeys(
            (*_values(preferences, "skills_to_develop"), *_values(profile, "skills_to_develop"))
        )),
        "Career goals": tuple(dict.fromkeys(
            (*_values(preferences, "career_goals"), *_values(profile, "career_goals"))
        )),
        "Desired support": tuple(dict.fromkeys(
            (*_values(preferences, "support_needs"), *_values(profile, "desired_support", "support_needs"))
        )),
        "Languages": tuple(dict.fromkeys(
            (*_values(preferences, "preferred_language"), *_values(profile, "languages"))
        )),
        "Meeting format": _values(preferences, "meeting_format"),
    }
    return _render(profile_id, version, fields)


def deterministic_shared_fields(
    mentor: BuiltMatchingProfile, mentee: BuiltMatchingProfile
) -> dict[str, list[str]]:
    """Return exact normalized intersections only; semantic overlap stays in cosine similarity."""

    def intersection(left: Iterable[str], right: Iterable[str]) -> list[str]:
        right_keys = {value.casefold() for value in right}
        return sorted(
            {value for value in left if value.casefold() in right_keys},
            key=lambda value: (value.casefold(), value),
        )

    mentor_expertise = (
        mentor.fields.get("Expertise", ()) + mentor.fields.get("Mentoring areas", ())
    )
    mentee_needs = (
        mentee.fields.get("Needs", ())
        + mentee.fields.get("Bottlenecks", ())
        + mentee.fields.get("Goals", ())
        + mentee.fields.get("Desired support", ())
        + mentee.fields.get("Skills to develop", ())
        + mentee.fields.get("Career goals", ())
        + mentee.fields.get("Desired role", ())
    )
    candidates = {
        "industry_sector": intersection(
            mentor.fields.get("Industries", ()),
            mentee.fields.get("Sector", ()) + mentee.fields.get("Industries", ()),
        ),
        "startup_stage": intersection(
            mentor.fields.get("Startup-stage experience", ()),
            mentee.fields.get("Startup stage", ()),
        ),
        "technologies": intersection(
            mentor.fields.get("Technologies", ()),
            mentee.fields.get("Technologies", ()),
        ),
        "expertise_support": intersection(mentor_expertise, mentee_needs),
        "languages": intersection(
            mentor.fields.get("Languages", ()), mentee.fields.get("Languages", ())
        ),
    }
    return {key: values for key, values in candidates.items() if values}
