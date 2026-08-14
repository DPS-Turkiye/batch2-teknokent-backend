#!/usr/bin/env python3
"""Prepare labeled Luna-baseline and hybrid-repair matching fixtures."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


TOOL_DIR = Path(__file__).resolve().parent
ROOT = TOOL_DIR / "results" / "hybrid-repair"
CATEGORIES = {
    "arts": ["05_ARTS_22848179", "29_ARTS_27936502"],
    "automobile": ["06_AUTOMOBILE_22946204", "30_AUTOMOBILE_15210069"],
    "banking": ["08_BANKING_28895997", "32_BANKING_33286293"],
    "bpo": ["09_BPO_41152404", "33_BPO_89512321"],
    "chef": ["11_CHEF_10333299", "35_CHEF_10588874"],
}
MENTEE_PROFILES: dict[str, dict[str, Any]] = {
    "arts": {
        "sectors": ["Arts", "Creative industries"], "stage": "Early stage",
        "current_bottlenecks": ["Creative production", "Community engagement"],
        "goals": ["Grow an arts and creative organization"],
        "technologies": ["Digital media"],
        "support_needs": ["Arts management", "Creative direction", "Community programs"],
    },
    "automobile": {
        "sectors": ["Automotive", "Automobile engineering"], "stage": "Growth",
        "current_bottlenecks": ["Vehicle engineering", "Product development"],
        "goals": ["Improve automotive engineering and manufacturing"],
        "technologies": ["CAD", "Vehicle systems"],
        "support_needs": ["Automotive engineering", "Manufacturing", "Quality"],
    },
    "banking": {
        "sectors": ["Banking", "Financial services"], "stage": "Early revenue",
        "current_bottlenecks": ["Banking operations", "Customer financial services"],
        "goals": ["Improve financial operations and customer service"],
        "technologies": ["Banking systems"],
        "support_needs": ["Banking", "Finance", "Risk management"],
    },
    "bpo": {
        "sectors": ["Business process outsourcing", "Professional services"], "stage": "Growth",
        "current_bottlenecks": ["Process quality", "Workforce operations"],
        "goals": ["Scale reliable outsourced business operations"],
        "technologies": ["CRM", "Quality assurance tools"],
        "support_needs": ["BPO operations", "Quality assurance", "People operations"],
    },
    "chef": {
        "sectors": ["Food service", "Hospitality"], "stage": "Early revenue",
        "current_bottlenecks": ["Kitchen operations", "Menu development"],
        "goals": ["Grow a profitable food and hospitality business"],
        "technologies": ["Kitchen equipment"],
        "support_needs": ["Culinary operations", "Food safety", "Restaurant management"],
    },
}


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def latest(directory: Path) -> Path:
    paths = sorted(directory.glob("*/all-results.json"), key=lambda path: path.stat().st_mtime)
    if not paths:
        raise RuntimeError(f"No results found under {directory}")
    return paths[-1]


def main() -> int:
    baseline_runs = read_json(latest(ROOT / "baseline"))
    repair_rows = read_json(latest(ROOT / "repairs"))
    output = ROOT / "matching" / "inputs"
    output.mkdir(parents=True, exist_ok=True)
    baseline_mentors = []
    hybrid_mentors = []
    for run, repair in zip(baseline_runs, repair_rows):
        stem = Path(run["pdf"]).stem
        baseline = run["results"]["local_text"]["profile"]
        hybrid = repair["hybrid_profile"] or baseline
        baseline_mentors.append({
            "mentor_id": stem, "profile_version": "hybrid-test-baseline-v1", "profile": baseline,
        })
        hybrid_mentors.append({
            "mentor_id": stem, "profile_version": "hybrid-test-repaired-v1", "profile": hybrid,
        })
    mentees = [
        {"mentee_id": f"mentee-{category}", "profile_version": "hybrid-test-v1", "profile": profile}
        for category, profile in MENTEE_PROFILES.items()
    ]
    expected = {f"mentee-{category}": values for category, values in CATEGORIES.items()}
    write_json(output / "baseline-mentors.json", baseline_mentors)
    write_json(output / "hybrid-mentors.json", hybrid_mentors)
    write_json(output / "mentees.json", mentees)
    write_json(output / "expected_matches.json", expected)
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
