"""Print what the compiled bundle contains: sections, DISPATCH_OP counts, QAIRT strings,
signatures/context hints. Usage: gemma-aot-inspect.py <compiled.litertlm> [<source.litertlm>]"""
import pathlib, re, sys, tempfile
try:
    import tomllib
except ImportError:
    import tomli as tomllib
from ai_edge_litert.tools import flatbuffer_utils
from litert_lm_builder import litertlm_peek

fb = flatbuffer_utils.schema_fb

def inspect(path):
    print("=== INSPECT", path)
    with tempfile.TemporaryDirectory() as d:
        litertlm_peek.peek_litertlm_file(path, d, sys.stdout)
        dp = pathlib.Path(d)
        toml = tomllib.loads((dp / "model.toml").read_text())
        for sec in toml.get("section", []):
            print("SECTION", {k: v for k, v in sec.items() if k != "data_path"})
            if sec.get("section_type") != "TFLiteModel":
                continue
            data = (dp / sec["data_path"]).read_bytes()
            m = fb.Model.GetRootAsModel(data, 0)
            n = 0
            sigs = []
            for i in range(m.SubgraphsLength()):
                g = m.Subgraphs(i)
                nm = g.Name().decode() if g.Name() else ""
                sigs.append(nm)
                for j in range(g.OperatorsLength()):
                    oc = m.OperatorCodes(g.Operators(j).OpcodeIndex())
                    if oc.BuiltinCode() == fb.BuiltinOperator.CUSTOM and oc.CustomCode() == b"DISPATCH_OP":
                        n += 1
            print(f"  {sec.get('model_type')}: subgraphs={m.SubgraphsLength()} DISPATCH_OP={n} names={sigs[:12]}")
            for q in sorted(set(re.findall(rb"(?:v|QAIRT[ _])2\.\d+\.\d+\.\d{6,}", data)))[:5]:
                print("  QAIRT string:", q.decode())
            for q in sorted(set(re.findall(rb"prefill_\d+|decode[_a-z0-9]*", data)))[:10]:
                print("  signature-like string:", q.decode())
        for f in dp.iterdir():
            if f.suffix in (".pb", ".textproto", ".json", ".toml"):
                print("FILE", f.name, f.stat().st_size)

for p in sys.argv[1:]:
    inspect(p)
