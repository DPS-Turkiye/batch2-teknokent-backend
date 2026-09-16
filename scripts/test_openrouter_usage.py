import io
import json
import os
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

import openrouter_usage


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def __enter__(self):
        return io.BytesIO(json.dumps(self.payload).encode("utf-8"))

    def __exit__(self, exc_type, exc_value, traceback):
        return False


class OpenRouterUsageTests(unittest.TestCase):
    @patch("openrouter_usage.urlopen")
    def test_fetch_key_usage_authenticates_and_returns_data(self, mock_urlopen):
        expected = {"usage": 1.25}
        mock_urlopen.return_value = FakeResponse({"data": expected})

        result = openrouter_usage.fetch_key_usage("secret", timeout=3)

        self.assertEqual(expected, result)
        request = mock_urlopen.call_args.args[0]
        self.assertEqual("Bearer secret", request.get_header("Authorization"))
        self.assertEqual(3, mock_urlopen.call_args.kwargs["timeout"])

    @patch("openrouter_usage.urlopen")
    def test_fetch_key_usage_reports_api_message_without_key(self, mock_urlopen):
        body = io.BytesIO(b'{"error":{"message":"Invalid API key"}}')
        mock_urlopen.side_effect = HTTPError(
            openrouter_usage.OPENROUTER_KEY_URL, 401, "Unauthorized", {}, body
        )

        with self.assertRaisesRegex(
            openrouter_usage.OpenRouterUsageError,
            "HTTP 401: Invalid API key",
        ) as raised:
            openrouter_usage.fetch_key_usage("never-print-this")

        self.assertNotIn("never-print-this", str(raised.exception))

    def test_format_usage_includes_periods_and_limit(self):
        output = openrouter_usage.format_usage(
            {
                "label": "Production key",
                "usage": 25.5,
                "usage_daily": 1.0,
                "usage_weekly": 4.0,
                "usage_monthly": 10.0,
                "limit": 100.0,
                "limit_remaining": 74.5,
                "limit_reset": "monthly",
            }
        )

        self.assertIn("Lifetime:     $25.500000", output)
        self.assertIn("This month:   $10.000000", output)
        self.assertIn("Remaining:    $74.500000", output)
        self.assertIn("Limit used:   25.5%", output)

    def test_main_requires_environment_key(self):
        with patch.dict(os.environ, {}, clear=True), patch(
            "sys.stderr", new_callable=io.StringIO
        ) as stderr:
            exit_code = openrouter_usage.main([])

        self.assertEqual(2, exit_code)
        self.assertIn("OPENROUTER_API_KEY is not set", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
