#!/usr/bin/env python3
"""Builds the rolling SideStore source.json from the per-PR releases of the open iOS pull requests.

  build-sidestore-source.py --facts source-facts.tsv --out pages/sidestore.json --source-url URL

--facts holds one tab-separated line per release: PR, version, commit, date, size, downloadURL, title
(as gathered by the pages-publish job of ios.yml). The source offers ONE com.therapylocal.app entry:
the newest build — the version stamp 0.<PR>.<RUN_NUMBER> is monotonic across pull requests, so
max(version) is the latest. Plain AltStore v1 shape only, with exactly the fields SideStore's strict
decoding accepts (SideStore #735): bundleIdentifier, name, developerName, version, versionDate,
versionDescription, downloadURL, size. No checksums, no extra fields. With no usable release at all
it publishes the source with an empty apps list, so the stable URL stays live.
"""
import argparse
import json

APP_ID = "com.therapylocal.app"
APP_NAME = "MindVault"
DEVELOPER = "Therapy_local"


def order(version):
    """Sort key for 0.<PR>.<RUN_NUMBER>; unknown shapes sort oldest."""
    try:
        _, pr, run = version.split(".")
        return (int(pr), int(run))
    except ValueError:
        return (0, 0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--facts", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--source-url", required=True)
    o = ap.parse_args()

    newest = None
    for line in open(o.facts, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if len(f) != 7:
            continue
        pr, version, commit, date, size, url, title = f
        if newest is None or order(version) > order(newest["version"]):
            newest = {"pr": pr, "version": version, "commit": commit, "date": date,
                      "size": size, "url": url, "title": title}
    if newest is None:
        # An empty source (apps: []) keeps the URL live while no open pull request has a
        # new-format release yet — the next run that publishes one fills it. Better than a 404.
        print(f"no usable ios-pr-* release found — publishing an empty source -> {o.out}")
        source = {
            "name": "Therapy_local iOS builds",
            "identifier": "com.therapylocal.app.source",
            "sourceURL": o.source_url,
            "apps": [],
        }
    else:
        app = {
            "bundleIdentifier": APP_ID,
            "name": APP_NAME,
            "developerName": DEVELOPER,
            "version": newest["version"],
            "versionDate": newest["date"],
            "versionDescription": (
                f"Build of pull request #{newest['pr']} ({newest['title']}) at commit {newest['commit']} "
                "— unsigned device build, signed on the phone by SideStore."
            ),
            "downloadURL": newest["url"],
            "size": int(newest["size"]),
        }
        source = {
            "name": "Therapy_local iOS builds",
            "identifier": "com.therapylocal.app.source",
            "sourceURL": o.source_url,
            "apps": [app],
        }
        print(f"source offers version {newest['version']} (PR #{newest['pr']}) -> {o.out}")

    with open(o.out, "w", encoding="utf-8") as fh:
        json.dump(source, fh, ensure_ascii=False, indent=2)
        fh.write("\n")


if __name__ == "__main__":
    main()
