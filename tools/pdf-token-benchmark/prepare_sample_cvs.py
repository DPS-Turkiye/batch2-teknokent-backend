#!/usr/bin/env python3
"""Extract a balanced, reproducible PDF sample from the Kaggle resume corpus."""

from __future__ import annotations

import argparse
import csv
import random
import zipfile
from collections import defaultdict
from pathlib import Path


SOURCE_URL = "https://www.kaggle.com/datasets/snehaanbhawal/resume-dataset"
DOWNLOAD_URL = (
    "https://www.kaggle.com/api/v1/datasets/download/"
    "snehaanbhawal/resume-dataset"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Extract a balanced PDF sample from the Resume Dataset archive."
    )
    parser.add_argument("archive", type=Path, help="Downloaded Kaggle ZIP archive.")
    parser.add_argument("output", type=Path, help="Destination directory.")
    parser.add_argument("--count", type=int, default=35)
    parser.add_argument("--seed", type=int, default=35)
    return parser.parse_args()


def grouped_pdf_paths(archive: zipfile.ZipFile) -> dict[str, list[str]]:
    grouped: dict[str, list[str]] = defaultdict(list)
    for name in archive.namelist():
        parts = Path(name).parts
        if (
            len(parts) == 4
            and parts[:2] == ("data", "data")
            and parts[-1].lower().endswith(".pdf")
        ):
            grouped[parts[2]].append(name)
    return dict(grouped)


def balanced_selection(
    grouped: dict[str, list[str]], count: int, seed: int
) -> list[tuple[str, str]]:
    available = sum(len(paths) for paths in grouped.values())
    if count < 1 or count > available:
        raise ValueError(f"count must be between 1 and {available}")

    rng = random.Random(seed)
    pools: dict[str, list[str]] = {}
    for category, paths in grouped.items():
        pools[category] = sorted(paths)
        rng.shuffle(pools[category])

    selected: list[tuple[str, str]] = []
    categories = sorted(pools)
    while len(selected) < count:
        made_progress = False
        for category in categories:
            if pools[category] and len(selected) < count:
                selected.append((category, pools[category].pop()))
                made_progress = True
        if not made_progress:
            break
    return selected


def main() -> int:
    args = parse_args()
    archive_path = args.archive.expanduser().resolve()
    output_dir = args.output.expanduser().resolve()
    if not archive_path.is_file():
        raise SystemExit(f"Archive not found: {archive_path}")
    if output_dir.exists() and any(output_dir.iterdir()):
        raise SystemExit(f"Output directory is not empty: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)

    manifest_rows: list[dict[str, str | int]] = []
    with zipfile.ZipFile(archive_path) as archive:
        grouped = grouped_pdf_paths(archive)
        selected = balanced_selection(grouped, args.count, args.seed)
        for index, (category, source_path) in enumerate(selected, start=1):
            source_name = Path(source_path).name
            target_name = f"{index:02d}_{category}_{source_name}"
            content = archive.read(source_path)
            if not content.startswith(b"%PDF-"):
                raise ValueError(f"Invalid PDF signature: {source_path}")
            (output_dir / target_name).write_bytes(content)
            manifest_rows.append(
                {
                    "sample_number": index,
                    "filename": target_name,
                    "category": category,
                    "original_archive_path": source_path,
                    "bytes": len(content),
                    "source_url": SOURCE_URL,
                    "dataset_license": "CC0 (as listed by Kaggle)",
                }
            )

    with (output_dir / "manifest.csv").open(
        "w", encoding="utf-8", newline=""
    ) as handle:
        writer = csv.DictWriter(handle, fieldnames=manifest_rows[0].keys())
        writer.writeheader()
        writer.writerows(manifest_rows)

    (output_dir / "SOURCE_AND_USAGE.md").write_text(
        "# Resume benchmark sample\n\n"
        f"- Source: {SOURCE_URL}\n"
        f"- Download endpoint: {DOWNLOAD_URL}\n"
        "- Dataset license: CC0, as listed on the Kaggle dataset page.\n"
        f"- Selection: {args.count} PDFs, deterministic seed {args.seed}, "
        "round-robin across sorted job categories.\n\n"
        "These documents may contain personal information. Keep them local, do not "
        "redistribute them, and use them only for parser evaluation consistent with "
        "the dataset terms. The folder is intentionally ignored by Git.\n",
        encoding="utf-8",
    )
    print(f"Extracted {len(manifest_rows)} PDFs to {output_dir}")
    print(f"Categories represented: {len({row['category'] for row in manifest_rows})}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
