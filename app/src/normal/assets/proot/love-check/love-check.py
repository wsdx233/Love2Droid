#!/usr/bin/env python3
"""Finite, isolated Linux LÖVE 11.5 smoke checks for terminals and agents."""

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import selectors
import shutil
import signal
import stat
import struct
import subprocess
import sys
import tempfile
import threading
import time
import zlib

ASSETS = Path(__file__).resolve().parent
ENGINE_VERSION = "11.5"
MAX_ENTRIES = 4096
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_PROJECT_BYTES = 256 * 1024 * 1024
MAX_LOG_BYTES = 64 * 1024
SCOPE = "Linux LÖVE 11.5 smoke check; not Android runtime verification"


class CheckFailure(Exception):
    pass


class CheckTimeout(Exception):
    pass


class CheckCancelled(Exception):
    def __init__(self, signum):
        self.signum = signum


def require_time(deadline):
    if time.monotonic() >= deadline:
        raise CheckTimeout("Check did not finish before the deadline")


def stop_group(process):
    # Own the whole session, including game-created child processes.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=0.5)
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def run_process(command, cwd, environment, deadline):
    require_time(deadline)
    logs = bytearray()
    truncated = False
    timed_out = False
    process = subprocess.Popen(
        command, cwd=cwd, env=environment, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, start_new_session=True, bufsize=0,
    )
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    timed_out = True
                    break
                for key, _ in selector.select(min(remaining, 0.1)):
                    data = os.read(key.fd, 65536)
                    if not data:
                        selector.unregister(key.fileobj)
                        continue
                    logs.extend(data)
                    if len(logs) > MAX_LOG_BYTES:
                        del logs[:-MAX_LOG_BYTES]
                        truncated = True
            if not timed_out:
                try:
                    process.wait(timeout=max(0.001, deadline - time.monotonic()))
                except subprocess.TimeoutExpired:
                    timed_out = True
    finally:
        stop_group(process)
        process.stdout.close()
    return {
        "exit_code": process.returncode,
        "logs": logs.decode("utf-8", errors="replace"),
        "logs_truncated": truncated,
        "timed_out": timed_out,
    }


def write_authority(path, display, cookie):
    # Xauthority: FamilyWild, address, display, protocol and cookie. Xserver
    # loads protocol/cookie pairs; Xlib additionally matches the display field.
    fields = (b"", display.encode("ascii"), b"MIT-MAGIC-COOKIE-1", cookie)
    path.write_bytes(struct.pack(">H", 65535) + b"".join(struct.pack(">H", len(value)) + value for value in fields))
    path.chmod(0o600)


@contextmanager
def virtual_display(work, environment, deadline):
    # xauth locks use hard links, which conflict with PRoot --link2symlink.
    # Private, single-writer auth files need neither xauth nor its lock files.
    cookie = secrets.token_bytes(16)
    server_auth = work / "server.auth"
    client_auth = work / "client.auth"
    write_authority(server_auth, "0", cookie)
    read_fd, write_fd = os.pipe()
    server = None
    reader = None
    logs = bytearray()

    def drain():
        while data := server.stdout.read(65536):
            logs.extend(data)
            if len(logs) > MAX_LOG_BYTES:
                del logs[:-MAX_LOG_BYTES]

    try:
        try:
            server = subprocess.Popen(
                [executable("Xvfb"), "-displayfd", str(write_fd), "-screen", "0", "800x600x24",
                 "-nolisten", "tcp", "-noreset", "-auth", str(server_auth)],
                cwd=work, env=environment, pass_fds=(write_fd,), stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, start_new_session=True, bufsize=0,
            )
        finally:
            os.close(write_fd)
        reader = threading.Thread(target=drain, daemon=True)
        reader.start()
        ready = bytearray()
        with selectors.DefaultSelector() as selector:
            selector.register(read_fd, selectors.EVENT_READ)
            while b"\n" not in ready:
                require_time(deadline)
                if server.poll() is not None:
                    raise CheckFailure("Xvfb failed to start: " + logs.decode("utf-8", errors="replace"))
                if selector.select(min(0.1, max(0, deadline - time.monotonic()))):
                    data = os.read(read_fd, 64)
                    if not data:
                        raise CheckFailure("Xvfb closed its readiness channel: " + logs.decode("utf-8", errors="replace"))
                    ready.extend(data)
                    if len(ready) > 64:
                        raise CheckFailure("Invalid Xvfb readiness response")
        display = ready.strip()
        if not display.isdigit():
            raise CheckFailure("Invalid Xvfb display number")
        write_authority(client_auth, display.decode("ascii"), cookie)
        yield environment | {"DISPLAY": ":" + display.decode("ascii"), "XAUTHORITY": str(client_auth)}
    finally:
        os.close(read_fd)
        if server is not None:
            stop_group(server)
            if reader is not None:
                reader.join(timeout=1)
            server.stdout.close()


def snapshot_project(root, target, deadline):
    """Follow Play's non-hidden-file policy; reject escaping links and cycles."""
    lua_files = []
    entries = 0
    total_bytes = 0

    def visit(directory, destination, ancestors):
        nonlocal entries, total_bytes
        require_time(deadline)
        real = directory.resolve(strict=True)
        if not real.is_relative_to(root):
            raise CheckFailure(f"Project path escapes its root: {directory}")
        if real in ancestors:
            raise CheckFailure(f"Directory link cycle: {directory}")
        destination.mkdir()
        children = []
        with os.scandir(real) as listing:
            for item in listing:
                require_time(deadline)
                # LoveArchiveTransfer.addDirectory excludes all dot-prefixed names.
                if item.name.startswith("."):
                    continue
                entries += 1
                if entries > MAX_ENTRIES:
                    raise CheckFailure(f"Project exceeds {MAX_ENTRIES} entries")
                children.append(item.name)
        for name in sorted(children):
            source = (real / name).resolve(strict=True)
            if not source.is_relative_to(root):
                raise CheckFailure(f"Project path escapes its root: {directory / name}")
            before = source.stat()
            output = destination / name
            if stat.S_ISDIR(before.st_mode):
                visit(source, output, ancestors | {real})
                continue
            if not stat.S_ISREG(before.st_mode):
                raise CheckFailure(f"Not a regular project file: {source}")
            if before.st_size > MAX_FILE_BYTES:
                raise CheckFailure(f"Project file exceeds 64 MiB: {source}")
            copied = 0
            with source.open("rb") as incoming, output.open("xb") as outgoing:
                while chunk := incoming.read(65536):
                    require_time(deadline)
                    copied += len(chunk)
                    total_bytes += len(chunk)
                    if copied > MAX_FILE_BYTES or total_bytes > MAX_PROJECT_BYTES:
                        raise CheckFailure("Project exceeds the 64 MiB file or 256 MiB total limit")
                    outgoing.write(chunk)
                after = os.fstat(incoming.fileno())
            if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns
            ):
                raise CheckFailure(f"Project changed while taking a snapshot: {source}")
            if output.suffix.lower() == ".lua":
                lua_files.append(output.relative_to(target).as_posix())

    visit(root, target, set())
    if not (target / "main.lua").is_file():
        raise CheckFailure("Project requires a main.lua file at its root")
    return lua_files


def instrument_snapshot(project):
    originals = project / ".love-check"
    originals.mkdir()
    (project / "main.lua").rename(originals / "main.lua")
    conf = project / "conf.lua"
    has_config = conf.exists()
    if has_config:
        conf.rename(originals / "conf.lua")
    conf.write_text(
        'local check = dofile(assert(os.getenv("LOVE_CHECK_BOOTSTRAP")))\n'
        'package.loaded["love-check.runtime"] = check\n'
        + ('check.loadSource(".love-check/conf.lua", "conf.lua")\n' if has_config else '')
        + 'local configure = love.conf\n'
        'love.conf = function(config) check.configure(configure, config) end\n',
        encoding="utf-8",
    )
    (project / "main.lua").write_text(
        'local check = assert(package.loaded["love-check.runtime"])\n'
        'check.start()\n'
        'check.loadSource(".love-check/main.lua", "main.lua")\n'
        'check.wrapRun()\n', encoding="utf-8",
    )


def create_doctor_project(project):
    project.mkdir()
    shutil.copyfile(ASSETS / "doctor.lua", project / "main.lua")

    def png_chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    (project / "pixel.png").write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", struct.pack(">2I5B", 1, 1, 8, 6, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(b"\0\xff\xff\xff\xff"))
        + png_chunk(b"IEND", b"")
    )


def child_environment(work):
    environment = os.environ.copy()
    for key in ("LUA_INIT", "LUA_INIT_5_1", "LUA_PATH", "LUA_CPATH"):
        environment.pop(key, None)
    for key, directory in (
        ("HOME", "home"), ("XDG_DATA_HOME", "data"), ("XDG_CONFIG_HOME", "config"),
        ("XDG_CACHE_HOME", "cache"), ("XDG_RUNTIME_DIR", "runtime"), ("TMPDIR", "tmp"),
    ):
        path = work / directory
        path.mkdir(mode=0o700)
        environment[key] = str(path)
    environment.update({
        "LIBGL_ALWAYS_SOFTWARE": "1",
        "GALLIUM_DRIVER": "llvmpipe",
        "LP_NUM_THREADS": "2",
        "SDL_VIDEODRIVER": "x11",
        # Keep the real audio API and mixer, without requiring an audio device.
        "ALSOFT_DRIVERS": "null",
        "LOVE_CHECK_BOOTSTRAP": str(ASSETS / "bootstrap.lua"),
        "LOVE_CHECK_REPORT": str(work / "runtime-result.json"),
    })
    if os.uname().machine in ("aarch64", "arm64"):
        # Use LLVM's generic ARM64 target: model-specific llvmpipe code can
        # SIGILL in PRoot even when standalone LuaJIT works.
        environment["LLVM_CPUINFO"] = "/dev/null"
    return environment


def executable(name):
    path = shutil.which(name)
    if not path:
        raise CheckFailure(f"Missing {name}; install the LÖVE 11.5 headless component")
    return path


def check_project(options, result, deadline):
    with tempfile.TemporaryDirectory(prefix="love-check-") as temporary:
        work = Path(temporary).resolve()
        environment = child_environment(work)
        project = work / "project"
        result["phase"] = "snapshot"
        if options.command == "doctor":
            original = work / "doctor"
            create_doctor_project(original)
        else:
            original = Path(options.project).expanduser().resolve(strict=True)
        if not original.is_dir():
            raise CheckFailure("Project must be a directory")
        lua_files = snapshot_project(original, project, deadline)
        result["lua_files"] = len(lua_files)
        manifest = work / "lua-files"
        manifest.write_bytes(b"".join(os.fsencode(name) + b"\0" for name in lua_files))
        result["phase"] = "syntax"
        syntax = run_process(
            [executable("luajit"), str(ASSETS / "syntax.lua"), str(manifest)],
            project, environment, deadline,
        )
        result.update(syntax)
        if syntax["timed_out"]:
            raise CheckTimeout("LuaJIT syntax check timed out")
        if syntax["exit_code"] != 0:
            raise CheckFailure("LuaJIT syntax check failed; see logs")
        if options.syntax_only:
            result.update(status="passed", phase="syntax")
            return 0

        result["phase"] = "environment"
        love = executable("love")
        version = run_process([love, "--version"], work, environment, deadline)
        result.update(version)
        if version["timed_out"]:
            raise CheckTimeout("LÖVE version query timed out")
        if version["exit_code"] != 0 or version["logs"].split(" ", 2)[:2] != ["LOVE", ENGINE_VERSION]:
            raise CheckFailure(f"Expected LÖVE {ENGINE_VERSION}; see version output in logs")
        instrument_snapshot(project)
        environment["LOVE_CHECK_FRAMES"] = str(options.frames)
        result["phase"] = "startup"
        with virtual_display(work, environment, deadline) as display_environment:
            result["phase"] = "runtime"
            runtime = run_process([love, str(project)], project, display_environment, deadline)
        result.update(runtime)
        if runtime["timed_out"]:
            raise CheckTimeout("Game or virtual display did not finish before the deadline")
        report = work / "runtime-result.json"
        if not report.is_file():
            raise CheckFailure("Game exited without a checker result; see process exit code and logs")
        if report.stat().st_size > MAX_LOG_BYTES * 8:
            raise CheckFailure("Runtime result exceeds its size limit")
        details = json.loads(report.read_text(encoding="utf-8", errors="replace"))
        for key in ("status", "phase", "frames", "engine", "renderer", "error"):
            if key in details:
                result[key] = details[key]
        if details.get("status") == "passed":
            if runtime["exit_code"] != 0 or details.get("frames") != options.frames or details.get("engine") != ENGINE_VERSION:
                raise CheckFailure("Runtime exited without completing the requested frame contract")
            if "llvmpipe" not in details.get("renderer", "").lower():
                raise CheckFailure("Runtime did not confirm llvmpipe software rendering")
            return 0
        if details.get("status") == "incomplete":
            return 3
        result["status"] = "error"
        return 1


def bounded_integer(minimum, maximum):
    def parse(value):
        number = int(value)
        if not minimum <= number <= maximum:
            raise argparse.ArgumentTypeError(f"must be between {minimum} and {maximum}")
        return number
    return parse


def main():
    parser = argparse.ArgumentParser(description=SCOPE)
    subcommands = parser.add_subparsers(dest="command", required=True)
    for command in ("check", "doctor"):
        subparser = subcommands.add_parser(command, help="Check a saved project" if command == "check" else "Verify real rendering, assets, audio and isolated saves")
        if command == "check":
            subparser.add_argument("project", help="Saved project directory (unsaved editor buffers are not read)")
            subparser.add_argument("--syntax-only", action="store_true", help="Compile Lua sources without executing them")
        else:
            subparser.set_defaults(syntax_only=False)
        subparser.add_argument("--frames", type=bounded_integer(1, 100000), default=300, help="Successful presentations required (default: 300)")
        subparser.add_argument("--timeout", type=bounded_integer(1, 600), default=30, help="Total deadline in seconds, including setup (default: 30)")
    options = parser.parse_args()
    started = time.monotonic()
    result = {"status": "error", "scope": SCOPE, "mode": options.command, "phase": "setup", "frames": 0}

    def cancel(signum, _frame):
        raise CheckCancelled(signum)

    signal.signal(signal.SIGTERM, cancel)
    signal.signal(signal.SIGINT, cancel)
    try:
        code = check_project(options, result, started + options.timeout)
    except CheckTimeout as error:
        result.update(status="timeout", error=str(error))
        code = 124
    except CheckCancelled as error:
        result.update(status="cancelled", error="Check cancelled")
        code = 128 + error.signum
    except (CheckFailure, OSError, ValueError, RuntimeError) as error:
        result.update(status="error", error=str(error))
        code = 1
    result["elapsed_seconds"] = round(time.monotonic() - started, 3)
    print(json.dumps(result, ensure_ascii=False))
    return code


if __name__ == "__main__":
    sys.exit(main())
