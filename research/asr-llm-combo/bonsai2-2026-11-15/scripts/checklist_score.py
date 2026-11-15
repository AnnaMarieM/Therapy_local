import json,sys,glob
sys.path.insert(0,"/home/user/Therapy_local/research/asr-llm-combo/final-workflow"); sys.path.insert(0,"/home/user/Therapy_local/e2e/combo"); sys.path.insert(0,"/home/user/Therapy_local/bench")
from score import check, CHECK
from lenient import lenient
from collections import Counter
res={}
for f in sorted(glob.glob(sys.argv[1]+"/script__*__lane1.json")):
    r=json.load(open(f)); m=r["model"]
    for c,v in r["clients"].items():
        fs=[x for raw in v["raw_parts"] for x in lenient(raw)]
        txt=" . ".join(x["heading"]+" "+x["content"] for x in fs)
        ch=check(c,txt,v["lang"]); cnt=Counter(ch.values())
        res[(c,m)]=(cnt,ch,len(fs))
print(f"{'client':9}{'model':14}{'items':>6}{'kept':>6}{'lost':>6}{'inv?':>6}  facts")
tot={}
for (c,m),(cnt,ch,n) in sorted(res.items()):
    print(f"{c:9}{m:14}{len(ch):>6}{cnt['kept']:>6}{cnt['lost']:>6}{cnt['inverted?']:>6}  {n}")
    t=tot.setdefault(m,Counter()); t["items"]+=len(ch); t["kept"]+=cnt["kept"]; t["inv"]+=cnt["inverted?"]; t["facts"]+=n
print()
for m,t in tot.items(): print(f"{m:14} recall {t['kept']}/{t['items']} = {100*t['kept']/t['items']:.0f}%  inverted? {t['inv']}  facts {t['facts']}")
if len(sys.argv)>2:
    for (c,m),(cnt,ch,n) in sorted(res.items()):
        if c==sys.argv[2]: print(m,[k for k,s in ch.items() if s!="kept"])
