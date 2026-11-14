#!/bin/bash
# usage: rsh.sh 'cmd' [bg]  -- run a shell command on Colab (kernel exec). bg => detached, output to /content/logs/<name>.log (name = $3)
D=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/gpu
F=$D/_rsh_$$.py
python3 - "$1" "$2" "$3" > $F <<'P'
import sys
cmd, bg, name = sys.argv[1], sys.argv[2], sys.argv[3]
if bg == "bg":
    print("import subprocess,os; os.makedirs('/content/logs',exist_ok=True)")
    print("subprocess.Popen(['bash','-c',%r], stdout=open('/content/logs/%s.log','w'), stderr=subprocess.STDOUT, start_new_session=True); print('started %s')" % (cmd, name, name))
else:
    print("import subprocess; r=subprocess.run(['bash','-c',%r],capture_output=True,text=True); print(r.stdout); print(r.stderr)" % cmd)
P
$D/colab.sh exec -s therapy-asr --timeout ${TMO:-600} -f $F
rm -f $F
