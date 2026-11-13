# e1_turbo_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e1_turbo_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 10.1% | 6.3% | 1511/1494 | 31.2 |
| hannah-w | 7.1% | 4.4% | 1317/1316 | 28.4 |
| jana-n | 12.3% | 5.2% | 897/901 | 33.2 |
| tomas-b | 20.4% | 8.7% | 962/959 | 35.2 |

## Reading

**daniel-r**
- names/places absent (stem match): none
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: `frustrated, he's having a dream and he's always been the strong one`
- largest wrong spans (script -> heard): [it a thing with claire tea on the] -> [out of dango s glare do you know]; [ten kilometres] -> [i got me two years]; [i feel it s] -> [he feels]; [down a bag] -> [an angle bad but]; [just starts] -> [s a start]
**hannah-w**
- names/places absent (stem match): ['Hannah', 'Hargreaves', 'Ofsted']
- largest wrong spans (script -> heard): [so you ve lost] -> [say it was]; [year four] -> [ear for a]; [along with] -> [how long was]; [year four] -> [here]; [headteacher] -> [head teacher]
**jana-n**
- names/places absent (stem match): ['Alena', 'Brno']
- largest wrong spans (script -> heard): [jindy zase ze] -> [jen kdyz]; [vam honilo] -> [mam o nelo]; [jsem to takhle] -> [tohle]; [co kdyby] -> [tak kdo by]; [pojdte dal] -> [petra]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítě tě ublíží. Ublížíte jak? Ne, že bych chtě`
- largest wrong spans (script -> heard): [je mi spatne od] -> [mam spatnil]; [v olomouci delam] -> [mam moci ja mam]; [poslednich mesicich mi dela] -> [posledni pristi si smidla]; [predal kolegovi protoze] -> [prijal kolik ale proc]; [nebo stojim u schodu] -> [dostajim uschodu a]

Kinds of errors: Czech: diacritics and case endings, whole garbled words in the hard stretches (tomas-b "knoflík zvlášť" -> "knov tyzdlar", "vyběhli" -> "vyvelili", "pot třesou se mi ruce" -> "po tresouzene"), names of people drift ("Barbora" -> "Barbara", "Matějovi" -> "Matějevi", sister "Alena" -> "Alanu"). English: contractions and spelling variants (counted as errors: "gonna", "burnt", "math"), dropped "and", and lost named entities: "Hannah" -> "hana"/"hanach", "Ofsted" -> "offset", "year four" -> "earful"/"quarter".
Hallucinated or dropped sentences: none dropped wholesale (word counts match the script within 2%); the damage is local substitutions. In daniel-r the father's age is LOST in every variant: "he's seventy-three" is heard as "he's having a dream".
Self-harm sentence (tomas-b): the first-person verb is lost. Script "Že jí a dítěti ublížím" is heard as "Že jí a dítě tě ublíží" (third person, reads as "that he will hurt"). The therapist's next line "Ublížíte jak?" survives, so a human or an LLM can still infer it, but the client's own words are garbled.
Clean enough for fact extraction? Mostly for jana-n and the English recordings; tomas-b is borderline (OCD content mostly readable; the risk sentence and "Barbora je těhotná ve čtvrtém měsíci" -> "probora denotna" are damaged).
Verdict: same kinds of errors as large-v3 but 1 to 3 points worse on Czech (tomas-b 20 to 22%); self-harm sentence damaged in the same way. Father's age lost. The candidate that fits a phone.
