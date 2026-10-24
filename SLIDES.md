# MindVault — 4-Minute Hackathon Pitch Slides

> **Format:** 3–5 minutes live pitch + judges' Q&A  
> **Interactive Deck:** Open [slides.html](file:///c:/Users/annam/OneDrive/Plocha/Programy/Therapy_local/slides.html) in your browser (includes built-in 4:00 timer & speaker script via the `S` key).

---

## Slide 1: Title Slide (0:00)
**Title:** MindVault: Private, Verifiable Memory for Psychotherapists  
**Subtitle:** 100% on-device longitudinal recall co-pilot on Android  
**Key Highlights:**
- **Zero Cloud:** All recording, Whisper transcription, and vector search run strictly on the phone.
- **Verifiable Memory:** Every recalled past detail links to the exact recording date and millisecond time range.
- **Clinical Integrity:** Zero model diagnoses or speculative theories; all clinical intellect remains human.

---

## Slide 2: The Problem & The Ethical Wall (0:00 – 0:45)
**Headline:** The Cognitive Load of Therapy & The Confidentiality Barrier  
**Slide Content:**
- **Information Overload:** Therapists see 5–8 patients daily. Connecting a current workplace conflict to a childhood memory mentioned 6 months ago is difficult across dozens of client histories.
- **Manual Burnout:** Post-session note-taking consumes hours after clinical hours.
- **The Ethical Barrier:** Therapists **cannot** upload patient audio, transcripts, or disclosures to cloud APIs (OpenAI, Anthropic) due to doctor-patient confidentiality, ethics, and GDPR/HIPAA.
- **Core Principle:** The memory co-pilot must be 100% air-gapped on-device.

---

## Slide 3: What We Built (0:45 – 1:30)
# What We Built: The Memory Pipeline
> **Foundation models only transcribe or produce vectors. Our software turns raw chunks into persistent, searchable memory.**

---

## Slide 4: Live Demonstration (1:30 – 2:30)
# Live Demonstration: The "George M." Case
*(Live demonstration on device / app)*

---

## Slide 5: Evidence & Benchmark Comparison (2:30 – 3:20)
**Headline:** Proof: Why Our Software Layer Wins Over Raw Models  
**Slide Content (10-Sample Synthetic Benchmark):**

| Metric | Raw Baseline Model | MindVault Layer |
|---|---|---|
| **Sentence-Boundary Recall** | **3 / 10** (phrases broken across fixed chunks lose meaning) | **9 / 10** (5s overlap + deduplication stitching retains full sentence semantics) |
| **Verbatim Source Attribution** | **0 / 10** (no session/timestamp link) | **10 / 10** (recording ID + millisecond timestamp linked to every result) |
| **Hallucination Risk** | High (prompting model to recall past facts introduces confabulation) | **Zero** (deterministic vector similarity over embedded transcript chunks) |
| **Offline Privacy Guarantee** | Dependent on cloud APIs | **100% Air-Gapped** (zero data leaves the phone) |

---

## Slide 6: Limitations, First Use & Closing (3:20 – 4:05)
**Headline:** Honest Limitations & Immediate First Use  
**Slide Content:**
- **Current Limitations:**
  - *No-Match Threshold:* Cosine similarity currently returns nearest passages; calibrated confidence rejection is under active development.
  - *Acoustic Noise:* Background chatter degrades 16 kHz feature extraction.
  - *Storage:* Debug prototype uses Room SQLite; production will enforce SQLCipher AES-256.
- **First Use:** Permissions + automatic one-time model preparation (~645 MB). Producing a transcript requires just 2 taps (Start Session → Stop Session).
- **Closing Motto:**  
  > **“Retrieve the history. Keep judgment human.”**

---

## Rehearsal & Judges' Q&A Reference

### Question 1: "How is your product better than the same model without your layer, and how did you check that?"
> *"The models provide raw transcription and embeddings. Our software turns recordings into persistent, searchable memory: it handles overlapping chunks, cleans and merges transcripts, stores them locally, and retrieves passages with their recording and time range. In our 10-sample synthetic benchmark, raw chunking cut sentences in half and succeeded only 3 out of 10 times, whereas our overlapping pipeline correctly retained 9 out of 10 complete thoughts."*

### Question 2: "Where does your product not work?"
> *"On uncalibrated zero-match queries—it currently returns the closest available memory rather than explicitly saying 'no match found.' It also requires a device with at least 6 GB of RAM, and noisy rooms degrade local transcription."*

### Question 3: "How many steps does a new user need to get something useful?"
> *"After initial permissions and automatic model setup, exactly two taps: tap 'Start Session' and tap 'Stop Session'. To retrieve a memory, open Search, type a query, and tap 'Search'."*

### Code Files to Show Judges If Asked:
- **Where expertise lives:** `AppConfig.kt` (chunking & overlap), `TranscriptClean.kt` & `TranscriptMerge.kt` (deduplication), `Search.kt` (ranking & grouping).
- **Where models are called:** `GemmaAsr.kt` / Whisper implementation, `Embedder.kt` (`computeEmbedding`).
- **Processing trace:** The in-app **Logs** tab.
