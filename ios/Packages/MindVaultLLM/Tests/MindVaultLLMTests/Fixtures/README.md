# Test fixtures

`gemma4-chat-template.txt` is the pinned model's own chat template, copied byte for
byte — no edits:

- file: `gemma-4-E2B-it-Q4_0.gguf` (the registry's default pin,
  `https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf`,
  sha256 `8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52`)
- GGUF key `tokenizer.chat_template` (type 8, string), value length 18,569 bytes,
  value at byte offset 15,764,652 of the file
- extracted with an HTTP `Range` request and checked against the GGUF header's own
  length field: 18,569 bytes, sha256
  `0a2c8073c878ab1da004bee933a998606537bbb62016310352c7285c3f01c5b5`

`Gemma4ChatTemplateTests` asserts both the length and that sha256, so the fixture
cannot drift from the pinned file's metadata without failing. The expected prompts in
the tests are the output of rendering this exact file with Jinja2 3.1 for the app's
message shapes; `ChatTemplatePlanTests` re-extracts the template through the same
`#filePath` helper.
