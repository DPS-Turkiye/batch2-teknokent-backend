from __future__ import annotations

import unittest
from collections.abc import Sequence

from matching_prototype.cache import MemoryEmbeddingCache
from matching_prototype.matcher import EmbeddingMatcher, cosine_similarity
from matching_prototype.models import EmbeddingConfiguration, UnusableProfileError
from matching_prototype.providers import EmbeddingProvider
from matching_prototype.text_builder import (
    build_mentee_matching_profile,
    build_mentor_matching_profile,
)


class CountingProvider(EmbeddingProvider):
    def __init__(self, vectors_by_text=None):
        self.calls = []
        self.vectors_by_text = vectors_by_text or {}
        self._configuration = EmbeddingConfiguration("test", "test-model", 2)

    @property
    def configuration(self):
        return self._configuration

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        self.calls.append(list(texts))
        return [list(self.vectors_by_text.get(text, [1.0, 0.0])) for text in texts]


def mentor(mentor_id: str, expertise: str, **extra):
    profile = {"professional_functions": [expertise], **extra}
    return {"mentor_id": mentor_id, "profile_version": "mentor-v1", "profile": profile}


def mentee(mentee_id: str = "mentee-1", need: str = "sales"):
    return {
        "mentee_id": mentee_id,
        "profile_version": "mentee-v1",
        "profile": {"support_needs": [{"claim": need, "page": 1, "quote": need, "information_type": "explicit"}]},
    }


class MatchingPrototypeTests(unittest.TestCase):
    def test_deterministic_matching_text_construction(self):
        record = mentor(
            "m1",
            "Pricing Strategy",
            industries=["SaaS", "Fintech"],
            technologies=["Python", "CRM", "Python"],
            employment_highlights=[
                {"claim": "Built sales teams.", "page": 2, "quote": "secret quote", "information_type": "explicit"}
            ],
        )
        first = build_mentor_matching_profile(record)
        record["profile"]["industries"] = ["Fintech", "SaaS"]
        second = build_mentor_matching_profile(record)
        self.assertEqual(first.matching_text, second.matching_text)
        self.assertIn("Expertise:\n- Pricing Strategy", first.matching_text)
        self.assertIn("Industries:\n- Fintech\n- SaaS", first.matching_text)
        self.assertIn("Professional experience:\n- Built sales teams.", first.matching_text)

    def test_missing_fields_are_omitted(self):
        built = build_mentee_matching_profile(
            {"mentee_id": "x", "profile": {"stage": None, "sectors": [], "technologies": ["Python"]}}
        )
        self.assertEqual(built.matching_text, "Technologies:\n- Python")
        self.assertNotIn("Startup stage", built.matching_text)

    def test_personal_and_metadata_fields_are_excluded(self):
        built = build_mentor_matching_profile(
            {
                "mentor_id": "m1",
                "profile": {
                    "full_name": "Private Name",
                    "email": "private@example.com",
                    "phone": "+90 555 000 0000",
                    "warnings": ["Private warning"],
                    "confidence": 0.99,
                    "availability": "Friday",
                    "capacity": 5,
                    "professional_functions": ["Sales"],
                    "employment_highlights": [
                        {"claim": "Private Name led sales; contact private@example.com.", "page": 1, "quote": "private evidence quote", "information_type": "explicit"}
                    ],
                },
            }
        )
        for excluded in ("Private Name", "private@example.com", "+90", "Private warning", "0.99", "Friday", "capacity", "private evidence quote"):
            self.assertNotIn(excluded, built.matching_text)
        self.assertIn("led sales", built.matching_text)

    def test_cosine_similarity(self):
        self.assertAlmostEqual(cosine_similarity([1, 0], [1, 0]), 1.0)
        self.assertAlmostEqual(cosine_similarity([1, 0], [0, 1]), 0.0)
        self.assertAlmostEqual(cosine_similarity([1, 0], [-1, 0]), -1.0)

    def test_ranking_order(self):
        mentors = [mentor("a", "alpha"), mentor("b", "beta"), mentor("c", "gamma")]
        mentor_texts = {m["mentor_id"]: build_mentor_matching_profile(m).matching_text for m in mentors}
        mentee_record = mentee(need="beta")
        mentee_text = build_mentee_matching_profile(mentee_record).matching_text
        provider = CountingProvider({
            mentor_texts["a"]: [0.0, 1.0],
            mentor_texts["b"]: [1.0, 0.0],
            mentor_texts["c"]: [0.5, 0.5],
            mentee_text: [1.0, 0.0],
        })
        result = EmbeddingMatcher(provider).match(mentee_record, mentors)
        self.assertEqual([item.mentor_id for item in result.recommendations], ["b", "c", "a"])
        self.assertEqual([item.ranking_position for item in result.recommendations], [1, 2, 3])

    def test_fewer_than_three_available_mentors(self):
        result = EmbeddingMatcher(CountingProvider()).match(mentee(), [mentor("m1", "sales"), mentor("m2", "sales")])
        self.assertEqual(len(result.recommendations), 2)

    def test_equal_similarity_scores_are_ordered_by_mentor_id(self):
        result = EmbeddingMatcher(CountingProvider()).match(
            mentee(), [mentor("z", "sales"), mentor("a", "sales"), mentor("m", "sales")]
        )
        self.assertEqual([item.mentor_id for item in result.recommendations], ["a", "m", "z"])

    def test_empty_or_unusable_profiles(self):
        with self.assertRaises(UnusableProfileError):
            build_mentee_matching_profile({"mentee_id": "empty", "profile": {"warnings": ["none"]}})
        result = EmbeddingMatcher(CountingProvider()).match(
            mentee(), [{"mentor_id": "empty", "profile": {"full_name": "Excluded"}}]
        )
        self.assertEqual(result.recommendations, ())
        self.assertEqual(result.skipped_mentor_ids, ("empty",))

    def test_cache_reuses_and_invalidates_by_text_model_config_and_versions(self):
        cache = MemoryEmbeddingCache()
        provider = CountingProvider()
        matcher = EmbeddingMatcher(provider, cache)
        mentors = [mentor("m1", "sales")]
        matcher.match(mentee(), mentors)
        matcher.match(mentee(), mentors)
        mentor_batch_calls = [call for call in provider.calls if call and call[0].startswith("Expertise:")]
        self.assertEqual(len(mentor_batch_calls), 1)

        mentors[0]["profile"]["professional_functions"] = ["pricing"]
        matcher.match(mentee(), mentors)
        mentor_batch_calls = [call for call in provider.calls if call and call[0].startswith("Expertise:")]
        self.assertEqual(len(mentor_batch_calls), 2)

        matcher_v2 = EmbeddingMatcher(provider, cache, matching_text_version="matching-text-v2")
        matcher_v2.match(mentee(), mentors)
        mentor_batch_calls = [call for call in provider.calls if call and call[0].startswith("Expertise:")]
        self.assertEqual(len(mentor_batch_calls), 2)

        mentors[0]["profile_version"] = "mentor-v2"
        matcher_v2.match(mentee(), mentors)
        mentor_batch_calls = [call for call in provider.calls if call and call[0].startswith("Expertise:")]
        self.assertEqual(len(mentor_batch_calls), 2)

        changed_provider = CountingProvider()
        changed_provider._configuration = EmbeddingConfiguration("test", "test-model-v2", 2)
        EmbeddingMatcher(changed_provider, cache).match(mentee(), mentors)
        changed_mentor_calls = [call for call in changed_provider.calls if call and call[0].startswith("Expertise:")]
        self.assertEqual(len(changed_mentor_calls), 1)

    def test_result_records_versions_and_shared_fields(self):
        result = EmbeddingMatcher(CountingProvider()).match(
            {"mentee_id": "x", "profile_version": "mentee-v9", "profile": {"technologies": ["Python"]}},
            [{"mentor_id": "m", "profile_version": "mentor-v7", "profile": {"technologies": ["Python"]}}],
        )
        recommendation = result.recommendations[0]
        self.assertEqual(recommendation.mentor_profile_version, "mentor-v7")
        self.assertEqual(recommendation.mentee_profile_version, "mentee-v9")
        self.assertEqual(recommendation.shared_fields, {"technologies": ["Python"]})
        self.assertEqual(recommendation.embedding_model, "test-model")


if __name__ == "__main__":
    unittest.main()
