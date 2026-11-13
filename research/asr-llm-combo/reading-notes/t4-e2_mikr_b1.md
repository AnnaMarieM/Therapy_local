# e2_mikr_b1 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e2_mikr_b1/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 10.1% | 6.4% | 1511/1477 | 134.4 |
| hannah-w | 8.5% | 5.7% | 1317/1320 | 125.7 |
| jana-n | 14.3% | 6.0% | 897/882 | 189.9 |
| tomas-b | 23.1% | 11.1% | 962/945 | 162.2 |

## Reading

**daniel-r**
- names/places absent (stem match): none
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: `frustrated. He's having a dream and he's always bein`
- largest wrong spans (script -> heard): [i feel it s] -> [he feels]; [seventy three] -> [having a dream]; [out loud] -> [i would hold]; [putting down] -> [put it in]; [i ll write] -> [right]
**hannah-w**
- names/places absent (stem match): ['Hargreaves', 'Ofsted']
- largest wrong spans (script -> heard): [in a similar] -> [going to assemble your]; [so you ve lost] -> [say it was]; [] -> [everything s been so]; [do you have] -> [be]; [in and] -> [on]
**jana-n**
- names/places absent (stem match): ['Nováková', 'Alena', 'Brno']
- largest wrong spans (script -> heard): [prazdno strida se to] -> [prazdnost tri dvacetu]; [novakova pojdte dal] -> [dobra chvapet dala]; [nasla jste ji] -> [naslastny]; [darilo rict ji] -> [dorelejici]; [sama s hlavou] -> [samozahlavou]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítětě ublížím.Ublížíte jak?Ne, že bych chtěl,`
- largest wrong spans (script -> heard): [a je mi spatne od] -> [me spatnil]; [po kazde zakazce se vracim] -> [pokracujeme]; [mizerne vstavam dvakrat v noci] -> [mezerne stavam noce vakrat]; [dnes dari dobry den] -> [to zdari dobre jdem]; [zime a koukal jak] -> [zeme ukazek]

Kinds of errors: Czech worse than large-v3 on both recordings (jana-n 13.9% vs 11.6%, tomas-b 21.4% vs 17.8%, beam 5); the raw output drops the space after "?" (cosmetic, not counted by the WER); client address "pane Bárto" and "Nováková" missed. English nearly intact (daniel-r 10.1%, hannah-w 8.5% greedy vs 9.7/7.5 for large-v3): little forgetting, nothing gained.
Hallucinated or dropped sentences: no large drops. Father's age (daniel-r) lost like in every variant.
Self-harm sentence (tomas-b): the BEST so far: "Že jí a dítětě ublížím." The first-person verb "ublížím" is right, only the case of "dítěti" is wrong. Clinically more faithful than every other variant although the WER is higher.
Clean enough for fact extraction? Comparable to large-v3 on jana-n; tomas-b worse overall but the key risk sentence is better.
Verdict: higher WER, not a WER win. The only variant so far that keeps the self-harm statement in first person; one sentence on one recording is not enough to conclude anything.
