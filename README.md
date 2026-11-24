# Talk Aurelia • Hills Karbi (Standard) Translation App

Offline trilingual (**English – Assamese – Hills Karbi Standard**) Android application built with **Kotlin**, **Jetpack Compose**, **LiteRT-LM**, and **DSPy + KarbiVerifier**.

---

## About the App
**Talk Aurelia** is an offline-first language learning and translation app designed for **Standard Hills Karbi (Karbi Lammet Amei / Rongkhang-Diphu literary standard)** alongside **Assamese** and **English**. 

Whether you are a student, teacher, or everyday learner, the app works completely offline on Android devices without needing an internet connection or cloud translation APIs.

### Key Features
* **100% Offline Trilingual Support:** Translate everyday phrases and sentences across **English**, **Assamese (অসমীয়া)**, and **Standard Hills Karbi** in Latin script.
* **Accurate Hills Karbi Grammar:** Built around authentic Karbi **Subject–Object–Verb (SOV)** sentence structure, nominal prefixes (`a-`, `ke-`), plurals (`-tum`), and verbal aspect markers (`-lo`, `-bom`, `-ji`, `-po`).
* **Modern Standard Focus:** Uses everyday modern Hills Karbi (such as *Chorolo* for everyday "thank you" and *Kalang* for "yes"), keeping 19th-century historical spellings and non-standard regional variants separated.
* **Live In-App Corrections:** Tap **Edit** on any translation card to fix or refine a phrase—your correction is saved immediately on your phone and overrides future translations automatically.
* **Backup & Share Rules:** Export or import your saved `karbi_corrections.json` vocabulary rules anytime from the Settings menu.

---

## How to Download & Install on Android
You do not need Android Studio to try the app—GitHub builds the APK automatically:
1. Open the **Actions** tab at the top of this repository.
2. Click on the latest workflow run with a green checkmark (**Validate Karbi Assets & Build Talk Aurelia APK**).
3. Scroll down to **Artifacts** and download **`Talk-Aurelia-Hills-Karbi-Debug-APK`**.
4. Extract the downloaded `.zip` file on your Android phone and tap **`app-debug.apk`** to install (allow *"Install from unknown sources"* if prompted).

---

## Direct GitHub APK Build
Every push to `main` automatically:
1. Runs `python scripts/validate_karbi_assets.py` to enforce **Hills Karbi (Standard / Rongkhang)** orthography, SOV word order, and exclude historical 1878/Plains/Amri variants.
2. Compiles the Android Debug APK via Gradle 8.7 and uploads `Talk-Aurelia-Hills-Karbi-Debug-APK` under **Actions -> Artifacts**.
