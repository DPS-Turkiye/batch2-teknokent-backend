#!/usr/bin/env python3
"""Build a reproducible mentor/mentee PDF cohort from a Kaggle resume CSV."""

from __future__ import annotations

import csv
import hashlib
import json
import re
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle


ROOT = Path(__file__).resolve().parent
SOURCE_CSV = ROOT / "source" / "resume_dataset_2.csv"
MANIFEST_PATH = ROOT / "cohort-manifest.json"
TARGET_ROLES = ("Software Engineer", "Data Scientist", "Financial Analyst")
PER_ROLE = 2


def source_id(row: dict[str, str]) -> str:
    return hashlib.sha256(row["Resume_Text"].strip().encode("utf-8")).hexdigest()[:12]


def safe_text(value: str) -> str:
    replacements = {
        "\u2010": "-",
        "\u2011": "-",
        "\u2012": "-",
        "\u2013": "-",
        "\u2014": "-",
        "\u2022": "-",
    }
    for old, new in replacements.items():
        value = value.replace(old, new)
    return value.encode("ascii", "replace").decode("ascii")


def sanitized_resume(row: dict[str, str]) -> list[str]:
    lines: list[str] = []
    for raw_line in row["Resume_Text"].splitlines():
        line = safe_text(raw_line.strip())
        if not line:
            continue
        if re.match(r"^(Email|Phone):", line, flags=re.IGNORECASE):
            continue
        if line.lower() == f"name: {safe_text(row['Name']).lower()}":
            continue
        lines.append(line)
    return lines


def select_rows(rows: list[dict[str, str]], cohort: str) -> list[dict[str, str]]:
    selected: list[dict[str, str]] = []
    seen_hashes: set[str] = set()
    seen_names: set[str] = set()
    for role in TARGET_ROLES:
        if cohort == "mentees":
            candidates = [
                r
                for r in rows
                if r["Job_Role"] == role
                and int(r["Years_Experience"]) <= 1
                and r["Skills"].strip()
            ]
            candidates.sort(key=lambda r: (int(r["Years_Experience"]), r["Name"], source_id(r)))
        else:
            candidates = [
                r
                for r in rows
                if r["Job_Role"] == role
                and int(r["Years_Experience"]) >= 7
                and r["Skills"].strip()
            ]
            candidates.sort(key=lambda r: (-int(r["Years_Experience"]), r["Name"], source_id(r)))

        role_selection: list[dict[str, str]] = []
        for row in candidates:
            digest = source_id(row)
            normalized_name = row["Name"].strip().casefold()
            if digest in seen_hashes or normalized_name in seen_names:
                continue
            role_selection.append(row)
            seen_hashes.add(digest)
            seen_names.add(normalized_name)
            if len(role_selection) == PER_ROLE:
                break
        if len(role_selection) != PER_ROLE:
            raise RuntimeError(f"Could not select {PER_ROLE} unique {cohort} for {role}.")
        selected.extend(role_selection)
    return selected


def render_pdf(row: dict[str, str], cohort: str) -> Path:
    role_slug = re.sub(r"[^a-z0-9]+", "-", row["Job_Role"].lower()).strip("-")
    path = ROOT / "pdfs" / cohort / f"{cohort[:-1]}-{role_slug}-{source_id(row)}.pdf"
    styles = getSampleStyleSheet()
    title = ParagraphStyle(
        "ResumeTitle",
        parent=styles["Title"],
        alignment=TA_LEFT,
        fontName="Helvetica-Bold",
        fontSize=22,
        leading=27,
        textColor=colors.HexColor("#101A36"),
        spaceAfter=5 * mm,
    )
    section = ParagraphStyle(
        "Section",
        parent=styles["Heading2"],
        fontName="Helvetica-Bold",
        fontSize=11,
        leading=14,
        textColor=colors.HexColor("#1358EE"),
        spaceBefore=4 * mm,
        spaceAfter=2 * mm,
    )
    body = ParagraphStyle(
        "Body",
        parent=styles["BodyText"],
        fontName="Helvetica",
        fontSize=9.5,
        leading=13,
        textColor=colors.HexColor("#26324A"),
        spaceAfter=1.5 * mm,
    )

    def footer(canvas, document):
        canvas.saveState()
        canvas.setFont("Helvetica", 7)
        canvas.setFillColor(colors.HexColor("#68738A"))
        canvas.drawString(18 * mm, 11 * mm, f"Kaggle synthetic resume fixture | source {source_id(row)}")
        canvas.drawRightString(A4[0] - 18 * mm, 11 * mm, f"Page {document.page}")
        canvas.restoreState()

    document = SimpleDocTemplate(
        str(path),
        pagesize=A4,
        leftMargin=18 * mm,
        rightMargin=18 * mm,
        topMargin=17 * mm,
        bottomMargin=18 * mm,
        title=f"{row['Name']} - synthetic resume fixture",
        author="Kaggle Resume Dataset test cohort",
    )
    story = [
        Paragraph(safe_text(row["Name"]), title),
        Paragraph(
            f"<b>{safe_text(row['Job_Role'])}</b> &nbsp; | &nbsp; "
            f"{int(row['Years_Experience'])} years of experience",
            body,
        ),
        Spacer(1, 2 * mm),
    ]
    metadata = [
        ["University", safe_text(row["University"]) or "Not provided"],
        ["Graduation year", safe_text(row["Graduation_Year"]) or "Not provided"],
        ["Skills", safe_text(row["Skills"])],
    ]
    table = Table(metadata, colWidths=[34 * mm, 134 * mm], hAlign="LEFT")
    table.setStyle(
        TableStyle(
            [
                ("FONTNAME", (0, 0), (0, -1), "Helvetica-Bold"),
                ("FONTNAME", (1, 0), (1, -1), "Helvetica"),
                ("FONTSIZE", (0, 0), (-1, -1), 8.5),
                ("TEXTCOLOR", (0, 0), (0, -1), colors.HexColor("#59647A")),
                ("TEXTCOLOR", (1, 0), (1, -1), colors.HexColor("#101A36")),
                ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#F6F8FC")),
                ("BOX", (0, 0), (-1, -1), 0.5, colors.HexColor("#D8DDEA")),
                ("INNERGRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#D8DDEA")),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 7),
                ("RIGHTPADDING", (0, 0), (-1, -1), 7),
                ("TOPPADDING", (0, 0), (-1, -1), 6),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
            ]
        )
    )
    story.extend([table, Paragraph("Resume content", section)])
    for line in sanitized_resume(row):
        if line.endswith(":"):
            story.append(Paragraph(line[:-1], section))
        else:
            story.append(Paragraph(line, body))
    document.build(story, onFirstPage=footer, onLaterPages=footer)
    return path


def main() -> None:
    with SOURCE_CSV.open(encoding="utf-8-sig", newline="") as source:
        rows = list(csv.DictReader(source))

    manifest: dict[str, object] = {
        "dataset": {
            "title": "Resume Dataset for NLP and Recruitment Analytics",
            "kaggle_slug": "souptikghosh/resume-dataset",
            "source_url": "https://www.kaggle.com/datasets/souptikghosh/resume-dataset",
            "license": "MIT",
            "downloaded_file": str(SOURCE_CSV.relative_to(ROOT)),
            "total_rows": len(rows),
        },
        "selection": {
            "mentee_rule": "Years_Experience <= 1",
            "mentor_rule": "Years_Experience >= 7",
            "roles": list(TARGET_ROLES),
            "records_per_role_per_cohort": PER_ROLE,
            "deduplication": "Unique Resume_Text SHA-256 and unique name within each cohort",
            "contact_handling": "Email and phone lines omitted from generated PDFs",
        },
        "cohorts": {},
    }
    cohorts: dict[str, list[dict[str, object]]] = {}
    for cohort in ("mentees", "mentors"):
        cohort_records: list[dict[str, object]] = []
        for row in select_rows(rows, cohort):
            pdf_path = render_pdf(row, cohort)
            cohort_records.append(
                {
                    "source_id": source_id(row),
                    "synthetic_name": row["Name"],
                    "job_role": row["Job_Role"],
                    "years_experience": int(row["Years_Experience"]),
                    "skills": [item.strip() for item in row["Skills"].split(",") if item.strip()],
                    "pdf": str(pdf_path.relative_to(ROOT)),
                }
            )
        cohorts[cohort] = cohort_records
    manifest["cohorts"] = cohorts
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
