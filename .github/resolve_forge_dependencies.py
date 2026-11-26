#!/usr/bin/env python3
"""Resolve released Forge dependencies once per GTSH build."""
import base64
import json
import os
from pathlib import Path
import re
import sys
import tomllib
from urllib.parse import quote
from urllib.request import Request, urlopen

UI = "dishanhai/SH-Better-UI"
COMMON = "dishanhai/SH-Common-Lib"
VERSION = r"[0-9]+\.[0-9]+\.[0-9]+"


def version(text):
    if not re.fullmatch(VERSION, text):
        raise ValueError(f"Expected a stable x.y.z version, got {text!r}")
    return tuple(map(int, text.split(".")))


def candidates(releases, tag_prefix, asset_prefix, minimum):
    eligible = []
    for release in releases:
        if release["draft"] or release["prerelease"]:
            continue
        tag = release["tag_name"]
        match = re.fullmatch(re.escape(tag_prefix) + f"({VERSION})", tag)
        if match is None:
            continue
        name = match[1]
        semver = version(name)
        if semver >= minimum and any(
            item["name"] == f"{asset_prefix}{name}.jar" and item["state"] == "uploaded"
            for item in release["assets"]
        ):
            eligible.append((semver, tag))
    return sorted(eligible, reverse=True)


def properties(text):
    return dict(re.findall(r"^([a-zA-Z_][a-zA-Z_0-9]*)\s*=\s*([^\s#]+)", text, re.M))


def ui_range(metadata, variables):
    toml = tomllib.loads(metadata)
    dependency = next(entry for entry in toml["dependencies"]["sh_common_lib"]
                      if entry["modId"] == "sh_better_ui")
    raw = dependency["versionRange"]
    raw = re.sub(r"\$\{([a-zA-Z_][a-zA-Z_0-9]*)\}", lambda match: variables[match[1]], raw)
    match = re.fullmatch(r"(\[|\()([^,]+),([^\])]*)(\]|\))", raw)
    if match is None:
        raise ValueError(f"Unsupported SHCLIB → SHBUI dependency range: {raw}")
    left, low, high, right = match.groups()
    return version(low), left == "[", version(high) if high else None, right == "]"


def compatible(candidate, bounds):
    low, include_low, high, include_high = bounds
    return (candidate > low or include_low and candidate == low) and (
        high is None or candidate < high or include_high and candidate == high
    )


class GitHub:
    def __init__(self, token):
        self.headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "GTSH-CI-dependency-resolver",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if token:
            self.headers["Authorization"] = "Bearer " + token

    def get(self, path):
        request = Request(f"https://api.github.com/repos/{path}", headers=self.headers)
        with urlopen(request, timeout=30) as reply:
            return json.load(reply)

    def releases(self, repo):
        page = 1
        while True:
            results = self.get(f"{repo}/releases?per_page=100&page={page}")
            yield from results
            if len(results) < 100:
                break
            page += 1

    def commit(self, repo, ref):
        sha = self.get(f"{repo}/commits/{quote(ref, safe='')}")["sha"]
        if not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError("GitHub did not return a full commit SHA")
        return sha

    def file(self, repo, commit, name):
        content = self.get(f"{repo}/contents/{quote(name)}?ref={commit}")
        if content["encoding"] != "base64":
            raise ValueError(f"GitHub did not return base64 source for {name}")
        return base64.b64decode(content["content"]).decode("utf-8")


def resolve(api, project_properties):
    mins = properties(project_properties)
    forge = mins["minecraft_version"]
    version(forge)
    uis = candidates(api.releases(UI), "SHBUI-v", f"sh_better_ui-forge-{forge}-",
                     version(mins["sh_better_ui_version"]))
    commons = candidates(api.releases(COMMON), "SHCLIB-v", f"sh-common-lib-forge-{forge}-",
                         version(mins["sh_common_lib_version"]))
    for _, common_tag in commons:
        common_sha = api.commit(COMMON, common_tag)
        common_properties = properties(api.file(COMMON, common_sha, f"forge-{forge}/gradle.properties"))
        bounds = ui_range(api.file(COMMON, common_sha, f"forge-{forge}/src/main/resources/META-INF/mods.toml"),
                          common_properties)
        for ui_version, ui_tag in uis:
            if compatible(ui_version, bounds):
                return {
                    "shbui_ref": api.commit(UI, ui_tag), "shbui_tag": ui_tag,
                    "shclib_ref": common_sha, "shclib_tag": common_tag,
                }
    raise ValueError(f"No compatible released Forge {forge} pair: "
                     f"SHBUI >= {mins['sh_better_ui_version']}, SHCLIB >= {mins['sh_common_lib_version']}")


def main():
    props = Path(sys.argv[1]).read_text(encoding="utf-8")
    forge = properties(props)["minecraft_version"]
    selection = resolve(GitHub(os.environ.get("GH_TOKEN")), props)
    print(f"Forge {forge}: {selection['shbui_tag']} ({selection['shbui_ref']}); "
          f"{selection['shclib_tag']} ({selection['shclib_ref']})")
    if path := os.environ.get("GITHUB_OUTPUT"):
        with open(path, "a", encoding="utf-8") as output:
            output.writelines(f"{key}={value}\n" for key, value in selection.items())
    if path := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(path, "a", encoding="utf-8") as summary:
            summary.write(f"### Forge {forge} resolved dependencies\n\n"
                          f"- SHBUI: `{selection['shbui_tag']}` / `{selection['shbui_ref']}`\n"
                          f"- SHCLIB: `{selection['shclib_tag']}` / `{selection['shclib_ref']}`\n")


if __name__ == "__main__":
    main()
