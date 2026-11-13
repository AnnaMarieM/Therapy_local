# e1_turbo_b1 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e1_turbo_b1/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 10.6% | 6.5% | 1511/1472 | 21.5 |
| hannah-w | 7.1% | 4.3% | 1317/1313 | 20.6 |
| jana-n | 12.5% | 5.1% | 897/902 | 39.4 |
| tomas-b | 21.7% | 9.2% | 962/959 | 24.7 |

## Reading

**daniel-r**
- names/places absent (stem match): none
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: `frustrated. He's on the train and he's always been t`
- largest wrong spans (script -> heard): [the client whether i ve] -> [decline weather and]; [bag i didn t realise] -> [bad way to realize]; [with claire tea on the] -> [was clear do you know]; [and i say fine] -> [lisefroin]; [just starts] -> [s a start]
**hannah-w**
- names/places absent (stem match): ['Hargreaves', 'Ofsted']
- largest wrong spans (script -> heard): [that helped even a little] -> [to tell you and let me]; [re now in a similar] -> [know when assemble your]; [year four] -> [ear for a]; [year four] -> [here]; [headteacher] -> [head teacher]
**jana-n**
- names/places absent (stem match): ['Brno', 'Chorvatsko']
- largest wrong spans (script -> heard): [k detem ceho] -> [kde tam kde ho]; [jsem to takhle] -> [tohle]; [co kdyby] -> [tak kdo by]; [pojdte dal] -> [petra]; [chorvatsku a] -> [horvatsku]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítě tě ublíží. Ublížíte jak? Ne, že bych chtě`
- largest wrong spans (script -> heard): [v olomouci delam] -> [vam a mouce ja mam]; [mizerne vstavam dvakrat v noci] -> [mezerne stavam noce vlakrat]; [tricetkrat za vecer kontroluji] -> [trickrat zvecer kontrolu]; [je mi spatne od] -> [jme spatnil]; [poslednich mesicich mi] -> [posledni pristi si my]

Kinds of errors: Czech: diacritics and case endings, whole garbled words in the hard stretches (tomas-b "knoflík zvlášť" -> "knov tyzdlar", "vyběhli" -> "vyvelili", "pot třesou se mi ruce" -> "po tresouzene"), names of people drift ("Barbora" -> "Barbara", "Matějovi" -> "Matějevi", sister "Alena" -> "Alanu"). English: contractions and spelling variants (counted as errors: "gonna", "burnt", "math"), dropped "and", and lost named entities: "Hannah" -> "hana"/"hanach", "Ofsted" -> "offset", "year four" -> "earful"/"quarter".
Hallucinated or dropped sentences: none dropped wholesale (word counts match the script within 2%); the damage is local substitutions. In daniel-r the father's age is LOST in every variant: "he's seventy-three" is heard as "he's having a dream".
Self-harm sentence (tomas-b): the first-person verb is lost. Script "Že jí a dítěti ublížím" is heard as "Že jí a dítě tě ublíží" (third person, reads as "that he will hurt"). The therapist's next line "Ublížíte jak?" survives, so a human or an LLM can still infer it, but the client's own words are garbled.
Clean enough for fact extraction? Mostly for jana-n and the English recordings; tomas-b is borderline (OCD content mostly readable; the risk sentence and "Barbora je těhotná ve čtvrtém měsíci" -> "probora denotna" are damaged).
Verdict: same kinds of errors as large-v3 but 1 to 3 points worse on Czech (tomas-b 20 to 22%); self-harm sentence damaged in the same way. Father's age lost. The candidate that fits a phone.
