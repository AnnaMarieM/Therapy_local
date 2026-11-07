import json,sys,re,glob
# prints one line per memprobe json: load_ms, backend, per-call ms, min sys_avail_mb, reply length; and llm_call speeds from the logcat of the same tag
for f in sorted(glob.glob(sys.argv[1]+'/memprobe_gemma_*.json')):
    tag=f.split('memprobe_')[1][:-5]; d=json.load(open(f)); st=d['steps']
    ld=[s for s in st if s['step']=='loaded']; calls=[s for s in st if s['step'].startswith('call_')]
    log=open(f'{sys.argv[1]}/{tag}.logcat',errors='ignore').read()
    sp=re.findall(r'evt=llm_call backend=(\w+).*?prefill_tok_s=([\d.]+).*?decode_tok_s=([\d.]+)',log)
    print(tag,'err',d.get('error'),'load_ms',ld and ld[0]['load_ms'],ld and ld[0]['backend'],'calls_ms',[c['call_ms'] for c in calls],
      'min_avail_mb',min(s['sys_avail_mb'] for s in st),'chars',[c['reply_chars'] for c in calls],'prefill',[x[1] for x in sp],'decode',[x[2] for x in sp])
