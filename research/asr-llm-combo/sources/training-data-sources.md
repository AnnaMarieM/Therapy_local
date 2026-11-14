# ASR training data sources: Czech first, English second

Research date 2026-11-13. Read-only. Web facts come from search snippets and page fetches in this session. Anything marked (memory) comes from my background knowledge and was NOT re-verified. Anything marked UNVERIFIED needs a check before use.

## 1. Local search (this machine)

- Searched ~ to depth 4-6, HF cache, ~/therapy-measurements, ~/models, ~/research, ~/backups, ~/media, /mnt, plus a filesystem-wide name search (parczech, parlament, snemov, common_voice, fleurs, voxpopuli).
- **No Czech parliament database found.** No ParCzech, ParlaSpeech, VoxPopuli, Common Voice or FLEURS files anywhere. No ~/.cache/huggingface/datasets. The HF hub cache (1.3 GB) holds only faster-whisper-small and faster-whisper-large-v3-turbo models.
- ~/therapy-measurements holds only gpu-float32-2026-11-13 (JSON/logs of benchmarks, no audio).
- It may live on another machine, an external disk, or cloud (Drive was not searched). /mnt/card and /mnt/oldsd are empty.
- **Disk: / is 937 GB, 862 GB used, 29 GB free (97%).** This is the main constraint: ParCzech4Speech segmented is 167 GB, so stream it or use a rented box.
- Only audio found: ~/media/{audio-recordings, audio-recordings-desktop, business-calls, phone-calls}, 161 files, about 3.2 GB (m4a, personal/work calls and voice memos, many in Czech by file name). I did not open or transcribe them. They are private, so they are not training data unless every speaker consented. They are only useful as a personal test set (with hand-corrected transcripts), never to publish.

## 2. Catalogue, sorted by usefulness for spontaneous Czech psychotherapy talk

Legend: C = commercial training OK per stated licence; hours are as stated by the source.

| # | Source | Speech type | Hours | Transcripts | Licence | Access | On HF | Notes |
|---|---|---|---|---|---|---|---|---|
| 1 | **Vystadial 2013 / 2016 Czech** (UFAL, LINDAT; also OpenSLR 6; HF eval remaster BrunoHays/vystadial-telephony-cs) | Telephone, human-machine dialogues (public transport info), spontaneous, 8 kHz | 2013: ~15-18 h Czech. 2016: ~77 h incl. 2013 (UNVERIFIED, from a third-party list). HF remaster is 19 h, 156 sessions, eval only | Manual | CC BY-SA 3.0 (2013), CC BY-SA 4.0 (2016). C ok, share-alike on derived data | Free download (LINDAT, OpenSLR) | Remaster only | Only free Czech spontaneous-ish source. Narrow domain (bus/train queries), many callers, noisy phone. Good for phone robustness, bad for vocabulary |
| 2 | **Czech Spontaneous Dialogue (SpeechDataAI)** | Spontaneous two-person conversations, dual channel, 100 speakers | 1,000 h | Human-verified, time-aligned | Commercial, paid (speechdata-commercial) | Samples on request only; audio not on HF | Card only | Best match to our use case but not free. Ask for a quote and the free sample |
| 3 | **Czech podcasts / spontaneous dialogue / IVR (Defined.ai)** | Live podcasts (2,392 h or 8.4k h listings), spontaneous dialogue, IVR | see left | Commercial | Paid | defined.ai | no | Not free. Podcasts would be ideal in style. Prices not checked |
| 4 | **MALACH Czech: LINDAT "Czech Malach Cross-lingual Speech Retrieval Test Collection"** (PID 11234/1-1912) | Spontaneous interviews with Holocaust survivors, one interviewer + one speaker, emotional, elderly voices | 353 recordings, 592 h (as listed) | LINDAT collection is for retrieval, may have only partial transcripts (UNVERIFIED). LDC2014S04 has 229 h speech, 143 h transcribed | LINDAT: licence not seen (UNVERIFIED). LDC: paid, for-profit tier exists | LINDAT download; LDC purchase | no | Closest real-world style (interview, emotional narrative, two speakers). Check the licence and whether transcripts are included before counting on it |
| 5 | **Prague DaTabase of Spoken Czech (PDTSC 1.0 / 2.0)** (LINDAT) | Dialogues: Malach testimonies + Companions project dialogues | hours not found | Manual | Not confirmed | LINDAT | no | Small, spontaneous. UNVERIFIED hours and licence |
| 6 | **Czech Broadcast Conversation Corpus** (ZCU, Radioforum on Czech Radio 1) | Radio discussion, 128 speakers | ~33 h transcribed | Manual | Was slated for LDC, so paid (UNVERIFIED) | LDC | no | Conversational, but broadcast studio quality |
| 7 | **OVM (Otazky Vaclava Moravce)** (LINDAT) | TV talk show, spontaneous political debate | ~35 h | Manual | **CC-BY-NC 3.0, non-commercial** | LINDAT | no | Good style, licence blocks commercial training |
| 8 | **ORAL2013 / ORTOFON / Czech National Corpus spoken corpora** | Informal spontaneous Czech (best natural language sample) | not found; HF sample is text only | Orthographic + phonetic tiers | HF sample CC-BY-NC-SA 4.0. Audio terms UNVERIFIED | korpus.cz (registration) | CZLC/CNC_oral_ortofon (text only) | Audio availability and licence unclear. Text is useful for LM / vocabulary and for TTS scripts. Contact UCNK FF UK |
| 9 | **ParCzech4Speech 1.0** (Charles Univ., TSD 2025, arXiv 2509.06675) | Parliament plenary, 588 speakers, partly spontaneous debate | Largest variant 2,695 h (unsegmented). Segmented: train 1,131 h, dev 10.1 h, test 20.6 h | Official transcript aligned to audio, Whisper and wav2vec2 transcripts alongside, edit-distance filter columns | CC BY (HF tag says cc-by-2.0, LINDAT text says CC-BY). C ok | LINDAT; HF ufal/parczech4speech-segmented (167 GB, WebDataset FLAC tars) and -unsegmented | **Yes** | Largest and cleanest open Czech set. Formal/read-ish register, adult male-heavy. Use a subset of low-edit-distance segments |
| 10 | **Large Corpus of Czech Parliament Plenary Hearings** (LINDAT 11234/1-3126, LREC 2020) | Parliament | ~444 h (paper); another listing says ~1,200 h (UNVERIFIED which) | Aligned | CC-BY 4.0 (per search summary) | LINDAT | no | Older, superseded in size by item 9 |
| 11 | **YODAS / YODAS2 Czech** (espnet) | YouTube, mixed: vlogs, talks, interviews. Includes real spontaneous speech | cs000 = 167.6 h (original YODAS, "manual" captions = user-uploaded, not necessarily human-checked) | Noisy uploader captions. Must re-label or filter | cc-by-3.0 on the card (videos have their own rights, legal gray zone) (note) | HF espnet/yodas (shards), yodas2 (unsegmented, 24 kHz) | **Yes** | Best open source of varied spontaneous Czech. Re-transcribe with a big model and keep only matches (Granary does this) |
| 12 | **NVIDIA Granary, Czech part** | Pseudo-labelled YODAS + YouTube-Commons + MOSEL | Total 643k h for 25 languages. Czech hours NOT found (see paper Table 1) | Whisper-large-v3 two-pass pseudo labels, filtered | Card not read. Parakeet v3 card is CC-BY-4.0; check the Granary card (UNVERIFIED) | HF nvidia/Granary | **Yes** | Probably several thousand hours of Czech. Ready-made labels, but pseudo labels cap quality at Whisper-large level |
| 13 | **Mozilla Common Voice Czech, Scripted 25.0** (Mar 2026) | Read sentences, many speakers, phone/laptop mics | 268.7 h recorded, **81.1 h validated**, 1,134 speakers, 5.56 GB (v23: 272/75 h). v26 exists, Czech figure not found | Prompt text | CC0 | Mozilla Data Collective (datacollective.mozillafoundation.org), free account | Older versions on HF (gated) | Common Voice has a Spontaneous Speech product, but no Czech figure was found. Read style, short clips, varied mics |
| 14 | **chosenek/czech-speech-combined** | Mix: audiobooks 72 h, YODAS 37 h, VoxPopuli 33 h, CV 67 h | ~209 h (my sum; page says 146k clips, 34.7 GB) | Whisper-filtered for YODAS/VoxPopuli | cc-by-4.0 on card (inherits upstream terms, check each) | HF | **Yes** | Convenient starter, 24 kHz, clips 2-20 s. Viewer broken. Speaker count on the card is inconsistent |
| 15 | **VoxPopuli Czech (transcribed)** (Meta) | European Parliament interpreters/speakers | 59.8 h (MFA docs), 21k utts | Manual | CC0 / EP terms (memory) | HF facebook/voxpopuli | Yes | Small, formal, EU topics |
| 16 | **FLEURS cs** (Google) | Read Wikipedia-style sentences | ~10-12 h (memory, UNVERIFIED) | Manual | CC-BY 4.0 (memory) | HF google/fleurs | Yes | Use as test set only. Parakeet v3 reports 11.0 % Czech WER here |
| 17 | **ParlaSpeech-CZ** (CLARIN.SI) | Parliament, GMM-HMM aligned | not found | Aligned | CC-BY (memory, UNVERIFIED) | HF classla/ParlaSpeech-CZ, clarin.si 11356/1785 | Yes | Overlaps item 9 in source audio |
| 18 | **Czech audiobooks (LibriVox cs, plus the audiobook sets in item 14)** | Read | LibriVox Czech is small (memory, UNVERIFIED), audiobooks_new ~72 h in item 14 | Aligned text | Public domain (LibriVox) | librivox.org | partly | Useful for TTS, poor for conversation |
| 19 | **SPEECON / SpeechDat-E Czech** (ELRA, Appen) | Scripted telephone / read with environments | Appen lists 93 h scripted Czech | Manual | Paid | ELRA / Appen | no | Not free, scripted |
| 20 | **LDC Czech Broadcast News (LDC2004S01/T01), VOA Czech (LDC2000S89)** | Broadcast news | 50 h audio, ~23 h transcribed | Manual | Paid LDC | LDC | no | Not worth the money for our use |
| 21 | **Brno "in-the-wild" Czech YouTube pipeline** (FIT VUT student project, excel.fit.vutbr.cz 2026) | YouTube, 100 channels | 25.4k h raw, 9.7k h curated | Automatic | Unknown | Pipeline open source, data release UNVERIFIED | ? | Worth an email to the authors |
| 22 | **MOSEL / EuroSpeech** | Large EU multilingual pseudo labels / parliament | 950k h total (MOSEL), Czech portion not found | Automatic | See cards | HF | Yes | Mostly parliament and VoxPopuli-style audio, likely overlaps items 9-12 |
| - | **MLS, Emilia, SPGISpeech** | - | - | - | - | - | - | MLS has no Czech (memory). Emilia covers zh/en/de/fr/ja/ko (memory). Skip |

**Combined open Czech estimate (rough, with big overlap):**
- Clean, commercially usable and non-overlapping: ParCzech4Speech 2,695 h + Common Voice 81 h validated + Vystadial 77 h (2016) + VoxPopuli 60 h + YODAS cs 168 h = about 3,100 h.
- Add Granary Czech (unknown, likely thousands, overlaps YODAS), OVM 35 h (non-commercial), MALACH 592 h (licence unverified), and the total is roughly 3,700 h before Granary.
- Of this, truly conversational/spontaneous and free is under about 300 h (Vystadial, YODAS portion, parts of MALACH/OVM, spontaneous parts of parliament debate). This is the real bottleneck.

## 3. English secondary list (licences from memory unless noted, UNVERIFIED)

| Source | Type | Hours | Licence | Notes |
|---|---|---|---|---|
| **Fisher + Switchboard** (LDC) | Telephone conversation, two speakers | ~2,000 h Fisher, ~260 h Switchboard | LDC paid, non-member fee, commercial use needs for-profit licence | Best English match for two-speaker spontaneous speech, but paid |
| **AMI** | Meetings, multi-speaker | ~100 h | CC-BY 4.0 | Free, far-field, meetings |
| **Primock57** (Babylon Health) | Simulated GP consultation, 57 consultations | ~9 h (memory) | Licence not seen in search, check LICENSE in github.com/babylonhealth/primock57 | Small, medical vocabulary, doctor-patient two speakers, closest free analogue to a consultation. Good eval set |
| **GigaSpeech** | Podcasts, YouTube, audiobooks | 10,000 h | Apache-2.0 for code; data subject to terms, personal/research (memory) | Spontaneous podcasts; check terms for commercial use |
| **People's Speech** | Mixed | ~30,000 h | CC-BY-SA / CC-BY (memory) | Large, noisy labels |
| **TED-LIUM 3** | Talks | 452 h | CC-BY-NC-ND (memory) | Non-commercial |
| **Common Voice en** | Read | thousands h | CC0 | Read, many accents |
| **LibriSpeech** | Read | 960 h | CC-BY 4.0 | Baseline only |
| **MedDialog / ACI-Bench** | Text only | - | see repos | Useful as text for LM vocabulary or as scripts for TTS, no audio |
| **CUEMPATHY** | Cantonese counselling speech, 156 sessions, 39 dyads | n/a | not verified | Not English or Czech, shows such data exists (arXiv 2409.02466) |
| **Anno-MI** | Motivational interviewing (video transcripts) | - | repo public domain (code); transcript terms UNVERIFIED | Text, useful for vocabulary |
| **Psych8k** | Text from counselling recordings | - | cc-by-nc-sa-4.0 | Text, non-commercial |
| **Alexander Street Counseling & Psychotherapy** | Real/demo sessions, video + transcripts | - | Commercial institutional subscription, no ML redistribution | Do not scrape. Ask the publisher |
| **Psychology, Counselling and Psychotherapy Consultation Talk Bank (USQ)** | Audio/video + transcripts | - | Access conditions not seen | Contact the data holder, ethics approval likely required |

Ethics note: no public dataset of real clinical therapy audio exists that is both open and clean to use. Do not scrape private or clinical recordings. Realistic substitutes: (1) consented role-play recorded by the team, (2) synthetic dialogue via TTS, (3) interview/podcast speech.

## 4. Pretrained small starting points

| Model | Size | Czech? | Czech quality | sherpa-onnx? | Licence |
|---|---|---|---|---|---|
| **nvidia/parakeet-tdt-0.6b-v3** (FastConformer-TDT, 25 languages) | 0.6B; sherpa-onnx int8 encoder 622 MB + 18 MB | Yes | FLEURS cs WER 11.01 % (greedy, no punctuation); AMI-style cs row 11.0 in snippet unclear. No CV figure | **Yes** (sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8) | CC-BY-4.0 |
| **Whisper small cs fine-tunes** (mikr/whisper-small-cs-cv11) | ~244M params, int8 ~250 MB | Yes | 18.6 % WER on CV11 cs test | Yes, sherpa-onnx whisper export | Apache-2.0 base |
| **Whisper base cs fine-tunes** (sgangireddy/whisper-base-cv-highLR-cs) | ~74M, int8 ~80 MB | Yes | 26.9 % CV11 cs | Yes | Apache-2.0 base |
| **Whisper tiny cs fine-tunes** (maag/whisper_tiny_cs, kozak-vaclav/whisper-tiny-cs) | 39M | Yes | 44.1 % CV13, 40.8 % CV17 | Yes | Apache-2.0 base |
| **NVIDIA Nemotron ASR streaming 0.6B / unified 0.6B** | 0.6B, ~600 MB int8 | Yes (13 locales) | Czech FLEURS 20.4-24.2 streaming; unified 10.6 offline (snippet, columns unclear) | Not confirmed | see card |
| **Moonshine (Flavors)** | Tiny 27M | **No Czech**: Arabic, Chinese, Japanese, Korean, Ukrainian, Vietnamese only | - | Yes for supported languages | permissive |
| **SenseVoice** | ~230M | No Czech (zh, yue, en, ja, ko) (memory) | - | Yes | see card |
| **NeMo stt_cs_*** | - | I found no NeMo `stt_cs` model in these searches. UNVERIFIED | - | - | - |
| **Czech zipformer / icefall recipe** | - | None found in sherpa-onnx catalogue; no Czech icefall recipe found | - | - | - |

Size reality check: the only good Czech model sherpa-onnx runs today (parakeet v3 int8) is ~640 MB, over the 300 MB budget. Whisper small int8 fits but is 18.6 % CV WER and slow on phone. For a real small model plan on either (a) fine-tune whisper-small/base for Czech+English, or (b) train a Czech/English zipformer (~70 M params, ~70-100 MB int8) with icefall, possibly distilled from parakeet v3 or large-v3 pseudo labels.

## 5. Synthetic data (TTS)

| Model | Licence | Notes |
|---|---|---|
| fav-kky/SpeechT5-base-cs-tts (ZCU), pretrained on 120k h Czech | cc-by-nc-sa-4.0 | Non-commercial, so synthetic audio may inherit the restriction |
| cekal/hlas-1 | BreezeBlue Research / non-commercial | Same issue |
| vvolhejn/pocket-tts-czech | not seen | Voice cloning, fast on CPU. Check licence |
| chosenek f5-tts-czech-model | not seen | F5-TTS Czech fine-tune, check licence |
| Piper / VITS / XTTS Czech | no Czech checkpoint confirmed | Piper cs voices exist (memory, UNVERIFIED), mostly MIT or CC licences per voice. XTTS-v2 has Czech but a non-commercial licence (memory) |

Transfer (memory/general experience, not measured here): synthetic speech helps names, rare vocabulary and domain terms (family, sleep, anxiety words) when mixed 10-30 % with real audio. It does not give spontaneous prosody, disfluencies, overlap or room acoustics. Add noise, room impulse responses, 8 kHz phone codecs, speed perturbation. Text for scripts: LLM-written therapy-style dialogues, ORTOFON/ORAL text (licence permitting), MedDialog translated. Use several voices and voice cloning from CC0 CV speakers only if the TTS licence allows.

## 6. Recommendation: fetch first, in order

1. **ParCzech4Speech segmented, subset** (HF, stream, do not download the 167 GB). Filter with `seg_edit_dist` and `align_edit_dist_max`, take ~300-500 h. Gives clean Czech vocabulary, names, numbers. CC BY.
2. **Common Voice Czech 25.0 from Mozilla Data Collective** (5.6 GB, CC0, 81 h validated). Many mics and speakers, also the dev/test set for tuning. Also CV English for the English half.
3. **Vystadial 2016 Czech** (LINDAT, ~77 h, CC BY-SA, telephone) plus the HF remaster as test set. Only free spontaneous dialogue, and good for phone bandwidth robustness.
4. **YODAS cs000 (168 h)** or **Granary Czech**: re-transcribe or filter by agreement between uploader captions and a strong model (parakeet v3 or large-v3). This is where real spontaneous, varied speech comes from.
5. **Ask for the paid sample from SpeechDataAI** (1,000 h two-person spontaneous Czech) and request a quote. It is the only source matching the use case directly. In parallel, ask the Czech National Corpus about ORTOFON/ORAL audio and the Brno YouTube dataset authors.

English side: AMI + Primock57 (eval) + a slice of GigaSpeech or CV en. Add Fisher only if a license is worth buying.

### Rough plan (estimates, not measured)
- Data mix for a Czech+English model: ~60 % Czech (ParCzech subset 35 %, YODAS/Granary 25 %, CV 15 %, Vystadial 10 %, synthetic 10 %, spontaneous paid or consented 5-10 % if obtained), ~40 % English. Total about 800-1,500 h per language is enough for fine-tuning.
- Cheapest path: fine-tune **whisper-small** (or base) for Czech+English, int8 export fits under 300 MB. A free Colab T4 can do whisper-base on ~100-200 h in a day with LoRA or gradient accumulation (slow, 16 GB VRAM, session limits). Whisper-small on 500 h needs ~1-2 days on a rented A100/4090 (a few tens of USD).
- Better runtime fit: **icefall zipformer transducer (~70 M)** from scratch or distilled from parakeet v3 pseudo labels, 1,000+ h, about 2-4 days on 1-2 rented A100/4090 (rough). Needs a Czech lexicon-free BPE of ~500-1,000 tokens. Quality from scratch with ~1,000 h Czech is likely worse than whisper-small fine-tune on conversational speech; test both on a small hand-checked set of our own consented conversations.
- Always keep a private eval set: Primock57-like Czech role-play (consented) and FLEURS cs / CV test for comparability (parakeet v3 11.0 % FLEURS cs is the bar).

## 7. Not verified (flagged)
- No Czech hours for: Granary, MOSEL, FLEURS cs, YODAS2 (unsegmented), PDTSC, ORAL/ORTOFON audio, CV Spontaneous cs.
- Licences not seen: MALACH LINDAT, PDTSC, SpeechDataAI price, Granary card, Pocket-TTS Czech, F5-TTS Czech, Primock57, USQ Talk Bank.
- Vystadial 2016 "77 h" and the "444 h vs 1,200 h" plenary figure come from third-party summaries.
- "CzechSpeech" or "moravianai" datasets: search found nothing, so I cannot confirm they exist. LINDAT repository pages for ParCzech4Speech and Vystadial 2016 timed out when fetched directly.
- OVM licence (CC-BY-NC) and Czech Broadcast Conversation LDC status come from search summaries.
- I found no sherpa-onnx Czech-specific model (Zipformer/FastConformer) and no Czech icefall recipe, but that is absence of results, not proof.
- English licences in section 3 are from memory except where noted.
- The user's former parliament database was not found locally.
