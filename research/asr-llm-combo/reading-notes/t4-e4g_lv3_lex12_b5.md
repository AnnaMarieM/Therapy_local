# e4g_lv3_lex12_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e4g_lv3_lex12_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| jana-n | 58.2% | 91.3% | 897/447 | 580.1 |
| tomas-b | 53.6% | 76.7% | 962/525 | 572.6 |

## Reading

**jana-n**
- names/places absent (stem match): ['Brno']
- largest wrong spans (script -> heard): [rict pak jsem sla uklidit nadobi jako by se nic ne] -> [rictojictojictojictojictojictojictojictojictojicto]; [rict jeste jste jim to nerekla ne radek chce abych] -> [rictorictorictorictorictorictorictorictorictoricto]; [skoro kazdy vecer a kamaradku evu z prace co jim r] -> [rikazechbramhroznematekolomsebinekohozkymuzetemluv]; [objala co byste ji chtela rict ze to boli ze nechc] -> [olbelalatejtejtejtejtejtejtejtejtejtejtejtejtejtej]; [ze bych se nemohla vcas vzbudit pro deti a nechci ] -> [nezvladnou]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítě tě ublíží. Ublížíte jak? Ne, že bych chtě`
- largest wrong spans (script -> heard): [rika ze uz to takhle nejde a ja vlastne taky ne co] -> [rikasuztovstvotakhlenejdeaovlastnietakynejdeaovlas]; [vybehli jsme jenom v pyzamech jak vam tehdy bylo z] -> [vyvehlizmejicimejicimejicimejicimejicimejicimejici]; [rika ze jsem nejpeclivejsi ve firme ale nevidi kol] -> [rikajicijicijicijijicijijijijijijijijijijijijijiji]; [pot tresou se mi ruce a je mi spatne od zaludku kd] -> [po tresouzeneucejmespartnildelutkujikujikujikujiku]; [nebo stojim u schodu pak se bojim ze to pujde samo] -> [na dostajimymununununununununununununununununununu]

Method: SoftLexicon LogitsProcessor; whenever the word typed so far is not in the word list, every token that would END the word (space-initial token, punctuation, EOT, timestamp) gets `penalty` subtracted. Word list: generic Hunspell cs_CZ unmunch (3.93M forms) + generic Wikidata Czech given names (458) and municipalities (5,347) with the same case-ending expansion for each (71,614 forms); NOTHING taken from the scripts (an earlier run that used capitalised script words was discarded as script-leaked and is not reported). English recordings not run (Czech word list).
Reference: large-v3 beam 5 without the processor: jana-n 11.6, tomas-b 17.8 (mean 14.7).
Kinds of errors: COLLAPSE (jana-n 58.2%, tomas-b 53.6%). A penalty this large traps the decoder: the word never gets closed because the half-typed word is never in the list, so it loops on word pieces ("ícícícíc...") and the transcript shrinks to 446 of 897 words for jana-n; the ending ("Děkuji. Uvidíme se příští týden.") survives but the middle of the session is lost.
Hallucinated or dropped sentences: yes, large parts dropped and replaced by loops. A hard-block-like penalty is clinically unsafe: it silently deletes content.
Verdict: rejected; shows that the penalty must be small.
