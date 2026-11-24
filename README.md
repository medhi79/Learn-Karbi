# Talk Aurelia • Hills Karbi (Standard) Translation App

Offline trilingual (**English – Assamese – Hills Karbi Standard**) Android application built with **Kotlin**, **Jetpack Compose**, **LiteRT-LM**, and **DSPy + KarbiVerifier**.

---

## About the App
**Talk Aurelia** is a native offline-first language learning and translation app designed for **Standard Hills Karbi (Karbi Lammet Amei / Rongkhang-Diphu literary standard)** alongside **Assamese** and **English**.

### Key Features
* **Native Android UI (Kotlin + Jetpack Compose):** Four dedicated screens—**Translate**, **Lexicon**, **History**, and **AI & Rules**—with no WebView/HTML dependency.
* **Accurate Hills Karbi Grammar:** Enforces Karbi **Subject–Object–Verb (SOV)** word order, nominal prefixes (`a-`, `ke-`), plurals (`-tum`), and verbal aspect markers (`-lo`, `-bom`, `-ji`, `-po`).
* **On-Device Generative AI (`LiteRT-LM`):** Supports importing `gemma-4-E2B-it.litertlm` directly from phone storage (`filesDir/models/`) for offline sentence generation.
* **Live In-App Corrections & SAF Export:** Tap **Edit** on any translation card to save verified overrides immediately on-device and export `karbi_corrections.json` anytime.

---

## Direct GitHub APK Build
Every push to `main` automatically:
1. Runs `python scripts/validate_karbi_assets.py` to enforce **Hills Karbi (Standard / Rongkhang)** orthography, SOV word order, and exclude historical 1878/Plains/Amri variants.
2. Compiles the Android Debug APK via Gradle 8.7 and uploads `Talk-Aurelia-Hills-Karbi-Debug-APK` under **Actions -> Artifacts**.
