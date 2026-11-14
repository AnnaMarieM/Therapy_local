cd /content/fw/t4
rm -rf P-QUALITY P-BASE; cp -r P-QUALITY.b5-noprompt P-QUALITY; cp -r P-BASE.b5-noprompt P-BASE
cd /content/w
python3 mkturns.py --src /content/fw/t4/P-QUALITY --kind en --out /content/fw/turns_P-QUALITY.json
python3 mkturns.py --src /content/fw/t4/P-BASE --kind cs --out /content/fw/turns_P-BASE.json
CL=tomas-b bash stage2.sh P-QUALITY e4b /content/fw/turns_P-QUALITY.json en 0 8111
CL=tomas-b bash stage2.sh P-BASE e4b /content/fw/turns_P-BASE.json src 1 8111
CL=tomas-b bash stage2.sh P-QUALITY-E2B e2b /content/fw/turns_P-QUALITY.json en 0 8111
echo PREPB_DONE
