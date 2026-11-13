import json, sys
print(json.load(open("/content/models.json"))["unsloth/gemma-4-%s-it-GGUF/gemma-4-%s-it-Q4_K_M.gguf" % (sys.argv[1], sys.argv[1])]["path"])
