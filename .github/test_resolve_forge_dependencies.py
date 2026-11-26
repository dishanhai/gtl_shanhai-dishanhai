from unittest import TestCase
import resolve_forge_dependencies as resolver

PROPS = "minecraft_version=1.20.1\nsh_better_ui_version=1.0.12\nsh_common_lib_version=0.4.1\n"
MANIFEST = '''
[[dependencies.sh_common_lib]]
modId="sh_better_ui"
versionRange="[${sh_better_ui_version},2.0.0)"
'''


def release(tag, asset, **changes):
    return dict({"tag_name": tag, "assets": [{"name": asset, "state": "uploaded"}],
                 "draft": False, "prerelease": False}, **changes)


def ui(v, target="forge-1.20.1", **changes):
    return release(f"SHBUI-v{v}", f"sh_better_ui-{target}-{v}.jar", **changes)


def common(v):
    return release(f"SHCLIB-v{v}", f"sh-common-lib-forge-1.20.1-{v}.jar")


class FakeGitHub:
    def __init__(self, uis, commons, minimums=None):
        self.available = {resolver.UI: uis, resolver.COMMON: commons}
        self.minimums = minimums or {}

    def releases(self, repo):
        return self.available[repo]

    def commit(self, repo, tag):
        return tag  # Keep the fixture's tag readable when testing selected pairs.

    def file(self, repo, commit, path):
        if path.endswith("gradle.properties"):
            return f"sh_better_ui_version={self.minimums.get(commit, '1.0.14')}"
        return MANIFEST


class ResolveTests(TestCase):
    def test_selects_highest_compatible_stable_forge_pair(self):
        api = FakeGitHub([
            ui("1.0.14"), ui("1.9.0"), ui("1.10.0"), ui("2.0.0"),
            ui("1.99.0", prerelease=True), ui("1.98.0", target="neoforge-1.21.1"),
            ui("1.97.0", draft=True), ui("1.20.0-alpha.2"),
        ], [common("0.4.1"), common("0.10.2")])
        pair = resolver.resolve(api, PROPS)
        self.assertEqual("SHCLIB-v0.10.2", pair["shclib_tag"])
        self.assertEqual("SHBUI-v1.10.0", pair["shbui_tag"])

    def test_skips_common_with_unsatisfied_ui_minimum(self):
        api = FakeGitHub([ui("1.0.14")], [common("0.4.2"), common("0.4.1")],
                         {"SHCLIB-v0.4.2": "1.2.0"})
        self.assertEqual("SHCLIB-v0.4.1", resolver.resolve(api, PROPS)["shclib_tag"])

    def test_missing_compatible_release_is_explicit(self):
        api = FakeGitHub([ui("1.0.11"), ui("2.0.0")], [common("0.4.1")])
        with self.assertRaisesRegex(ValueError, "No compatible released Forge"):
            resolver.resolve(api, PROPS)
