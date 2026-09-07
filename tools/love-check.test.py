#!/usr/bin/env python3
"""Behavioral checks against real Linux LÖVE 11.5, LuaJIT, Xvfb and Mesa."""

from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "app/src/normal/assets/proot/love-check/love-check.py"
spec = importlib.util.spec_from_file_location("love_check", CLI)
checker = importlib.util.module_from_spec(spec)
# Do not create __pycache__ inside packaged Android assets.
sys.dont_write_bytecode = True
spec.loader.exec_module(checker)


class LoveCheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for command in ("love", "luajit", "Xvfb"):
            if not shutil.which(command):
                raise RuntimeError(f"Missing real dependency {command}; see doc/verification.md")

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="love-check-test-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.project = self.base / "游戏 with spaces"
        self.project.mkdir()
        self.main = self.project / "main.lua"
        self.main.write_text("function love.draw() love.graphics.circle('fill', 20, 20, 5) end")

    def run_check(self, *extra, frames=3, seconds=15):
        source = self.main.read_bytes()
        process = subprocess.run(
            [sys.executable, str(CLI), "check", str(self.project), "--frames", str(frames),
             "--timeout", str(seconds), *extra],
            capture_output=True, text=True, timeout=seconds + 5,
        )
        self.assertEqual(source, self.main.read_bytes(), "Checker changed the original main.lua")
        self.assertEqual("", process.stderr)
        return process.returncode, json.loads(process.stdout)

    def assert_error(self, source, token, phase):
        self.main.write_text(source)
        code, result = self.run_check()
        self.assertEqual(1, code, result)
        self.assertEqual("error", result["status"])
        self.assertEqual(phase, result["phase"])
        self.assertIn(token, result["error"])
        self.assertIn("main.lua:", result["error"])

    def test_doctor_renders_assets_shader_font_and_uses_real_audio(self):
        process = subprocess.run(
            [sys.executable, str(CLI), "doctor", "--frames", "3", "--timeout", "15"],
            capture_output=True, text=True, timeout=20,
        )
        result = json.loads(process.stdout)
        self.assertEqual(0, process.returncode, result)
        self.assertEqual("passed", result["status"])
        self.assertEqual("11.5", result["engine"])
        self.assertEqual(3, result["frames"])
        self.assertIn("llvmpipe", result["renderer"].lower())

    def test_cpu_target_is_arm64_only_and_does_not_mutate_parent_environment(self):
        for machine in ("aarch64", "arm64", "x86_64", "armv7l"):
            for inherited in (None, "/tmp/custom-llvm-cpuinfo"):
                with self.subTest(machine=machine, inherited=inherited):
                    work = self.base / f"{machine}-{inherited is not None}"
                    work.mkdir()
                    host = os.uname_result(("Linux", "test-host", "test-release", "test-version", machine))
                    with patch.dict(os.environ), patch.object(checker.os, "uname", return_value=host):
                        if inherited is None:
                            os.environ.pop("LLVM_CPUINFO", None)
                        else:
                            os.environ["LLVM_CPUINFO"] = inherited
                        environment = checker.child_environment(work)
                        expected = "/dev/null" if machine in ("aarch64", "arm64") else inherited
                        self.assertEqual(expected, environment.get("LLVM_CPUINFO"))
                        self.assertEqual(inherited, os.environ.get("LLVM_CPUINFO"))

    def test_arm64_cpu_target_reaches_real_doctor_and_project_runtime(self):
        self.main.write_text('''
            function love.load()
                assert(os.getenv("LLVM_CPUINFO") == "/dev/null")
            end
            function love.draw()
                love.graphics.circle("fill", 20, 20, 5)
            end
        ''')
        host = os.uname_result(("Linux", "test-host", "test-release", "test-version", "aarch64"))
        for command in ("doctor", "check"):
            with self.subTest(command=command):
                options = checker.argparse.Namespace(
                    command=command, project=str(self.project), syntax_only=False, frames=3,
                )
                result = {"status": "passed"}
                with patch.dict(os.environ, {"LLVM_CPUINFO": "/tmp/custom-llvm-cpuinfo"}), \
                        patch.object(checker.os, "uname", return_value=host):
                    code = checker.check_project(options, result, time.monotonic() + 15)
                self.assertEqual(0, code, result)
                self.assertEqual("passed", result["status"])
                self.assertEqual(3, result["frames"])
                self.assertIn("llvmpipe", result["renderer"].lower())

    def test_syntax_only_never_executes_and_checks_unloaded_modules(self):
        self.main.write_text('error("must not execute")')
        code, result = self.run_check("--syntax-only")
        self.assertEqual(0, code, result)
        self.assertEqual("syntax", result["phase"])
        module = self.project / "unused.lua"
        module.write_text("function broken( end")
        code, result = self.run_check("--syntax-only")
        self.assertEqual(1, code)
        self.assertIn("unused.lua", result["logs"])
        module.unlink()
        (self.project / ".hidden.lua").write_text("function broken( end")
        self.assertEqual(0, self.run_check("--syntax-only")[0])

    def test_luajit_does_not_accept_lua54_integer_division(self):
        self.main.write_text("local value = 7 // 2")
        code, result = self.run_check("--syntax-only")
        self.assertEqual(1, code)
        self.assertEqual("syntax", result["phase"])

    def test_initialization_error_returns_stack(self):
        self.assert_error('function love.load() error("LOAD_SENTINEL") end', "LOAD_SENTINEL", "love.load")

    def test_draw_error_returns_stack(self):
        self.assert_error('function love.draw() error("DRAW_SENTINEL") end', "DRAW_SENTINEL", "love.draw")

    def test_missing_image_is_not_replaced_by_a_stub(self):
        self.assert_error('function love.load() love.graphics.newImage("missing.png") end', "missing.png", "love.load")

    def test_invalid_shader_fails(self):
        self.assert_error(
            'function love.load() love.graphics.newShader("vec4 effect(vec4 c, Image t, vec2 uv, vec2 sc) { return MISSING_SHADER_SYMBOL; }") end',
            "MISSING_SHADER_SYMBOL", "love.load",
        )

    def test_config_error_and_incompatible_version_cannot_open_a_dialog(self):
        conf = self.project / "conf.lua"
        conf.write_text('function love.conf(t) error("CONF_SENTINEL") end')
        code, result = self.run_check()
        self.assertEqual(1, code)
        self.assertIn("conf.lua:1: CONF_SENTINEL", result["error"])
        conf.write_text('function love.conf(t) t.version = "12.0" end')
        code, result = self.run_check()
        self.assertEqual(1, code)
        self.assertIn("12.0", result["error"])
        self.assertEqual("config", result["phase"])

    def test_early_exit_is_incomplete_not_passed(self):
        self.main.write_text("function love.load() love.event.quit() end")
        code, result = self.run_check()
        self.assertEqual(3, code)
        self.assertEqual("incomplete", result["status"])
        self.assertEqual(0, result["frames"])

    def test_direct_process_exit_without_report_is_not_passed(self):
        self.main.write_text("os.exit(0)")
        code, result = self.run_check()
        self.assertEqual(1, code)
        self.assertIn("without a checker result", result["error"])

    def test_loading_presentations_do_not_complete_the_frame_budget(self):
        self.assert_error(
            'function love.load() for i=1,10 do love.graphics.present() end error("AFTER_LOADING") end',
            "AFTER_LOADING", "love.load",
        )

    def test_blocking_custom_run_is_still_bounded_by_presentations(self):
        self.main.write_text("function love.run() while true do love.graphics.clear(1,0,0); love.graphics.present() end end")
        code, result = self.run_check(frames=4)
        self.assertEqual(0, code, result)
        self.assertEqual(4, result["frames"])

    def test_watchdog_terminates_an_infinite_lua_loop(self):
        self.main.write_text("function love.load() while true do end end")
        code, result = self.run_check(seconds=2)
        self.assertEqual(124, code)
        self.assertEqual("timeout", result["status"])
        self.assertLess(result["elapsed_seconds"], 5)

    def test_saves_and_relative_writes_stay_in_the_temporary_copy(self):
        self.main.write_text('''
            function love.load()
                assert(not love.filesystem.getInfo("new-save.txt"))
                assert(love.filesystem.write("new-save.txt", "save"))
                local file = assert(io.open("relative-write.txt", "w"))
                file:write("temporary")
                file:close()
            end
        ''')
        for _ in range(2):
            code, result = self.run_check()
            self.assertEqual(0, code, result)
        self.assertFalse((self.project / "relative-write.txt").exists())
        self.assertFalse((self.project / "new-save.txt").exists())
        self.assertFalse((self.project / ".love-check").exists())

    def test_escaping_links_and_directory_cycles_are_rejected(self):
        outside = self.base / "outside.lua"
        outside.write_text("return 1")
        link = self.project / "escape.lua"
        link.symlink_to(outside)
        code, result = self.run_check("--syntax-only")
        self.assertEqual(1, code)
        self.assertIn("escapes", result["error"])
        link.unlink()
        (self.project / "loop").symlink_to(self.project, target_is_directory=True)
        code, result = self.run_check("--syntax-only")
        self.assertEqual(1, code)
        self.assertIn("cycle", result["error"])

    def test_log_output_is_bounded_without_hiding_the_final_error(self):
        self.main.write_text('function love.load() print(string.rep("x", 100000)); error("FINAL_ERROR") end')
        code, result = self.run_check()
        self.assertEqual(1, code)
        self.assertTrue(result["logs_truncated"])
        self.assertLessEqual(len(result["logs"].encode()), checker.MAX_LOG_BYTES)
        self.assertIn("FINAL_ERROR", result["error"])

    def test_two_checks_do_not_share_displays_or_results(self):
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: self.run_check(frames=2), range(2)))
        for code, result in results:
            self.assertEqual(0, code, result)
            self.assertEqual(2, result["frames"])

    def test_sigterm_cancels_and_reaps_owned_processes(self):
        marker = self.base / "running"
        self.main.write_text('function love.load() local f=assert(io.open(' + json.dumps(str(marker)) + ', "w")); f:write("ready"); f:close(); while true do end end')
        process = subprocess.Popen(
            [sys.executable, str(CLI), "check", str(self.project), "--timeout", "15"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        try:
            deadline = time.monotonic() + 10
            while not marker.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(marker.exists(), "Game did not reach the cancellation scenario")
            children_file = Path(f"/proc/{process.pid}/task/{process.pid}/children")
            children = children_file.read_text().split()
            process.send_signal(signal.SIGTERM)
            stdout, stderr = process.communicate(timeout=5)
            result = json.loads(stdout)
            self.assertEqual(143, process.returncode, (result, stderr))
            self.assertEqual("cancelled", result["status"])
            for child in children:
                self.assertFalse(Path(f"/proc/{child}").exists(), f"Checker leaked child {child}")
        finally:
            if process.poll() is None:
                process.terminate()
                process.communicate(timeout=5)

    def test_private_x_display_requires_its_cookie(self):
        work = self.base / "display"
        work.mkdir()
        environment = checker.child_environment(work)
        probe = '''
import ctypes, ctypes.util, sys
x11 = ctypes.CDLL(ctypes.util.find_library("X11"))
x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
x11.XOpenDisplay.restype = ctypes.c_void_p
x11.XCloseDisplay.argtypes = [ctypes.c_void_p]
display = x11.XOpenDisplay(None)
if display: x11.XCloseDisplay(display)
sys.exit(0 if display else 1)
'''
        with checker.virtual_display(work, environment, time.monotonic() + 15) as authenticated:
            good = subprocess.run([sys.executable, "-c", probe], env=authenticated, capture_output=True, timeout=5)
            bad = subprocess.run(
                [sys.executable, "-c", probe], env=authenticated | {"XAUTHORITY": str(work / "missing.auth")},
                capture_output=True, timeout=5,
            )
            self.assertEqual(0, good.returncode, good.stderr)
            self.assertEqual(1, bad.returncode, bad.stderr)


if __name__ == "__main__":
    unittest.main()
