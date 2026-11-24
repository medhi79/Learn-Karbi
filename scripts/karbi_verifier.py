import json
import re
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple


def normalize(text: str) -> str:
    text = text.strip().lower()
    text = re.sub(r"[?.!,;:]+$", "", text)
    return re.sub(r"\s+", " ", text)


class KarbiVerifier:
    """
    Validates Karbi sentences strictly against Hills Karbi (Standard / Rongkhang-Diphu):
      1. Enforces Hills Karbi (Standard) dialect tags and excludes Plains (Dumra) / Amri / Historical entries
      2. Enforces karbi_corrections.json rules (e.g., Chorolo over Kardom for everyday thanks)
      3. Validates root lexicon + Hills Karbi nominal/verbal affixes (a-, ke-, -tum, -lo, -po, -ji, -bom, -pe/-the)
      4. Validates SOV clause-final verb placement, polarity, and standard 5-vowel KLA orthography
    """

    ALLOWED_DIALECTS = {
        "",
        "hills karbi (standard)",
        "hills karbi",
        "rongkhang",
        "standard",
    }

    EXCLUDED_DIALECTS = {
        "plains karbi",
        "dumra",
        "dumra karbi",
        "ajz",
        "amri",
        "hills amri",
        "historical",
        "hills karbi (historical)",
    }

    NON_STANDARD_VARIANTS: Dict[str, str] = {
        "nétum": "itum / netum",
        "p6": "(strip 1878 citation particle 'po')",
        "pó": "(strip 1878 citation particle 'po')",
        "thekire": "daktor (thekire is historical/traditional healer)",
    }

    HISTORICAL_DIACRITICS = re.compile(r"[áéíóúâêîôûÁÉÍÓÚÂÊÎÔÛ]")

    NOMINAL_PREFIXES = ("a-", "ke-", "ne-", "nang-", "a", "ke", "ne", "nang")
    NOMINAL_SUFFIXES = ("-tum", "-mar", "-huy", "-phan", "-si", "-le", "tum", "mar", "huy", "phan")

    VERBAL_PREFIXES = ("ke-", "pe-", "pa-", "che-", "cho-", "ke", "pe", "pa", "che", "cho")
    VERBAL_SUFFIXES = (
        "-detlo", "-bomlo", "-jilo", "-pelo", "-thelo",
        "-lo", "-det", "-ji", "-po", "-bom", "-pe", "-the", "-pin", "-noi", "-non", "-tha", "-ri",
        "detlo", "bomlo", "jilo", "pelo", "thelo",
        "lo", "det", "ji", "po", "bom", "pe", "the", "pin", "noi", "non", "tha", "ri"
    )

    CLAUSE_PARTICLES = {"ma", "la", "ne", "mo", "ba", "te", "si", "le", "ke", "ta"}

    EN_NEG_CUES = re.compile(r"\b(not|n't|never|no|cannot|can't|won't|don't|doesn't|didn't)\b", re.I)
    KARBI_NEG_SUFFIXES = ("-pe", "-the", "-pin", "-ri", "pe", "the", "pin", "ri")
    KARBI_NEG_WORDS = {"kali", "ave", "thekthe", "un-eh"}

    def __init__(self, corrections_path: str, vocab_paths: List[str]):
        self.rules = self._load_corrections(corrections_path)

        self.all_roots: Set[str] = set()
        self.noun_roots: Set[str] = set()
        self.verb_roots: Set[str] = set()

        self._load_lexicons(vocab_paths)

    def _is_standard_hills_karbi(self, entry: dict) -> bool:
        status = str(entry.get("status", "current")).strip().lower()
        if status == "historical":
            return False

        dialect = str(entry.get("dialect", "")).strip().lower()
        if dialect in self.EXCLUDED_DIALECTS:
            return False
        if any(bad in dialect for bad in ("plains", "dumra", "amri", "historical", "ajz")):
            return False

        return dialect in self.ALLOWED_DIALECTS or "hills karbi" in dialect

    def _load_corrections(self, path: str) -> List[dict]:
        p = Path(path)
        if not p.exists():
            return []
        with p.open("r", encoding="utf-8") as f:
            raw_rules = json.load(f).get("rules", [])
        return [
            r for r in raw_rules
            if r.get("verified", True) and self._is_standard_hills_karbi(r)
        ]

    def _load_lexicons(self, vocab_paths: List[str]) -> None:
        for vp in vocab_paths:
            p = Path(vp)
            if not p.exists():
                continue
            with p.open("r", encoding="utf-8") as f:
                data = json.load(f)

            items = data if isinstance(data, list) else data.get("items", data.get("vocabulary", []))
            for item in items:
                if not isinstance(item, dict) or not self._is_standard_hills_karbi(item):
                    continue

                karbi_text = (
                    item.get("karbi_current")
                    or item.get("karbi")
                    or item.get("karbi_word")
                    or ""
                )
                pos = (item.get("pos") or item.get("part_of_speech") or "").strip().lower()

                tokens = re.findall(r"[a-zA-Z'-]+", karbi_text.lower())
                for tok in tokens:
                    self.all_roots.add(tok)
                    if "verb" in pos or pos == "v":
                        self.verb_roots.add(tok)
                    elif "noun" in pos or pos == "n":
                        self.noun_roots.add(tok)

        for r in self.rules:
            for tok in re.findall(r"[a-zA-Z'-]+", r["karbi_preferred"].lower()):
                self.all_roots.add(tok)

    def check_hills_standard_orthography(self, karbi_sentence: str) -> List[str]:
        issues: List[str] = []

        if self.HISTORICAL_DIACRITICS.search(karbi_sentence):
            issues.append(
                "Dialect/Orthography violation: contains 1878 historical accent diacritics; use standard Hills Karbi KLA spelling."
            )

        tokens = re.findall(r"[a-zA-ZÀ-ÿ'-]+", karbi_sentence.lower())
        for tok in tokens:
            if tok in self.NON_STANDARD_VARIANTS:
                issues.append(
                    f"Non-standard/historical variant '{tok}' detected; use Hills Karbi (Standard): {self.NON_STANDARD_VARIANTS[tok]}."
                )
            elif tok.endswith("ei") and tok not in self.all_roots and len(tok) >= 3:
                issues.append(
                    f"Possible Hills Amri dialect spelling '{tok}' ('-ei' diphthong); verify Standard Hills Karbi form."
                )

        return issues

    def analyze_token(self, token: str) -> Dict[str, object]:
        clean = token.lower().strip(".,!?;:\"'()[]")
        if not clean:
            return {"valid": True, "root": "", "pos_guess": "punct", "affixes": []}

        if clean in self.all_roots:
            pos_guess = "verb" if clean in self.verb_roots else ("noun" if clean in self.noun_roots else "unknown")
            return {"valid": True, "root": clean, "pos_guess": pos_guess, "affixes": []}

        for pre in ("",) + self.NOMINAL_PREFIXES:
            if pre and not clean.startswith(pre):
                continue
            rem = clean[len(pre):]
            for suf in ("",) + self.NOMINAL_SUFFIXES:
                if suf and not rem.endswith(suf):
                    continue
                stem = rem[:len(rem) - len(suf)] if suf else rem
                if len(stem) >= 2 and (stem in self.noun_roots or (stem in self.all_roots and stem not in self.verb_roots)):
                    return {"valid": True, "root": stem, "pos_guess": "noun", "affixes": [x for x in (pre, suf) if x]}

        for pre in ("",) + self.VERBAL_PREFIXES:
            if pre and not clean.startswith(pre):
                continue
            rem = clean[len(pre):]
            for suf in ("",) + self.VERBAL_SUFFIXES:
                if suf and not rem.endswith(suf):
                    continue
                stem = rem[:len(rem) - len(suf)] if suf else rem
                if len(stem) >= 2 and (stem in self.verb_roots or (stem in self.all_roots and stem not in self.noun_roots)):
                    return {"valid": True, "root": stem, "pos_guess": "verb", "affixes": [x for x in (pre, suf) if x]}

        return {"valid": False, "root": clean, "pos_guess": "unknown", "affixes": []}

    def check_sov_verb_placement(self, karbi_sentence: str) -> Tuple[bool, Optional[str]]:
        clauses = [c.strip() for c in re.split(r"[.!?;]+|\b(?:ason|bon|tangte)\b", karbi_sentence) if c.strip()]

        for clause in clauses:
            tokens = re.findall(r"[a-zA-Z'-]+", clause)
            if len(tokens) < 2:
                continue

            idx = len(tokens) - 1
            while idx > 0 and tokens[idx].lower() in self.CLAUSE_PARTICLES:
                idx -= 1

            final_word = tokens[idx]
            analysis = self.analyze_token(final_word)

            has_verbal_suffix = any(
                aff.lstrip("-") in {s.lstrip("-") for s in self.VERBAL_SUFFIXES}
                for aff in analysis["affixes"]
            )
            is_known_verb = (analysis["pos_guess"] == "verb") or has_verbal_suffix

            if not is_known_verb and analysis["pos_guess"] == "noun":
                return False, f"SOV violation in clause '{clause}': ends on noun '{final_word}' instead of a verb."

            if not is_known_verb and self.verb_roots:
                return False, f"SOV warning in clause '{clause}': final token '{final_word}' is not a recognized verb."

        return True, None

    def verify_pair(
        self,
        english: str,
        karbi: str,
        assamese: str = "",
        dialect: str = "Hills Karbi (Standard)",
    ) -> Tuple[bool, List[str]]:
        issues: List[str] = []

        if not self._is_standard_hills_karbi({"dialect": dialect, "status": "current"}):
            issues.append(f"Rejected non-standard dialect '{dialect}': only Hills Karbi (Standard) is permitted.")

        issues.extend(self.check_hills_standard_orthography(karbi))

        norm_en = normalize(english)
        for r in self.rules:
            src = normalize(r["source_text"])
            if r["match_type"] == "exact_phrase" and norm_en == src:
                if normalize(karbi) != normalize(r["karbi_preferred"]):
                    issues.append(f"[{r['id']}] Expected Hills Karbi '{r['karbi_preferred']}', got '{karbi}'")
            elif src in norm_en:
                for bad in r.get("karbi_avoid", []):
                    if re.search(rf"\b{re.escape(bad)}\b", karbi, re.IGNORECASE):
                        issues.append(f"[{r['id']}] Forbidden term '{bad}' used instead of '{r['karbi_preferred']}'")

        if self.all_roots:
            tokens = re.findall(r"[a-zA-Z'-]+", karbi)
            for tok in tokens:
                if tok.lower() in self.CLAUSE_PARTICLES:
                    continue
                res = self.analyze_token(tok)
                if not res["valid"]:
                    issues.append(f"Unverified token not in Hills Karbi (Standard) lexicon: '{tok}'")

        sov_ok, sov_msg = self.check_sov_verb_placement(karbi)
        if not sov_ok and sov_msg:
            issues.append(sov_msg)

        if self.EN_NEG_CUES.search(english):
            karbi_lower = karbi.lower()
            has_karbi_neg = (
                any(w in self.KARBI_NEG_WORDS for w in re.findall(r"[a-zA-Z'-]+", karbi_lower))
                or any(tok.endswith(self.KARBI_NEG_SUFFIXES) for tok in re.findall(r"[a-zA-Z'-]+", karbi_lower))
            )
            if not has_karbi_neg:
                issues.append("Polarity mismatch: English prompt is negative, but Hills Karbi lacks negation (-pe/-the/kali/ave).")

        return (len(issues) == 0), issues
