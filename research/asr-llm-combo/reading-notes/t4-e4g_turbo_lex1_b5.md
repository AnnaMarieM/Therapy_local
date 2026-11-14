# e4g_turbo_lex1_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e4g_turbo_lex1_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| jana-n | 11.7% | 5.2% | 897/901 | 34.2 |
| tomas-b | 19.5% | 8.6% | 962/965 | 36.9 |

## Reading

**jana-n**
- names/places absent (stem match): ['Alena', 'Brno']
- largest wrong spans (script -> heard): [jindy zase ze] -> [jen kdyz]; [vam honilo] -> [mam o nelo]; [jsem to takhle] -> [tohle]; [co kdyby] -> [tak kdo by]; [pojdte dal] -> [petra]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítě tě ublíží. Ublížíte jak? Ne, že bych chtě`
- largest wrong spans (script -> heard): [v olomouci delam] -> [mam moci ja mam]; [poslednich mesicich mi dela] -> [posledni pristi si smidla]; [predal kolegovi protoze] -> [prijal kolik ale proc]; [nebo stojim u schodu] -> [dostajim uschodu a]; [donuti nechat] -> [do nuti nechal tutorene]

Method: SoftLexicon LogitsProcessor; whenever the word typed so far is not in the word list, every token that would END the word (space-initial token, punctuation, EOT, timestamp) gets `penalty` subtracted. Word list: generic Hunspell cs_CZ unmunch (3.93M forms) + generic Wikidata Czech given names (458) and municipalities (5,347) with the same case-ending expansion for each (71,614 forms); NOTHING taken from the scripts (an earlier run that used capitalised script words was discarded as script-leaked and is not reported). English recordings not run (Czech word list).
Reference: turbo beam 5 without the processor: jana-n 12.3, tomas-b 20.4 (mean 16.3).
Changes: jana-n 12.3 -> 11.7, tomas-b 20.4 -> 19.5, i.e. mean 16.3 -> 15.6, better on both recordings. Same kinds of fixes as for large-v3 (non-words replaced by real words); the remaining errors are as in plain turbo.
Self-harm sentence (tomas-b): unchanged "dítě tě ublíží".
Hallucinated or dropped sentences: none seen at this penalty.
Clean enough for fact extraction? As plain turbo, slightly better.
Verdict: the only gain in the whole T4 study that holds on both Czech recordings for the phone candidate (turbo): 0.6 to 0.9 points. Small; it would need a score hook in the phone decoder (not checked whether the app's runtime has one).
