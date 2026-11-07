import json,re,sys
sys.path.insert(0,'/home/martin/projects/therapylocal_workspaces/.bigcache/npueval')
from an_win import *
def lines_norm(k):
    raw=open(W+f'win{k}.txt').read().split('\n',1)[1]
    return [norm(re.sub(r'\s+',' ',l)) for l in raw.splitlines() if l.strip()]
def toks(s): return {w[:5] for w in re.findall(r'\w{4,}',norm(s))}
def score(k,reply):
    a=analyse(k,reply); t=wtext(k); L=lines_norm(k)
    os_=a['objs']; seen=set(); dist=[]
    for o in os_:
        key=(norm(str(o.get('heading',''))),norm(str(o.get('evidence',''))),norm(str(o.get('content',''))))
        if key in seen: continue
        seen.add(key); dist.append(o)
    full=[o for o in dist if set(o)=={'heading','content','evidence'}]
    q=[o for o in full if norm(re.sub(r'\s+',' ',str(o['evidence']))).strip(' .,"„“') in t]
    ther=0
    for o in q:
        ev=norm(re.sub(r'\s+',' ',str(o['evidence']))).strip(' .,"„“')
        for l in L:
            if ev in l:
                ther += l.startswith('terapeut:'); break
    match=[o for o in full if toks(o['content'])&toks(o['evidence'])]
    # unsupported numbers/names in heading+content
    unsup=[]
    for o in dist:
        txt=str(o.get('heading',''))+' '+str(o.get('content',''))
        for w in re.findall(r'\d+|[A-ZÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ][a-záčďéěíňóřšťúůýž]{3,}',txt):
            if norm(w) not in t and norm(w) not in ('petra','jan','eva','marek','alena','klient','klientka'):
                unsup.append(w)
    return dict(valid=a['valid'],total=a['n'],distinct=len(dist),full=len(full),quote=len(q),ther=ther,match=len(match),found=a['found'],nf=a['nfacts'],unsup=sorted(set(unsup)))
if __name__=='__main__':
    for be in ['npu','gpu','cpu']:
        d=json.load(open(f'{W}../out/prooflab_{be}_win.json'))
        for c in d['cases']:
            k=int(c['case'][3]); s=score(k,c['reply'])
            print(be,k,f"ms={c['ms']} dec={c['decode_tokens']} valid={s['valid']} tot={s['total']} dist={s['distinct']} keys={s['full']} quote={s['quote']} ther={s['ther']} match={s['match']} found={s['found']}/{s['nf']} unsup={s['unsup']}")
