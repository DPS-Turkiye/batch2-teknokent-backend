import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import web_app
import accuracy_audit
import benchmark
from benchmark import redact_pii


class BatchReportTests(unittest.TestCase):
    def test_redacts_contact_and_turkish_identity_data(self):
        source = (
            "Email: ayse@example.com Phone: +90 532 123 45 67 "
            "TCKN: 12345678901 Role: Backend Developer"
        )

        redacted, summary = redact_pii(source)

        self.assertNotIn("ayse@example.com", redacted)
        self.assertNotIn("532 123 45 67", redacted)
        self.assertNotIn("12345678901", redacted)
        self.assertIn("Backend Developer", redacted)
        self.assertEqual(summary["emails"], 1)
        self.assertEqual(summary["phone_numbers"], 1)
        self.assertEqual(summary["identity_numbers"], 1)
        self.assertEqual(summary["total"], 3)

    def test_auto_ocr_detects_weak_extraction(self):
        self.assertTrue(
            benchmark._needs_ocr("[Page 1]\nshort", 1, [])
        )
        self.assertFalse(
            benchmark._needs_ocr("[Page 1]\n" + "backend developer " * 80, 1, [])
        )

    def test_forced_ocr_falls_back_when_runtime_is_unavailable(self):
        with patch.object(
            benchmark, "extract_text", return_value=("existing text", 1, [])
        ), patch.object(benchmark.shutil, "which", return_value=None):
            text, _, warnings, metadata = benchmark.extract_text_with_ocr(
                Path("sample.pdf"), mode="force", preferred_languages="eng+tur"
            )

        self.assertEqual(text, "existing text")
        self.assertFalse(metadata["applied"])
        self.assertEqual(metadata["reason"], "runtime-unavailable")
        self.assertTrue(any("unavailable" in warning for warning in warnings))

    def sample_rows(self):
        return [
            {
                "pdf": "alpha.pdf",
                "status": "completed",
                "pages": 2,
                "local_total_tokens": 3000,
                "direct_total_tokens": 3300,
                "local_cost_usd": 0.01,
                "direct_cost_usd": 0.012,
                "local_latency_seconds": 9,
                "direct_latency_seconds": 10,
                "local_warning_count": 1,
                "direct_warning_count": 2,
            },
            {
                "pdf": "beta.pdf",
                "status": "completed",
                "pages": 1,
                "local_total_tokens": 2000,
                "direct_total_tokens": 2200,
                "local_cost_usd": 0.008,
                "direct_cost_usd": 0.009,
                "local_latency_seconds": 8,
                "direct_latency_seconds": 8.5,
            },
            {"pdf": "failed.pdf", "status": "failed", "error": "API timeout"},
        ]

    def test_summary_excludes_failed_rows(self):
        rows = [web_app.normalize_report_row(row) for row in self.sample_rows()]
        summary = web_app.summarize_report(rows)
        self.assertEqual(summary["files_total"], 3)
        self.assertEqual(summary["completed"], 2)
        self.assertEqual(summary["failed"], 1)
        self.assertEqual(summary["median_token_difference"], -250)
        self.assertAlmostEqual(summary["total_api_cost_usd"], 0.039)

    def test_report_writes_html_csv_and_json(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(web_app, "REPORTS_DIR", Path(directory)):
                report = web_app.create_batch_report(
                    {
                        "rows": self.sample_rows(),
                        "settings": {
                            "profile_type": "mentor",
                            "model": "gpt-5.6-luna",
                            "pdf_detail": "low",
                            "order": "local-first",
                        },
                    }
                )
                report_dir = Path(directory) / report["report_id"]
                self.assertTrue((report_dir / "report.html").is_file())
                self.assertTrue((report_dir / "batch-summary.csv").is_file())
                self.assertTrue((report_dir / "batch-results.json").is_file())
                html = (report_dir / "report.html").read_text(encoding="utf-8")
                self.assertIn("Technical summary", html)
                self.assertIn("Per-CV results", html)
                self.assertIn("Limitations and robustness", html)
                self.assertIn("<td>failed.pdf</td><td>—</td>", html)
                self.assertNotIn("failed.pdf</td><td>0</td>", html)

    def test_saved_parser_result_can_be_imported_for_matching(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result_dir = root / "results" / "job123" / "run"
            result_dir.mkdir(parents=True)
            (result_dir / "all-results.json").write_text(
                '[{"results":{"local_text":{"profile":{"professional_functions":["Sales"]}}}}]',
                encoding="utf-8",
            )
            with patch.object(web_app, "RESULTS_DIR", root / "results"):
                imported = web_app.import_matching_profiles(
                    {
                        "profile_type": "mentor",
                        "approach": "local_text",
                        "sources": [{"job_id": "job123", "source_name": "mentor.pdf"}],
                    },
                    root / "library.json",
                )
        self.assertEqual(imported["imported"], 1)
        self.assertEqual(imported["mentor_count"], 1)

    def test_deterministic_evidence_checks_document_and_page(self):
        profile = {
            "employment_highlights": [
                {"claim": "Built APIs", "page": 2, "quote": "Built REST APIs", "information_type": "explicit"},
                {"claim": "Led sales", "page": 1, "quote": "Not in source", "information_type": "explicit"},
            ]
        }
        result = accuracy_audit.deterministic_evidence(
            profile, ["Summary", "Built REST APIs with Python"]
        )
        self.assertEqual(result["evidence_count"], 2)
        self.assertEqual(result["quote_supported_count"], 1)
        self.assertEqual(result["quote_on_cited_page_count"], 1)
        self.assertEqual(result["quote_support_rate_percent"], 50.0)

    def test_normalizes_evident_ten_point_judge_scale(self):
        item = {"judge": {}}
        for approach in ("local_text", "direct_pdf"):
            item["judge"][approach] = {
                "factual_precision_score": 9,
                "completeness_score": 8,
                "evidence_quality_score": 9,
                "overall_accuracy_score": 9,
            }
        adjusted = accuracy_audit.normalize_judge_score_scale(item)
        self.assertEqual(adjusted, ["local_text", "direct_pdf"])
        self.assertEqual(item["judge"]["local_text"]["overall_accuracy_score"], 90)


if __name__ == "__main__":
    unittest.main()
