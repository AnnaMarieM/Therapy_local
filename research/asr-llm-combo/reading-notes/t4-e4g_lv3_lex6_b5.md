# e4g_lv3_lex6_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e4g_lv3_lex6_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| jana-n | 11.0% | 4.9% | 897/902 | 306.1 |
| tomas-b | 17.9% | 8.6% | 962/967 | 310.7 |

## Reading

**jana-n**
- names/places absent (stem match): ['Alena', 'Brno']
- largest wrong spans (script -> heard): [prazdno strida se] -> [prazdnost tridaste]; [jsem to takhle] -> [tohle]; [rict detem pojdme] -> [rictete tak my]; [co kdyby] -> [tak kdo by]; [smutek je dovoleny] -> [jsme take dovoleni]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítě tě ublíží. Ublížíte jak? Ne, že bych chtě`
- largest wrong spans (script -> heard): [spatne pripojim vodic a] -> [vpadnim prikladne v vode ci]; [potrebuju lecit aby ji neztratil] -> [potrebovala ze zabehy nestratila]; [a kontroluji nekdy] -> [do kontrolu jen kdyz]; [predal kolegovi protoze] -> [prijal kolik ale proc]; [kontroluji sporak] -> [kontrola je v sporach]

Method: SoftLexicon LogitsProcessor; whenever the word typed so far is not in the word list, every token that would END the word (space-initial token, punctuation, EOT, timestamp) gets `penalty` subtracted. Word list: generic Hunspell cs_CZ unmunch (3.93M forms) + generic Wikidata Czech given names (458) and municipalities (5,347) with the same case-ending expansion for each (71,614 forms); NOTHING taken from the scripts (an earlier run that used capitalised script words was discarded as script-leaked and is not reported). English recordings not run (Czech word list).
Reference: large-v3 beam 5 without the processor: jana-n 11.6, tomas-b 17.8 (mean 14.7).
Kinds of errors: as lex1 but slightly less good: jana-n 11.0, tomas-b 17.9 (worse than plain beam 5 on tomas-b).
Self-harm sentence (tomas-b): unchanged. Hallucinated or dropped sentences: none new.
Verdict: no gain.
