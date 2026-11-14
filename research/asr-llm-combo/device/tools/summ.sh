cd /tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
g() { grep -m1 "$2" $1 | sed 's/.*= *//;s/ ms.*//'; }
for f in out/v3_*.err; do b=$(basename $f .err)
 echo "$b | total $(g $f 'total time') | load $(g $f 'load time') | enc $(g $f 'encode time') | dec $(grep -m1 'batchd' $f | sed 's/.*= *//;s/ms.*//') | RSS $(grep -m1 'Max RSS' $f | sed 's/.*: //') | $(grep -m1 'Thermal Status' out/$b.thermal)"
done
echo ---WER cs
python3 -I wer.py cs out/v3_*_cs_*.txt
echo ---WER en
python3 -I wer.py en out/v3_*_en_*.txt
echo ---WER harm
python3 -I wer2.py 480 600 out/v3_*_harm_*.txt
for f in out/v3_*harm*.txt; do echo "$f: $(grep -o 'ubl[^ ]*\|[^ ]* [^ ]* ubl[^ ]* [^ ]*' $f | head -3 | tr '\n' '|')"; done
