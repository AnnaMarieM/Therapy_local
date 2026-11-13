# e3_turbo_t0.5_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e3_turbo_t0.5_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 15.1% | 9.8% | 1511/1490 | 108.7 |
| hannah-w | 10.9% | 7.5% | 1317/1323 | 115.8 |
| jana-n | 18.9% | 8.1% | 897/888 | 98.9 |
| tomas-b | 27.1% | 12.5% | 962/946 | 94.3 |

## Reading

**daniel-r**
- names/places absent (stem match): none
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: `frustrated. Someone's a dream and he's always been t`
- largest wrong spans (script -> heard): [irritable i snapped at a colleague last week over] -> [colleagues must be going]; [thing with claire tea on the] -> [dangles glare do you know a]; [putting down a bag] -> [put an angle bad but]; [ten kilometres] -> [i got me tears]; [an answer before] -> [anyone to be for]
**hannah-w**
- names/places absent (stem match): ['Hargreaves', 'Ofsted']
- largest wrong spans (script -> heard): [] -> [to tell even less than]; [depth that they] -> [debt the day you]; [in a similar] -> [going to assemble your]; [] -> [everything s been support]; [helped even a little] -> []
**jana-n**
- names/places absent (stem match): ['Nováková', 'Alena', 'Eva', 'Brno', 'Olomouc']
- largest wrong spans (script -> heard): [vite o tom nekdo ve skole jen reditelka] -> []; [v lete jsme byli na dovolene] -> [vlatec nebyl nedopalane]; [prazdno strida se to] -> [prazdnost z trida jste]; [aspon zatim deti] -> [alespon se tim ti]; [treba si vedle postele] -> [co vas sedla postala]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a díce tě ublíží. Ublíží to jak? Ne, že bych cht`
- largest wrong spans (script -> heard): [to bych zvladl o tech myslenkach jeste ne] -> []; [zkontroluji sporak kazdy knoflik] -> [zkontroluje sprah kezdiknu v tech]; [lide kriceli vybehli jsme jenom] -> [kde jak recil vyvele zmano]; [zime a koukal jak hori] -> [zeme koukaji gori]; [nikdy ale po kazde zakazce] -> [nikde od pokracte tak ja]

Kinds of errors: every recording gets worse: new hallucinated words appear ("Daniel" -> "Danielle", an inserted "uh", "he's seventy-three" -> "is dirt"), more garbled Czech words. Names no better.
Hallucinated or dropped sentences: no wholesale drops, many local inventions. Father's age lost.
Self-harm sentence (tomas-b): "Že jí a díce tě ublíží" (still third person and garbled).
Clean enough for fact extraction? Worse than 1.0x.
Verdict: slowing to 0.5x hurts: +4 to 7 WER points on every recording versus turbo beam 5 without slowdown. Rejected.
