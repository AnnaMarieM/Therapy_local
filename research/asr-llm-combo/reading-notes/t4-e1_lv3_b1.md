# e1_lv3_b1 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e1_lv3_b1/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 9.7% | 6.4% | 1511/1490 | 76.7 |
| hannah-w | 7.5% | 4.8% | 1317/1313 | 70.8 |
| jana-n | 11.6% | 5.1% | 897/904 | 150.9 |
| tomas-b | 18.4% | 8.4% | 962/957 | 95.7 |

## Reading

**daniel-r**
- names/places absent (stem match): none
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: `frustrated. He's having a dream and he's always bein`
- largest wrong spans (script -> heard): [i feel it s] -> [he feels]; [ten kilometres] -> [i m a kilometer]; [down a bag] -> [it on bad but]; [seventy three] -> [having a dream]; [out loud] -> [i would hold]
**hannah-w**
- names/places absent (stem match): ['Hannah', 'Hargreaves', 'Ofsted']
- largest wrong spans (script -> heard): [in a similar] -> [going to assemble your]; [at school you can] -> [that go and]; [i am glad too] -> [good luck to you]; [] -> [a light fall]; [year four] -> [here]
**jana-n**
- names/places absent (stem match): ['Alena', 'Brno']
- largest wrong spans (script -> heard): [pojdme] -> [s detem tak mi]; [at je to] -> [a deto]; [jsem to takhle] -> [tohle]; [co kdyby] -> [tak kdo by]; [smutek je dovoleny] -> [jsme take dovoleni]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítě tě ublížím. Ublížíte jak? Ne, že bych cht`
- largest wrong spans (script -> heard): [spatne pripojim vodic a] -> [v patem prikladu jim vodi ceho]; [je mi spatne od zaludku] -> [me spatny udeloutku]; [byt uprimny nikdy] -> [mit tu primy nikde]; [pot tresou se mi] -> [po tresouzene]; [predal kolegovi protoze] -> [pridal kolik ale proc]

Kinds of errors: Czech: diacritics and case endings, whole garbled words in the hard stretches (tomas-b "knoflík zvlášť" -> "knov tyzdlar", "vyběhli" -> "vyvelili", "pot třesou se mi ruce" -> "po tresouzene"), names of people drift ("Barbora" -> "Barbara", "Matějovi" -> "Matějevi", sister "Alena" -> "Alanu"). English: contractions and spelling variants (counted as errors: "gonna", "burnt", "math"), dropped "and", and lost named entities: "Hannah" -> "hana"/"hanach", "Ofsted" -> "offset", "year four" -> "earful"/"quarter".
Hallucinated or dropped sentences: none dropped wholesale (word counts match the script within 2%); the damage is local substitutions. In daniel-r the father's age is LOST in every variant: "he's seventy-three" is heard as "he's having a dream".
Self-harm sentence (tomas-b): the first-person verb is lost. Script "Že jí a dítěti ublížím" is heard as "Že jí a dítě tě ublíží" (third person, reads as "that he will hurt"). The therapist's next line "Ublížíte jak?" survives, so a human or an LLM can still infer it, but the client's own words are garbled.
Clean enough for fact extraction? Mostly for jana-n and the English recordings; tomas-b is borderline (OCD content mostly readable; the risk sentence and "Barbora je těhotná ve čtvrtém měsíci" -> "probora denotna" are damaged).
Verdict: baseline, greedy. The CI faster-whisper numbers are reproduced on the GPU within about 1 point, so the GPU is only a speed-up.
