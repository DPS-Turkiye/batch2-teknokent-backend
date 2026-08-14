#!/usr/bin/env python3
"""Build labeled downstream matching fixtures from the Claude parser comparison."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


TOOL_DIR = Path(__file__).resolve().parent
RESULTS_DIR = TOOL_DIR / "results" / "claude-comparison"
PARSER_DIRS = {"luna": "luna", "haiku": "haiku", "sonnet": "sonnet"}
CATEGORIES = {
    "accounting": ["01_ACCOUNTANT_25867805", "25_ACCOUNTANT_20624984"],
    "advocacy": ["02_ADVOCATE_20544228", "26_ADVOCATE_53169257"],
    "agriculture": ["03_AGRICULTURE_11197262", "27_AGRICULTURE_28247753"],
    "apparel": ["04_APPAREL_16723524", "28_APPAREL_91533580"],
    "aviation": ["07_AVIATION_16279537", "31_AVIATION_42546558"],
}


MENTEE_PROFILES: dict[str, dict[str, Any]] = {
    "accounting": {
        "sectors": ["Accounting", "Financial services"],
        "stage": "Early revenue",
        "current_bottlenecks": ["Financial reporting", "Tax compliance"],
        "goals": ["Improve accounting controls and financial planning"],
        "technologies": ["Excel", "Accounting software"],
        "support_needs": ["Accounting", "Financial reporting", "Tax"],
    },
    "advocacy": {
        "sectors": ["Legal services", "Advocacy"],
        "stage": "Early stage",
        "current_bottlenecks": ["Legal research", "Case strategy"],
        "goals": ["Improve legal operations and client advocacy"],
        "technologies": [],
        "support_needs": ["Legal practice", "Litigation", "Advocacy"],
    },
    "agriculture": {
        "sectors": ["Agriculture", "Farming"],
        "stage": "Growth",
        "current_bottlenecks": ["Agricultural operations", "Supply planning"],
        "goals": ["Scale sustainable agricultural production"],
        "technologies": ["Agricultural equipment"],
        "support_needs": ["Agriculture", "Farm operations"],
    },
    "apparel": {
        "sectors": ["Apparel", "Fashion retail"],
        "stage": "Early revenue",
        "current_bottlenecks": ["Merchandising", "Retail growth"],
        "goals": ["Grow an apparel and fashion business"],
        "technologies": ["Retail systems"],
        "support_needs": ["Apparel", "Fashion", "Merchandising"],
    },
    "aviation": {
        "sectors": ["Aviation", "Aerospace"],
        "stage": "Growth",
        "current_bottlenecks": ["Flight operations", "Aviation safety"],
        "goals": ["Improve aviation operations and safety"],
        "technologies": ["Aircraft systems"],
        "support_needs": ["Aviation", "Aircraft maintenance", "Flight operations"],
    },
}


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def latest_run(directory: Path) -> Path | None:
    candidates = sorted(directory.glob("*/all-results.json"), key=lambda path: path.stat().st_mtime)
    return candidates[-1] if candidates else None


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    output_dir = RESULTS_DIR / "matching" / "inputs"
    output_dir.mkdir(parents=True, exist_ok=True)
    mentees = [
        {
            "mentee_id": f"mentee-{category}",
            "profile_version": "claude-comparison-v1",
            "profile": profile,
        }
        for category, profile in MENTEE_PROFILES.items()
    ]
    expected = {
        f"mentee-{category}": mentor_ids for category, mentor_ids in CATEGORIES.items()
    }
    write_json(output_dir / "mentees.json", mentees)
    write_json(output_dir / "expected_matches.json", expected)
    expected_mentor_count = sum(len(values) for values in CATEGORIES.values())
    manifest = {"expected_mentor_count": expected_mentor_count, "parsers": {}}
    for parser_name, directory in PARSER_DIRS.items():
        run_path = latest_run(RESULTS_DIR / "parsing" / directory)
        runs = read_json(run_path) if run_path else []
        mentors = []
        errors = []
        for run in runs:
            result = run.get("results", {}).get("local_text", {})
            profile_id = Path(run["pdf"]).stem
            if result.get("error") or not result.get("profile"):
                raw_error = str(result.get("error") or "Unknown parser failure")
                safe_error = (
                    "OpenRouter HTTP 402: insufficient funded credit for the requested output reservation."
                    if "402" in raw_error
                    else raw_error.splitlines()[0][:240]
                )
                errors.append({"mentor_id": profile_id, "error": safe_error})
                continue
            mentors.append({
                "mentor_id": profile_id,
                "profile_version": "claude-comparison-v1",
                "profile": result["profile"],
            })
        write_json(output_dir / f"{parser_name}-mentors.json", mentors)
        manifest["parsers"][parser_name] = {
            "source": str(run_path) if run_path else None,
            "mentor_count": len(mentors),
            "complete_pool": len(mentors) == expected_mentor_count,
            "errors": errors,
        }
    write_json(output_dir / "manifest.json", manifest)
    print(output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
