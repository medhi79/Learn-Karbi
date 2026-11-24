import argparse
import json
import re
import shutil
import unicodedata
from pathlib import Path
from typing import Any, Dict, List, Tuple


HISTORICAL_META_RE = re.compile(r"\b(neighbor|1878|mikir|walker|1925|archaic|historical)\b", re.I)
HISTORICAL_DIACRITICS_RE = re.compile(r"[áéíóúâêîôûÁÉÍÓÚÂÊÎÔÛ]")
HISTORICAL_CITATION_PO_RE = re.compile(r"(?:\s+|^)(?:po|pó|p6)\s*$", re.I)
HISTORICAL_TOKENS = {"nétum", "netum_1878", "thekire", "p6", "pó"}
NON_STANDARD_DIALECT_RE = re.compile(r"\b(plains|dumra|ajz|amri)\b", re.I)


def strip_historical_accents(text: str) -> str:
    nfd = unicodedata.normalize("NFD", text)
    return "".join(ch for ch in nfd if unicodedata.category(ch) != "Mn")


def clean_neighbor_citation(karbi_text: str) -> str:
    cleaned = HISTORICAL_CITATION_PO_RE.sub("", karbi_text).strip()
    return strip_historical_accents(cleaned)


def extract_karbi_fields(entry: Dict[str, Any]) -> str:
    candidates = [
        entry.get("karbi_current"),
        entry.get("karbi_preferred"),
        entry.get("karbi"),
        entry.get("karbi_word"),
        entry.get("swap_word"),
    ]
    return " ".join(str(c).strip() for c in candidates if c).strip()


def is_neighbor_historical(entry: Dict[str, Any]) -> Tuple[bool, str]:
    if str(entry.get("status", "")).strip().lower() == "historical":
        return True, "Existing historical status"

    meta_blob = " ".join(
        str(entry.get(k, ""))
        for k in ("source", "notes", "note", "category", "origin", "reference", "dialect")
    )
    if HISTORICAL_META_RE.search(meta_blob):
        return True, "Metadata references Neighbor 1878 / historical source"

    karbi_text = extract_karbi_fields(entry)
    if not karbi_text:
        return False, ""

    if HISTORICAL_DIACRITICS_RE.search(karbi_text):
        return True, "Contains 1878 accent diacritics"

    primary_karbi = str(
        entry.get("karbi_current")
        or entry.get("karbi_preferred")
        or entry.get("karbi")
        or entry.get("karbi_word")
        or ""
    ).strip()
    if HISTORICAL_CITATION_PO_RE.search(primary_karbi):
        return True, "Contains 1878 verb citation particle ('Po'/'p6')"

    tokens = {t.lower() for t in re.findall(r"[a-zA-ZÀ-ÿ'-]+", karbi_text)}
    matched_archaic = tokens.intersection(HISTORICAL_TOKENS)
    if matched_archaic:
        return True, f"Contains historical token(s): {', '.join(sorted(matched_archaic))}"

    return False, ""


def tag_entry(entry: Dict[str, Any]) -> str:
    existing_dialect = str(entry.get("dialect", "")).strip()
    if existing_dialect and NON_STANDARD_DIALECT_RE.search(existing_dialect):
        entry["status"] = entry.get("status", "excluded_dialect")
        return "excluded_dialect"

    hist, reason = is_neighbor_historical(entry)
    if hist:
        entry["status"] = "historical"
        entry["dialect"] = "Hills Karbi (Historical)"
        if "source" not in entry or not entry["source"]:
            entry["source"] = "Neighbor 1878 (Historical)"

        primary_karbi = str(
            entry.get("karbi_current")
            or entry.get("karbi_preferred")
            or entry.get("karbi")
            or entry.get("karbi_word")
            or ""
        ).strip()
        if primary_karbi and "karbi_modern_hint" not in entry:
            entry["karbi_modern_hint"] = clean_neighbor_citation(primary_karbi)

        if "historical_reason" not in entry:
            entry["historical_reason"] = reason
        return "historical"

    entry["status"] = "current"
    entry["dialect"] = "Hills Karbi (Standard)"
    return "current_standard"


def process_json_file(file_path: Path, backup: bool = True, dry_run: bool = False) -> Dict[str, int]:
    counts = {"current_standard": 0, "historical": 0, "excluded_dialect": 0}

    with file_path.open("r", encoding="utf-8") as f:
        data = json.load(f)

    target_list: List[Any] | None = None
    if isinstance(data, list):
        target_list = data
    elif isinstance(data, dict):
        for key in ("rules", "items", "vocabulary", "entries", "drills", "lessons"):
            if isinstance(data.get(key), list):
                target_list = data[key]
                break

    if target_list is None:
        print(f"[SKIP] {file_path.name}: No recognized entry list found.")
        return counts

    for item in target_list:
        if not isinstance(item, dict):
            continue
        if isinstance(item.get("sentences"), list):
            for sub in item["sentences"]:
                if isinstance(sub, dict):
                    label = tag_entry(sub)
                    counts[label] += 1
        label = tag_entry(item)
        counts[label] += 1

    if isinstance(data, dict):
        data["default_dialect"] = "Hills Karbi (Standard)"

    if not dry_run:
        if backup:
            backup_path = file_path.with_suffix(file_path.suffix + ".bak")
            if not backup_path.exists():
                shutil.copy2(file_path, backup_path)

        tmp_path = file_path.with_suffix(file_path.suffix + ".tmp")
        with tmp_path.open("w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        tmp_path.replace(file_path)

    return counts


def main():
    parser = argparse.ArgumentParser(
        description="Tag Karbi JSON datasets with 'dialect: Hills Karbi (Standard)' and label 1878 Neighbor entries as 'status: historical'."
    )
    parser.add_argument(
        "target",
        nargs="?",
        default="app/src/main/assets",
        help="Path to a Karbi JSON file or directory (default: app/src/main/assets).",
    )
    parser.add_argument("--no-backup", action="store_true", help="Do not create .json.bak backup files.")
    parser.add_argument("--dry-run", action="store_true", help="Preview counts without modifying files.")
    args = parser.parse_args()

    target_path = Path(args.target)
    json_files = [target_path] if target_path.is_file() else sorted(target_path.glob("*.json"))

    if not json_files:
        print(f"No JSON files found at {target_path}")
        return

    total = {"current_standard": 0, "historical": 0, "excluded_dialect": 0}
    for jf in json_files:
        if jf.name.endswith("_optimized.json") or jf.name == "hills_karbi_prompt_config.json":
            continue
        res = process_json_file(jf, backup=not args.no_backup, dry_run=args.dry_run)
        for k in total:
            total[k] += res[k]
        print(
            f"{'[DRY-RUN] ' if args.dry_run else ''}{jf.name}: "
            f"{res['current_standard']} Hills Karbi (Standard) | "
            f"{res['historical']} Historical (1878) | "
            f"{res['excluded_dialect']} Excluded"
        )

    print("\n--- Summary ---")
    print(f"Total Hills Karbi (Standard) [status: current] : {total['current_standard']}")
    print(f"Total Historical (1878)      [status: historical]: {total['historical']}")
    print(f"Total Excluded Dialects      [Plains/Amri]     : {total['excluded_dialect']}")


if __name__ == "__main__":
    main()
