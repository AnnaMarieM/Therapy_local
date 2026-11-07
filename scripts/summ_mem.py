import json,sys
for f in sys.argv[1:]:
    d=json.load(open(f)); print(f.split('/')[-1], 'ERR', d.get('error'))
    for s in d['steps']:
        print({k:s.get(k) for k in ('step','load_ms','backend','call_ms','prefill_tok_s','decode_tok_s','prompt_tokens','reply_chars','reply_lines','vm_hwm_kb','pss_total_kb','sys_avail_mb') if s.get(k) is not None})
