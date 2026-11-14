# e4g_lv3_lex1_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e4g_lv3_lex1_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| jana-n | 11.0% | 4.7% | 897/903 | 515.3 |
| tomas-b | 17.3% | 8.2% | 962/958 | 307.9 |

## Reading

**jana-n**
- names/places absent (stem match): ['Alena', 'Brno']
- largest wrong spans (script -> heard): [jsem to takhle] -> [tohle]; [co kdyby] -> [tak kdo by]; [smutek je dovoleny] -> [jsme take dovoleni]; [si odnesete z] -> [dnes se test]; [koberec zpod] -> [koverec pod]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítě tě ublíží. Ublížíte jak? Ne, že bych chtě`
- largest wrong spans (script -> heard): [ruce a je mi spatne od] -> [spatnil]; [spatne pripojim vodic a] -> [vpadnim prikladne v vode ci]; [a kontroluji nekdy] -> [do kontrolu jen kdyz]; [predal kolegovi protoze] -> [prijal kolik ale proc]; [nebo stojim u schodu] -> [na dostojim uschodu a]

Method: SoftLexicon LogitsProcessor; whenever the word typed so far is not in the word list, every token that would END the word (space-initial token, punctuation, EOT, timestamp) gets `penalty` subtracted. Word list: generic Hunspell cs_CZ unmunch (3.93M forms) + generic Wikidata Czech given names (458) and municipalities (5,347) with the same case-ending expansion for each (71,614 forms); NOTHING taken from the scripts (an earlier run that used capitalised script words was discarded as script-leaked and is not reported). English recordings not run (Czech word list).
Reference: large-v3 beam 5 without the processor: jana-n 11.6, tomas-b 17.8 (mean 14.7).
Changes vs plain beam 5 (aligned to the script, cmp.py): jana-n fixed 5 words, broke 2; tomas-b fixed 9, broke 7. Fixed: "tricet x zecer" -> "třicetkrát", "kredlikem" -> "knedlíkem", "tesi" -> "děsí", "mestnosti" -> "místnosti", "se ovisi" -> "souvisí", "matějevi" -> "Matějovi" (non-words replaced by real words). Broke: "vyhořelo celé" -> "vyhořel celá", "v Olomouci" -> "mam holce mam" (a different non-word, the town is still missing), "ano" -> "ne".
Kinds of errors that remain: the same hard stretches (garbled verbs, "Olomouci" lost), because a wrong REAL word or wrong form is not penalised, only non-words. The processor cannot fix case endings or substitutions with valid words.
Self-harm sentence (tomas-b): unchanged "Že jí a dítě tě ublíží" (third person; all words are valid Czech, so the lexicon has nothing to correct). Father's age: not applicable (Czech only).
Hallucinated or dropped sentences: none new.
Clean enough for fact extraction? Same as plain beam 5.
Verdict: gain 0.5 mean WER (11.6 -> 11.0, 17.8 -> 17.3), holds on both recordings but is only about 5 words on each; too small to call a real effect on two recordings. Not a clinically relevant improvement.
