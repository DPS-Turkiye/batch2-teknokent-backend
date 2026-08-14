from __future__ import annotations

import unittest

from matching_prototype.cache import MemoryEmbeddingCache
from matching_prototype.pool import (
    BM25Retriever,
    assess_evidence,
    assess_mentor_depth,
    evaluate_eligibility,
    generate_pool,
    infer_problem_domains,
    select_diverse_pool,
    validate_problem,
)
from matching_prototype.providers import DeterministicHashEmbeddingProvider


def mentor(mentor_id: str, *, functions: list[str], industries=None, stages=None, evidence=None):
    return {
        "mentor_id": mentor_id,
        "profile_version": "mentor-v1",
        "profile": {
            "professional_functions": functions,
            "mentoring_areas": functions,
            "industries": industries or [],
            "startup_stage_experience": stages or [],
            "employment_highlights": evidence or [],
        },
    }


class MentorPoolTests(unittest.TestCase):
    def test_problem_domains_are_inferred_and_explicit_values_preserved(self):
        domains = infer_problem_domains(
            "We need help converting enterprise pilots into annual contracts and setting prices.",
            ["Go-to-market"],
        )
        self.assertIn("Go-to-market", domains)
        self.assertIn("Enterprise sales", domains)
        self.assertIn("Pricing and negotiation", domains)

    def test_problem_validation_requires_meaningful_text(self):
        with self.assertRaises(ValueError):
            validate_problem({"problem_statement": "Too short"})

    def test_bm25_prefers_the_document_with_query_terms(self):
        scores = BM25Retriever(
            {
                "sales": "enterprise sales pricing annual contract",
                "other": "manufacturing supply chain operations",
            }
        ).score("enterprise annual contract")
        self.assertGreater(scores["sales"], scores["other"])
        self.assertEqual(scores["sales"], 1.0)

    def test_eligibility_filters_capacity_conflict_and_language(self):
        problem = validate_problem(
            {
                "problem_statement": "We need enterprise sales support for annual contracts.",
                "organization": "Startup A",
                "languages": ["Turkish"],
            }
        )
        result = evaluate_eligibility(
            problem,
            {
                "capacity": 0,
                "languages": ["English"],
                "conflict_organizations": ["Startup A"],
            },
        )
        self.assertFalse(result.eligible)
        self.assertEqual(
            set(result.reasons),
            {"AT_CAPACITY", "LANGUAGE_MISMATCH", "CONFLICT_OF_INTEREST"},
        )

    def test_eligibility_requires_confirmed_constraints_and_experience(self):
        problem = validate_problem({
            "problem_statement": "We need urgent financial planning and forecasting guidance.",
            "languages": ["Turkish"],
            "meeting_formats": ["Online"],
            "minimum_mentor_years": 7,
        })
        result = evaluate_eligibility(
            problem,
            {"capacity": 1, "maximum_load": 2, "current_load": 2},
            {"years_of_experience": 4},
        )
        self.assertEqual(set(result.reasons), {
            "LANGUAGE_UNCONFIRMED", "MEETING_FORMAT_UNCONFIRMED",
            "MAXIMUM_LOAD_REACHED", "INSUFFICIENT_EXPERIENCE",
        })

    def test_evidence_quality_rewards_explicit_outcome_evidence(self):
        assessment = assess_evidence({
            "employment_highlights": [
                {"claim": "Led enterprise sales and converted pilots into annual contracts in 2025.", "information_type": "explicit"},
                {"claim": "Built pricing systems for enterprise customers.", "information_type": "explicit"},
            ]
        }, "enterprise sales pilot conversion pricing")
        self.assertGreater(assessment["quality_score"], 0.6)
        self.assertEqual(assessment["explicit_evidence"], 1.0)
        self.assertGreaterEqual(assessment["relevant_claim_count"], 2)

    def test_problem_signals_reject_generic_database_evidence(self):
        assessment = assess_evidence({
            "employment_highlights": [
                {"claim": "Completed a database internship at a technology company in 2024.", "information_type": "explicit"},
                {"claim": "Built TensorFlow machine-learning models for product recommendations.", "information_type": "explicit"},
            ]
        }, "Integrate AI into a health app MVP", signal_terms=["AI product strategy", "machine learning", "TensorFlow"])
        self.assertEqual(assessment["relevant_claim_count"], 1)
        self.assertIn("TensorFlow", assessment["selected_evidence"][0]["claim"])

    def test_mentor_depth_rewards_stated_senior_experience(self):
        senior = assess_mentor_depth({"years_of_experience": 8, "professional_functions": ["Data Scientist"]})
        intern = assess_mentor_depth({"professional_summary": "Final-year student and database intern"})
        self.assertGreater(senior["score"], intern["score"])

    def test_ai_pool_ranks_experienced_data_scientist_above_student_intern(self):
        mentors = [
            {
                "mentor_id": "intern",
                "profile_version": "v1",
                "profile": {
                    "full_name": "Student Intern",
                    "professional_summary": "Final-year student and database intern",
                    "professional_functions": ["Backend development", "AI/ML pipeline development"],
                    "technologies": ["Python", "OpenAI API"],
                    "employment_highlights": [
                        {"claim": "Completed a database internship at a technology company.", "information_type": "explicit"},
                        {"claim": "Integrated OpenAI into a database indexing tool.", "information_type": "explicit"},
                    ],
                },
            },
            {
                "mentor_id": "senior-ai",
                "profile_version": "v1",
                "profile": {
                    "full_name": "Senior Data Scientist",
                    "professional_summary": "Data Scientist with 8 years of experience.",
                    "professional_functions": ["Data Scientist"],
                    "technologies": ["TensorFlow", "Machine Learning", "Python"],
                    "years_of_experience": 8,
                    "employment_highlights": [
                        {"claim": "8 years of experience working as a Data Scientist.", "information_type": "explicit"},
                        {"claim": "Built TensorFlow machine-learning products for industry projects.", "information_type": "explicit"},
                    ],
                },
            },
        ]
        report = generate_pool(
            problem_input={
                "problem_statement": "Integrate AI into a calorie-tracking health app MVP.",
                "problem_domains": ["Technology architecture", "AI product strategy"],
                "sectors": ["Health Tech", "AI", "Machine Learning"],
                "startup_stage": "MVP",
                "technologies": ["Python"],
            },
            mentors=mentors,
            enrichment_by_mentor={"intern": {"capacity": 2}, "senior-ai": {"capacity": 2}},
            provider=DeterministicHashEmbeddingProvider(),
            cache=MemoryEmbeddingCache(),
            pool_size=2,
        )
        self.assertEqual(report["suggested_pool"][0]["mentor_id"], "senior-ai")
        self.assertGreater(
            report["suggested_pool"][0]["components"]["mentor_depth"],
            report["suggested_pool"][1]["components"]["mentor_depth"],
        )

    def test_diversity_selection_avoids_near_duplicate_pool(self):
        candidates = [
            {"mentor_id": "a", "final_score": 0.90, "diversity_signature": ["sales"], "recent_recommendation_count": 0},
            {"mentor_id": "b", "final_score": 0.89, "diversity_signature": ["sales"], "recent_recommendation_count": 0},
            {"mentor_id": "c", "final_score": 0.85, "diversity_signature": ["pricing"], "recent_recommendation_count": 0},
        ]
        selected = select_diverse_pool(candidates, 2)
        self.assertEqual([row["mentor_id"] for row in selected], ["a", "c"])
        self.assertEqual(selected[1]["diversity_penalty"], 0.0)

    def test_pool_combines_retrieval_components_and_excludes_ineligible_mentors(self):
        mentors = [
            mentor(
                "sales",
                functions=["Enterprise sales", "Pricing strategy"],
                industries=["B2B SaaS"],
                stages=["Early revenue"],
                evidence=[{"claim": "Converted enterprise pilots into annual contracts."}],
            ),
            mentor(
                "operations",
                functions=["Supply chain", "Operations"],
                industries=["Manufacturing"],
                stages=["Growth"],
                evidence=[{"claim": "Improved manufacturing throughput."}],
            ),
        ]
        report = generate_pool(
            problem_input={
                "mentee_id": "mentee-1",
                "problem_statement": "We cannot convert enterprise pilots into annual contracts.",
                "problem_domains": ["Enterprise sales", "Pricing and negotiation"],
                "sectors": ["B2B SaaS"],
                "startup_stage": "Early revenue",
            },
            mentors=mentors,
            enrichment_by_mentor={
                "sales": {"active": True, "capacity": 2},
                "operations": {"active": True, "capacity": 0},
            },
            provider=DeterministicHashEmbeddingProvider(),
            cache=MemoryEmbeddingCache(),
            pool_size=5,
        )
        self.assertEqual(report["eligible_count"], 1)
        self.assertEqual(report["suggested_pool"][0]["mentor_id"], "sales")
        self.assertGreater(report["suggested_pool"][0]["retrieval"]["lexical"], 0)
        self.assertIn("problem_expertise", report["suggested_pool"][0]["components"])
        self.assertIn("quality_score", report["suggested_pool"][0]["evidence_assessment"])
        self.assertEqual(report["reranker"]["type"], "evidence-aware")
        self.assertEqual(report["diversity"]["strategy"], "maximal-marginal-relevance")
        self.assertEqual(report["exclusions"][0]["reasons"], ["AT_CAPACITY"])


if __name__ == "__main__":
    unittest.main()
