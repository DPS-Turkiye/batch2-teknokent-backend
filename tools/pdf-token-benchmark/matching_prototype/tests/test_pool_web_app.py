from __future__ import annotations

import tempfile
import unittest
import os
from pathlib import Path
from unittest.mock import patch

from matching_prototype.pool_web_app import aggregate_feedback_metrics, bootstrap_payload, run_pool, sample_enrichment, save_review


class MentorPoolWebAppTests(unittest.TestCase):
    def test_feedback_metrics_aggregate_labels_and_score_bands(self):
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary) / "run-1"
            run_dir.mkdir(parents=True)
            (run_dir / "pool.json").write_text(
                '{"candidates":[{"mentor_id":"m1","final_score":0.8}]}', encoding="utf-8"
            )
            (run_dir / "review.json").write_text(
                '{"decisions":[{"mentor_id":"m1","label":"strong","approved":true}]}', encoding="utf-8"
            )
            metrics = aggregate_feedback_metrics(Path(temporary))
        self.assertEqual(metrics["labelled_decisions"], 1)
        self.assertEqual(metrics["approval_rate"], 1.0)
        self.assertEqual(metrics["score_calibration"]["high"]["approval_rate"], 1.0)
    def test_parsed_mentor_fields_are_mapped_to_controlled_problem_domains(self):
        enrichment = sample_enrichment(
            [
                {
                    "mentor_id": "software-mentor",
                    "profile": {
                        "professional_functions": ["Software Engineer"],
                        "technologies": ["Spring Boot", "Kubernetes"],
                    },
                },
                {
                    "mentor_id": "finance-mentor",
                    "profile": {
                        "professional_functions": ["Financial Analyst"],
                        "technologies": ["Financial Modeling", "Forecasting"],
                    },
                },
            ]
        )
        self.assertIn(
            "Technology architecture",
            enrichment["software-mentor"]["preferred_problem_domains"],
        )
        self.assertIn(
            "Financial management",
            enrichment["finance-mentor"]["preferred_problem_domains"],
        )

    def test_bootstrap_contains_taxonomy_problem_and_mentors(self):
        payload = bootstrap_payload()
        self.assertGreater(payload["mentor_count"], 0)
        self.assertIn("Enterprise sales", payload["taxonomy"])
        self.assertIn("annual contracts", payload["taxonomy_aliases"]["Enterprise sales"])
        self.assertIn("problem_statement", payload["sample_problem"])

    def test_offline_run_returns_exclusion_details_when_no_mentor_is_eligible(self):
        bootstrap = bootstrap_payload()
        enrichment = {
            mentor_id: {**settings, "capacity": 0}
            for mentor_id, settings in bootstrap["enrichment"].items()
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with (
                patch("matching_prototype.pool_web_app.CACHE_PATH", root / "cache.json"),
                patch("matching_prototype.pool_web_app.RUNS_DIR", root / "runs"),
            ):
                report = run_pool(
                    {
                        "provider": "deterministic",
                        "mentors": bootstrap["mentors"],
                        "enrichment": enrichment,
                        "problem": {
                            "problem_statement": "I need guidance with software architecture.",
                            "problem_domains": ["Technology architecture"],
                        },
                        "pool_size": 3,
                    }
                )
        self.assertEqual(report["eligible_count"], 0)
        self.assertEqual(report["suggested_count"], 0)
        self.assertEqual(report["suggested_pool"], [])
        self.assertTrue(report["exclusions"])
        self.assertTrue(all("AT_CAPACITY" in row["reasons"] for row in report["exclusions"]))

    def test_offline_run_and_review_are_persisted(self):
        bootstrap = bootstrap_payload()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with (
                patch("matching_prototype.pool_web_app.CACHE_PATH", root / "cache.json"),
                patch("matching_prototype.pool_web_app.RUNS_DIR", root / "runs"),
            ):
                report = run_pool(
                    {
                        "provider": "deterministic",
                        "mentors": bootstrap["mentors"],
                        "enrichment": bootstrap["enrichment"],
                        "problem": bootstrap["sample_problem"],
                        "pool_size": 3,
                    }
                )
                self.assertTrue((root / "runs" / report["run_id"] / "pool.json").is_file())
                candidate = report["suggested_pool"][0]
                review = save_review(
                    report["run_id"],
                    {
                        "coordinator": "Test Coordinator",
                        "decisions": [
                            {
                                "mentor_id": candidate["mentor_id"],
                                "label": "strong",
                                "approved": True,
                                "display_position": 1,
                            }
                        ],
                    },
                )
                self.assertEqual(review["decisions"][0]["label"], "strong")
                self.assertTrue((root / "runs" / report["run_id"] / "review.json").is_file())

    def test_unconfigured_llm_reranker_uses_evidence_fallback(self):
        bootstrap = bootstrap_payload()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            clean_environment = {
                key: value
                for key, value in os.environ.items()
                if key not in {"OPENAI_API_KEY", "OPENROUTER_API_KEY", "LLM_RERANKER_PROVIDER"}
            }
            clean_environment["AI_PROVIDER"] = "openai"
            with (
                patch.dict(os.environ, clean_environment, clear=True),
                patch("matching_prototype.pool_web_app.CACHE_PATH", root / "cache.json"),
                patch("matching_prototype.pool_web_app.RUNS_DIR", root / "runs"),
            ):
                report = run_pool(
                    {
                        "provider": "deterministic",
                        "mentors": bootstrap["mentors"],
                        "enrichment": bootstrap["enrichment"],
                        "problem": bootstrap["sample_problem"],
                        "pool_size": 3,
                        "reranker": "llm",
                    }
                )
        self.assertEqual(report["reranker"]["requested"], "llm")
        self.assertEqual(report["reranker"]["type"], "evidence-aware")
        self.assertIn("not configured", report["reranker"]["fallback_reason"])


if __name__ == "__main__":
    unittest.main()
