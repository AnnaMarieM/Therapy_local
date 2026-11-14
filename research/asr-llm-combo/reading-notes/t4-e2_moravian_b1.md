# e2_moravian_b1 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e2_moravian_b1/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 41.0% | 29.4% | 1511/1313 | 27.1 |
| hannah-w | 30.3% | 20.0% | 1317/1210 | 24.7 |
| jana-n | 16.8% | 9.8% | 897/845 | 44.0 |
| tomas-b | 30.9% | 22.5% | 962/831 | 22.8 |

## Reading

**daniel-r**
- names/places absent (stem match): ['Hannah']
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: ``
- largest wrong spans (script -> heard): [carry that you d like to put down first the nights] -> [care ktery vam ktery byl putt dal vrst]; [stopped about two months ago my legs felt like lea] -> [stopt]; [probably i just can t picture where the time would] -> [dve let toho my vlada sdili a my vlada sdili a jiz]; [then not one thing we lost a big client and half t] -> [dan]; [think part of me would be relieved and then i d fe] -> [vam]
**hannah-w**
- names/places absent (stem match): ['Hannah', 'Ofsted']
- largest wrong spans (script -> heard): [i was made deputy head at my school i ve taught ye] -> []; [those are clear goals how do you feel now saying t] -> []; [september he was delighted that makes it worse som] -> [septembr a hy vas delajte dat mas jit vrat semhou ]; [similar] -> [liby ktera sam ze jde na nejake dob debate z]; [library after her and you re now in] -> [jde jde nam namit]
**jana-n**
- names/places absent (stem match): ['Nováková', 'Brno']
- largest wrong spans (script -> heard): [ze me nechala odejit driv dala mi hodinu volna] -> []; [ze jsem to mela videt ze jsem slepa] -> [a]; [nejradeji bych to mela za sebou ale] -> []; [presne tak pro dnesek tady skoncime] -> []; [co delate ve vsednich dnech] -> []
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítěti ublížím. Ublížíte jak? Ne, že bych chtě`
- largest wrong spans (script -> heard): [o hodinu vic denne uz jsem par zakazek predal kole] -> []; [ale ta myslenka prijde treba kdyz krajim chleba ne] -> []; [zacalo to pred dvema lety kdy horelo u sousedu a m] -> []; [ne zacali bychom treba jen tim ze zkontrolujete dv] -> [neni]; [a zatahnu za kliku pak zkontroluji sporak kazdy kn] -> []

Kinds of errors: the parliament fine-tune is WORSE than plain turbo on our conversational audio (Czech 19.0% vs 16.3% beam 5) and collapses on English (22 to 41%; the forced-English decode comes out with Czech spellings: "praktoština", "Oktober", "tím", "logistik's"). Czech: drops whole phrases (tomas-b 885 of 962 words; "pracuji jako elektrikář v Olomouci dělám rozvody v bytech" -> "betech"), garbles verbs ("probudil" -> "pardubilo", "vyhořelo celé" -> "jehorilo cela"), keeps fillers ("uh") that the script does not have.
Hallucinated or dropped sentences: yes, dropped phrases of 3 to 8 words, several in tomas-b. Father's age in daniel-r lost like everywhere.
Self-harm sentence (tomas-b): heard "Že jí ať jí děti ublíží" (wrong person, plural), not the client's "dítěti ublížím".
Clean enough for fact extraction? No for tomas-b; English not usable.
Verdict: rejected. Parliament training (ParCzech4Speech, per the model card) does not transfer to dialogue and the model forgets English.
