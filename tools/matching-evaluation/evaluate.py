#!/usr/bin/env python3
"""Run labelled, request-specific mentor matching checks against the live backend."""

from __future__ import annotations

import argparse
import base64
import json
import urllib.error
import urllib.request
from pathlib import Path


HERE = Path(__file__).resolve().parent


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080/api/mentor-recommendations")
    parser.add_argument("--cases", type=Path, default=HERE / "cases.json")
    parser.add_argument("--username", default="teknokent")
    parser.add_argument("--password", default="change-me")
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding="utf-8"))
    rows = []
    reciprocal_rank_total = 0.0
    hits = 0
    for case in cases:
        request = urllib.request.Request(
            args.url,
            data=json.dumps(case["request"]).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Basic " + base64.b64encode(
                    f"{args.username}:{args.password}".encode("utf-8")
                ).decode("ascii"),
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                mentors = json.loads(response.read().decode("utf-8"))
                method = response.headers.get("X-Matching-Method", "unknown")
        except urllib.error.HTTPError as error:
            raise SystemExit(f"{case['id']}: backend returned {error.code}: {error.read().decode()}") from error
        ranked_ids = [str(mentor["id"]) for mentor in mentors]
        relevant = set(case["relevantMentorIds"])
        first_rank = next((index + 1 for index, mentor_id in enumerate(ranked_ids) if mentor_id in relevant), None)
        hit = first_rank is not None and first_rank <= 3
        hits += int(hit)
        reciprocal_rank_total += 0.0 if first_rank is None else 1.0 / first_rank
        evidence_items = sum(len(mentor.get("recommendationEvidence") or []) for mentor in mentors[:3])
        rows.append({
            "case": case["id"], "method": method, "first_relevant_rank": first_rank,
            "hit_at_3": hit, "top_3": ranked_ids[:3], "top_3_evidence_items": evidence_items,
        })
    report = {
        "case_count": len(cases),
        "hit_rate_at_3": round(hits / len(cases), 4),
        "mean_reciprocal_rank": round(reciprocal_rank_total / len(cases), 4),
        "cases": rows,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
