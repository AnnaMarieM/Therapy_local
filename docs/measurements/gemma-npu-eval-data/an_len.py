import json,re,sys,unicodedata
def norm(s): return re.sub(r'\p{M}','',s) if False else ''.join(c for c in unicodedata.normalize('NFD',s) if unicodedata.category(c)!='Mn').lower()
SPOOL=["Moje sestra Lucie se přestěhovala do Brna a já mám pocit, že jsme si s ní teď strašně vzdálené.","Usínám až po půlnoci a v pět ráno se budím.","Pila jsem proto víc kávy, tři až čtyři hrnky denně.","V pátek mi šéf řekl, že dostanu výpověď.","Poslední den budu mít 30. září.","Bojím se, jak zaplatím nájem, platím osm tisíc měsíčně.","Chodím na dlouhé procházky podél řeky.","Můj syn Matyáš začal chodit do první třídy a často pláče, když ho tam vedu.","Minulý měsíc jsem přestala cvičit jógu, protože nemám sílu vstát z postele.","Mamce je sedmdesát dva a v březnu měla operaci kyčle, takže k ní jezdím každý druhý den.","S manželem Tomášem se už tři týdny skoro nebavíme, večer sedí u počítače.","Na jaře jsem si chtěla otevřít malou keramickou dílnu, ale banka mi půjčku zamítla.","Včera večer jsem vypila celou láhev vína, což se mi předtím nestalo.","Kamarádka Hana mi nabídla, že u ní můžu týden bydlet.","Do práce jezdím autobusem číslo 17 a cesta mi trvá čtyřicet minut.","Minulou středu jsem měla záchvat paniky v supermarketu a musela jsem odejít bez nákupu."]
def objs(reply):
    body='\n'.join(l for l in reply.splitlines() if not l.strip().startswith('```'))
    dec=json.JSONDecoder(); i=0; out=[]
    while i<len(body):
        if body[i] in ' \n\t,[]': i+=1; continue
        try: o,j=dec.raw_decode(body,i)
        except Exception: return out,False
        if isinstance(o,dict): out.append(o)
        elif isinstance(o,list): out+= [x for x in o if isinstance(x,dict)]
        i=j
    return out,True
def score(case,reply):
    m=re.match(r's(\d+)(u?)',case); n=int(m.group(1))
    text=norm(' '.join(SPOOL[:n]))
    os_,ok=objs(reply)
    nk=[len(o) for o in os_]
    quotes=[norm(str(list(o.values())[-1])).strip() for o in os_ if o]
    qok=sum(1 for q in quotes if q and q in text)
    allq=sum(1 for o in os_ for v in o.values() if norm(str(v)).strip() in text)
    # shipped checkFacts verdict (n=3 only meaningful)
    v='Ok'
    if not os_: v='empty/invalid'
    elif len(os_)<min(n,3): v='too_few_objects'
    elif any(len(o)!=3 for o in os_): v='wrong_keys'
    elif qok<len(quotes): v='evidence_not_quote'
    keys=sorted({k for o in os_ for k in o})
    return dict(valid=ok,objects=len(os_),keys_per=nk,last_is_quote=f"{qok}/{len(quotes)}",verdict_like_proof2=v,keys=keys)
for f in sys.argv[1:]:
    d=json.load(open(f)); print('#',f.split('/')[-1],d.get('file'),'init_ms',d.get('init_ms'))
    for c in d['cases']:
        if 'reply' not in c: print(c['case'],'ERR',c.get('error')); continue
        r=score(c['case'].split('@')[0],c['reply']); print(c['case'],'tok',c['prefill_tokens'],'ms',c['ms'],'dec',c['decode_tokens'],r['objects'],'obj',r['keys_per'],'quote',r['last_is_quote'],r['verdict_like_proof2'],r['keys'])
