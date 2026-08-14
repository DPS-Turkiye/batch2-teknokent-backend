"""Deterministic, whitespace-tolerant verification for CV evidence snippets."""

from __future__ import annotations

import re
import unicodedata
from difflib import SequenceMatcher
from typing import Any


def normalize_evidence_text(value: str) -> str:
    value = unicodedata.normalize("NFKD", value).casefold()
    value = "".join(character for character in value if not unicodedata.combining(character))
    value = value.replace("ı", "i")
    return re.sub(r"[\W_]+", " ", value, flags=re.UNICODE).strip()


def verify_evidence_quote(quote: str, source: str, *, threshold: float = 0.88) -> dict[str, Any]:
    normalized_quote = normalize_evidence_text(quote)
    normalized_source = normalize_evidence_text(source)
    if not normalized_quote or not normalized_source:
        return {"supported": False, "similarity": 0.0, "method": "empty"}
    if normalized_quote in normalized_source:
        return {"supported": True, "similarity": 1.0, "method": "normalized-exact"}

    quote_tokens = normalized_quote.split()
    source_tokens = normalized_source.split()
    if len(quote_tokens) < 3:
        return {"supported": False, "similarity": 0.0, "method": "too-short"}

    best = 0.0
    minimum = max(3, int(len(quote_tokens) * 0.8))
    maximum = min(len(source_tokens), max(minimum, int(len(quote_tokens) * 1.2) + 1))
    for width in range(minimum, maximum + 1):
        for start in range(0, len(source_tokens) - width + 1):
            candidate = " ".join(source_tokens[start : start + width])
            best = max(best, SequenceMatcher(None, normalized_quote, candidate, autojunk=False).ratio())
            if best >= 0.98:
                break
        if best >= 0.98:
            break
    return {"supported": best >= threshold, "similarity": round(best, 4), "method": "fuzzy-window"}
