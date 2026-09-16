#!/usr/bin/env python3
"""Show spending recorded for the OpenRouter API key in the environment."""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


OPENROUTER_KEY_URL = "https://openrouter.ai/api/v1/key"
DEFAULT_TIMEOUT_SECONDS = 10.0


class OpenRouterUsageError(RuntimeError):
    """Raised when OpenRouter usage cannot be retrieved or understood."""


def fetch_key_usage(
    api_key: str,
    *,
    url: str = OPENROUTER_KEY_URL,
    timeout: float = DEFAULT_TIMEOUT_SECONDS,
) -> dict[str, Any]:
    """Fetch and return the current key's usage object from OpenRouter."""
    request = Request(
        url,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Accept": "application/json",
            "User-Agent": "dps-openrouter-usage/1.0",
        },
    )

    try:
        with urlopen(request, timeout=timeout) as response:
            payload = json.load(response)
    except HTTPError as error:
        detail = _http_error_detail(error)
        raise OpenRouterUsageError(
            f"OpenRouter returned HTTP {error.code}{detail}."
        ) from error
    except URLError as error:
        raise OpenRouterUsageError(
            f"Could not reach OpenRouter: {error.reason}."
        ) from error
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise OpenRouterUsageError("OpenRouter returned invalid JSON.") from error

    data = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(data, dict):
        raise OpenRouterUsageError(
            "OpenRouter response did not contain a usage object."
        )
    return data


def _http_error_detail(error: HTTPError) -> str:
    try:
        payload = json.loads(error.read().decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError):
        return ""

    if not isinstance(payload, dict):
        return ""
    error_value = payload.get("error")
    if isinstance(error_value, dict):
        message = error_value.get("message")
    else:
        message = error_value
    return f": {message}" if isinstance(message, str) and message else ""


def format_usage(data: dict[str, Any]) -> str:
    """Format OpenRouter's current-key response for terminal output."""
    lines = ["OpenRouter API key usage"]
    label = data.get("label")
    if label:
        lines.append(f"Key:          {label}")

    lines.extend(
        [
            f"Lifetime:     {_money(data.get('usage'))}",
            f"Today:        {_money(data.get('usage_daily'))}",
            f"This week:    {_money(data.get('usage_weekly'))}",
            f"This month:   {_money(data.get('usage_monthly'))}",
        ]
    )

    spending_limit = data.get("limit")
    if _is_number(spending_limit):
        reset = data.get("limit_reset") or "no reset"
        lines.append(f"Limit:        {_money(spending_limit)} ({reset})")
        lines.append(f"Remaining:    {_money(data.get('limit_remaining'))}")
        if spending_limit > 0 and _is_number(data.get("limit_remaining")):
            used = max(0.0, spending_limit - data["limit_remaining"])
            lines.append(f"Limit used:   {used / spending_limit:.1%}")
    else:
        lines.append("Limit:        not configured")

    byok_usage = data.get("byok_usage")
    if _is_number(byok_usage) and byok_usage != 0:
        lines.append(f"BYOK usage:   {_money(byok_usage)}")

    return "\n".join(lines)


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _money(value: Any) -> str:
    return f"${value:,.6f}" if _is_number(value) else "unavailable"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Show usage for the API key in OPENROUTER_API_KEY."
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="print the API usage object as JSON instead of a summary",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT_SECONDS,
        help=f"request timeout in seconds (default: {DEFAULT_TIMEOUT_SECONDS:g})",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    api_key = os.environ.get("OPENROUTER_API_KEY", "").strip()
    if not api_key:
        print("OPENROUTER_API_KEY is not set.", file=sys.stderr)
        return 2
    if args.timeout <= 0:
        print("--timeout must be greater than zero.", file=sys.stderr)
        return 2

    try:
        data = fetch_key_usage(api_key, timeout=args.timeout)
    except OpenRouterUsageError as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1

    if args.json:
        print(json.dumps(data, indent=2, sort_keys=True))
    else:
        print(format_usage(data))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
