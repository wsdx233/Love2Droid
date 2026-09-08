#!/usr/bin/env python3
"""Exercise installed tools without downloading anything or contacting model providers."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import sys
import time
import urllib.request

SHARE = Path('/root/.local/share/love2droid')


def run(*command, timeout=120):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, timeout=timeout)
    if result.stderr:
        print(result.stderr.rstrip(), file=sys.stderr, flush=True)
    if result.returncode:
        raise RuntimeError(f'{command[0]} exited {result.returncode}: {(result.stdout + result.stderr)[-8192:]}')
    return result.stdout.strip()


def verify_web(timeout):
    run('node', str(SHARE / 'dsh-love2droid/activate.mjs'), '/root/.local/bin/dsh', timeout=timeout)
    # Resolve the actual profile dependencies; a stale completion marker is insufficient.
    run('node', '--input-type=module', '-e', '''
import { createRequire } from 'node:module';
const require = createRequire('/root/.dsh/profiles/web/package.json');
for (const name of ['dsh-plugin', 'dsh-web-mobile', 'dsh-love2droid']) require.resolve(name);
''', timeout=timeout)
    process = subprocess.Popen(['dsh', '--profile', 'web', '--no-open', '--port', '3080'],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               start_new_session=True)
    output = b''
    deadline = time.monotonic() + timeout
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while time.monotonic() < deadline:
                if not selector.select(timeout=1):
                    if process.poll() is not None:
                        break
                    continue
                data = os.read(process.stdout.fileno(), 8192)
                if not data:
                    break
                output = (output + data)[-65536:]
                match = re.search(rb'(?m)^dsh web: (http://127\.0\.0\.1:3080/\?token=[A-Za-z0-9_-]+)(?:[^\r\n]*)\r?\n', output)
                if match:
                    opener = urllib.request.build_opener(
                        urllib.request.ProxyHandler({}),
                        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
                    with opener.open(match[1].decode(), timeout=20) as response:
                        if response.status != 200 or b'<html' not in response.read(1048576).lower():
                            raise RuntimeError('DSH did not serve its authenticated Web UI')
                    return 'authenticated Web UI: HTTP 200'
        # Do not persist the authentication URL in the image or installer logs.
        details = re.sub(rb'token=[A-Za-z0-9_-]+', b'token=[redacted]', output).decode(errors='replace')
        raise RuntimeError(f'DSH Web startup failed: {details}')
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        process.stdout.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--component', action='append', choices=['base', 'luals', 'omp', 'dsh', 'love-check'])
    parser.add_argument('--timeout', type=int, default=120)
    args = parser.parse_args()
    for component in args.component or ['base', 'luals', 'omp', 'dsh', 'love-check']:
        if component == 'base':
            assert run('uname', '-m') == 'aarch64', 'The image must contain ARM64 executables'
            run('bash', '-n', '/root/.bashrc')
            run('bash', '-n', '/root/.local/share/bash-prompt/prompt.sh')
            result = run('git', '--version')
        elif component == 'luals':
            result = run('/opt/lua-language-server/bin/lua-language-server', '--version')
        elif component == 'omp':
            result = run('omp', '--version', timeout=args.timeout)
        elif component == 'dsh':
            assert run('node', '-p', 'process.arch') == 'arm64'
            result = '; '.join([run('bash', '-c', '. /root/.bashrc && nvm --version', timeout=args.timeout),
                                run('node', '--version'), run('npm', '--version', timeout=args.timeout),
                                run('pnpm', '--version', timeout=args.timeout),
                                run('dsh', '--version', timeout=args.timeout), verify_web(args.timeout)])
        else:
            result = run('love-check', 'doctor', '--frames', '3', '--timeout', str(args.timeout),
                         timeout=args.timeout + 15)
            assert json.loads(result)['status'] == 'passed', result
        print(f'{component}: {result}', flush=True)


if __name__ == '__main__':
    main()
