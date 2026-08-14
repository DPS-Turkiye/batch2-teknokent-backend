from __future__ import annotations

import json
import unittest
from types import SimpleNamespace

from matching_prototype.rerankers import LLMReranker


class FakeCompletions:
    def __init__(self, payload):
        self.payload = payload
        self.request = None

    def create(self, **kwargs):
        self.request = kwargs
        message = SimpleNamespace(content=json.dumps(self.payload))
        return SimpleNamespace(choices=[SimpleNamespace(message=message)])


class FakeClient:
    def __init__(self, payload):
        self.completions = FakeCompletions(payload)
        self.chat = SimpleNamespace(completions=self.completions)


def candidate(mentor_id: str, score: float) -> dict:
    return {
        "mentor_id": mentor_id,
        "pre_rerank_score": score,
        "retrieval": {"taxonomy": score, "lexical": score, "semantic": score},
        "components": {"mentor_depth": score},
        "evidence_assessment": {"quality_score": score},
        "evidence": [f"Evidence for {mentor_id}"],
        "reranker_text": f"Profile for {mentor_id}",
        "uncertainties": [],
    }


class LLMRerankerTests(unittest.TestCase):
    def test_structured_assessments_replace_baseline_for_evaluated_candidates(self):
        client = FakeClient(
            {
                "assessments": [
                    {
                        "mentor_id": "m1",
                        "score": 0.91,
                        "rationale": "Direct evidence and sufficient depth.",
                        "evidence_quotes": ["Evidence for m1"],
                        "concerns": [],
                    },
                    {
                        "mentor_id": "m2",
                        "score": 0.32,
                        "rationale": "Only indirect evidence.",
                        "evidence_quotes": ["Evidence for m2"],
                        "concerns": ["Limited problem-specific ownership"],
                    },
                ]
            }
        )
        reranker = LLMReranker(client, model="test-model", provider="test-provider")

        scores = reranker.rerank("Need AI product integration guidance", [candidate("m1", 0.7), candidate("m2", 0.6)])

        self.assertEqual(scores, {"m1": 0.91, "m2": 0.32})
        self.assertEqual(reranker.details["m1"]["rationale"], "Direct evidence and sufficient depth.")
        self.assertTrue(reranker.configuration["experimental"])
        self.assertEqual(client.completions.request["response_format"]["type"], "json_schema")

    def test_candidates_outside_limit_keep_deterministic_evidence_score(self):
        client = FakeClient(
            {
                "assessments": [
                    {
                        "mentor_id": "m1",
                        "score": 0.95,
                        "rationale": "Strong evidence.",
                        "evidence_quotes": ["Evidence for m1"],
                        "concerns": [],
                    }
                ]
            }
        )
        reranker = LLMReranker(client, model="test-model", provider="openai", candidate_limit=1)

        scores = reranker.rerank("Problem", [candidate("m1", 0.8), candidate("m2", 0.4)])

        self.assertEqual(scores["m1"], 0.95)
        self.assertAlmostEqual(scores["m2"], 0.4)
        self.assertNotIn("m2", reranker.details)

    def test_missing_assessment_fails_closed_for_pool_fallback(self):
        client = FakeClient({"assessments": []})
        reranker = LLMReranker(client, model="test-model", provider="openai")

        with self.assertRaisesRegex(RuntimeError, "did not assess every"):
            reranker.rerank("Problem", [candidate("m1", 0.8)])


if __name__ == "__main__":
    unittest.main()
