import json,sys,glob
from pathlib import Path
rows=[]
for f in sorted(glob.glob(sys.argv[1]+"/script__*__lane1.json")):
    r=json.load(open(f)); m=r["model"]
    for c,v in r["clients"].items():
        fs=v["facts"]; qf=sum(1 for x in fs if x.get("quote_found"))
        heads=[x["heading"].lower() for x in fs]; dup=len(heads)-len(set(heads))
        prof=sum(1 for x in fs if x.get("scope")=="profile")
        rows.append((c,m,len(fs),qf,dup,prof,v["roles"]["roles"],v["seconds"],v["stages"].get("facts_s")))
print(f"{'client':9}{'model':14}{'facts':>6}{'quote✓':>7}{'dupHd':>6}{'prof':>5} roles   secs")
for r in sorted(rows): print(f"{r[0]:9}{r[1]:14}{r[2]:>6}{r[3]:>7}{r[4]:>6}{r[5]:>5} {r[6]:7}{r[7]:>6}")
