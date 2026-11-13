cd /content/w
export LD_LIBRARY_PATH=/content/llama.cpp/build/bin:$LD_LIBRARY_PATH
O=/content/fw/t4
C=tomas-b
python3 g4.py --size e2b --mode onepass --clients $C --lane 1 --port 8101 --out $O/P-FAST
python3 g4.py --size e2b --mode onepass --clients $C --lane 2 --port 8101 --out $O/P-FAST
python3 g4.py --size e2b --mode translate --clients $C --lane 1 --port 8101 --out $O/P-FAST_translate-only
python3 g4.py --size e4b --mode onepass --clients $C --lane 1 --port 8101 --out $O/P-FAST-E4B
python3 g4.py --size e4b --mode translate --clients $C --lane 1 --port 8101 --out $O/P-FAST-E4B_translate-only
echo G4ALLDONE
