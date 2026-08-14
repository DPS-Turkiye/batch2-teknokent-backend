from __future__ import annotations

import unittest
from pathlib import Path
from unittest.mock import patch

from hybrid_repair_benchmark import merge_profile, repair_triggers


class HybridRepairTests(unittest.TestCase):
    def test_noisy_technologies_and_functions_are_flagged(self) -> None:
        profile = {
            "industries": ["Banking"],
            "professional_functions": [f"Function {index}" for index in range(17)],
            "technologies": [f"Technology {index}" for index in range(21)],
            "employment_highlights": [{"claim": f"Claim {index}", "evidence": []} for index in range(4)],
            "missing_fields": [],
            "warnings": [],
        }
        with patch("hybrid_repair_benchmark.pdf_pages", return_value=[]), patch(
            "hybrid_repair_benchmark.deterministic_evidence",
            return_value={"quote_support_rate_percent": None},
        ):
            triggers = repair_triggers(profile, Path("sample.pdf"))
        self.assertIn("professional_functions", triggers)
        self.assertIn("technologies", triggers)

    def test_explicit_missing_technology_is_flagged(self) -> None:
        profile = {
            "industries": ["Retail"],
            "professional_functions": ["Operations"],
            "technologies": ["Excel"],
            "employment_highlights": [{"claim": f"Claim {index}", "evidence": []} for index in range(4)],
            "missing_fields": ["software and database details"],
            "warnings": [],
        }
        with patch("hybrid_repair_benchmark.pdf_pages", return_value=[]), patch(
            "hybrid_repair_benchmark.deterministic_evidence",
            return_value={"quote_support_rate_percent": 100.0},
        ):
            triggers = repair_triggers(profile, Path("sample.pdf"))
        self.assertIn("technologies", triggers)

    def test_merge_changes_only_requested_nonempty_fields(self) -> None:
        baseline = {
            "industries": ["Banking"],
            "technologies": ["Excel"],
            "professional_functions": ["Operations"],
        }
        repair = {
            "industries": ["Financial services"],
            "technologies": [],
            "professional_functions": ["Branch operations"],
        }
        merged, applied = merge_profile(
            baseline, repair, ["technologies", "professional_functions"]
        )
        self.assertEqual(merged["industries"], ["Banking"])
        self.assertEqual(merged["technologies"], ["Excel"])
        self.assertEqual(merged["professional_functions"], ["Branch operations"])
        self.assertEqual(applied, ["professional_functions"])
        self.assertEqual(baseline["professional_functions"], ["Operations"])


if __name__ == "__main__":
    unittest.main()
