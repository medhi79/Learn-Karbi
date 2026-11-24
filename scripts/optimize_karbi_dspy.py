import json
import os
import random
import sys
from pathlib import Path
from typing import List

import dspy
from sacrebleu.metrics import CHRF

sys.path.insert(0, str(Path(__file__).resolve().parent))
from karbi_verifier import KarbiVerifier, normalize


class HillsKarbiTranslationSignature(dspy.Signature):
    """
    Translate English into Standard Hills Karbi (Karbi Lammet Amei / Diphu-Rongkhang standard,
    strict SOV word order, modern diacritic-free Latin orthography) and Assamese.
    Strictly exclude Plains Karbi (Dumra), Hills Amri variants, and 19th-century archaic forms.
    """

    english: str = dspy.InputField(desc="English phrase or sentence to translate")
    glossary_hints: str = dspy.InputField(
        desc="Mandatory verified Hills Karbi (Standard) and Assamese vocabulary rules"
    )
    karbi: str = dspy.OutputField(
        desc="Standard Hills Karbi (Rongkhang literary standard) translation in strict SOV order"
    )
    assamese: str = dspy.OutputField(
        desc="Natural Assamese translation in Assamese script matching the English meaning"
    )


class HillsKarbiTranslatorModule(dspy.Module):
    def __init__(self, verifier: KarbiVerifier):
        super().__init__()
        self.verifier = verifier
        self.translate_cot = dspy.ChainOfThought(HillsKarbiTranslationSignature)

    def _build_hints(self, english: str) -> str:
        norm_en = normalize(english)
        matched = []
        for r in self.verifier.rules:
            if normalize(r["source_text"]) in norm_en:
                avoid = f" [NEVER use: {', '.join(r['karbi_avoid'])}]" if r.get("karbi_avoid") else ""
                matched.append(
                    f"'{r['source_text']}' -> Hills Karbi (Standard): '{r['karbi_preferred']}'{avoid}, Assamese: '{r['assamese']}'"
                )
        base_rule = "Dialect: Hills Karbi (Standard) ONLY. Use strict SOV order and modern KLA spelling."
        return f"{base_rule} Rules: {'; '.join(matched)}" if matched else base_rule

    def forward(self, english: str) -> dspy.Prediction:
        hints = self._build_hints(english)
        pred = self.translate_cot(english=english, glossary_hints=hints)
        return dspy.Prediction(karbi=pred.karbi.strip(), assamese=pred.assamese.strip())


def load_hills_karbi_dataset(
    verifier: KarbiVerifier,
    vocab_paths: List[str],
    seed: int = 42,
) -> tuple[List[dspy.Example], List[dspy.Example]]:
    examples: List[dspy.Example] = []
    seen_en = set()

    for r in verifier.rules:
        en = r["source_text"].strip()
        kb = r["karbi_preferred"].strip()
        asm = r.get("assamese", "").strip()
        if en and kb and normalize(en) not in seen_en:
            seen_en.add(normalize(en))
            examples.append(
                dspy.Example(
                    english=en,
                    karbi=kb,
                    assamese=asm,
                    dialect="Hills Karbi (Standard)",
                ).with_inputs("english")
            )

    for vp in vocab_paths:
        p = Path(vp)
        if not p.exists():
            continue
        with p.open("r", encoding="utf-8") as f:
            data = json.load(f)
        items = data if isinstance(data, list) else data.get("items", data.get("vocabulary", []))

        for item in items:
            if not isinstance(item, dict) or not verifier._is_standard_hills_karbi(item):
                continue

            en = (item.get("english") or item.get("en") or "").strip()
            kb = (item.get("karbi_current") or item.get("karbi") or item.get("karbi_word") or "").strip()
            asm = (item.get("assamese") or item.get("as") or "").strip()

            if en and kb and normalize(en) not in seen_en:
                if verifier.check_hills_standard_orthography(kb):
                    continue
                seen_en.add(normalize(en))
                examples.append(
                    dspy.Example(
                        english=en,
                        karbi=kb,
                        assamese=asm,
                        dialect="Hills Karbi (Standard)",
                    ).with_inputs("english")
                )

    random.Random(seed).shuffle(examples)
    split_idx = max(1, int(len(examples) * 0.8))
    return examples[:split_idx], examples[split_idx:]


def build_hills_karbi_metric(verifier: KarbiVerifier):
    chrf = CHRF(word_order=2)

    def hills_karbi_metric(example: dspy.Example, pred: dspy.Prediction, trace=None) -> float | bool:
        pred_karbi = (pred.karbi or "").strip()
        if not pred_karbi:
            return False if trace is not None else 0.0

        is_valid, _issues = verifier.verify_pair(
            english=example.english,
            karbi=pred_karbi,
            assamese=getattr(pred, "assamese", ""),
            dialect="Hills Karbi (Standard)",
        )

        sim_score = chrf.sentence_score(pred_karbi, [example.karbi]).score / 100.0

        if trace is not None:
            return bool(is_valid and sim_score >= 0.70)

        rule_bonus = 0.50 if is_valid else 0.0
        return rule_bonus + (0.50 * sim_score)

    return hills_karbi_metric


def main():
    assets_dir = Path("app/src/main/assets")
    corrections_file = str(assets_dir / "karbi_corrections.json")
    vocab_files = [
        str(assets_dir / "Karbi_A_Letter_Vocabulary_Final_Handcrafted.json"),
        str(assets_dir / "Karbi_Full_Vocabulary_Substitution_Drills.json"),
        str(assets_dir / "karbi_custom_lessons.json"),
    ]

    student_lm = dspy.LM(
        "ollama_chat/gemma4:e2b",
        api_base="http://localhost:11434",
        api_key="",
        temperature=0.2,
    )

    teacher_lm = (
        dspy.LM("gemini/gemini-2.5-pro", api_key=os.environ["GEMINI_API_KEY"])
        if os.environ.get("GEMINI_API_KEY")
        else student_lm
    )

    dspy.configure(lm=student_lm)

    verifier = KarbiVerifier(corrections_path=corrections_file, vocab_paths=vocab_files)
    trainset, valset = load_hills_karbi_dataset(verifier, vocab_files)
    print(f"Loaded {len(trainset)} train and {len(valset)} val examples [Hills Karbi (Standard) ONLY].")

    metric_fn = build_hills_karbi_metric(verifier)
    program = HillsKarbiTranslatorModule(verifier=verifier)

    if len(trainset) < 50:
        optimizer = dspy.BootstrapFewShot(
            metric=metric_fn,
            max_bootstrapped_demos=4,
            max_labeled_demos=6,
            max_rounds=2,
        )
        compiled_program = optimizer.compile(program, trainset=trainset)
    else:
        optimizer = dspy.MIPROv2(
            metric=metric_fn,
            prompt_model=teacher_lm,
            task_model=student_lm,
            auto="light",
            num_threads=2,
        )
        compiled_program = optimizer.compile(
            program,
            trainset=trainset,
            valset=valset,
            max_bootstrapped_demos=4,
            max_labeled_demos=4,
        )

    out_dir = Path("compiled_karbi_dspy")
    out_dir.mkdir(exist_ok=True)
    compiled_path = out_dir / "hills_karbi_standard_optimized.json"
    compiled_program.save(str(compiled_path), save_program=False)
    print(f"Saved Hills Karbi (Standard) optimized prompt state to {compiled_path}")


if __name__ == "__main__":
    main()
