import json,sys
from lenient import lenient
m,c=sys.argv[1],sys.argv[2]
r=json.load(open(f"out/script__{m}__lane1.json"))["clients"][c]
n=0
for raw in r["raw_parts"]:
    for x in lenient(raw):
        n+=1; print(f"{n:2}. [{x.get('scope','')[:1]}] {x['heading']}: {x['content']}")
