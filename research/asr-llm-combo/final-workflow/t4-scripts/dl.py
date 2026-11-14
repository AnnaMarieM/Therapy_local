from huggingface_hub import hf_hub_download
import hashlib, json, time
out = {}
for repo, f in [("unsloth/gemma-4-E2B-it-GGUF","gemma-4-E2B-it-Q4_K_M.gguf"),("unsloth/gemma-4-E2B-it-GGUF","mmproj-F16.gguf"),("unsloth/gemma-4-E4B-it-GGUF","gemma-4-E4B-it-Q4_K_M.gguf"),("unsloth/gemma-4-E4B-it-GGUF","mmproj-F16.gguf")]:
    t = time.time(); p = hf_hub_download(repo, f); 
    h = hashlib.sha256(); fh = open(p, "rb")
    while (b := fh.read(1 << 24)): h.update(b)
    out[repo + "/" + f] = dict(path=p, sha256=h.hexdigest()); print(repo, f, p, h.hexdigest(), round(time.time() - t), "s", flush=True)
json.dump(out, open("/content/models.json", "w"), indent=1)
