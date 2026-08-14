import unittest

from evidence_verification import verify_evidence_quote


class EvidenceVerificationTest(unittest.TestCase):
    def test_accepts_pdf_spacing_and_minor_ocr_noise(self):
        result = verify_evidence_quote(
            "Built scalable full-stack applications for healthcare teams.",
            "Experience\nBuilt scalable full stack applicatlons for health care teams and hospitals.",
        )
        self.assertTrue(result["supported"])
        self.assertEqual(result["method"], "fuzzy-window")

    def test_rejects_unrelated_evidence(self):
        result = verify_evidence_quote(
            "Led machine learning infrastructure at scale.",
            "Prepared financial models and investor presentations for seed-stage startups.",
        )
        self.assertFalse(result["supported"])

    def test_accepts_normalized_exact_match(self):
        result = verify_evidence_quote("Kullanıcı araştırması yürüttü", "KULLANICI  araştırması\nyürüttü.")
        self.assertTrue(result["supported"])
        self.assertEqual(result["similarity"], 1.0)


if __name__ == "__main__":
    unittest.main()
