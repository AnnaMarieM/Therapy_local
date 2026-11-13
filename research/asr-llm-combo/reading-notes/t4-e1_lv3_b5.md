# e1_lv3_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e1_lv3_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 9.0% | 6.1% | 1511/1496 | 126.5 |
| hannah-w | 7.1% | 4.5% | 1317/1320 | 115.6 |
| jana-n | 11.6% | 4.8% | 897/904 | 154.1 |
| tomas-b | 17.8% | 8.4% | 962/962 | 164.3 |

## Reading

**daniel-r**
- names/places absent (stem match): none
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: `frustrated. He's having a dream and he's always been`
- largest wrong spans (script -> heard): [i feel it s] -> [he feels]; [tea on the] -> [do you have a]; [seventy three] -> [having a dream]; [your gp] -> [the rgp uh]; [ten kilometres] -> [on a kilometer]
**hannah-w**
- names/places absent (stem match): ['Hannah', 'Hargreaves', 'Ofsted']
- largest wrong spans (script -> heard): [in a similar] -> [going to assemble your]; [] -> [everything s been so]; [the four a m] -> [before i am]; [i am glad too] -> [good luck to you]; [] -> [a light fall]
**jana-n**
- names/places absent (stem match): ['Alena', 'Brno']
- largest wrong spans (script -> heard): [jsem to takhle] -> [tohle]; [co kdyby] -> [tak kdo by]; [smutek je dovoleny] -> [jsme take dovoleni]; [si odnesete z] -> [dnes se tez]; [koberec zpod] -> [koverec pod]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítě tě ublíží. Ublížíte jak? Ne, že bych chtě`
- largest wrong spans (script -> heard): [spatne pripojim vodic a] -> [vpadnim pred klidem vode ci]; [pot tresou se mi] -> [po tresouzene]; [je mi spatne od] -> [me spatnil]; [a kontroluji nekdy] -> [do kontrolu jen kdyz]; [predal kolegovi protoze] -> [prijal kolik ale proc]

Kinds of errors: Czech: diacritics and case endings, whole garbled words in the hard stretches (tomas-b "knoflík zvlášť" -> "knov tyzdlar", "vyběhli" -> "vyvelili", "pot třesou se mi ruce" -> "po tresouzene"), names of people drift ("Barbora" -> "Barbara", "Matějovi" -> "Matějevi", sister "Alena" -> "Alanu"). English: contractions and spelling variants (counted as errors: "gonna", "burnt", "math"), dropped "and", and lost named entities: "Hannah" -> "hana"/"hanach", "Ofsted" -> "offset", "year four" -> "earful"/"quarter".
Hallucinated or dropped sentences: none dropped wholesale (word counts match the script within 2%); the damage is local substitutions. In daniel-r the father's age is LOST in every variant: "he's seventy-three" is heard as "he's having a dream".
Self-harm sentence (tomas-b): the first-person verb is lost. Script "Že jí a dítěti ublížím" is heard as "Že jí a dítě tě ublíží" (third person, reads as "that he will hurt"). The therapist's next line "Ublížíte jak?" survives, so a human or an LLM can still infer it, but the client's own words are garbled.
Clean enough for fact extraction? Mostly for jana-n and the English recordings; tomas-b is borderline (OCD content mostly readable; the risk sentence and "Barbora je těhotná ve čtvrtém měsíci" -> "probora denotna" are damaged).
Verdict: best plain Whisper on Czech so far (beam 5 vs greedy differs by under 1 point). The baseline other variants must beat on BOTH recordings of a language.
