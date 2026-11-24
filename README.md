# Talk Aurelia • Hills Karbi (Standard) Translation App

Offline trilingual (**English – Assamese – Hills Karbi Standard**) Android application built with **Kotlin**, **Jetpack Compose**, **LiteRT-LM**, and **DSPy + KarbiVerifier**.

## Direct GitHub APK Build
Every push to `main` automatically:
1. Runs `python scripts/validate_karbi_assets.py` to enforce **Hills Karbi (Standard / Rongkhang)** orthography, SOV word order, and exclude historical 1878/Plains/Amri variants.
2. Compiles the Android Debug APK via Gradle 8.7 and uploads `Talk-Aurelia-Hills-Karbi-Debug-APK` under **Actions -> Artifacts**.
