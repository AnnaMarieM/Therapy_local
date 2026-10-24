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
- **Open questions & threads:** Suggested topics to follow up on today.

### 3. Local Live Session & Real-Time Recall
During the session:
- The app locally records and transcribes the conversation.
- If George mentions something that resonates with an earlier session (e.g., *"My boss criticized my work again"*), the app displays an unobtrusive, gentle hint:
  > **Related past session (Oct 12):** *George felt "never good enough" when criticized by his father.*
- The therapist stays fully in control and can weave this insight into the conversation naturally.

### 4. Post-Session Structured Summary
Immediately after the session concludes, the app processes the transcript into clear, structured sections:
- **Main Topics & Life Events**
- **Emotional Themes & Triggers**
- **Key Relationships Mentioned**
- **Open Loops for Next Session**
- *Distinction between patient statements, therapist observations, and model suggestions.*

---

## 4. Why "On-Device" Is Our Defining Advantage

- **Zero Cloud Transmission:** All voice processing, transcript generation, text summarization, and vector search occur strictly inside the Android phone.
- **Air-Gapped & Resilient:** Works seamlessly in offline therapy rooms, basement offices, or without Wi-Fi.
- **Hard Patient Isolation:** Patient data is partitioned—George's memory will never mix with or leak into another patient's data.
- **Full Data Sovereignty:** If a patient requests deletion, one tap permanently erases their voice, transcripts, summaries, and embeddings from the device hardware.

---

## 5. Walkthrough Demo: The "George" Story (Hackathon Flow)

To prove this product works end-to-end today:

1. **Session 1 (October 12):**
   - George discusses work anxiety, relationship conflicts, and a deep-seated feeling of "never being good enough" stemming from his father's harsh criticism.
   - App records, transcribes, produces the structured summary, and indexes George's memories.
2. **Three Weeks Later — Pre-Session Brief:**
   - The therapist opens George's profile.
   - The briefing reminds the therapist of the father-criticism theme and work presentation anxiety.
3. **Session 2 (Today):**
   - George says: *"I don't know why, but whenever my boss critiques my report, I shut down and feel like a total failure."*
   - **The Wow Moment:** The app highlights:
     > 💡 **Related Memory (Session 1):** *Feeling "never good enough" when facing authority figure criticism (father).*
   - The therapist uses the cue: *"Does that feeling with your boss remind you of how you felt during your father's critiques?"*

---

## 6. What Makes Our Approach Different

- **Therapeutic Presence First (No "Clippy"):** We do not clutter the screen with noisy AI interruptions. Prompts are silent, subtle, and require therapist interaction to expand.
- **Fact vs. Inference Safeguard:** The AI never writes assumptions as clinical facts. It distinguishes between *what the patient said* and *potential patterns detected*.
- **Designed for the Therapist, Not to Replace Them:** The app does not diagnose or advise the patient; it amplifies the therapist's human empathy and memory.
