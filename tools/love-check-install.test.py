#!/usr/bin/env python3
"""Real APT/dpkg installation regression in a disposable amd64 Ubuntu Base guest.

Set LOVE_CHECK_TEST_UBUNTU_BASE to the official 24.04.4 amd64 Base archive.
Requires network access and the host tools used by tools/proot-debug.sh.
Uses native Linux hardlinks; Android's link2symlink path needs device verification.
"""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/normal/assets/proot/love-check"
BASE_SHA256 = "c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58"
GUEST_ASSETS = "/root/.local/share/love2droid/love-check"
MAN_PAGE = "usr/share/man/man6/love-11.5.6.gz"
FILTER = "etc/dpkg/dpkg.cfg.d/love2droid-love-check"
PACKAGE = "/var/cache/apt/archives/love_11.5-1build1_amd64.deb"


class LoveCheckInstallTest(unittest.TestCase):
    def setUp(self):
        archive = Path(os.environ["LOVE_CHECK_TEST_UBUNTU_BASE"])
        with archive.open("rb") as stream:
            self.assertEqual(BASE_SHA256, hashlib.file_digest(stream, "sha256").hexdigest())
        state = ROOT / ".proot-debug"
        state.mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="love-install-test-", dir=state)
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.guest = self.directory / "ubuntu"
        self.guest.mkdir()
        # The archive is pinned and verified before trusting its rootfs links.
        with tarfile.open(archive) as source:
            source.extractall(self.guest, filter="fully_trusted")
        for name in ("tmp", "dev/shm", "usr/local/bin", GUEST_ASSETS.lstrip("/")):
            (self.guest / name).mkdir(parents=True, exist_ok=True)
        (self.guest / "etc/resolv.conf").write_text(Path("/etc/resolv.conf").read_text())
        (self.guest / "etc/apt/apt.conf.d/99proot-nosandbox").write_text('APT::Sandbox::User "root";\n')
        (self.guest / "etc/dpkg/dpkg.cfg.d/force-unsafe-io").write_text("force-unsafe-io\n")
        for asset in ASSETS.iterdir():
            shutil.copyfile(asset, self.guest / GUEST_ASSETS.lstrip("/") / asset.name)
        launcher = self.guest / "usr/local/bin/love-check"
        launcher.write_text(f'#!/bin/sh\nexec /usr/bin/python3 {GUEST_ASSETS}/love-check.py "$@"\n')
        launcher.chmod(0o755)
        self.command = [
            str(ROOT / "tools/proot-debug.sh"), "host-proot", "--root-id", "--kill-on-exit",
            "-r", str(self.guest), "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", f"{self.guest / 'tmp'}:/dev/shm", "-w", "/root", "/usr/bin/env", "-i",
            "HOME=/root", "LANG=C.UTF-8", "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        ]

    def run_guest(self, *arguments, expected=0):
        process = subprocess.run(self.command + list(arguments), capture_output=True, text=True, timeout=600)
        self.assertEqual(expected, process.returncode, process.stdout[-6000:] + process.stderr[-6000:])
        return process

    def install(self):
        process = self.run_guest("/bin/sh", f"{GUEST_ASSETS}/install.sh")
        result = json.loads(process.stdout.splitlines()[-1])
        self.assertEqual("passed", result["status"])
        self.assertEqual(3, result["frames"])
        self.assertIn("llvmpipe", result["renderer"].lower())
        return process

    def test_fresh_install_repairs_half_configured_package_and_reuses_healthy_install(self):
        excludes = self.guest / "etc/dpkg/dpkg.cfg.d/excludes"
        original_excludes = excludes.read_bytes()
        self.install()
        manual = (self.guest / MAN_PAGE).read_bytes()
        self.assertGreater(len(manual), 0)
        # Keep the Base image's space-saving policy for unrelated documentation.
        self.assertFalse((self.guest / "usr/share/man/man1/luajit.1.gz").exists())

        # Recreate the old installer state using the real package/postinst.
        (self.guest / FILTER).unlink()
        self.run_guest("dpkg", "--unpack", PACKAGE)
        self.assertFalse((self.guest / MAN_PAGE).exists())
        failure = self.run_guest("dpkg", "--configure", "love", expected=1)
        self.assertIn(f"alternative path /{MAN_PAGE} doesn't exist", failure.stderr)
        status = self.run_guest("dpkg-query", "-W", "-f=${db:Status-Status}", "love")
        self.assertEqual("half-configured", status.stdout)

        self.install()
        status = self.run_guest("dpkg-query", "-W", "-f=${Status}\n", "love", "python3")
        self.assertEqual("install ok installed\ninstall ok installed\n", status.stdout)
        self.assertEqual(manual, (self.guest / MAN_PAGE).read_bytes())
        self.assertEqual(original_excludes, excludes.read_bytes())
        self.assertEqual("", self.run_guest("dpkg", "--audit").stdout)

        inode = (self.guest / MAN_PAGE).stat().st_ino
        self.install()
        self.assertEqual(inode, (self.guest / MAN_PAGE).stat().st_ino)
        self.assertEqual(original_excludes, excludes.read_bytes())


if __name__ == "__main__":
    unittest.main()
