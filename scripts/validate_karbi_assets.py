import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from karbi_verifier import KarbiVerifier


ASSETS_DIR = Path("app/src/main/assets")
PROMPT_CONFIG_PATH = ASSETS_DIR / "hills_karbi_prompt_config.json"
CORRECTIONS_PATH = ASSETS_DIR / "karbi_corrections.json"

VOCAB_CANDIDATES = [
    ASSETS_DIR / "Karbi_A_Letter_Vocabulary_Final_Handcrafted.json",
    ASSETS_DIR / "Karbi_Full_Vocabulary_Substitution_Drills.json",
    ASSETS_DIR / "karbi_custom_lessons.json",
]


def fail_ci(errors: list[str]) -> None:
    print("\n❌ KARBI ASSET VALIDATION FAILED:")
    for err in errors:
        print(f"  - {err}")
    sys.exit(1)


def main() -> None:
    errors: list[str] = []

    for req_file in (PROMPT_CONFIG_PATH, CORRECTIONS_PATH):
        if not req_file.exists():
            errors.append(f"Missing required asset file: {req_file}")
    if errors:
        fail_ci(errors)

    try:
        prompt_config = json.loads(PROMPT_CONFIG_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        fail_ci([f"Invalid JSON in {PROMPT_CONFIG_PATH}: {e}"])

    try:
        corrections_store = json.loads(CORRECTIONS_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        fail_ci([f"Invalid JSON in {CORRECTIONS_PATH}: {e}"])

    existing_vocabs = [str(p) for p in VOCAB_CANDIDATES if p.exists()]
    verifier = KarbiVerifier(
        corrections_path=str(CORRECTIONS_PATH),
        vocab_paths=existing_vocabs,
    )

    if prompt_config.get("dialect") != "Hills Karbi (Standard)":
        errors.append(
            f"{PROMPT_CONFIG_PATH.name}: top-level 'dialect' must be 'Hills Karbi (Standard)', got '{prompt_config.get('dialect')}'"
        )

    if not str(prompt_config.get("system_instruction", "")).strip():
        errors.append(f"{PROMPT_CONFIG_PATH.name}: 'system_instruction' is empty.")

    demos = prompt_config.get("few_shot_demos", [])
    if not isinstance(demos, list):
        errors.append(f"{PROMPT_CONFIG_PATH.name}: 'few_shot_demos' must be a list.")
    else:
        for idx, demo in enumerate(demos, start=1):
            en = str(demo.get("english", "")).strip()
            kb = str(demo.get("karbi", "")).strip()
            asm = str(demo.get("assamese", "")).strip()
            dialect = str(demo.get("dialect", "Hills Karbi (Standard)")).strip()
            status = str(demo.get("status", "current")).strip().lower()

            if status != "current":
                errors.append(f"{PROMPT_CONFIG_PATH.name} demo #{idx} ('{en}'): status must be 'current', got '{status}'.")
            if not en or not kb:
                errors.append(f"{PROMPT_CONFIG_PATH.name} demo #{idx}: missing 'english' or 'karbi' text.")
                continue

            ok, issues = verifier.verify_pair(english=en, karbi=kb, assamese=asm, dialect=dialect)
            if not ok:
                for issue in issues:
                    errors.append(f"{PROMPT_CONFIG_PATH.name} demo #{idx} ('{en}' -> '{kb}'): {issue}")

    rules = corrections_store.get("rules", [])
    if not isinstance(rules, list):
        errors.append(f"{CORRECTIONS_PATH.name}: 'rules' must be a list.")
    else:
        seen_sources = set()
        for rule in rules:
            rid = rule.get("id", "<missing-id>")
            src = str(rule.get("source_text", "")).strip()
            kb = str(rule.get("karbi_preferred", "")).strip()
            asm = str(rule.get("assamese", "")).strip()
            dialect = str(rule.get("dialect", "Hills Karbi (Standard)")).strip()
            status = str(rule.get("status", "current")).strip().lower()

            if not src or not kb:
                errors.append(f"{CORRECTIONS_PATH.name} [{rid}]: missing 'source_text' or 'karbi_preferred'.")
                continue

            if src.lower() in seen_sources:
                errors.append(f"{CORRECTIONS_PATH.name} [{rid}]: duplicate source_text '{src}'.")
            seen_sources.add(src.lower())

            if status != "current":
                errors.append(f"{CORRECTIONS_PATH.name} [{rid}]: active correction must have status 'current', got '{status}'.")

            ok, issues = verifier.verify_pair(english=src, karbi=kb, assamese=asm, dialect=dialect)
            if not ok:
                for issue in issues:
                    errors.append(f"{CORRECTIONS_PATH.name} [{rid}] ('{src}' -> '{kb}'): {issue}")

    if errors:
        fail_ci(errors)

    print(
        f"✅ KarbiVerifier passed: {len(demos)} few-shot demos and {len(rules)} correction rules "
        f"verified for Hills Karbi (Standard)."
    )


if __name__ == "__main__":
    main()
