"""Exercise the release workflow's commit filters without running a release.

Run from any directory with: python3 tools/test_release_changelog.py
"""

import os
from pathlib import Path
import shutil
import subprocess
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/release.yml"


def release_entries(variable, commits):
    assignments = [
        line.strip()
        for line in WORKFLOW.read_text(encoding="utf-8").splitlines()
        if line.strip().startswith(f"{variable}=$(")
    ]
    if len(assignments) != 1:
        raise AssertionError(f"Expected one {variable} filter in the release workflow")
    # Windows 的进程搜索顺序可能先找到 WSL；完整路径才能保留 PATH 选中的 Bash。
    result = subprocess.run(
        [shutil.which("bash") or "bash", "-e", "-o", "pipefail", "-c",
         assignments[0] + f'\nprintf "%s" "${{{variable}}}"'],
        env={**os.environ, "COMMITS": "\n".join(commits)},
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.splitlines()


class ReleaseChangelogTest(unittest.TestCase):
    def test_unscoped_commits_keep_their_existing_format(self):
        self.assertEqual(["- add a feature"], release_entries("FEATS", ["feat: add a feature"]))
        self.assertEqual(["- repair a bug"], release_entries("FIXES", ["fix: repair a bug"]))

    def test_scoped_commits_are_included_and_have_the_prefix_removed(self):
        self.assertEqual(["- add a feature"], release_entries("FEATS", ["feat(ui): add a feature"]))
        self.assertEqual(["- repair a bug"], release_entries("FIXES", ["fix(xposed): repair a bug"]))

    def test_mixed_commits_stay_in_the_correct_section_and_order(self):
        commits = ["fix(import): first fix", "docs: explain usage", "feat(ui): a feature",
                   "fix: second fix", "Merge pull request #1", "prefix fix: not a fix"]
        self.assertEqual(["- first fix", "- second fix"], release_entries("FIXES", commits))
        self.assertEqual(["- a feature"], release_entries("FEATS", commits))

    def test_empty_or_malformed_scopes_do_not_match(self):
        commits = ["fix(): empty scope", "fix(ui: missing close", "fix((ui)): nested",
                   "feature: other type", "docs(ui): documentation"]
        self.assertEqual([], release_entries("FIXES", commits))
        self.assertEqual([], release_entries("FEATS", commits))

    def test_empty_input_has_no_entries(self):
        self.assertEqual([], release_entries("FIXES", []))
        self.assertEqual([], release_entries("FEATS", []))

    def test_subject_is_preserved_as_data(self):
        subject = 'keep $(printf unexpected) and "quoted" text'
        self.assertEqual(["- " + subject], release_entries("FIXES", ["fix(import): " + subject]))


if __name__ == "__main__":
    unittest.main()
