from __future__ import annotations

import math
import re
from datetime import UTC, datetime
from collections import Counter
from collections.abc import Iterable, Sequence
from dataclasses import asdict, dataclass
from typing import Any

from .matcher import EmbeddingMatcher
from .providers import EmbeddingProvider
from .rerankers import EvidenceAwareReranker, PoolReranker


DEFAULT_WEIGHTS = {
    "problem_expertise": 0.25,
    "supporting_evidence": 0.30,
    "mentor_depth": 0.25,
    "startup_stage": 0.05,
    "sector": 0.05,
    "availability": 0.05,
    "preferences": 0.05,
}

PROBLEM_TAXONOMY = {
    "Customer discovery": ("customer discovery", "user research", "problem validation"),
    "Product-market fit": ("product market fit", "pmf", "market validation"),
    "Go-to-market": ("go to market", "gtm", "market entry"),
    "Lead generation": ("lead generation", "prospecting", "sales leads"),
    "Enterprise sales": (
        "enterprise sales",
        "b2b sales",
        "sales cycle",
        "pilot conversion",
        "proof of concept conversion",
        "annual contracts",
    ),
    "Pricing and negotiation": (
        "pricing",
        "prices",
        "negotiation",
        "contract negotiation",
        "commercial terms",
    ),
    "Fundraising": ("fundraising", "venture capital", "investor relations", "seed round"),
    "Financial management": (
        "financial management",
        "cash flow",
        "financial planning",
        "unit economics",
        "financial analyst",
        "financial modeling",
        "forecasting",
        "accounting",
        "budgeting",
    ),
    "Growth marketing": ("growth marketing", "customer acquisition", "performance marketing"),
    "Operations": ("operations", "operational efficiency", "process improvement"),
    "Supply chain": ("supply chain", "procurement", "logistics"),
    "Hiring and leadership": ("hiring", "leadership", "team building", "people management"),
    "Internationalization": ("internationalization", "international expansion", "new geography"),
    "Regulatory compliance": ("regulatory", "compliance", "certification", "approval pathway"),
    "Technology architecture": (
        "technology architecture",
        "software architecture",
        "cloud architecture",
        "software engineer",
        "software engineering",
        "backend",
        "microservices",
        "devops",
        "kubernetes",
        "spring boot",
    ),
    "AI product strategy": (
        "ai product strategy",
        "artificial intelligence",
        "generative ai",
        "openai",
        "large language model",
        "llm",
        "machine learning product",
        "data scientist",
        "data science",
        "machine learning",
        "tensorflow",
        "transformer",
        "computer vision",
        "nlp",
    ),
    "Cybersecurity": ("cybersecurity", "information security", "security governance"),
}

_TOKEN = re.compile(r"[\w]+", re.UNICODE)


def _tokens(value: str) -> list[str]:
    return _TOKEN.findall(value.casefold().replace("-", " "))


def _clean(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip()


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


def _profile(record: dict[str, Any]) -> dict[str, Any]:
    profile = record.get("profile", record)
    return profile if isinstance(profile, dict) else {}


def _mentor_id(record: dict[str, Any], index: int = 0) -> str:
    return str(record.get("mentor_id") or record.get("profile_id") or record.get("id") or f"mentor-{index}")


def _field_values(profile: dict[str, Any], *keys: str) -> list[str]:
    values: list[str] = []
    seen: set[str] = set()
    for key in keys:
        for item in _items(profile.get(key)):
            normalized = item.casefold()
            if normalized not in seen:
                values.append(item)
                seen.add(normalized)
    return values


def taxonomy_terms() -> list[str]:
    return list(PROBLEM_TAXONOMY)


def infer_problem_domains(text: str, explicit: Sequence[str] = ()) -> list[str]:
    haystack = " ".join(_tokens(text))
    selected: list[str] = []
    for value in explicit:
        if value in PROBLEM_TAXONOMY and value not in selected:
            selected.append(value)
    for domain, aliases in PROBLEM_TAXONOMY.items():
        if domain in selected:
            continue
        candidates = (domain, *aliases)
        if any(" ".join(_tokens(candidate)) in haystack for candidate in candidates):
            selected.append(domain)
    return selected


def validate_problem(raw: dict[str, Any]) -> dict[str, Any]:
    statement = _clean(raw.get("problem_statement"))
    if len(statement) < 12:
        raise ValueError("Problem statement must contain at least 12 characters.")
    explicit_domains = [value for value in _items(raw.get("problem_domains")) if value in PROBLEM_TAXONOMY]
    domains = infer_problem_domains(
        " ".join([statement, *_items(raw.get("desired_outcomes")), *explicit_domains]),
        explicit_domains,
    )
    try:
        minimum_years = max(0.0, float(raw.get("minimum_mentor_years") or 0))
    except (TypeError, ValueError):
        minimum_years = 0.0
    urgency = _clean(raw.get("urgency")).casefold()
    if urgency not in {"", "low", "normal", "high", "urgent"}:
        urgency = "normal"
    return {
        "mentee_id": _clean(raw.get("mentee_id")) or "mentee-problem",
        "problem_statement": statement,
        "desired_outcomes": _items(raw.get("desired_outcomes")),
        "problem_domains": domains,
        "sectors": _items(raw.get("sectors") or raw.get("sector")),
        "startup_stage": _clean(raw.get("startup_stage") or raw.get("stage")),
        "technologies": _items(raw.get("technologies")),
        "languages": _items(raw.get("languages")),
        "meeting_formats": _items(raw.get("meeting_formats")),
        "organization": _clean(raw.get("organization")),
        "minimum_mentor_years": minimum_years,
        "urgency": urgency or "normal",
    }


def _term_overlap(left: Sequence[str], right: Sequence[str]) -> float:
    left_tokens = set(_tokens(" ".join(left)))
    right_tokens = set(_tokens(" ".join(right)))
    if not left_tokens or not right_tokens:
        return 0.0
    return len(left_tokens & right_tokens) / len(left_tokens)


def _exact_overlap(left: Sequence[str], right: Sequence[str]) -> float:
    left_values = {value.casefold() for value in left if value}
    right_values = {value.casefold() for value in right if value}
    if not left_values:
        return 0.0
    return len(left_values & right_values) / len(left_values)


class BM25Retriever:
    def __init__(self, documents: dict[str, str], *, k1: float = 1.5, b: float = 0.75):
        self.documents = {key: _tokens(text) for key, text in documents.items()}
        self.k1 = k1
        self.b = b
        self.average_length = (
            sum(len(tokens) for tokens in self.documents.values()) / len(self.documents)
            if self.documents
            else 0.0
        )
        self.document_frequency: Counter[str] = Counter()
        for tokens in self.documents.values():
            self.document_frequency.update(set(tokens))

    def score(self, query: str) -> dict[str, float]:
        query_tokens = _tokens(query)
        raw_scores: dict[str, float] = {}
        document_count = len(self.documents)
        for document_id, tokens in self.documents.items():
            frequencies = Counter(tokens)
            score = 0.0
            for token in query_tokens:
                frequency = frequencies[token]
                if not frequency:
                    continue
                document_frequency = self.document_frequency[token]
                inverse_document_frequency = math.log(
                    1 + (document_count - document_frequency + 0.5) / (document_frequency + 0.5)
                )
                length_normalization = frequency + self.k1 * (
                    1 - self.b + self.b * len(tokens) / max(self.average_length, 1.0)
                )
                score += inverse_document_frequency * frequency * (self.k1 + 1) / length_normalization
            raw_scores[document_id] = score
        maximum = max(raw_scores.values(), default=0.0)
        return {key: (value / maximum if maximum else 0.0) for key, value in raw_scores.items()}


@dataclass(frozen=True)
class EligibilityResult:
    eligible: bool
    reasons: tuple[str, ...]
    status: str


def evaluate_eligibility(
    problem: dict[str, Any], enrichment: dict[str, Any] | None,
    mentor_profile: dict[str, Any] | None = None,
) -> EligibilityResult:
    settings = enrichment or {}
    reasons: list[str] = []
    if settings.get("active") is False:
        reasons.append("INACTIVE")
    try:
        capacity = int(settings.get("capacity", 1))
    except (TypeError, ValueError):
        capacity = 0
    if capacity <= 0:
        reasons.append("AT_CAPACITY")
    if "maximum_load" in settings:
        try:
            current_load = int(settings.get("current_load", 0))
            maximum_load = int(settings["maximum_load"])
        except (TypeError, ValueError):
            current_load, maximum_load = 1, 0
        if maximum_load >= 0 and current_load >= maximum_load:
            reasons.append("MAXIMUM_LOAD_REACHED")
    conflicts = {value.casefold() for value in _items(settings.get("conflict_organizations"))}
    if problem.get("organization") and problem["organization"].casefold() in conflicts:
        reasons.append("CONFLICT_OF_INTEREST")
    mentor_languages = _items(settings.get("languages"))
    if problem.get("languages"):
        reasons.append(
            "LANGUAGE_MISMATCH" if mentor_languages and not _exact_overlap(problem["languages"], mentor_languages)
            else "LANGUAGE_UNCONFIRMED" if not mentor_languages else ""
        )
    mentor_formats = _items(settings.get("meeting_formats"))
    if problem.get("meeting_formats"):
        reasons.append(
            "MEETING_FORMAT_MISMATCH" if mentor_formats and not _exact_overlap(problem["meeting_formats"], mentor_formats)
            else "MEETING_FORMAT_UNCONFIRMED" if not mentor_formats else ""
        )
    excluded = {value.casefold() for value in _items(settings.get("excluded_problem_domains"))}
    if excluded & {value.casefold() for value in problem.get("problem_domains", [])}:
        reasons.append("EXCLUDED_PROBLEM_DOMAIN")
    minimum_years = float(problem.get("minimum_mentor_years") or 0)
    if minimum_years:
        try:
            mentor_years = float((mentor_profile or {}).get("years_of_experience") or 0)
        except (TypeError, ValueError):
            mentor_years = 0.0
        if mentor_years < minimum_years:
            reasons.append("INSUFFICIENT_EXPERIENCE")
    reasons = [reason for reason in reasons if reason]
    return EligibilityResult(not reasons, tuple(reasons), "eligible" if not reasons else "excluded")


def _mentor_document(record: dict[str, Any]) -> str:
    profile = _profile(record)
    values = _field_values(
        profile,
        "professional_functions",
        "expertise",
        "industries",
        "startup_stage_experience",
        "startup_stages",
        "technologies",
        "mentoring_areas",
        "professional_experience",
        "employment_highlights",
    )
    return "\n".join(values)


def _mentor_taxonomy_score(problem: dict[str, Any], record: dict[str, Any], enrichment: dict[str, Any]) -> tuple[float, list[str]]:
    profile = _profile(record)
    mentor_text = " ".join(
        _field_values(profile, "professional_functions", "expertise", "mentoring_areas", "employment_highlights")
        + _items(enrichment.get("preferred_problem_domains"))
    )
    mapped = infer_problem_domains(mentor_text, _items(enrichment.get("preferred_problem_domains")))
    problem_domains = problem.get("problem_domains", [])
    matched = sorted(set(problem_domains) & set(mapped))
    score = len(matched) / len(problem_domains) if problem_domains else 0.0
    return score, matched


def _evidence(profile: dict[str, Any], query: str) -> list[str]:
    highlights = _field_values(profile, "employment_highlights", "professional_experience")
    if not highlights:
        return []
    query_tokens = set(_tokens(query))
    ranked = sorted(
        highlights,
        key=lambda value: (-len(query_tokens & set(_tokens(value))), value.casefold()),
    )
    return ranked[:2]


def _evidence_records(profile: dict[str, Any]) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for key in ("employment_highlights", "achievement_highlights", "professional_experience"):
        value = profile.get(key)
        if isinstance(value, dict):
            value = [value]
        if not isinstance(value, Iterable) or isinstance(value, str):
            continue
        for item in value:
            if isinstance(item, dict):
                claim = _clean(item.get("claim"))
                if claim:
                    records.append({
                        "claim": claim,
                        "information_type": _clean(item.get("information_type")) or "unknown",
                        "page": item.get("page"),
                    })
            else:
                claim = _clean(item)
                if claim:
                    records.append({"claim": claim, "information_type": "unknown", "page": None})
    return records


_GENERIC_EVIDENCE_TOKENS = {
    "app", "application", "development", "experience", "integration", "mvp", "product",
    "project", "software", "system", "technology", "work", "worked",
}


def _problem_signal_terms(problem: dict[str, Any]) -> list[str]:
    terms = [*problem.get("technologies", [])]
    for domain in problem.get("problem_domains", []):
        terms.extend((domain, *PROBLEM_TAXONOMY.get(domain, ())))
    return terms


def assess_evidence(
    profile: dict[str, Any], query: str, *, signal_terms: Sequence[str] | None = None
) -> dict[str, Any]:
    query_tokens = set(_tokens(query))
    signal_tokens = set(_tokens(" ".join(signal_terms or ()))) - _GENERIC_EVIDENCE_TOKENS
    records = _evidence_records(profile)
    ranked = sorted(
        records,
        key=lambda item: (
            -len(signal_tokens & set(_tokens(item["claim"]))),
            -len(query_tokens & set(_tokens(item["claim"]))),
            item["claim"].casefold(),
        ),
    )
    minimum_overlap = 1 if len(query_tokens) <= 5 else 2
    relevant = [
        item for item in ranked
        if (
            len(signal_tokens & set(_tokens(item["claim"]))) >= 1
            if signal_tokens
            else len(query_tokens & set(_tokens(item["claim"]))) >= minimum_overlap
        )
    ]
    selected = relevant[:3]
    relevance = max(
        (
            min(1.0, 0.70 * len(signal_tokens & set(_tokens(item["claim"]))) / 2
                + 0.30 * len(query_tokens & set(_tokens(item["claim"]))) / 4)
            if signal_tokens
            else len(query_tokens & set(_tokens(item["claim"]))) / max(1, min(len(query_tokens), 8))
            for item in selected
        ),
        default=0.0,
    )
    depth = min(1.0, len(relevant) / 3)
    explicit = (
        sum(item["information_type"].casefold() == "explicit" for item in selected) / len(selected)
        if selected else 0.0
    )
    responsibility_terms = {"led", "built", "managed", "designed", "founded", "advised", "implemented", "owned"}
    outcome_terms = {"grew", "increased", "reduced", "improved", "launched", "converted", "delivered", "raised", "achieved"}
    selected_tokens = set(_tokens(" ".join(item["claim"] for item in selected)))
    responsibility = min(1.0, len(responsibility_terms & selected_tokens) / 2)
    outcomes = min(1.0, len(outcome_terms & selected_tokens) / 2)
    years = [
        int(year)
        for item in selected
        for year in re.findall(r"\b(?:19|20)\d{2}\b", item["claim"])
        if 1980 <= int(year) <= datetime.now(UTC).year
    ]
    recency = (
        max(0.0, 1.0 - (datetime.now(UTC).year - max(years)) / 10)
        if years
        else 0.5 if selected else 0.0
    )
    quality = (
        0.30 * relevance
        + 0.15 * depth
        + 0.15 * explicit
        + 0.15 * responsibility
        + 0.15 * outcomes
        + 0.10 * recency
    )
    return {
        "quality_score": round(quality, 6),
        "query_relevance": round(relevance, 6),
        "depth": round(depth, 6),
        "explicit_evidence": round(explicit, 6),
        "responsibility": round(responsibility, 6),
        "outcomes": round(outcomes, 6),
        "recency": round(recency, 6),
        "relevant_claim_count": len(relevant),
        "selected_evidence": selected,
    }


def assess_mentor_depth(profile: dict[str, Any]) -> dict[str, Any]:
    """Score whether the CV supports mentor-level professional depth, not just topic overlap."""
    raw_years = profile.get("years_of_experience")
    try:
        years = max(0.0, float(raw_years)) if raw_years is not None else None
    except (TypeError, ValueError):
        years = None
    role_text = " ".join(_field_values(profile, "professional_summary", "professional_functions")).casefold()
    if years is not None:
        experience_score = min(1.0, years / 8.0)
        basis = f"{years:g} years stated in CV"
    elif any(term in role_text for term in ("student", "intern", "entry level", "junior")):
        experience_score = 0.10
        basis = "student/intern profile; total professional years unconfirmed"
    elif any(term in role_text for term in ("senior", "lead", "director", "head", "founder", "manager")):
        experience_score = 0.75
        basis = "senior responsibility indicated; total professional years unconfirmed"
    else:
        experience_score = 0.35
        basis = "professional depth unconfirmed"
    return {
        "score": round(experience_score, 6),
        "years_of_experience": years,
        "basis": basis,
    }


def _candidate_similarity(left: dict[str, Any], right: dict[str, Any]) -> float:
    left_values = set(left.get("diversity_signature", []))
    right_values = set(right.get("diversity_signature", []))
    if not left_values or not right_values:
        return 0.0
    return len(left_values & right_values) / len(left_values | right_values)


def select_diverse_pool(
    candidates: Sequence[dict[str, Any]], pool_size: int, *, relevance_weight: float = 0.82
) -> list[dict[str, Any]]:
    remaining = [dict(candidate) for candidate in candidates]
    selected: list[dict[str, Any]] = []
    while remaining and len(selected) < pool_size:
        best: dict[str, Any] | None = None
        best_score = -1.0
        for candidate in remaining:
            redundancy = max((_candidate_similarity(candidate, other) for other in selected), default=0.0)
            exposure_penalty = min(1.0, float(candidate.get("recent_recommendation_count", 0)) / 10)
            selection_score = (
                relevance_weight * float(candidate["final_score"])
                - (1 - relevance_weight) * redundancy
                - 0.05 * exposure_penalty
            )
            if selection_score > best_score or (
                selection_score == best_score and best is not None and candidate["mentor_id"] < best["mentor_id"]
            ):
                best, best_score = candidate, selection_score
                candidate["diversity_penalty"] = round(redundancy, 6)
                candidate["selection_score"] = round(selection_score, 6)
        assert best is not None
        remaining.remove(best)
        selected.append(best)
    return selected


def _confidence(candidate: dict[str, Any]) -> tuple[float, list[str]]:
    retrieval = candidate["retrieval"]
    signals = [retrieval.get("taxonomy", 0), retrieval.get("lexical", 0), retrieval.get("semantic", 0)]
    agreement = max(0.0, 1.0 - (max(signals) - min(signals)))
    evidence = float(candidate["evidence_assessment"].get("quality_score", 0.0))
    completeness = 1.0 - min(1.0, len(candidate.get("uncertainties", [])) / 5)
    score = 0.4 * agreement + 0.4 * evidence + 0.2 * completeness
    caveats: list[str] = []
    if evidence < 0.4:
        caveats.append("Limited problem-specific profile evidence")
    if agreement < 0.5:
        caveats.append("Retrieval signals disagree")
    if completeness < 0.7:
        caveats.append("Profile has important missing fields")
    return round(score, 6), caveats


def _normalise_weights(raw: dict[str, Any] | None) -> dict[str, float]:
    weights: dict[str, float] = {}
    for key, default in DEFAULT_WEIGHTS.items():
        try:
            value = float((raw or {}).get(key, default))
        except (TypeError, ValueError):
            value = default
        weights[key] = max(0.0, value)
    total = sum(weights.values())
    if total <= 0:
        return dict(DEFAULT_WEIGHTS)
    return {key: value / total for key, value in weights.items()}


def generate_pool(
    *,
    problem_input: dict[str, Any],
    mentors: Sequence[dict[str, Any]],
    enrichment_by_mentor: dict[str, dict[str, Any]],
    provider: EmbeddingProvider,
    cache: Any,
    pool_size: int = 5,
    weights: dict[str, Any] | None = None,
    reranker: PoolReranker | None = None,
    enable_reranking: bool = True,
) -> dict[str, Any]:
    if not mentors:
        raise ValueError("At least one mentor profile is required.")
    if not 1 <= pool_size <= 20:
        raise ValueError("Pool size must be between 1 and 20.")
    problem = validate_problem(problem_input)
    normalized_weights = _normalise_weights(weights)
    mentee_record = {
        "mentee_id": problem["mentee_id"],
        "profile_version": "problem-profile-v1",
        "profile": {
            "stage": problem["startup_stage"],
            "sectors": problem["sectors"],
            "technologies": problem["technologies"],
            "current_bottlenecks": [problem["problem_statement"]],
            "goals": problem["desired_outcomes"],
            "support_needs": problem["problem_domains"],
        },
    }
    matcher = EmbeddingMatcher(provider, cache, top_k=len(mentors))
    semantic_result = matcher.match(mentee_record, mentors)
    semantic_scores = {
        recommendation.mentor_id: max(0.0, min(1.0, recommendation.cosine_similarity))
        for recommendation in semantic_result.recommendations
    }
    documents = {_mentor_id(record, index): _mentor_document(record) for index, record in enumerate(mentors)}
    query = " ".join(
        [
            problem["problem_statement"],
            *problem["desired_outcomes"],
            *problem["problem_domains"],
            *problem["sectors"],
            problem["startup_stage"],
            *problem["technologies"],
        ]
    )
    lexical_scores = BM25Retriever(documents).score(query)
    candidates: list[dict[str, Any]] = []
    exclusions: list[dict[str, Any]] = []
    for index, record in enumerate(mentors):
        mentor_id = _mentor_id(record, index)
        profile = _profile(record)
        enrichment = enrichment_by_mentor.get(mentor_id, {})
        eligibility = evaluate_eligibility(problem, enrichment, profile)
        taxonomy_score, matched_domains = _mentor_taxonomy_score(problem, record, enrichment)
        semantic_score = semantic_scores.get(mentor_id, 0.0)
        lexical_score = lexical_scores.get(mentor_id, 0.0)
        if not eligibility.eligible:
            exclusions.append(
                {
                    "mentor_id": mentor_id,
                    "reasons": list(eligibility.reasons),
                    "taxonomy_score": round(taxonomy_score, 6),
                    "lexical_score": round(lexical_score, 6),
                    "semantic_score": round(semantic_score, 6),
                }
            )
            continue
        expertise_score = 0.55 * taxonomy_score + 0.45 * semantic_score
        evidence_assessment = assess_evidence(
            profile,
            query,
            signal_terms=_problem_signal_terms(problem),
        )
        mentor_depth = assess_mentor_depth(profile)
        evidence_score = (
            0.65 * evidence_assessment["quality_score"]
            + 0.20 * lexical_score
            + 0.15 * semantic_score
        )
        mentor_stages = _field_values(profile, "startup_stage_experience", "startup_stages")
        stage_score = _exact_overlap([problem["startup_stage"]] if problem["startup_stage"] else [], mentor_stages)
        mentor_sectors = _field_values(profile, "industries")
        sector_score = _exact_overlap(problem["sectors"], mentor_sectors)
        preferred = _items(enrichment.get("preferred_problem_domains")) or _field_values(profile, "mentoring_areas")
        preference_score = _exact_overlap(problem["problem_domains"], preferred)
        try:
            available_capacity = max(0, int(enrichment.get("capacity", 1)))
            current_load = max(0, int(enrichment.get("current_load", 0)))
        except (TypeError, ValueError):
            available_capacity, current_load = 0, 1
        availability_score = min(1.0, available_capacity / 2) * (1 / (1 + 0.2 * current_load))
        components = {
            "problem_expertise": expertise_score,
            "supporting_evidence": evidence_score,
            "mentor_depth": mentor_depth["score"],
            "startup_stage": stage_score,
            "sector": sector_score,
            "availability": availability_score,
            "preferences": preference_score,
        }
        final_score = sum(components[key] * normalized_weights[key] for key in components)
        evidence = [item["claim"] for item in evidence_assessment["selected_evidence"]]
        reasons: list[str] = []
        if matched_domains:
            reasons.append(f"Relevant problem expertise: {', '.join(matched_domains)}.")
        if evidence:
            reasons.append(evidence[0])
        if stage_score:
            reasons.append(f"Supports the {problem['startup_stage']} startup stage.")
        if sector_score:
            reasons.append(f"Has experience in {', '.join(problem['sectors'])}.")
        mapped_domains = infer_problem_domains(
            _mentor_document(record), _items(enrichment.get("preferred_problem_domains"))
        )
        diversity_signature = sorted({
            value.casefold()
            for value in (
                *mapped_domains,
                *_field_values(profile, "professional_functions", "industries", "mentoring_areas"),
            )
        })
        candidate = {
                "mentor_id": mentor_id,
                "mentor_name": _clean(profile.get("full_name")) or mentor_id,
                "profile_version": str(record.get("profile_version") or "unversioned"),
                "eligible": True,
                "eligibility_status": eligibility.status,
                "retrieval": {
                    "taxonomy": round(taxonomy_score, 6),
                    "lexical": round(lexical_score, 6),
                    "semantic": round(semantic_score, 6),
                    "matched_domains": matched_domains,
                },
                "evidence_assessment": evidence_assessment,
                "mentor_depth_assessment": mentor_depth,
                "components": {key: round(value, 6) for key, value in components.items()},
                "final_score": round(final_score, 6),
                "pre_rerank_score": round(final_score, 6),
                "reasons": reasons,
                "evidence": evidence,
                "uncertainties": _items(profile.get("missing_fields"))[:3],
                "shared_fields": next(
                    (
                        recommendation.shared_fields
                        for recommendation in semantic_result.recommendations
                        if recommendation.mentor_id == mentor_id
                    ),
                    {},
                ),
                "recent_recommendation_count": max(0, int(enrichment.get("recent_recommendation_count", 0) or 0)),
                "diversity_signature": diversity_signature,
                "reranker_text": _mentor_document(record),
            }
        confidence, caveats = _confidence(candidate)
        candidate["confidence"] = confidence
        candidate["confidence_level"] = "high" if confidence >= 0.72 else "medium" if confidence >= 0.48 else "low"
        candidate["confidence_caveats"] = caveats
        candidates.append(candidate)
    candidates.sort(key=lambda value: (-value["final_score"], value["mentor_id"]))
    reranker_status: dict[str, Any] = {"enabled": enable_reranking, "fallback_reason": None}
    if enable_reranking and candidates:
        selected_reranker = reranker or EvidenceAwareReranker()
        reranker_details: dict[str, dict[str, Any]] = {}
        try:
            reranker_scores = selected_reranker.rerank(query, candidates)
            reranker_status.update(selected_reranker.configuration)
            reranker_details = selected_reranker.details
        except Exception as error:
            fallback = EvidenceAwareReranker()
            reranker_scores = fallback.rerank(query, candidates)
            reranker_status.update(fallback.configuration)
            reranker_status["fallback_reason"] = f"{type(error).__name__}: {error}"
        for candidate in candidates:
            score = max(0.0, min(1.0, float(reranker_scores.get(candidate["mentor_id"], 0.0))))
            candidate["retrieval"]["reranker"] = round(score, 6)
            candidate["final_score"] = round(0.75 * candidate["pre_rerank_score"] + 0.25 * score, 6)
            candidate["reranker_assessment"] = reranker_details.get(candidate["mentor_id"])
    else:
        reranker_status.update({"type": "disabled", "model": None, "cross_encoder": False})
        for candidate in candidates:
            candidate["retrieval"]["reranker"] = None
            candidate["reranker_assessment"] = None
    candidates.sort(key=lambda value: (-value["final_score"], value["mentor_id"]))
    for rank, candidate in enumerate(candidates, start=1):
        candidate["ranking_position"] = rank
        candidate["suggested"] = False
    suggested_pool = sorted(
        select_diverse_pool(candidates, pool_size),
        key=lambda value: (-value["final_score"], value["mentor_id"]),
    )
    suggested_ids = {candidate["mentor_id"] for candidate in suggested_pool}
    for candidate in candidates:
        candidate["suggested"] = candidate["mentor_id"] in suggested_ids
    for position, candidate in enumerate(suggested_pool, start=1):
        candidate["pool_position"] = position
        candidate["suggested"] = True
    run_confidence = (
        sum(candidate["confidence"] for candidate in suggested_pool) / len(suggested_pool)
        if suggested_pool else 0.0
    )
    return {
        "problem_profile": problem,
        "weights": normalized_weights,
        "provider": provider.configuration.to_dict(),
        "mentor_count": len(mentors),
        "eligible_count": len(candidates),
        "excluded_count": len(exclusions),
        "retrieved_count": len(candidates),
        "suggested_count": min(pool_size, len(candidates)),
        "candidates": candidates,
        "suggested_pool": suggested_pool,
        "run_confidence": round(run_confidence, 6),
        "run_confidence_level": "high" if run_confidence >= 0.72 else "medium" if run_confidence >= 0.48 else "low",
        "reranker": reranker_status,
        "diversity": {"strategy": "maximal-marginal-relevance", "relevance_weight": 0.82},
        "exclusions": sorted(exclusions, key=lambda value: value["mentor_id"]),
        "skipped_mentor_ids": list(semantic_result.skipped_mentor_ids),
    }
