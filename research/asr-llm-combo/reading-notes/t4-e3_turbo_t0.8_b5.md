# e3_turbo_t0.8_b5 (t4-gpu, 2026-11-13)

HF transformers fp16 on a Colab T4, sequential long-form generate, language forced, no VAD (see the logbook). Raw transcripts: `transcripts/t4-gpu/<lang>/e3_turbo_t0.8_b5/`.

| recording | WER | CER | words ref/hyp | seconds on T4 |
|---|---|---|---|---|
| daniel-r | 11.1% | 7.0% | 1511/1501 | 102.6 |
| hannah-w | 9.3% | 6.2% | 1317/1328 | 99.6 |
| jana-n | 14.6% | 5.9% | 897/896 | 70.8 |
| tomas-b | 22.1% | 10.0% | 962/965 | 79.5 |

## Reading

**daniel-r**
- names/places absent (stem match): none
- father's age: LOST (script 'he's seventy-three'; see the aligned error list)
  - heard around it: `frustrated, he's having a dream and he's always been the strong one`
- largest wrong spans (script -> heard): [thing with claire tea on the] -> [dangle s glare do you have a]; [and irritable i snapped at a] -> [inheritable he slept to the]; [his hands shake and] -> [he s handshake]; [just starts] -> [s a start]; [seventy three] -> [having a dream]
**hannah-w**
- names/places absent (stem match): ['Hargreaves', 'Ofsted']
- largest wrong spans (script -> heard): [awkward so you ve lost] -> [all right say it was]; [at school you can] -> [that go and]; [out loud] -> [all well uh lightful]; [year four] -> [ear for a]; [year four] -> [here]
**jana-n**
- names/places absent (stem match): ['Brno']
- largest wrong spans (script -> heard): [vedet jak to rict] -> [vyjede kteric]; [nasla jste ji] -> [nas vlastni]; [alence] -> [ale to je]; [jsem to takhle] -> [tohle]; [co kdyby] -> [tak kdo by]
**tomas-b**
- names/places absent (stem match): ['Olomouc']
- self-harm sentence (script: 'Že jí a dítěti ublížím.'): heard `Že jí a dítě tě ublíží. Ublížíte jak? Ne, že bych chtě`
- largest wrong spans (script -> heard): [nebo stojim u schodu pak] -> [na dostajim uschodu a tak]; [knoflik zvlast pak zase] -> [knofty vzlar pavel se]; [pot tresou se mi] -> [po presuzeme]; [sporak potreti] -> [z poraku po treti]; [v olomouci delam] -> [malo moc ja mam]

Turbo beam 5 without slowdown is the reference: Czech 12.3 / 20.4 (mean 16.3), English 10.1 / 7.1 (mean 8.6).
Kinds of errors: same families as unslowed turbo.
Hallucinated or dropped sentences: none wholesale; father's age lost.
Self-harm sentence (tomas-b): same damage as at 1.0x.
Clean enough for fact extraction? Not better than 1.0x.
Verdict: Czech 18.4 (+2.1), English 10.2 (+1.6): worse on all four recordings. Rejected.
