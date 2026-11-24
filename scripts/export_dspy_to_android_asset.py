import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List


DEFAULT_SYSTEM_INSTRUCTION = (
    "You are Talk Aurelia's trilingual Karbi-Assamese-English assistant. "
    "Translate strictly into Standard Hills Karbi (Karbi Lammet Amei / Diphu-Rongkhang standard) "
    "using modern diacritic-free Latin script and strict Subject-Object-Verb (SOV) word order, "
    "alongside natural Assamese in Assamese script. "
    "Strictly exclude Plains Karbi (Dumra), Hills Amri variants, and 19th-century archaic forms."
)


def extract_compiled_state(compiled_data: Dict[str, Any]) -> tuple[str, List[Dict[str, str]]]:
    instructions = ""
    raw_demos: List[Dict[str, Any]] = []

    predictor_state: Dict[str, Any] = {}
    for candidate_key in ("translate_cot.predict", "translate_cot", "predict"):
        if isinstance(compiled_data.get(candidate_key), dict):
            predictor_state = compiled_data[candidate_key]
            break

    if not predictor_state:
        for val in compiled_data.values():
            if isinstance(val, dict) and ("signature" in val or "demos" in val):
                predictor_state = val
                break

    sig = predictor_state.get("signature", {})
    if isinstance(sig, dict):
        instructions = str(sig.get("instructions") or "").strip()
    if not instructions:
        instructions = str(predictor_state.get("instructions") or DEFAULT_SYSTEM_INSTRUCTION).strip()

    raw_demos = predictor_state.get("demos", []) if isinstance(predictor_state.get("demos"), list) else []
    cleaned_demos: List[Dict[str, str]] = []
    seen_en = set()

    for idx, demo in enumerate(raw_demos, start=1):
        if not isinstance(demo, dict):
            continue
        en = str(demo.get("english") or "").strip()
        kb = str(demo.get("karbi") or "").strip()
        asm = str(demo.get("assamese") or "").strip()
        rationale = str(demo.get("rationale") or demo.get("reasoning") or "").strip()

        if not en or not kb:
            continue
        norm_key = en.lower()
        if norm_key in seen_en:
            continue
        seen_en.add(norm_key)

        entry: Dict[str, str] = {
            "id": f"demo_{idx:02d}",
            "english": en,
            "karbi": kb,
            "assamese": asm,
            "dialect": "Hills Karbi (Standard)",
            "status": "current",
        }
        if rationale:
            entry["sov_note"] = rationale
        cleaned_demos.append(entry)

    return instructions, cleaned_demos


def export_android_asset(
    input_json: Path,
    output_asset_json: Path,
    max_demos: int = 6,
) -> Dict[str, Any]:
    with input_json.open("r", encoding="utf-8") as f:
        compiled_data = json.load(f)

    instructions, demos = extract_compiled_state(compiled_data)
    selected_demos = demos[:max_demos]

    few_shot_lines = []
    for d in selected_demos:
        line = f"English: {d['english']} -> Hills Karbi (Standard): {d['karbi']}"
        if d["assamese"]:
            line += f" | Assamese: {d['assamese']}"
        few_shot_lines.append(line)

    android_asset = {
        "schema_version": "1.0",
        "app": "Talk Aurelia",
        "dialect": "Hills Karbi (Standard)",
        "script": "Latin (KLA Standard) + Assamese",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "system_instruction": instructions,
        "few_shot_demos": selected_demos,
        "litert_prompt_prefix": (
            f"{instructions}\n\n"
            f"Verified Hills Karbi (Standard) SOV Examples:\n"
            + ("\n".join(few_shot_lines) if few_shot_lines else "None.")
        ),
    }

    output_asset_json.parent.mkdir(parents=True, exist_ok=True)
    tmp_file = output_asset_json.with_suffix(output_asset_json.suffix + ".tmp")
    with tmp_file.open("w", encoding="utf-8") as f:
        json.dump(android_asset, f, ensure_ascii=False, indent=2)
    tmp_file.replace(output_asset_json)

    return android_asset


def main():
    parser = argparse.ArgumentParser(
        description="Export compiled DSPy Hills Karbi instructions and few-shot demos into an Android asset JSON."
    )
    parser.add_argument(
        "--input",
        default="compiled_karbi_dspy/hills_karbi_standard_optimized.json",
        help="Path to DSPy compiled JSON file.",
    )
    parser.add_argument(
        "--output",
        default="app/src/main/assets/hills_karbi_prompt_config.json",
        help="Destination path for Talk Aurelia Android asset JSON.",
    )
    parser.add_argument(
        "--max-demos",
        type=int,
        default=6,
        help="Maximum number of few-shot demos to bundle.",
    )
    args = parser.parse_args()

    in_path = Path(args.input)
    if not in_path.exists():
        raise FileNotFoundError(f"Compiled DSPy file not found: {in_path}")

    asset = export_android_asset(in_path, Path(args.output), max_demos=args.max_demos)
    print(f"Exported Android asset JSON -> {args.output}")
    print(f"  Dialect         : {asset['dialect']}")
    print(f"  Few-shot demos  : {len(asset['few_shot_demos'])}")
    print(f"  Instruction len : {len(asset['system_instruction'])} chars")


if __name__ == "__main__":
    main()
