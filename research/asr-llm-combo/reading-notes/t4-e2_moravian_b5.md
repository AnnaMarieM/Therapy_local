# e2_moravian_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e2_moravian_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 38.8% | 31.3% | 1511/1309 | 42.7 |
| hannah-w | 22.1% | 14.2% | 1317/1283 | 30.3 |
| jana-n | 15.0% | 8.1% | 897/866 | 31.0 |
| tomas-b | 22.8% | 14.6% | 962/885 | 32.0 |

## Reading

**daniel-r**
- names/places absent (stem match): ['Hannah']
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: `frustrated, he's having to train and he's always me and he's from one`
- largest wrong spans (script -> heard): [that i ll write down when i wake up if i wake up] -> [dat hloubete tam kterou kterou kterou kterou ktero]; [my girlfriend booked it to be honest claire she sa] -> []; [might need something so even rest has a price yes ] -> [maji nejicicicicicicicicicicicicicicicicicicicicic]; [re nearly out of time how are you feeling now tire] -> []; [was a heart attack but it passed has that happened] -> [hapened bivor]
**hannah-w**
- names/places absent (stem match): ['Hannah', 'Ofsted']
- largest wrong spans (script -> heard): [never she s very supportive on the surface] -> []; [that sounds like recognition of your work] -> []; [and you can t just fix this] -> []; [tried that helped even a little] -> [try to tell you don let me]; [have friends at school you can] -> [fix it go]
**jana-n**
- names/places absent (stem match): ['Nováková', 'Alena', 'Brno', 'Olomouc']
- largest wrong spans (script -> heard): [prijala sedela jsem v kuchyni a nemohla] -> [prijal mela]; [jeste jste jim to nerekla ne] -> []; [mam sestru alenu v olomouci zavola] -> [vyvola]; [jak by se vam darilo] -> []; [co jim rikate alence] -> [a lince]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí ať jí děti ublíží. že bych chtěl, to je hrazné,`
- largest wrong spans (script -> heard): [mysli si ze jsem pedant sef mi rika ze jsem nejpec] -> []; [od te doby mam pocit ze to muzu zpusobit ja ze] -> [vzdyt]; [miluju a ze se potrebuju lecit aby ji neztratil] -> [nestratil]; [a diteti ublizim ublizite jak ne] -> [at ji deti ublizi]; [olomouci delam rozvody v bytech] -> [betech]

Kinds of errors: the parliament fine-tune is WORSE than plain turbo on our conversational audio (Czech 19.0% vs 16.3% beam 5) and collapses on English (22 to 41%; the forced-English decode comes out with Czech spellings: "praktoština", "Oktober", "tím", "logistik's"). Czech: drops whole phrases (tomas-b 885 of 962 words; "pracuji jako elektrikář v Olomouci dělám rozvody v bytech" -> "betech"), garbles verbs ("probudil" -> "pardubilo", "vyhořelo celé" -> "jehorilo cela"), keeps fillers ("uh") that the script does not have.
Hallucinated or dropped sentences: yes, dropped phrases of 3 to 8 words, several in tomas-b. Father's age in daniel-r lost like everywhere.
Self-harm sentence (tomas-b): heard "Že jí ať jí děti ublíží" (wrong person, plural), not the client's "dítěti ublížím".
Clean enough for fact extraction? No for tomas-b; English not usable.
Verdict: rejected. Parliament training (ParCzech4Speech, per the model card) does not transfer to dialogue and the model forgets English.
