# e4g_turbo_lex12_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e4g_turbo_lex12_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| jana-n | 66.6% | 104.6% | 897/346 | 117.2 |
| tomas-b | 70.4% | 90.9% | 962/357 | 131.5 |

## Reading

**jana-n**
- names/places absent (stem match): ['Alena', 'Brno', 'Olomouc']
- largest wrong spans (script -> heard): [ze ho mame oba radi a ze se to nezmeni jenze ja ne] -> [zehohohohohohohohohohohohohohohohohohohohohohohoho]; [rika minuly tyden se me zeptala jestli se s tatou ] -> [rikatevatevatevatevatevatevatevatevatevatevatevate]; [reditelka rekla jsem ji to protoze jsem se jednou ] -> [reditelkareklareklareklareklareklareklareklareklar]; [rict pak jsem sla uklidit nadobi jako by se nic ne] -> [rictesticicicicicicicicicicicicicicicicicicicicici]; [rikala ze manzelstvi se drzi at je to jak chce co ] -> [rikalazemanzelstvisedrziatatatatatatatatatatatatat]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `den, pane Bárto, pojďte dál. Jak se vám to zdaří? Dob`
- largest wrong spans (script -> heard): [odejit z bytu za pet minut a tesit se na dite bez ] -> [odejizdejicicicicicicicicicicicicicicicicicicicici]; [rika ze uz to takhle nejde a ja vlastne taky ne co] -> [rikazezustutototototototototototototototototototot]; [tohle je konec nikdo nebyl zranen ale sousedum to ] -> [dovovahaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa]; [cim se uzkost projevuje srdce pot tresou se mi ruc] -> [cimzuzitejstejstejstejstejstejstejstejstejstejstej]; [vodic a neco shori stalo se vam neco takoveho ne n] -> [vode clovek]

Method: SoftLexicon LogitsProcessor; whenever the word typed so far is not in the word list, every token that would END the word (space-initial token, punctuation, EOT, timestamp) gets `penalty` subtracted. Word list: generic Hunspell cs_CZ unmunch (3.93M forms) + generic Wikidata Czech given names (458) and municipalities (5,347) with the same case-ending expansion for each (71,614 forms); NOTHING taken from the scripts (an earlier run that used capitalised script words was discarded as script-leaked and is not reported). English recordings not run (Czech word list).
Reference: turbo beam 5 without the processor: jana-n 12.3, tomas-b 20.4 (mean 16.3).
Kinds of errors: collapse (jana-n 66.6%, tomas-b 70.4%): "ícícícící..." loops, 344 of 897 words in jana-n, invented "Petra".
Hallucinated or dropped sentences: yes, most of the session.
Verdict: rejected.
