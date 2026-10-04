"""Exercise the real draft step with a local gh boundary; no GitHub/network mutations."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/release.yml"


def draft_script():
    text = WORKFLOW.read_text(encoding="utf-8")
    draft = text.split("\n  draft:\n", 1)[1]
    lines = draft.split("        run: |\n", 1)[1].splitlines()
    return "\n".join(line[10:] for line in lines if line.startswith("          ")) + "\n"


class ReleaseWorkflowTest(unittest.TestCase):
    def run_scenario(self, scenario):
        bash = os.environ.get("ANDROID_TEST_BASH") or shutil.which("bash")
        self.assertTrue(bash, "bash is required to exercise the workflow")
        with tempfile.TemporaryDirectory(prefix="dsh-release-test-") as temporary:
            root = Path(temporary)
            assets = root / "release-assets"
            assets.mkdir()
            (assets / "synthetic.apk").write_bytes(b"synthetic unsigned verification asset")
            # The actual checksum command and checksum validation remain part of the seam.
            subprocess.run([bash, "-c", "cd release-assets && sha256sum synthetic.apk > synthetic.apk.sha256"], cwd=root, check=True)
            bindir = root / "bin"
            bindir.mkdir()
            gh = bindir / "gh"
            gh.write_text("""#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$FAKE_GH_LOG"
if [[ "$1" == api ]]; then
  if [[ "$FAKE_SCENARIO" == api-failure ]]; then exit 1; fi
  if [[ "$FAKE_SCENARIO" == draft || "$FAKE_SCENARIO" == draft-publishes || "$FAKE_SCENARIO" == published ]]; then
    # Support both the previous status query and the new existence query.
    if [[ "$*" == *'.draft'* ]]; then
      if [[ "$FAKE_SCENARIO" != published ]]; then echo true; else echo false; fi
    else echo 7; fi
  fi
elif [[ "$1 $2" == 'release create' ]]; then
  if [[ "$FAKE_SCENARIO" != absent ]]; then
    echo 'Synthetic tag already has a release at creation time.' >&2
    exit 1
  fi
elif [[ "$1 $2" == 'release upload' ]]; then
  echo 'MUTATED_EXISTING_RELEASE' >> "$FAKE_GH_LOG"
else exit 2; fi
""", encoding="utf-8", newline="\n")
            gh.chmod(0o755)
            script = root / "draft.sh"
            script.write_text(draft_script(), encoding="utf-8", newline="\n")
            log = root / "gh.log"
            environment = os.environ.copy()
            environment.update({
                "PATH": str(bindir) + os.pathsep + environment["PATH"],
                "GITHUB_REF_NAME": "v0.4.0",
                "GH_REPO": "synthetic/mobile",
                "GH_TOKEN": "synthetic-not-a-credential",
                "RUNNER_TEMP": root.as_posix(),
                "FAKE_GH_LOG": log.as_posix(),
                "FAKE_SCENARIO": scenario,
            })
            # Match Actions' bash -e -o pipefail behavior.
            result = subprocess.run([bash, "--noprofile", "--norc", "-eo", "pipefail", script.as_posix()], cwd=root, env=environment, capture_output=True, text=True)
            calls = log.read_text(encoding="utf-8") if log.exists() else ""
            return result, calls

    def test_existing_draft_is_never_modified(self):
        result, calls = self.run_scenario("draft")
        self.assertNotEqual(0, result.returncode, calls)
        self.assertNotIn("release upload", calls)
        self.assertNotIn("release create", calls)

    def test_owner_publishing_a_draft_after_check_cannot_allow_overwrite(self):
        result, calls = self.run_scenario("draft-publishes")
        self.assertNotEqual(0, result.returncode, calls)
        self.assertNotIn("release upload", calls)
        self.assertNotIn("MUTATED_EXISTING_RELEASE", calls)

    def test_published_release_is_never_modified(self):
        result, calls = self.run_scenario("published")
        self.assertNotEqual(0, result.returncode, calls)
        self.assertNotIn("release upload", calls)
        self.assertNotIn("release create", calls)

    def test_release_appearing_after_check_only_attempts_create_and_fails(self):
        result, calls = self.run_scenario("race")
        self.assertNotEqual(0, result.returncode, calls)
        self.assertIn("release create", calls)
        self.assertNotIn("release upload", calls)
        self.assertNotIn("--clobber", calls)

    def test_failed_lookup_never_mutates(self):
        result, calls = self.run_scenario("api-failure")
        self.assertNotEqual(0, result.returncode, calls)
        self.assertNotIn("release upload", calls)
        self.assertNotIn("release create", calls)

    def test_absent_release_is_created_as_new_draft_with_verified_tag(self):
        result, calls = self.run_scenario("absent")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, calls.count("release create"))
        self.assertIn("--draft", calls)
        self.assertIn("--verify-tag", calls)
        self.assertNotIn("release upload", calls)
        self.assertNotIn("--clobber", calls)


if __name__ == "__main__":
    unittest.main(verbosity=2)
