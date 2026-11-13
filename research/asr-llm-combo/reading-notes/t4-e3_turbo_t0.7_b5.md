# e3_turbo_t0.7_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e3_turbo_t0.7_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 12.0% | 7.8% | 1511/1487 | 121.9 |
| hannah-w | 9.2% | 6.1% | 1317/1318 | 98.1 |
| jana-n | 15.3% | 6.5% | 897/908 | 114.9 |
| tomas-b | 21.9% | 10.3% | 962/964 | 153.7 |

## Reading

**daniel-r**
- names/places absent (stem match): none
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: `frustrated. He's having a dream, and he's always bee`
- largest wrong spans (script -> heard): [gp suggested] -> [general practitioner used to suggest that]; [ten kilometres] -> [and we call me two years]; [thing with claire tea on the] -> [dangless glare do you know]; [my head just starts] -> [we had to start]; [i snapped at a] -> [he slept to the]
**hannah-w**
- names/places absent (stem match): ['Hargreaves', 'Ofsted']
- largest wrong spans (script -> heard): [in a similar] -> [going to assemble your]; [at school you can] -> [that go and]; [year four] -> [ear for a]; [s awkward so] -> [all right say]; [along with] -> [how long was]
**jana-n**
- names/places absent (stem match): ['Alena', 'Brno']
- largest wrong spans (script -> heard): [pojdte dal] -> [a ted ted dala]; [delo uvnitr vas] -> [dalo u me trvas]; [anicka je citliva] -> [ani spet si treba]; [delate ve vsednich] -> [dal tve vsichni]; [alence] -> [ale to je]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a jí dětě ublížím. Ublížíte jak? Ne, že bych cht`
- largest wrong spans (script -> heard): [spatne pripojim vodic a] -> [v takem prikladem v hode co]; [vracim a kontroluji nekdy] -> [zavraci v moji kontrol jen kdyz]; [tydne opravy kdyz je co] -> [dydne no prave nez vsechno]; [tricetkrat za vecer] -> [tricet x zjes her]; [knoflik zvlast pak zase] -> [knofti vzvlast pada se]

Turbo beam 5 without slowdown is the reference: Czech 12.3 / 20.4 (mean 16.3), English 10.1 / 7.1 (mean 8.6).
Kinds of errors: same families as the unslowed turbo (garbled verbs and case endings in Czech, lost English named entities), slightly more of them.
Hallucinated or dropped sentences: no wholesale drops seen in the aligned error lists; father's age in daniel-r lost ("he's having a dream").
Self-harm sentence (tomas-b): "Že jí a jí dětě ublížím" - first-person verb kept, case wrong (better than at 1.0x, where it is "dítě tě ublíží"). One sentence, so a hint at most.
Clean enough for fact extraction? Not better than 1.0x.
Verdict: Czech 18.6 (+2.3), English 10.6 (+2.0): worse on all four recordings. Rejected.
