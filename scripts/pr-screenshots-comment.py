#!/usr/bin/env python3
"""Builds the sticky pull request comment(s) of AD-15 from the screenshots the screenshot job made.

  pr-screenshots-comment.py --root merged --repo O/R --pr N --head SHA --branch-sha SHA --out DIR

`root` holds manifest.tsv (sizes and flows, written by the instrumented tour), results.tsv (one line per screen and size) and
<screen id>/<WxH>.png. One file per screen and size: images are never combined. Each flow is a heading and a table, one row per step, one
column per window size (narrow to wide). The images are referenced by raw.githubusercontent.com URLs that name the commit of the screenshots
branch, so they never change. The comment is split into several (each with its own marker) when one would pass GitHub's 65,536 characters.
"""
import argparse, datetime, html, os, sys

LIMIT = 58000  # a margin under GitHub's 65,536 characters


def main():
    a = argparse.ArgumentParser()
    for n in ("root", "repo", "pr", "head", "branch-sha", "out"):
        a.add_argument("--" + n, required=True)
    a.add_argument("--marker", default="pr-screenshots")
    o = a.parse_args()
    short = o.head[:7]
    sizes, screens = [], []
    for line in open(os.path.join(o.root, "manifest.tsv"), encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if f[0] == "size":
            sizes.append({"key": f[1], "name": f[2], "w": int(f[3]), "h": int(f[4])})
        elif f[0] == "screen":
            screens.append({"id": f[1], "flow": f[2], "caption": f[3], "dialog": f[4] == "true"})
    results = {}
    rp = os.path.join(o.root, "results.tsv")
    if os.path.exists(rp):
        for line in open(rp, encoding="utf-8"):
            f = line.rstrip("\n").split("\t")
            if len(f) >= 3:
                results[(f[0], f[1])] = (f[2], f[3] if len(f) > 3 else "")

    def url(sid, key):
        return f"https://raw.githubusercontent.com/{o.repo}/{o.branch_sha}/p{o.pr}/{short}/{sid}/{key}.png"

    def has(sid, key):
        return os.path.exists(os.path.join(o.root, sid, key + ".png"))

    def cell(sid, key, width, span=1):
        s = f' colspan="{span}"' if span > 1 else ""
        if not has(sid, key):
            return f'<td align="center"{s}>-</td>'
        u = url(sid, key)
        return f'<td{s}><a href="{u}"><img src="{u}" width="{width}"></a></td>'

    flows = []  # (name, [screens]) in order of first appearance
    for s in screens:
        if not flows or flows[-1][0] != s["flow"]:
            if s["flow"] in [f[0] for f in flows]:
                [f for f in flows if f[0] == s["flow"]][0][1].append(s)
                continue
            flows.append((s["flow"], []))
        flows[-1][1].append(s)

    head_cells = "".join(
        f'<th><sub>{html.escape(z["name"])}<br>{z["w"]}x{z["h"]}</sub></th>' for z in sizes
    )

    def flow_md(n, name, steps):
        rows = []
        for i, s in enumerate(steps, 1):
            cap = f'<td><sub>{i}. {html.escape(s["caption"])}</sub></td>'
            if s["dialog"]:
                rows.append(f'<tr>{cap}{cell(s["id"], "device", 150, len(sizes))}</tr>')
            else:
                rows.append(f"<tr>{cap}" + "".join(cell(s["id"], z["key"], max(70, round(z["w"] * 0.12))) for z in sizes) + "</tr>")
        return f"### {n}. {html.escape(name)}\n\n<table><thead><tr><th></th>{head_cells}</tr></thead><tbody>\n" + "\n".join(rows) + "\n</tbody></table>\n"

    blocks = [flow_md(i, n, st) for i, (n, st) in enumerate(flows, 1)]
    # Pack flows into comments.
    parts, cur = [], []
    for b in blocks:
        if cur and sum(len(x) for x in cur) + len(b) > LIMIT:
            parts.append(cur)
            cur = []
        cur.append(b)
    if cur:
        parts.append(cur)

    total = sum(1 if s["dialog"] else len(sizes) for s in screens)
    made = sum(1 for s in screens for z in ([{"key": "device"}] if s["dialog"] else sizes) if has(s["id"], z["key"]))
    problems = sorted((k, v) for k, v in results.items() if v[0] == "fail")
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    index = " &middot; ".join(f"{html.escape(n)} ({len(st)})" for n, st in flows)

    os.makedirs(o.out, exist_ok=True)
    for i, blks in enumerate(parts, 1):
        lines = [f"<!-- {o.marker}:{i} -->"]
        if i == 1:
            lines += [
                "## Screenshots of every screen",
                f"Head commit [`{short}`](https://github.com/{o.repo}/commit/{o.head}) &middot; {now} &middot; {made} of {total} screenshots"
                + (f" &middot; **{len(problems)} failed**" if problems else ""),
                "",
                f"Flows: {index}",
                "",
                "One window size per column, narrow to wide, the same screen in every column; click a thumbnail for the full-size file. Sizes are those of "
                "`AdaptiveLayoutTest`, drawn on one emulator with fake data only. The repository is public, so these images are public.",
            ]
            if problems:
                lines += ["", "<details><summary>Screens that failed</summary>", ""]
                lines += [f"- `{k[0]}` at `{k[1]}`: {html.escape(v[1])}" for k, v in problems]
                lines += ["", "</details>"]
            if len(parts) > 1:
                lines += ["", f"Part 1 of {len(parts)} (the rest are in the following comments)."]
        else:
            lines += [f"## Screenshots, part {i} of {len(parts)}", f"Head commit `{short}`"]
        lines += [""] + blks
        text = "\n".join(lines)
        assert len(text) < 65000, len(text)
        open(os.path.join(o.out, f"comment-{i}.md"), "w", encoding="utf-8").write(text)
    print(f"{len(parts)} comment(s), {made}/{total} screenshots, {len(problems)} failed", file=sys.stderr)


if __name__ == "__main__":
    main()
