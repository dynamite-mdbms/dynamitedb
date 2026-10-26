"""Exercise real Git hooks in disposable repositories, without running Gradle."""

from pathlib import Path
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class HookTests(unittest.TestCase):
    def setUp(self):
        (ROOT / ".gradle").mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="hook-tests-", dir=ROOT / ".gradle")
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Hook test")
        self.git("config", "user.email", "hook-test@example.invalid")
        self.git("config", "core.autocrlf", "false")
        self.write("tracked.txt", "original\n")
        self.write(
            "gradlew",
            "#!/bin/sh\n"
            "printf '%s\\n' \"$*\" >> .hook-log\n"
            'case "$*" in\n'
            "  *format*)\n"
            "    [ ! -f .fail-format ] || exit 23\n"
            "    if [ -f .format-change ]; then echo formatted >> tracked.txt; fi\n"
            "    ;;\n"
            "  *lint*) [ ! -f .fail-lint ] || exit 24 ;;\n"
            "esac\n",
        )
        os.chmod(self.repo / "gradlew", 0o755)
        self.git("add", ".")
        self.git("-c", "core.hooksPath=", "commit", "--quiet", "-m", "fixture")
        self.git("config", "core.hooksPath", str(ROOT / ".githooks"))
        self.write("tracked.txt", "staged\n")
        self.git("add", "tracked.txt")

    def git(self, *args, check=True):
        result = subprocess.run(
            ["git", *args], cwd=self.repo, capture_output=True, text=True
        )
        if check and result.returncode:
            self.fail(result.stdout + result.stderr)
        return result

    def write(self, name, content=""):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")

    def commit(self, message="test (repo): verify hooks"):
        return self.git("commit", "--quiet", "-m", message, check=False)

    def assert_blocked(self, expected):
        result = self.commit()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(expected, result.stdout + result.stderr)

    def test_valid_commit_runs_format_lint_and_build(self):
        self.assertEqual(self.commit().returncode, 0)
        self.assertEqual(
            (self.repo / ".hook-log").read_text().splitlines(),
            ["--console=plain format", "--console=plain lint build"],
        )

    def test_unstaged_changes_block_before_checks(self):
        self.write("tracked.txt", "unstaged\n")
        self.assert_blocked("stage or stash")
        self.assertFalse((self.repo / ".hook-log").exists())
        self.assertEqual(self.git("show", ":tracked.txt").stdout, "staged\n")

    def test_untracked_sources_block_before_checks(self):
        self.write("contracts/src/main/resources/input.txt", "untracked\n")
        self.assert_blocked("untracked build inputs")
        self.assertFalse((self.repo / ".hook-log").exists())

    def test_format_changes_block_until_reviewed_and_staged(self):
        self.write(".format-change")
        self.assert_blocked("formatting changed files")
        self.assertEqual(self.git("show", ":tracked.txt").stdout, "staged\n")
        self.assertIn("formatted", (self.repo / "tracked.txt").read_text())
        (self.repo / ".format-change").unlink()
        self.git("add", "tracked.txt")
        self.assertEqual(self.commit().returncode, 0)

    def test_formatter_failure_blocks(self):
        self.write(".fail-format")
        self.assertNotEqual(self.commit().returncode, 0)
        self.assertNotIn("lint build", (self.repo / ".hook-log").read_text())

    def test_lint_or_build_failure_blocks(self):
        self.write(".fail-lint")
        self.assertNotEqual(self.commit().returncode, 0)

    def test_invalid_message_blocks(self):
        for message in ["fix stuff", "feat(qp): missing required space", "feat (unknown): add item"]:
            with self.subTest(message=message):
                result = self.commit(message)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("<type> (<component>): <description>", result.stderr)


if __name__ == "__main__":
    unittest.main()
