from __future__ import annotations

import unittest
import io
import json
from types import SimpleNamespace

from ai_provider import OPENROUTER_BASE_URL, provider_settings
from benchmark import IndividualMenteeProfile, MentorProfile, _openrouter_response, _strict_json_schema, normalize_usage, profile_config
from matching_prototype.providers import OpenRouterEmbeddingProvider
from model_catalog import available_models


class FakeEmbeddings:
    def create(self, **kwargs):
        self.kwargs = kwargs
        return SimpleNamespace(
            data=[SimpleNamespace(index=1, embedding=[0.0, 1.0]), SimpleNamespace(index=0, embedding=[1.0, 0.0])]
        )


class FakeClient:
    def __init__(self):
        self.embeddings = FakeEmbeddings()


class AIProviderTests(unittest.TestCase):
    def test_individual_mentee_schema_is_selectable_and_strict(self):
        model, instructions = profile_config("individual_mentee")
        self.assertIs(model, IndividualMenteeProfile)
        self.assertIn("career mentoring", instructions)
        schema = _strict_json_schema(model)["json_schema"]["schema"]
        self.assertFalse(schema["additionalProperties"])
    def test_openrouter_environment_configuration(self):
        settings = provider_settings(
            {
                "AI_PROVIDER": "openrouter",
                "OPENROUTER_API_KEY": "secret",
                "OPENROUTER_MODEL": "openai/gpt-4o-mini",
            }
        )
        self.assertEqual(settings.provider, "openrouter")
        self.assertEqual(settings.base_url, OPENROUTER_BASE_URL)
        self.assertEqual(settings.key_environment_variable, "OPENROUTER_API_KEY")
        self.assertEqual(settings.embedding_model, "openai/text-embedding-3-small")

    def test_structured_profile_schema_is_strict(self):
        response_format = _strict_json_schema(MentorProfile)
        schema = response_format["json_schema"]["schema"]
        self.assertTrue(response_format["json_schema"]["strict"])
        self.assertFalse(schema["additionalProperties"])
        self.assertFalse(schema["$defs"]["Evidence"]["additionalProperties"])

    def test_chat_completion_usage_is_normalized(self):
        usage = normalize_usage(
            {
                "prompt_tokens": 100,
                "completion_tokens": 25,
                "total_tokens": 125,
                "prompt_tokens_details": {"cached_tokens": 10},
                "completion_tokens_details": {"reasoning_tokens": 4},
            }
        )
        self.assertEqual(usage["input_tokens"], 100)
        self.assertEqual(usage["cached_input_tokens"], 10)
        self.assertEqual(usage["output_tokens"], 25)
        self.assertEqual(usage["reasoning_tokens"], 4)

    def test_openrouter_response_accepts_outer_json_code_fence(self):
        profile = {
            "full_name": None,
            "professional_summary": "Engineer",
            "industries": [],
            "professional_functions": ["Engineering"],
            "technologies": [],
            "languages": [],
            "years_of_experience": None,
            "employment_highlights": [],
            "education_highlights": [],
            "achievement_highlights": [],
            "missing_fields": [],
            "warnings": [],
        }
        response = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(
                content="```json\n" + json.dumps(profile) + "\n```"
            ))],
            id="response-id",
            model="vendor/model",
            usage=None,
        )
        parsed = _openrouter_response(response, MentorProfile)
        self.assertEqual(parsed.output_parsed.professional_summary, "Engineer")

    def test_openrouter_embeddings_use_full_model_slug_and_order(self):
        client = FakeClient()
        provider = OpenRouterEmbeddingProvider(
            client=client, model="openai/text-embedding-3-small"
        )
        vectors = provider.embed(["mentor", "mentee"])
        self.assertEqual(provider.configuration.provider, "openrouter")
        self.assertEqual(provider.configuration.model, "openai/text-embedding-3-small")
        self.assertEqual(vectors, [[1.0, 0.0], [0.0, 1.0]])
        self.assertNotIn("dimensions", client.embeddings.kwargs)
        self.assertEqual(provider.usage_calls, [{"input_tokens": 0, "total_tokens": 0}])

    def test_account_model_catalog_filters_for_structured_output(self):
        settings = provider_settings(
            {"AI_PROVIDER": "openrouter", "OPENROUTER_API_KEY": "catalog-secret"}
        )
        payload = {
            "data": [
                {
                    "id": "vendor/compatible",
                    "name": "Compatible",
                    "architecture": {"output_modalities": ["text"]},
                    "supported_parameters": ["structured_outputs"],
                    "context_length": 32000,
                },
                {
                    "id": "vendor/plain",
                    "name": "Plain",
                    "architecture": {"output_modalities": ["text"]},
                    "supported_parameters": ["temperature"],
                },
            ]
        }

        def opener(request, timeout):
            self.assertTrue(request.full_url.endswith("/models/user"))
            self.assertEqual(timeout, 12)
            return io.BytesIO(json.dumps(payload).encode("utf-8"))

        models, error = available_models(
            settings, "parsing", opener=opener, force_refresh=True
        )
        self.assertIsNone(error)
        self.assertIn("vendor/compatible", [model["id"] for model in models])
        self.assertNotIn("vendor/plain", [model["id"] for model in models])


if __name__ == "__main__":
    unittest.main()
