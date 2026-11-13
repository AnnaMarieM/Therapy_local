cd /content/w
export LD_LIBRARY_PATH=/usr/local/lib/python3.13/dist-packages/nvidia/cublas/lib:/usr/local/lib/python3.13/dist-packages/nvidia/cudnn/lib:$LD_LIBRARY_PATH
O=/content/fw/t4
python3 wh.py --task translate --beam 5 --prompt 0 --out $O/P-QUALITY.b5-noprompt
python3 wh.py --task translate --beam 5 --prompt 1 --out $O/P-QUALITY.b5-prompt
python3 wh.py --task translate --beam 1 --prompt 0 --out $O/P-QUALITY.greedy-noprompt
python3 wh.py --task translate --beam 1 --prompt 1 --out $O/P-QUALITY.greedy-prompt
python3 wh.py --task transcribe --beam 5 --prompt 0 --clients jana-n,tomas-b --out $O/P-BASE.b5-noprompt
python3 wh.py --task transcribe --beam 5 --prompt 1 --clients jana-n,tomas-b --out $O/P-BASE.b5-prompt
echo ALLDONE
