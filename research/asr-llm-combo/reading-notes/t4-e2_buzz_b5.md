# e2_buzz_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e2_buzz_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 9.7% | 6.3% | 1511/1485 | 207.2 |
| hannah-w | 7.5% | 4.6% | 1317/1312 | 200.5 |
| jana-n | 21.2% | 24.6% | 897/806 | 320.9 |
| tomas-b | 31.6% | 8.2% | 962/872 | 254.2 |

## Reading

**daniel-r**
- names/places absent (stem match): none
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: `frustrated. He's having a dream and he's always been`
- largest wrong spans (script -> heard): [] -> [at dango s glare do you have]; [thing with claire tea on the] -> []; [there did anything] -> [here tell me a thing]; [i feel it s] -> [he feels]; [seventy three] -> [having a dream]
**hannah-w**
- names/places absent (stem match): ['Hannah', 'Hargreaves', 'Ofsted']
- largest wrong spans (script -> heard): [re now in a similar] -> [already know how to assemble your]; [awkward so you ve lost] -> [all great saved us]; [only that i] -> [one lady]; [i am glad] -> [and luck]; [year four] -> [here]
**jana-n**
- names/places absent (stem match): ['Alena', 'Brno']
- largest wrong spans (script -> heard): [detem pojdme na to postupne co spanek zkousela jst] -> [detemppppppppppppppppppppppppppppppppppppppppppppp]; [muzeme se priste podivat jak to pripravit co si od] -> [muuuuuuuuuuuuuuuuuuuuuuuuuuuu muuuuuuuuuuuuuuuuuuu]; [nasla jste ji] -> [nas vlastni]; [at je to] -> [a deto]; [jsem to takhle] -> [tohle]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `den pane bartle pojďte dál jak se vám dnes daří dobrý`
- largest wrong spans (script -> heard): [ruce a je mi spatne od zaludku kdo] -> [spatnil zaludkukdo]; [horelo u sousedu a my] -> [jehorovou sousedou]; [takoveho ne nikdy ale po] -> [takovehone nikdypo]; [a kontroluji nekdy se tam] -> [do kontrolu jen kdyz to]; [se podivat a kolegove mysli] -> [co podivata kolegovepyslis]

Kinds of errors: with beam 5 the output has punctuation but the space after the sentence-final mark is missing ("myšlenkaJakáŽe ji a dítě tě ublížímUblížíte jakNe"), which fuses words and ruins the WER (jana-n 21.2%, tomas-b 31.6%) while the CER is good on tomas-b (8.2%). jana-n CER is 24.6% because the model falls into degenerate repetition loops ("ppppp...", "muuuu...") and two stretches near the end (the sleeping-pills talk and the goodbye) are replaced by them.
Hallucinated or dropped sentences: yes in jana-n: repetition-loop hallucination replaces the final part of the session (about 90 script words). Father's age lost.
Self-harm sentence (tomas-b): "že ji a dítě tě ublížím" - first-person verb "ublížím" correct, the rest as in large-v3.
Clean enough for fact extraction? No for jana-n (dropped content), tomas-b readable if word boundaries are repaired.
Verdict: rejected: output-format artefacts plus dropped content on jana-n; English close to large-v3 (9.7% and 7.5% vs 9.0% and 7.1% at beam 5), little forgetting.
