# e4t_turbo_lex6_therapistnames_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e4t_turbo_lex6_therapistnames_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| jana-n | 20.0% | 16.3% | 897/821 | 37.0 |
| tomas-b | 30.2% | 31.0% | 962/845 | 61.1 |

## Reading

**jana-n**
- names/places absent (stem match): ['Alena', 'Brno']
- largest wrong spans (script -> heard): [rict ji pravdu nejradeji bych to mela za sebou ale] -> [rictiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiii]; [vam honilo hlavou] -> [mam o ne lhavou]; [k detem ceho] -> [kde tam kde ho]; [jindy zase ze] -> [jen kdyz]; [jsem to takhle] -> [tohle]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítě tě ublíží. Ublížíte jak? Ne, že bych chtě`
- largest wrong spans (script -> heard): [knoflik zvlast pak zase dvere a kdyz to udelate co] -> [knoftizdlastejicicicicicicicicicicicicicicicicicic]; [by se mnou nechtela byt co si myslite ze by rekla ] -> [zabij chyti nestratilehozehozehozehozehozehozehoze]; [potrebuju lecit aby ji neztratil zatim se bojim] -> [potrebovala]; [tricetkrat za vecer kontroluji] -> [trickrat zves her kontroly]; [lide kriceli vybehli] -> [kde je kricela vyvehli]

Method: SoftLexicon LogitsProcessor; whenever the word typed so far is not in the word list, every token that would END the word (space-initial token, punctuation, EOT, timestamp) gets `penalty` subtracted. Word list: generic Hunspell cs_CZ unmunch (3.93M forms) + generic Wikidata Czech given names (458) and municipalities (5,347) with the same case-ending expansion for each (71,614 forms); NOTHING taken from the scripts (an earlier run that used capitalised script words was discarded as script-leaked and is not reported). English recordings not run (Czech word list).
LABELLED UPPER BOUND, not a fair test: therapist-given names added (see the large-v3 note). Turbo, penalty 6.
Result: identical to the generic list at penalty 6 (jana-n 20.0, tomas-b 30.2), i.e. the same collapse as without the names.
Verdict: no gain from giving the names.
