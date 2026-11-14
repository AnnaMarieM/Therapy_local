# e4g_turbo_lex3_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e4g_turbo_lex3_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| jana-n | 11.9% | 5.2% | 897/900 | 34.1 |
| tomas-b | 19.5% | 8.9% | 962/969 | 41.4 |

## Reading

**jana-n**
- names/places absent (stem match): ['Alena', 'Brno']
- largest wrong spans (script -> heard): [vam honilo hlavou] -> [mam o ne lhavou]; [jindy zase ze] -> [jen kdyz]; [jsem to takhle] -> [tohle]; [co kdyby] -> [tak kdo by]; [pojdte dal] -> [petra]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítě tě ublíží. Ublížíte jak? Ne, že bych chtě`
- largest wrong spans (script -> heard): [potrebuju lecit aby ji neztratil] -> [potrebovala ze zabij chybi nestratil]; [tricetkrat za vecer kontroluji] -> [trickrat zves her kontroly]; [v olomouci delam] -> [mam moci ja mam]; [poslednich mesicich mi] -> [posledni pristi si me]; [predal kolegovi protoze] -> [prival kolik ale proc]

Method: SoftLexicon LogitsProcessor; whenever the word typed so far is not in the word list, every token that would END the word (space-initial token, punctuation, EOT, timestamp) gets `penalty` subtracted. Word list: generic Hunspell cs_CZ unmunch (3.93M forms) + generic Wikidata Czech given names (458) and municipalities (5,347) with the same case-ending expansion for each (71,614 forms); NOTHING taken from the scripts (an earlier run that used capitalised script words was discarded as script-leaked and is not reported). English recordings not run (Czech word list).
Reference: turbo beam 5 without the processor: jana-n 12.3, tomas-b 20.4 (mean 16.3).
Changes: jana-n 11.9, tomas-b 19.5 (mean 15.7), better than plain turbo on both recordings, about equal to lex1.
Self-harm sentence (tomas-b): unchanged. Hallucinated or dropped sentences: none seen.
Verdict: same small gain as lex1.
