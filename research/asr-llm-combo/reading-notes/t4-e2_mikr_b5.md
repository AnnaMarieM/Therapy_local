# e2_mikr_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e2_mikr_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| jana-n | 13.9% | 5.6% | 897/886 | 244.9 |
| tomas-b | 21.4% | 10.7% | 962/952 | 256.1 |

## Reading

**jana-n**
- names/places absent (stem match): ['Nováková', 'Alena', 'Brno']
- largest wrong spans (script -> heard): [prazdno strida se to] -> [prazdnost tri dvacetu]; [anicka je citliva] -> [a ani cloveci pliva]; [novakova pojdte dal] -> [dovakala petra]; [nasla jste ji] -> [naslastny]; [povidejte] -> [to je jedno]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `e jí a dítětě ublížím.Ublížíte jak?Ne, že bych chtěl,`
- largest wrong spans (script -> heard): [po kazde zakazce se vracim a kontroluji nekdy se] -> [pokracujeme kontrolu jen kdyz]; [spatne pripojim vodic a neco shori] -> [vpadne prikladni vode cehonco skori]; [a je mi spatne od] -> [me spatnil]; [mizerne vstavam dvakrat v noci] -> [mezerne stavam noce vakrat]; [dnes dari dobry den] -> [to zdari dobre jdem]

Kinds of errors: Czech worse than large-v3 on both recordings (jana-n 13.9% vs 11.6%, tomas-b 21.4% vs 17.8%, beam 5); the raw output drops the space after "?" (cosmetic, not counted by the WER); client address "pane Bárto" and "Nováková" missed. English nearly intact (daniel-r 10.1%, hannah-w 8.5% greedy vs 9.7/7.5 for large-v3): little forgetting, nothing gained.
Hallucinated or dropped sentences: no large drops. Father's age (daniel-r) lost like in every variant.
Self-harm sentence (tomas-b): the BEST so far: "Že jí a dítětě ublížím." The first-person verb "ublížím" is right, only the case of "dítěti" is wrong. Clinically more faithful than every other variant although the WER is higher.
Clean enough for fact extraction? Comparable to large-v3 on jana-n; tomas-b worse overall but the key risk sentence is better.
Verdict: higher WER, not a WER win. The only variant so far that keeps the self-harm statement in first person; one sentence on one recording is not enough to conclude anything.
