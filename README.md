# MindVault (TherapyLocal) — Private, Verifiable Memory for Therapists

> **Privacy-First · 100% On-Device · Real-Time Longitudinal Memory for Android**
> Open-Source License: [MIT](LICENSE) | Repository: [github.com/AnnaMarieM/Therapy_local](https://github.com/AnnaMarieM/Therapy_local)

---

## ⚕️ Medical & Clinical Disclaimer

**MindVault is a clinical memory and transcription retrieval tool, NOT a diagnostic medical device.**  
It does not diagnose mental health conditions, prescribe treatments, or provide clinical advice. All clinical decisions, interpretations, and interventions remain solely the ethical and professional responsibility of the licensed therapist.

---

## 1. What It Is and Who It Is For

**MindVault** is an offline-first Android application designed for licensed psychotherapists and counselors who conduct 5–8 emotionally intensive sessions daily.

### The Problem
Patients often circle back to core emotional themes weeks or months apart (e.g. connecting a workplace conflict in November to a childhood trauma disclosed in May). Reviewing handwritten binders or manual notes between sessions is slow and fragmented. Crucially, therapists **cannot** upload confidential session audio or notes to cloud AI APIs (OpenAI, Anthropic, cloud databases) due to strict doctor-patient confidentiality, ethics, and GDPR/HIPAA regulations.

### The Solution
MindVault operates **100% locally on the therapist's phone**:
- **Continuous Local Audio Processing:** Records and transcribes audio directly on-device using Whisper / Gemma ASR.
- **Overlapping Sliding Chunks:** Splits audio into 20-second windows with 5-second overlap to prevent losing thoughts spoken across boundaries.
- **Persistent Semantic Memory:** Embeds text and audio chunks via Google EmbeddingGemma, indexing them into local storage.
- **Verifiable Memory Recall:** During or before sessions, searching a topic surfaces the exact past transcript excerpt with date, recording ID, and time range—ensuring zero AI hallucinations.

---

## 2. How to Run It

### Option A: Android App (On-Device Inference)

#### Prerequisites
- Android device (Android 14+, e.g. Google Pixel 8) with USB/Wireless debugging enabled.
- Android SDK installed (`platforms;android-34`, `build-tools;35.0.0`).
- Java 17+ / Kotlin.

#### Build and Install
1. **Clone the repository:**
   ```bash
   git clone https://github.com/AnnaMarieM/Therapy_local.git
   cd Therapy_local
   ```
2. **Configure SDK Path:**
   Create `local.properties` in the project root:
   ```properties
   sdk.dir=/path/to/your/Android/Sdk
   # Example Windows: sdk.dir=C\:\\Users\\<username>\\AppData\\Local\\Android\\Sdk
   ```
3. **Build the Debug APK:**
   ```bash
   ./gradlew :app:assembleDebug
   ```
4. **Install on Phone via ADB:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.therapylocal.app/.MainActivity
   ```
5. **Initial Model Download:**
   On first launch, the app downloads model weights (~645 MB default) over Wi-Fi directly to the app's isolated storage (`/sdcard/Android/data/com.therapylocal.app/files/models/`). After this initial download, the app functions **completely offline**.

### Option B: Interactive Web Prototype

For rapid review of the full clinical workflow, patient management, and session UI:
```bash
cd pixel-perfect-replay-6272-main
npm install
npm run dev
```
Open [http://localhost:8080](http://localhost:8080) in your browser.

---

## 3. Expertise: What Knowledge We Built In and Where It Comes From

The raw foundation models (Whisper, Gemma) only know how to turn audio into text or output vectors. They do **not** know how to structure clinical therapy continuity.

Our software layer introduces domain-specific architecture:
1. **20s / 5s Overlapping Audio Chunker (`AppConfig.kt`):** Therapy speech contains hesitant pauses, sighs, and sentence restarts. Standard fixed chunkers cut sentences in half. Our overlap pipeline stitches boundaries together cleanly.
2. **Deterministic Transcript Cleaning & Merging (`TranscriptClean.kt`, `TranscriptMerge.kt`):** Strips hallucinated repetitions and merges overlapping phonetic boundaries without calling an LLM.
3. **Grounded Source Attribution (`Search.kt`):** Every surfaced memory is strictly pinned to its recording timestamp (e.g. `Session 1 — Oct 12, 14:20-14:40`). The therapist is shown the **exact verbatim words** said by the patient, preventing model drift or false clinical suggestions.
4. **Zero-Ideation Guardrails:** Small on-device models must not diagnose or hypothesize. MindVault restricts LLM inference to indexing and verbatim similarity retrieval. The clinical intellect remains 100% human.
5. **Privacy Initials Formatter:** Protects patient dignity by automatically formatting stored records as `First L.` (e.g., *George M.*) rather than full surnames.

---

## 4. The Models and Licenses

MindVault utilizes open-weight, edge-optimized models licensed for permissive research and commercial use:

| Model | Purpose | Developer / Source | License & Terms |
|---|---|---|---|
| **Whisper (via sherpa-onnx)** | Real-time on-device speech-to-text | OpenAI / k2-fsa | [Apache 2.0](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE) / [MIT](https://github.com/openai/whisper/blob/main/LICENSE) |
| **EmbeddingGemma 2 (740M)** | On-device text and audio semantic embeddings | Google | [Gemma Terms of Use](https://ai.google.dev/gemma/terms) / Apache 2.0 |
| **Gemma 4 E2B / LiteRT-LM** | On-device local language compression and transcript extraction | Google LiteRT | [Gemma Terms of Use](https://ai.google.dev/gemma/terms) |

No proprietary or paid cloud APIs are used.

---

## 5. What Our Layer Adds Over the Raw Models

The raw foundation models (Whisper, Gemma) provide only raw transcription and embeddings. Our software layer turns these models into a persistent, verifiable memory pipeline:
- **Overlapping Segmentation:** Audio is partitioned into 20-second chunks with 5-second overlap to avoid splitting thoughts or hesitant therapy disclosures in half.
- **Deterministic Cleanup & Merging:** Stitches overlapping transcript boundaries and removes phonetic repetitions (`TranscriptClean.kt`, `TranscriptMerge.kt`).
- **Verifiable Source Metadata:** Every indexed passage stores its recording ID and start/end time range, allowing therapists to inspect the original context instead of trusting a generated summary.
- **Unit & Self Tests:** The codebase includes an embedding self-test and six transcript-cleaning and merging tests.

*(Note: We distinguish engineering implementation from benchmark claims. We have not completed a formal comparative accuracy benchmark against the raw model on clinical data yet, and prioritize honest evaluation over synthetic accuracy claims.)*

---

## 6. Where Does MindVault Not Work? (Limitations)

We believe in radical transparency about current prototype boundaries:
1. **Relevance Thresholding:** If a query matches nothing in the patient's history, cosine similarity may still surface the nearest available chunk. A calibrated "No relevant memory found" threshold is currently under active development.
2. **Noisy Backgrounds:** Heavy background ambient noise (air conditioning, street sounds) degrades on-device 16 kHz acoustic feature extraction.
3. **Encryption:** Patient data (both databases via SQLCipher, transcripts, queue and extraction cache as AES-256-GCM files) is encrypted at rest under a key unlocked by the therapist's password or strong biometrics. Scope, key handling and limits (forgotten password = data unrecoverable) are in [`docs/SECURITY.md`](docs/SECURITY.md).
4. **Hardware Requirements:** On-device inference requires an Android phone with at least 6 GB of RAM (tested on Google Pixel 8).

---

## 7. Submission Description (OrganizerHQ)

**Short Pitch (140 characters):**  
MindVault is an offline-first Android assistant providing therapists with 100% private, verifiable memory recall during clinical sessions.

**Full Pitch:**  
Therapists conduct 5–8 intense emotional sessions daily, struggling to track complex patient histories over months without compromising confidentiality. MindVault solves this with an entirely on-device Android co-pilot: it transcribes audio locally using Whisper, generates local semantic embeddings via Google EmbeddingGemma, and retrieves relevant past statements with exact timestamps during sessions. Zero cloud transmission, zero hallucinated diagnoses, 100% verifiable doctor-patient confidentiality.

---

## 8. Basic Hygiene & Security Checklist

- [x] **Repository is public:** [github.com/AnnaMarieM/Therapy_local](https://github.com/AnnaMarieM/Therapy_local)
- [x] **Open-source license:** MIT license included in root [`LICENSE`](LICENSE)
- [x] **API Keys & Secrets:** Zero secrets in code. No cloud API required. `.env` is gitignored; clean `.env.example` provided.
- [x] **Medical Disclaimer:** Visible at the top of README and embedded in application UI.
- [x] **Model Attribution:** Complete model names and license links provided in Section 4.
