import json, sys
base = "/home/martin/projects/Therapy_local/.claude/worktrees/frolicking-foraging-aurora/research/asr-llm-combo/final-workflow/t4/"
idx = [int(x) for x in sys.argv[2].split(",")]
for p in sys.argv[1].split(","):
    rs = [json.loads(l) for l in open(base + p + "/tomas-b/turns.jsonl")]
    print("==", p)
    for i in idx:
        print(i, " ".join(rs[i]["raw"]))
