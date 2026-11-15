import json,re,sys,glob
sys.path.insert(0,"/home/user/Therapy_local/e2e/combo"); sys.path.insert(0,"/home/user/Therapy_local/bench")
from common import norm_words
def lenient(raw):
    out=[];dec=json.JSONDecoder();i=0
    while True:
        i=raw.find("{",i)
        if i<0: break
        try:
            d,end=dec.raw_decode(raw,i); i=end
            if isinstance(d,dict) and d.get("heading") and d.get("content"): out.append(d)
        except Exception: i+=1
    return out
def strict(raw):
    out=[]
    for l in raw.splitlines():
        l=l.strip().strip("`")
        if l.startswith("{"):
            try:
                d=json.loads(l)
                if d.get("heading") and d.get("content"): out.append(d)
            except Exception: pass
    return out
if __name__=="__main__":
    print(f"{'model':14}{'client':9}{'strict':>7}{'lenient':>8}{'quote✓':>7}  facts/part(lenient)")
    for f in sorted(glob.glob(sys.argv[1]+"/script__*__lane1.json")):
        r=json.load(open(f))
        for c,v in r["clients"].items():
            turns=v["turns"]
            tot=0;qf=0;per=[]
            for raw in v["raw_parts"]:
                fs=lenient(raw); per.append(len(fs)); tot+=len(fs)
            body=" ".join(norm_words(" ".join(t["text"] for t in turns)))
            for raw in v["raw_parts"]:
                for x in lenient(raw):
                    e=" ".join(norm_words(x.get("evidence","")))
                    qf+= bool(e) and e in body
            print(f"{r['model']:14}{c:9}{sum(len(strict(p)) for p in v['raw_parts']):>7}{tot:>8}{qf:>7}  {per}")
