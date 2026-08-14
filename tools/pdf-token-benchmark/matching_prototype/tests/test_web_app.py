from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from matching_prototype.web_app import RequestError, run_matching, sample_payload
from matching_prototype.profile_library import import_parsed_profiles, read_profile_library


class MatchingWebAppTests(unittest.TestCase):
    def test_samples_have_required_profile_arrays(self):
        payload = sample_payload()
        self.assertGreaterEqual(len(payload["mentors"]), 3)
        self.assertGreaterEqual(len(payload["mentees"]), 1)
        self.assertIsInstance(payload["expected"], dict)

    def test_deterministic_web_run_returns_report(self):
        samples = sample_payload()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with (
                patch("matching_prototype.web_app.CACHE_PATH", root / "cache.json"),
                patch("matching_prototype.web_app.REPORTS_DIR", root / "reports"),
            ):
                report = run_matching({"provider": "deterministic", **samples})
        self.assertEqual(report["mentor_count"], len(samples["mentors"]))
        self.assertEqual(report["mentee_count"], len(samples["mentees"]))
        self.assertEqual(len(report["results"]), len(samples["mentees"]))
        self.assertTrue(report["download_url"].endswith("/evaluation.json"))

    def test_invalid_profile_payload_is_rejected(self):
        with self.assertRaises(RequestError):
            run_matching({"provider": "deterministic", "mentors": {}, "mentees": []})

    def test_parser_profiles_round_trip_through_shared_library(self):
        with tempfile.TemporaryDirectory() as temporary:
            library_path = Path(temporary) / "library.json"
            imported = import_parsed_profiles(
                {
                    "profile_type": "mentor",
                    "approach": "local_text",
                    "entries": [
                        {
                            "source_name": "person.pdf",
                            "profile": {"professional_functions": ["Sales"]},
                        }
                    ],
                },
                library_path,
            )
            library = read_profile_library(library_path)
        self.assertEqual(imported["mentor_count"], 1)
        self.assertEqual(len(library["mentors"]), 1)
        record = library["mentors"][0]
        self.assertTrue(record["mentor_id"].startswith("mentor-"))
        self.assertEqual(record["source"]["parsing_approach"], "local_text")

    def test_reimport_replaces_same_source_instead_of_duplicating(self):
        with tempfile.TemporaryDirectory() as temporary:
            library_path = Path(temporary) / "library.json"
            base = {
                "profile_type": "mentee",
                "approach": "direct_pdf",
                "entries": [{"source_name": "startup.pdf", "profile": {"stage": "MVP"}}],
            }
            import_parsed_profiles(base, library_path)
            base["entries"][0]["profile"] = {"stage": "Growth"}
            import_parsed_profiles(base, library_path)
            library = read_profile_library(library_path)
        self.assertEqual(len(library["mentees"]), 1)
        self.assertEqual(library["mentees"][0]["profile"]["stage"], "Growth")

    def test_individual_mentee_import_uses_mentee_collection_and_kind(self):
        with tempfile.TemporaryDirectory() as temporary:
            library_path = Path(temporary) / "library.json"
            import_parsed_profiles({
                "profile_type": "individual_mentee",
                "approach": "local_text",
                "entries": [{"source_name": "intern.pdf", "profile": {"current_role": "Student"}}],
            }, library_path)
            record = read_profile_library(library_path)["mentees"][0]
        self.assertTrue(record["mentee_id"].startswith("mentee-"))
        self.assertEqual(record["profile_kind"], "individual")


if __name__ == "__main__":
    unittest.main()
