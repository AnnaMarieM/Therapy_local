import json,re,sys,unicodedata
sys.path.insert(0,'/home/martin/projects/therapylocal_workspaces/.bigcache/npueval')
from an_len import SPOOL,norm
def parse(reply):
    body='\n'.join(l for l in reply.splitlines() if not l.strip().startswith('```'))
    dup=[False]
    def hook(pairs):
        ks=[k for k,_ in pairs]
        if len(ks)!=len(set(ks)): dup[0]=True
        return dict(pairs)
    dec=json.JSONDecoder(object_pairs_hook=hook); i=0; out=[]; trunc=False
    while i<len(body):
        if body[i] in ' \n\t,[]': i+=1; continue
        try: o,j=dec.raw_decode(body,i)
        except Exception: trunc=True; break
        if isinstance(o,dict): out.append(o)
        i=j
    return out,dup[0],trunc
def cell(case,reply,ntok):
    n=int(re.match(r's(\d+)',case).group(1)); text=norm(' '.join(SPOOL[:n]))
    os_,dup,trunc=parse(reply)
    need=min(n,3)
    why=None
    if not os_: why='no complete object'
    elif dup: why='duplicate key'
    elif len(os_)<need: why=f'{len(os_)} object(s), need {need}'
    elif any(len(o)!=3 for o in os_): why='keys per object '+'/'.join(str(len(o)) for o in os_)
    else:
        bad=[i+1 for i,o in enumerate(os_) if norm(str(list(o.values())[-1])).strip() not in text]
        if bad: why='last value not a quote (obj '+','.join(map(str,bad))+')'
    return ('ok' if why is None else 'FAIL: '+why), len(os_)
rows={}
for be in ['npu','gpu','cpu']:
    d=json.load(open(f'/home/martin/projects/therapylocal_workspaces/.bigcache/npueval/out/prooflab_{be}_len.json'))
    for c in d['cases']:
        r,n=cell(c['case'].split('@')[0],c['reply'],c['prefill_tokens'])
        rows.setdefault(c['case'],{'tok':c['prefill_tokens']})[be]=(r,n,c['ms'])
print('| case | prompt tokens | NPU greedy | NPU app | GPU greedy | GPU app | CPU greedy | CPU app |'); print('|---|---|---|---|---|---|---|---|')
base=[]
for k in rows:
    if k.endswith('@g'): base.append(k[:-2])
for b in base:
    cells=[]
    for be in ['npu','gpu','cpu']:
        for m in 'ga':
            r,n,ms=rows[f'{b}@{m}'][be]; cells.append(r.replace('FAIL: ','FAIL ')+f' ({n} obj, {ms/1000:.1f}s)')
    print(f"| {b} | {rows[b+'@g']['tok']} | "+' | '.join(cells)+' |')
