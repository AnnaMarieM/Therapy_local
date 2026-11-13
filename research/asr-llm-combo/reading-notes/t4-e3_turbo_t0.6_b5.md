# e3_turbo_t0.6_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e3_turbo_t0.6_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 12.2% | 8.1% | 1511/1490 | 75.4 |
| hannah-w | 9.8% | 6.7% | 1317/1320 | 75.1 |
| jana-n | 15.7% | 6.4% | 897/902 | 132.8 |
| tomas-b | 23.4% | 11.1% | 962/960 | 89.0 |

## Reading

**daniel-r**
- names/places absent (stem match): none
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: `frustrated. He's having a dream and he's always been`
- largest wrong spans (script -> heard): [thing with claire tea on the] -> [dangling glare do you know]; [and irritable i snapped at] -> [inheritable he slept in]; [client whether i ve] -> [climb weather and]; [and i say fine] -> [my safe friend]; [seventy three] -> [having a dream]
**hannah-w**
- names/places absent (stem match): ['Hargreaves', 'Ofsted']
- largest wrong spans (script -> heard): [that helped even a little] -> [to tell you and left me]; [re now in a similar] -> [know how to assemble your]; [that sounds worth] -> [of some sort of back]; [school] -> [gold have talked to]; [got worse since] -> [the curse in]
**jana-n**
- names/places absent (stem match): ['Nováková', 'Alena', 'Brno']
- largest wrong spans (script -> heard): [delo uvnitr] -> [dalo u me treba]; [novakova pojdte dal] -> [mlakova petrela]; [ji chtela rict] -> [jitla lic]; [jsem to takhle] -> [tohle]; [vedet jak to] -> [vyjede k]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítě tě ublížím. Ublížíte jak? Ne, že bych cht`
- largest wrong spans (script -> heard): [vracim a kontroluji] -> [taka se zavracim ve kontrol jen]; [kontroluji sporak i kdyz tam nic] -> [kontrola je sporad takze v tamec]; [lide kriceli vybehli jsme jenom] -> [kde je kricela vybele zpnano]; [zime a koukal jak hori] -> [zeme ulka gori]; [tydne opravy kdyz je co] -> [dene no prave ne vsechno]

Kinds of errors: as at 0.5x but milder; Czech about 3 points worse than turbo beam 5 at 1.0x, English about 2.5 points worse. Self-harm sentence still third person ("dítě tě ublížím", a slip toward first person but wrong case). Father's age lost.
Hallucinated or dropped sentences: none wholesale.
Clean enough for fact extraction? Not better than 1.0x.
Verdict: worse than no slowdown on all four recordings. Rejected.
