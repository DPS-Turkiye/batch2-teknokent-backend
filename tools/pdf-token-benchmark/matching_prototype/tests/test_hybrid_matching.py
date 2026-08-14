from __future__ import annotations

import unittest

from matching_prototype.evaluate import evaluate_hybrid
from matching_prototype.hybrid_matcher import DIRECT_MATCH_WEIGHTS, HybridDirectMatcher
from matching_prototype.providers import DeterministicHashEmbeddingProvider
from matching_prototype.text_builder import build_mentee_matching_profile


class HybridDirectMatchingTests(unittest.TestCase):
    def setUp(self):
        self.matcher = HybridDirectMatcher(DeterministicHashEmbeddingProvider())
        self.mentors = [
            {"mentor_id": "software", "profile": {"professional_functions": ["Software Engineer"], "technologies": ["Python", "Kubernetes"], "years_of_experience": 8, "industries": ["Technology"]}},
            {"mentor_id": "finance", "profile": {"professional_functions": ["Financial Analyst"], "technologies": ["Excel", "Forecasting"], "years_of_experience": 8, "industries": ["Finance"]}},
        ]

    def test_individual_preferences_are_added_to_matching_text(self):
        built = build_mentee_matching_profile({
            "mentee_id": "intern",
            "profile": {"current_role": "Student", "technologies": ["Python"]},
            "matching_preferences": {"desired_role": "Software Engineer", "skills_to_develop": ["Kubernetes"], "career_goals": ["Ship backend services"]},
        })
        self.assertIn("Desired role:\n- Software Engineer", built.matching_text)
        self.assertIn("Skills to develop:\n- Kubernetes", built.matching_text)
        self.assertIn("Career goals:\n- Ship backend services", built.matching_text)

    def test_same_role_senior_is_ranked_first_with_components(self):
        result = self.matcher.match({
            "mentee_id": "intern",
            "profile": {"current_role": "Student", "technologies": ["Python"], "years_of_experience": 0},
            "matching_preferences": {"desired_role": "Software Engineer", "skills_to_develop": ["Kubernetes"], "preferred_industry": ["Technology"]},
        }, self.mentors)
        self.assertEqual(result["recommendations"][0]["mentor_id"], "software")
        self.assertEqual(set(result["recommendations"][0]["components"]), set(DIRECT_MATCH_WEIGHTS))

    def test_unusable_mentee_is_skipped_without_failing_batch(self):
        report = evaluate_hybrid(
            self.mentors,
            [{"mentee_id": "bad", "profile": {}}, {"mentee_id": "good", "profile": {"technologies": ["Python"]}}],
            None,
            self.matcher,
        )
        self.assertEqual([row["mentee_id"] for row in report["results"]], ["good"])
        self.assertEqual(report["skipped_mentees"][0]["mentee_id"], "bad")


if __name__ == "__main__":
    unittest.main()
