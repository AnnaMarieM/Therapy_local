# MindVault (TherapyLocal) — On-Device AI Memory Assistant for Psychotherapists

> **Privacy-First · 100% Offline · Real-Time Longitudinal Memory on Android**

---

## 1. What Are We Building?

We are building a **privacy-first, on-device Android assistant for psychotherapists** that acts as an intelligent longitudinal memory co-pilot. 

Therapists often conduct 5–8 intense emotional sessions every day. Currently, they must manually write notes, review binders of past sessions, and mentally keep track of intricate details across months or years of patient history. 

**MindVault** runs entirely on the therapist's Android device:
- It **records and transcribes** therapy sessions locally.
- It automatically generates **structured clinical summaries** and updates the patient's persistent memory.
- It provides a **quick pre-session briefing** so the therapist is fully prepared in minutes.
- During the session, it provides **subtle, real-time memory prompts** when a topic connects to a previous conversation from weeks or months ago.

---

## 2. The Core Problem We Are Solving

1. **Information Overload & Fragmented Memory:**
   Patients often circle back to foundational conflicts months apart. Remembering that a conflict with a manager in November connects to a childhood story told in May is difficult when treating dozens of patients.
2. **Time-Consuming Manual Note-Taking:**
   Therapists spend valuable hours after sessions typing notes rather than resting or preparing for the next patient.
3. **The Confidentiality Barrier:**
   Therapists **cannot** upload confidential patient disclosures, voice recordings, or psychiatric notes to cloud APIs (OpenAI, Anthropic, cloud databases) due to ethics, HIPAA/GDPR, and doctor-patient confidentiality.

---

## 3. The 4 Key Product Pillars

```
┌────────────────────────────────────────────────────────────────────────┐
│                        ANDROID LOCAL APP                               │
│                                                                        │
│  [1. Patient Profile] ──> [2. Pre-Session Brief]                       │
│           │                          │                                 │
│           ▼                          ▼                                 │
│  [3. Live Session]    ──> [4. Real-Time Hint] ("Oct 12 connection...") │
│    - Record & Transcribe             ▲                                 │
│           │                          │                                 │
│           ▼                          │                                 │
│  [5. Post-Session Summary] ──────────┘                                 │
│    - Facts / Themes / Open Loops                                       │
│    - Embedded & Stored in Local Memory                                 │
└────────────────────────────────────────────────────────────────────────┘
```

### 1. Unified Patient Profile & Longitudinal Memory
Each patient (e.g., George) has an individual profile. All their sessions, recurring emotional themes, key life figures (parents, partner, boss), and unresolved topics are linked under one roof.

### 2. The 5-Minute Pre-Session Briefing
Before George walks through the door, the therapist taps his profile and receives an instant, scannable briefing:
- **Who George is:** Core background and primary goals.
- **Where we left off:** Key themes and emotional state from the last session.

### 3. Local Live Session & Real-Time Recall
During the session:
- **Explicit Privacy & Consent State:** The interface clearly displays an active, reassuring status banner:
  > 🟢 **Recording Locally**  
  > *Processing locally on this device · No audio or transcript leaves the phone.*  
  > *[✓] Patient consent confirmed*
- The app locally records and transcribes the conversation.
- **Traceable Memory Cues (With Source Verification):** If George mentions something that resonates with an earlier session (e.g., *"My boss criticized my work again"*), the app displays an unobtrusive, gentle hint with full traceability:
  > 💡 **Related Past Memory (Session 1 — Oct 12, 2026):**  
  > *Theme:* Feeling "never good enough" under authority criticism.  
  > *Source Excerpt:* *"Whenever my dad looked over my homework, he would sigh and tell me I'd never make it in the real world."*
- The therapist doesn't have to blindly trust an AI interpretation—they can instantly verify the source excerpt before choosing to bring it up.

### 4. Post-Session Structured Summary (Strictly Grounded, No AI Ideation)
Immediately after the session concludes, the app processes the transcript. Small on-device local models must not be allowed to invent interpretations, play psychologist, or hallucinate connections. Therefore, the summary is strictly bounded to structured extraction:
- **Patient-Reported Facts & Statements:** Concrete life events, situations, and direct disclosures explicitly voiced by the patient (e.g., *"Passed over for promotion on Tuesday; sister visiting next weekend"*).
- **Therapist Observations:** Clinical notes, affect assessments, and demeanor entered or confirmed directly by the therapist.
- **Entities & Recurring Topics:** Direct extraction of named individuals (*Father, Boss, Sister*) and topics explicitly mentioned.
- **Zero Model Speculation:** The model is strictly prohibited from introducing new clinical theories, psychological diagnoses, or interpretive hypotheses. It acts solely as an objective recording, compression, and indexing tool.

---

## 4. Why "On-Device" Is Our Defining Advantage

- **Zero Cloud Transmission:** All voice processing, transcript generation, text summarization, and vector search occur strictly inside the Android phone.
- **Visible Privacy Guarantee:** The app provides a persistent on-screen reassurance:
  > **Processing locally on this device · No audio or transcript leaves the phone.**
- **Air-Gapped & Resilient:** Works seamlessly in offline therapy rooms, basement offices, or without Wi-Fi.
- **Hard Patient Isolation:** Patient data is partitioned—George's memory will never mix with or leak into another patient's data.
- **Full Data Sovereignty:** If a patient requests deletion, one tap permanently erases their voice, transcripts, summaries, and embeddings from the device hardware.

---

## 5. Walkthrough Demo: The "George" Story (Hackathon Flow)

To prove this product works end-to-end today:

1. **Session 1 (October 12):**
   - Active status: *Processing locally on this device · Consent confirmed*.
   - George discusses work anxiety, relationship conflicts, and feeling "never good enough" due to his father's harsh criticism.
   - App records, transcribes, produces the factual structured summary, and indexes George's memories.
2. **Three Weeks Later — Pre-Session Brief:**
   - The therapist opens George's profile.
   - The briefing presents the factual summary: father's past criticism, work presentation anxiety, and sister's visit.
3. **Session 2 (Today):**
   - George says: *"I don't know why, but whenever my boss critiques my report, I shut down and feel like a total failure."*
   - **The Wow Moment (Semantic Search + Traceability):** The app's retrieval matches the concept of *critique / failure* to Session 1 and displays:
     > 💡 **Related Past Discussion:** Authority criticism  
     > 📅 **Session 1 (Oct 12, 2026)**  
     > 🔍 **Source Excerpt:** *"My dad would always say I wasn't trying hard enough, no matter how high my grades were."*
   - The model didn't "think" of this connection—it simply retrieved the closest verbatim past excerpt via embedding search. The therapist makes the clinical connection.

---

## 6. What Makes Our Approach Different

- **Zero AI Ideation / 100% Grounded:** On-device local models are strictly bounded to summarization and search. They are never asked to generate clinical theories, diagnose, or offer advice. The human therapist provides 100% of the clinical intellect.
- **Traceable to the Exact Words:** The therapist is never asked to trust a black box. Every recalled memory links directly to the session date and raw transcript excerpt.
- **Therapeutic Presence First (No "Clippy"):** Prompts are silent, subtle, and require therapist interaction to expand—preserving real human connection.
- **Consent & Privacy as a Core Feature:** Clear on-screen consent tracking and hardware-level assurance that no data ever leaves the phone.
