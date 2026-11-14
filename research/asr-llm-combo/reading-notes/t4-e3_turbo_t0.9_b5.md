# e3_turbo_t0.9_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e3_turbo_t0.9_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 10.0% | 6.7% | 1511/1496 | 108.1 |
| hannah-w | 7.1% | 4.9% | 1317/1317 | 113.0 |
| jana-n | 13.5% | 5.5% | 897/903 | 118.0 |
| tomas-b | 21.0% | 9.1% | 962/962 | 125.5 |

## Reading

**daniel-r**
- names/places absent (stem match): none
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: `frustrated, he's having a dream and he's always been the strong one`
- largest wrong spans (script -> heard): [with claire tea on the] -> [was clear do you know]; [client whether i ve] -> [climb weather and]; [out loud how] -> [i would love who]; [seventy three] -> [having a dream]; [guilt i feel] -> [girl]
**hannah-w**
- names/places absent (stem match): ['Hannah', 'Hargreaves', 'Ofsted']
- largest wrong spans (script -> heard): [year four] -> [here]; [the head] -> [they had]; [four] -> [for a]; [suggests] -> [s just]; [through the] -> [for a]
**jana-n**
- names/places absent (stem match): ['Alena', 'Brno']
- largest wrong spans (script -> heard): [prazdno strida se] -> [prazdnost trida jste]; [honilo hlavou] -> [o ni lhlavou]; [praci je] -> [prace jsem mela]; [at je to] -> [a deto]; [jsem to takhle] -> [tohle]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítě tě ublíží. Ublížíte jak? Ne, že bych chtě`
- largest wrong spans (script -> heard): [spatne pripojim vodic a] -> [v patem pripojem v hode co]; [vracim a kontroluji nekdy] -> [taka se zavracime kontrolo jen nekdo]; [pot tresou se mi] -> [po tresouzene]; [sporak potreti] -> [z porak po treti]; [poslednich mesicich mi dela] -> [posledni pristi si mydla]

Turbo beam 5 without slowdown is the reference: Czech 12.3 / 20.4 (mean 16.3), English 10.1 / 7.1 (mean 8.6).
Kinds of errors: same as unslowed turbo.
Hallucinated or dropped sentences: none wholesale; father's age lost.
Self-harm sentence (tomas-b): same damage as at 1.0x.
Clean enough for fact extraction? Equal to 1.0x.
Verdict: Czech 17.2 (+0.9, worse on both), English 8.6 (equal: daniel-r 10.0 vs 10.1, hannah-w 7.1 vs 7.1). Slowing down never helped on the T4; the effect is monotone, the more slowdown the worse. Rejected.
