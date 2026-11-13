# Critical-items checklist (2026-11-13)

Items a clinician must not lose, derived by reading the four scripts (`e2e/corpus/human/*/s01.json`). Each is judged per recording and pipeline as **kept**, **lost**, **garbled** (present but wrong: another name, number, place, month) or **inverted** (the meaning is reversed, for example a denied thought reported as intent).

How the numbers are produced: `checklist.json` holds, per item, regex patterns for the Czech and for the English wording (`final-workflow/score.py`). A pass over the raw output with these patterns gives kept / lost; matched items of the high-risk kind are then **read** (garbled / inverted cannot be detected by a regex), and `inv` rules flag the denial items (T10, J13) when the harm or pills wording is present without its denial. Where the final tables in `RESULTS-T4.md` say so, the figure is the reading result, not the regex.

The recordings jana-n, daniel-r and hannah-w contain no statement of harm to self or others; the only harm-related item in the corpus is **T10** (tomas-b).


## tomas-b (Czech)

| id | script (exact) | why it matters | kept / lost / garbled / inverted |
|---|---|---|---|
| T1 | Třicetkrát za večer kontroluji, jestli jsem zamkl dveře a vypnul sporák, a trvá mi to hodinu, než odejdu z bytu. | Core symptom: frequency (30x) and time cost (1 h). | kept = 30 times AND an hour; garbled = other number; lost = frequency or duration missing |
| T2 | Zamknu dveře, odejdu k výtahu, vrátím se a zatáhnu za kliku. Pak zkontroluji sporák, každý knoflík zvlášť. Pak zase dveře. | The checking ritual (door, handle, stove knobs, door again). | kept = door/handle AND stove knobs ritual |
| T3 | Chvíli se mi uleví. A pak mi do hlavy přijde obraz, že jsem to neudělal pořádně, a musím znovu. / Byt v plamenech. | Compulsion cycle and the image of the flat on fire. | kept = the burning-flat image; inverted = says he sees no image |
| T4 | Začalo to před dvěma lety, kdy hořelo u sousedů a my se museli evakuovat. ... Nikdo nebyl zraněn, ale sousedům to vyhořelo celé. | Trigger event: the fire two years ago (neighbours, evacuation, nobody hurt). | kept = two years ago + neighbours + fire; garbled = wrong time/place; lost = event absent |
| T5 | Od té doby mám pocit, že to můžu způsobit já. Že když něco nezkontroluji, tak se to stane a budu vinen. ... Já vím, že to nedává smysl. | Core belief: responsibility/guilt; insight that it makes no sense. | kept = belief that he could cause it/guilt |
| T6 | Srdce, pot. Třesou se mi ruce a je mi špatně od žaludku. | Physical anxiety symptoms. | kept = at least heart, sweating, shaking hands |
| T7 | Barbora. Moje manželka. ... Včera jsme přišli na kino o čtyřicet minut později. Čekala venku, než se vrátím a zkontroluji sporák potřetí. | Wife Barbora, the impact on her; the cinema incident (40 min late). | kept = Barbora + cinema + 40 minutes |
| T8 | Pracuji jako elektrikář v Olomouci. ... Bojím se, že špatně připojím vodič a něco shoří. ... V posledních měsících mi dělá práce o hodinu víc denně. Už jsem pár zakázek předal kolegovi. | Occupation (electrician, Olomouc), fear of wiring error, extra hour a day, handed jobs over, fear of losing job. | kept = electrician AND Olomouc; garbled = other trade or city; lost = either missing |
| T9 | Barbora je těhotná ve čtvrtém měsíci. ... Byl jsem šťastný. Opravdu. Čekali jsme na to tři roky. | Barbora is four months pregnant; three years of waiting; he was happy. | kept = pregnant AND fourth month; garbled = other month; lost = pregnancy absent |
| T10 | Že jí a dítěti ublížím. / Ne že bych chtěl. To je hrozné, nikdy bych to neudělal. Ale ta myšlenka přijde, třeba když krájím chleba nebo stojím u schodů. | THE HARM ITEM: an intrusive, unwanted thought of harming wife and child, explicitly denied as a wish. The denial must not be inverted; dropping the denial turns it into a risk statement. | kept = harm thought + explicit denial/intrusive framing, attributed to him as a thought; INVERTED = harm stated as intent/plan or the denial missing or negated; lost = absent; garbled = other meaning |
| T11 | Nikdy jsem o tom nikomu neřekl, ani Barboře. / Myslíte, že jsem nebezpečný? | He has told nobody (not even Barbora) of the thought; he fears he is dangerous. Concealment and his question must not become 'he is dangerous'. | kept = told nobody incl. Barbora; inverted = says Barbora knows |
| T12 | Spím mizerně. Vstávám dvakrát v noci a kontroluji sporák, i když tam nic nestojí. | Sleep disturbance with checking at night. | kept = wakes twice at night to check the stove |
| T13 | Pomáhá mi chodit na procházky s Barborou. Nebo když opravuji něco doma. ... Procházky dvakrát týdně. | Coping resources: walks with Barbora twice a week, repairs. | kept = walks + frequency |
| T14 | Odejít z bytu za pět minut. A těšit se na dítě bez těch myšlenek. / Zkontrolujte dveře jednou a zapište si, jak moc vám bylo úzko... číslo od jedné do deseti. | Goals and homework (check once, rate anxiety 1-10). | kept = goal 5 minutes and/or homework check once |

## jana-n (Czech)

| id | script (exact) | why it matters | kept / lost / garbled / inverted |
|---|---|---|---|
| J1 | Po patnácti letech manželství jsme se s manželem Radkem rozhodli pro rozvod. | The presenting problem: divorce after 15 years, husband Radek. | kept = divorce + 15 years + Radek; garbled = other number/name |
| J2 | Ještě v létě jsme byli na dovolené v Chorvatsku a všechno vypadalo normálně. A v září mi řekl, že už to takhle dál nejde. | Timeline: Croatia in summer, in September he said it cannot go on. | kept = September AND Croatia |
| J3 | Mám dvě děti. Aničce je dvanáct a Matějovi osm let. A nevím, jak jim to říct. | Two children, names and ages; they have not been told. | kept = both names and ages; garbled = wrong age/name |
| J4 | Minulý týden se mě zeptala, jestli se s tátou hádáme. Řekla jsem, že ne. ... Protože jsem lhala. | Anička senses it; Jana lied to her; guilt. | kept = daughter asked if they argue AND Jana lied |
| J5 | Že jí ničím život. Že jí vezmu domov. | Her self-blaming thought about harming her daughter's life/home (not self-harm; must not become a threat). | kept = ruining her life/taking her home as a fear; inverted = reported as an intent |
| J6 | Byt je na hypotéku. Radek se asi odstěhuje k bratrovi. Děti zůstanou se mnou. ... Mám strach, že to sama nezvládnu. Radek vždycky řešil peníze. | Housing, mortgage, custody plan, fear of managing alone. | kept = mortgage + Radek to brother |
| J7 | Pracuji jako učitelka češtiny na základní škole v Brně. Učím devítky a osmičky. | Occupation and place: Czech teacher, primary school in Brno. | kept = teacher AND Brno; garbled = other job or city |
| J8 | Jen ředitelka. Řekla jsem jí to, protože jsem se jednou rozplakala na chodbě. | Only the headteacher knows; cried in the corridor. | kept = headteacher knows |
| J9 | Každou noc se budím ve tři hodiny ráno ... Kolem páté usnu, a v půl sedmé vstávám. ... Zhubla jsem čtyři kila. | Sleep (wakes 3 am) and weight loss (4 kg). | kept = 3 am waking AND weight loss; garbled = other time/amount |
| J10 | Mám sestru Alenu v Olomouci. Zavolá mi skoro každý večer. A kamarádku Evu z práce. ... Evě jen to, že mám těžké období. | Support network: sister Alena in Olomouc, friend Eva (told only 'hard period'). | kept = Alena + Olomouc + Eva; garbled = wrong name/city |
| J11 | Že jsem selhala. Že žena, která nedokáže udržet manželství, není pro děti dobrý příklad. | Core belief of failure as a wife/mother. | kept = failed / not a good example |
| J12 | Asi od mojí mámy. ... Pak řekla, že doufá, že jsem to dobře zvážila. Už se od té doby moc neozvala. | Mother: origin of the belief; cool reaction; little contact since. | kept = mother + reaction |
| J13 | Doktorka mi nabídla prášky, ale nechci. ... Bojím se, že bych se nemohla včas vzbudit pro děti. A nechci být závislá. | Declines medication (fear of not waking for kids, dependence). The refusal must not be inverted. | kept = offered pills AND declined; INVERTED = says she takes/wants pills |
| J14 | Pláču. Omlouvám se. ... Děkuji. Ještě nikomu jsem to takhle neřekla. | She cries; first time she says it to anyone like this. | kept = cries + first time |
| J15 | Že zpanikařím a rozpláču se. ... Nebo by mohly vidět, že smutek je dovolený. ... Mohla bych [napsat, co mě budí]. | Fear of crying in front of children; reframe; homework: a paper by the bed. | kept = fear of crying before the children and/or the paper homework |

## daniel-r (English)

| id | script (exact) | why it matters | kept / lost / garbled / inverted |
|---|---|---|---|
| D1 | I work as a project manager at a logistics company, and I've been completely burned out since October. ... We lost a big client ... I ended up doing three people's jobs. | Occupation and the work trigger. | kept = project manager + logistics + October; garbled = other job/month |
| D2 | I'm at my desk by seven and I'm still answering messages at ten at night. | Working hours. | kept = 7 am to 10 pm |
| D3 | Most nights I sleep about four hours. I wake up at three in the morning and can't fall back asleep. | Sleep pattern (4 h, wakes at 3). | kept = about four hours AND waking at three |
| D4 | My father. He was diagnosed with Parkinson's disease in the autumn. ... He's seventy-three. | Father: Parkinson's, aged seventy-three (known ASR trap: 'seventy three' heard as 'having a dream'). | kept = Parkinson's AND 73; garbled = other age/disease |
| D5 | I'm the one who drives him to the hospital in Leeds. The neurology appointments are every few weeks, and it's an hour each way. | Caregiving burden; place Leeds. | kept = Leeds + hospital/neurology; garbled = other city |
| D6 | My younger sister Hannah lives in Toronto, so all the care for our father falls on me. | Sister Hannah in Toronto; sole carer. | kept = Hannah + Toronto; garbled = other name/city |
| D7 | I feel it's selfish to be tired when he's the one who's ill. So I don't say anything to anyone. | Guilt and silence. | kept = guilt about being tired + tells nobody |
| D8 | Some days I sit in the car park at the hospital for ten minutes before I go in, because I can't face it. | Avoidance behaviour at the hospital. | kept = car park, ten minutes |
| D9 | My girlfriend booked it, to be honest. Claire. She said she was done watching me disappear. | Girlfriend Claire booked the session; wording of concern. | kept = Claire booked it; garbled = other name |
| D10 | I ran. Ten kilometres every Saturday morning. I'd done it for six years. ... I stopped about two months ago. | Loss of running (10 km, six years, stopped two months ago). | kept = running + the numbers |
| D11 | Peterson. ... I answer it. Every time. ... There's a restructuring in the spring and I'm afraid they'll let me go. ... I have a mortgage. | Boss Peterson, answers every email, fear of dismissal at the spring restructuring, mortgage. | kept = Peterson + restructuring + mortgage |
| D12 | My heart was going and my hands were shaking. I thought for a second it was a heart attack ... Once on the train to Manchester. ... I haven't told anyone about it, not even Claire. | Panic episodes, concealed from everyone including Claire. | kept = panic/heart-attack fear + undisclosed; inverted = says Claire knows |
| D13 | I'm on about six cups a day. And I've started having wine in the evenings to switch my head off. Two glasses, sometimes three. | Substance use: coffee 6 cups, wine 2-3 glasses. | kept = coffee amount AND wine amount; garbled = other numbers |
| D14 | My grandfather worked in the mines in Yorkshire. He never complained ... I keep thinking he would think I was weak for being here. | Family pattern of stoicism; fear of being seen as weak. | kept = grandfather miner + weakness |
| D15 | A fixed time in the evening when you switch off your phone ... Half past nine, perhaps. I could make it a thing with Claire. Tea on the sofa. | Homework: phone off at 9.30 pm, tea with Claire, sleep note. | kept = 9.30 + phone off |

## hannah-w (English)

| id | script (exact) | why it matters | kept / lost / garbled / inverted |
|---|---|---|---|
| H1 | I was made deputy head at my school. I've taught Year 4 for eleven years and I loved it. | Trigger: promotion in September; eleven years as Year 4 teacher. | kept = deputy head + Year 4 + eleven years |
| H2 | I keep thinking they picked the wrong person, and that sooner or later someone will notice I have no idea what I'm doing. | Impostor belief. | kept = the belief |
| H3 | Mostly Sunday evenings. My chest goes tight and I start rehearsing Monday's staff meeting in my head. | Sunday-evening anxiety, rehearsal. | kept = Sunday + chest tightness + rehearsing |
| H4 | Last week I rewrote the same email to the governors four times. ... Mr Hargreaves, the chair of governors. | Compulsive rewriting; Mr Hargreaves. | kept = four times + Hargreaves; garbled = other name/number |
| H5 | Most days I feel like a child wearing her mother's coat. ... She was a headteacher for twenty years. ... they've named a library after her. | Mother as headteacher, comparison. | kept = mother headteacher + twenty years |
| H6 | She says I've always worried too much and I should stop being silly. ... So I don't tell her how bad it is. | Mother's minimising response; Hannah conceals. | kept = mother says stop being silly + she hides it |
| H7 | I'm awake at four every morning. ... Timetable changes, the Ofsted prep, a parent complaint, a child I'm worried about. | Early waking at 4 am and rumination content. | kept = 4 am + Ofsted; garbled = other time |
| H8 | Five or six. [cups of coffee] ... My heart goes fast. My hands are a bit shaky. Last Thursday I had to leave a meeting to splash my face with water. | Caffeine and somatic anxiety; meeting incident. | kept = coffee amount + heart + incident |
| H9 | My partner Ben knows a bit. He says I should just enjoy it. ... He's an engineer. ... Not even Ben knows about the four a.m. | Partner Ben, partial disclosure; does not know about 4 a.m. | kept = Ben (+ engineer or 4 a.m. undisclosed); inverted = says Ben knows everything |
| H10 | Now I'm their line manager and it's awkward. ... I miss my class too. ... Now my days are spreadsheets. | Loss of colleagues and class. | kept = line manager + misses |
| H11 | I said yes in the room because I couldn't say no to her. The head. Then I went home and cried. ... Would you want to go back? I don't know. | Ambivalence about the promotion; cried. | kept = could not say no + cried |
| H12 | The summer holidays. We went walking in the Lakes ... ten days. ... I couldn't eat on the Sunday. | Relief on holiday (Lakes, ten days) and anxiety returning before term. | kept = Lakes + ten days |
| H13 | I started writing down what went well each evening. ... It helped for about two weeks and then I stopped, because I felt silly. ... As if I were bragging. | Coping attempt: evening notebook, stopped after two weeks. | kept = notebook + two weeks |
| H14 | I'd like to do the job without feeling like a fraud. ... Walking into a meeting and not rehearsing it first. Sending an email once. | Therapy goals. | kept = fraud goal + a concrete goal |
| H15 | Yes, over breakfast. [telling Ben on Saturday] ... I'll put it by the kettle ... I nearly cancelled this morning. | Plan: tell Ben on Saturday over breakfast; notebook by the kettle. | kept = tell Ben Saturday breakfast and/or notebook by the kettle |
