# e2_buzz_b1 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e2_buzz_b1/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 9.0% | 5.9% | 1511/1476 | 131.3 |
| hannah-w | 8.0% | 4.9% | 1317/1313 | 121.2 |
| jana-n | 18.9% | 5.0% | 897/869 | 398.7 |
| tomas-b | 28.3% | 9.4% | 962/899 | 154.1 |

## Reading

**daniel-r**
- names/places absent (stem match): none
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: `frustrated. He's having a dream and he's always been`
- largest wrong spans (script -> heard): [bag i didn t realise] -> [bad wig and realized]; [i feel it s] -> [he feels]; [out loud] -> [i would allow it]; [thing with claire tea] -> [dangos glare d]; [seventy three] -> [having a dream]
**hannah-w**
- names/places absent (stem match): ['Hannah', 'Hargreaves', 'Ofsted']
- largest wrong spans (script -> heard): [re now in a similar] -> [already know how to assemble your]; [] -> [to tell even less than]; [so you ve lost] -> [saved us]; [helped even a little] -> []; [child laughing] -> [chill laugh thing]
**jana-n**
- names/places absent (stem match): ['Alena', 'Brno']
- largest wrong spans (script -> heard): [chybu a nasla jste ji ne jednou] -> [chybua nas vlastni nejednou]; [byt zavisla to je rozumne] -> [pit zavislato rozumim]; [koberec zpod nohou co] -> [koverec pod nohouco]; [hodne trapite ano a] -> [hodine trapiteano]; [nechci proc nechcete bojim] -> [nechciproc nechcetebodim]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `den pane bartle pojďte dál jak se vám dnes daří dobrý`
- largest wrong spans (script -> heard): [ruce a je mi spatne od zaludku kdo] -> [spatnil zaludkukdo]; [vracim a kontroluji nekdy] -> [zavraci doma kontrolovat jen kdyz]; [podivat a kolegove mysli si] -> [podivata kolegovem myslis]; [dozvedel to bych zvladl o] -> [dozvedelto tak zvladloo]; [zkontroluji sporak potreti nerekla] -> [zkontroluje sporaku po tretinerekla]

Kinds of errors: the raw output has no capitals and no punctuation and glues words together ("vypadázamknu", "kvítahu", "stavakazdy"), so WER is inflated (jana-n 18.9% WER but only 5.0% CER). Names lose their capital letters ("bartle" for "Bárto", "Barbora" missing, "Olomouc" missing). Czech content words are about as wrong as large-v3 ("knov týždlař", "pak byl u" for "probudil"). English is on par with large-v3 (daniel-r 9.0%, hannah-w 8.0%).
Hallucinated or dropped sentences: some dropped words in tomas-b (899 of 962 words). Father's age in daniel-r lost like everywhere.
Self-harm sentence (tomas-b): "že ji a dítě ubířím" - first-person verb present but garbled ("ubířím", not "ublížím").
Clean enough for fact extraction? No: no punctuation or capitalisation, fused words; an LLM could read it but names are harder to find.
Verdict: rejected as a drop-in. Its CER is good, so a repair step (punctuation and word splitting) could change the picture, but that is not tested.
