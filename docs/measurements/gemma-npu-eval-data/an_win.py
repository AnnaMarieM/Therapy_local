import json,re,sys,unicodedata
sys.path.insert(0,'/home/martin/projects/therapylocal_workspaces/.bigcache/npueval')
from facts import FACTS
W='/home/martin/projects/therapylocal_workspaces/.bigcache/npueval/win/'
def norm(s): return ''.join(c for c in unicodedata.normalize('NFD',s) if unicodedata.category(c)!='Mn').lower()
def wtext(k):
    raw=open(W+f'win{k}.txt').read().split('\n',1)[1]
    return norm(re.sub(r'\s+',' ',raw))
def objs(reply):
    body='\n'.join(l for l in reply.splitlines() if not l.strip().startswith('```'))
    dec=json.JSONDecoder(); i=0; out=[]; valid=True; whole=False
    while i<len(body):
        if body[i] in ' \n\t,[]': i+=1; continue
        try: o,j=dec.raw_decode(body,i)
        except Exception: valid=False; break
        if isinstance(o,dict): out.append(o)
        elif isinstance(o,list): out+=[x for x in o if isinstance(x,dict)]
        i=j
    return out,valid
def stem(w): return w[:5]
def analyse(k,reply):
    t=wtext(k); os_,valid=objs(reply)
    keys_ok=[o for o in os_ if set(o)=={'heading','content','evidence'}]
    quote_ok=[o for o in keys_ok if norm(re.sub(r'\s+',' ',str(o['evidence']))).strip(' .,"„“') in t]
    # evidence-claim overlap heuristic
    def toks(s): return {stem(w) for w in re.findall(r'\w{4,}',norm(s))}
    match=[o for o in quote_ok if toks(o['content'])&toks(o['evidence']) or toks(o['heading'])&toks(o['evidence'])]
    found=[]
    for i,rx in enumerate(FACTS[k]):
        hit=[n for n,o in enumerate(os_) if re.search(rx,norm(' '.join(str(v) for v in o.values())))]
        found.append(hit)
    return dict(valid=valid,n=len(os_),keys_ok=len(keys_ok),quote_ok=len(quote_ok),match=len(match),found=sum(1 for h in found if h),nfacts=len(FACTS[k]),hits=found,objs=os_)
if __name__=='__main__':
    for be in sys.argv[1:]:
        d=json.load(open(f'{W}../out/prooflab_{be}_win.json')); print('##',be,d.get('init_ms'))
        for c in d['cases']:
            k=int(re.match(r'win(\d)',c['case']).group(1))
            if 'reply' not in c: print(k,'ERR',c.get('error')); continue
            a=analyse(k,c['reply'])
            print(k,'tok',c['prefill_tokens'],'ms',c['ms'],'dec',c['decode_tokens'],'valid',a['valid'],'n',a['n'],'keys',a['keys_ok'],'quote',a['quote_ok'],'match~',a['match'],'facts',f"{a['found']}/{a['nfacts']}")
