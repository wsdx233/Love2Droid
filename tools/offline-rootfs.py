#!/usr/bin/env python3
"""Build a clean ARM64 offline image. Never imports a user's existing rootfs.

Requires Linux, Python 3.12+, curl, tar, xz, util-linux and rootless Podman.
On x86_64, downloads a pinned BuildKit QEMU with recursive exec support.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import sys

REPO = Path(__file__).resolve().parents[1]
ASSETS = REPO / 'app/src/normal/assets/proot'
LOCK = REPO / 'tools/offline-rootfs.lock.json'
GUEST_SHARE = '/root/.local/share/love2droid'
COMPONENTS = ['rootfs', 'lua_lsp', 'omp', 'dsh', 'git', 'love_check']
HOST_QEMU = {
    'version': '0.33.0',
    'url': 'https://github.com/moby/buildkit/releases/download/v0.33.0/buildkit-v0.33.0.linux-amd64.tar.gz',
    'sha256': 'b6242896d343100808dcbe37565caf381e0a444a6a83d7255926bb1519248ead',
}


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def execute(command, **kwargs):
    print('+', ' '.join(str(arg) for arg in command), file=sys.stderr, flush=True)
    return subprocess.run([str(arg) for arg in command], check=True, **kwargs)


class Image:
    def __init__(self, args):
        self.directory = args.output.resolve()
        self.work = self.directory / 'work'
        self.root = self.work / 'ubuntu'
        self.cache = self.directory / 'downloads'
        self.output = self.directory / 'assets/proot/offline'
        self.lock = json.loads(LOCK.read_text())
        self.custom_qemu = args.qemu is not None
        self.qemu = None
        if platform.machine() not in ('aarch64', 'arm64'):
            if platform.machine() != 'x86_64':
                raise RuntimeError('Build the ARM64 image on Linux aarch64 or x86_64')
            self.qemu = (Path(args.qemu).resolve() if args.qemu else
                         self.directory / f'host-tools/buildkit-{HOST_QEMU["version"]}/bin/buildkit-qemu-aarch64')
        self.input_hash = hashlib.sha256(LOCK.read_bytes()).hexdigest()

    def download(self, name, spec=None):
        spec = spec or self.lock[name]
        target = self.cache / name
        self.cache.mkdir(parents=True, exist_ok=True)
        if target.is_file() and digest(target) == spec['sha256']:
            return target
        temporary = target.with_suffix('.part')
        execute(['curl', '--fail', '--location', '--connect-timeout', '30',
                 spec['url'], '--output', temporary])
        if digest(temporary) != spec['sha256']:
            temporary.unlink()
            raise RuntimeError(f'{name}: SHA-256 mismatch')
        temporary.replace(target)
        return target

    def unpack(self, archive, destination, strip=False):
        destination.mkdir(parents=True, exist_ok=True)
        # Only pinned and verified upstream archives are accepted here.
        command = ['tar', '--extract', '--file', archive, '--directory', destination, '--no-same-owner']
        if strip:
            command.append('--strip-components=1')
        execute(command)

    def guest(self, *command, capture=False):
        if self.qemu and not self.custom_qemu:
            archive = self.download('buildkit', HOST_QEMU)
            if not self.qemu.is_file():
                self.qemu.parent.mkdir(parents=True, exist_ok=True)
                execute(['tar', '--extract', '--file', archive, '--directory', self.qemu.parent.parent,
                         'bin/buildkit-qemu-aarch64'])
        if self.qemu and not os.access(self.qemu, os.X_OK):
            raise RuntimeError(f'Missing direct-exec BuildKit QEMU: {self.qemu}')
        arguments = ['podman', 'unshare'] if os.geteuid() != 0 else []
        arguments += ['unshare', '--mount', '--fork', sys.executable, Path(__file__).resolve(),
                      '_guest', '--output', self.directory, '--guest-root', self.root]
        if self.qemu:
            arguments += ['--qemu', self.qemu]
        arguments += ['--command', *command]
        return execute(arguments, text=True, stdout=subprocess.PIPE if capture else None)

    def enter_guest(self, root, command):
        if root is None or not root.is_dir() or not command:
            raise RuntimeError('Internal guest entry requires a rootfs and command')
        execute(['mount', '--make-rprivate', '/'])
        for mount in ['/dev', '/proc', '/sys']:
            execute(['mount', '--rbind', mount, str(root) + mount])
        temporary = self.work / 'tmp'
        temporary.mkdir(parents=True, exist_ok=True)
        for target in [root / 'tmp', root / 'dev/shm']:
            execute(['mount', '--bind', temporary, target])
        if self.qemu:
            (root / '.host-qemu').touch()
            execute(['mount', '--bind', self.qemu, root / '.host-qemu'])
        arguments = ['/usr/bin/env', '-i', 'HOME=/root', 'LANG=C.UTF-8',
                     'PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
                     'TERM=dumb', 'TMPDIR=/tmp', 'XDG_DATA_HOME=/root/.local/share',
                     'XDG_CACHE_HOME=/root/.cache', 'DEBIAN_FRONTEND=noninteractive',
                     f'NODE_OPTIONS=--import={GUEST_SHARE}/dsh-filesystem-compat.mjs',
                     'GIT_CONFIG_COUNT=1', 'GIT_CONFIG_KEY_0=core.createObject', 'GIT_CONFIG_VALUE_0=rename',
                     *command]
        if self.qemu:
            arguments.insert(0, '/.host-qemu')
        os.chroot(root)
        os.chdir('/root')
        os.execve(arguments[0], arguments, {})

    def put(self, relative, content, mode=0o644):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
        target.chmod(mode)

    def deploy_assets(self):
        share = self.root / GUEST_SHARE.lstrip('/')
        share.mkdir(parents=True, exist_ok=True)
        for name in ['love-check', 'dsh-love2droid']:
            shutil.copytree(ASSETS / name, share / name, dirs_exist_ok=True)
        for name in ['dsh-filesystem-compat.mjs', 'offline-verify.py']:
            shutil.copyfile(ASSETS / name, share / name)
        self.put('usr/local/bin/love-check',
                 f'#!/bin/sh\nexec /usr/bin/python3 {GUEST_SHARE}/love-check/love-check.py "$@"\n', 0o755)

    def build(self, resume):
        self.work.mkdir(parents=True, exist_ok=True)
        (self.work / 'tmp').mkdir(exist_ok=True)
        stamp = self.work / 'inputs.sha256'
        if self.root.exists():
            if not resume or not stamp.is_file() or stamp.read_text() != self.input_hash:
                raise RuntimeError('Build workspace already exists; use --resume with the same lock file or a new --output directory')
        else:
            self.unpack(self.download('ubuntu'), self.root)
            stamp.write_text(self.input_hash)
        for directory in ['tmp', 'var/tmp', 'dev/shm', 'root/.cache', 'root/.local/bin']:
            (self.root / directory).mkdir(parents=True, exist_ok=True)
        self.put('etc/resolv.conf', Path('/etc/resolv.conf').read_text())
        self.put('etc/hosts', '127.0.0.1 localhost\n::1 localhost\n')
        self.put('etc/apt/apt.conf.d/99proot-nosandbox', 'APT::Sandbox::User "root";\n')
        self.put('etc/dpkg/dpkg.cfg.d/force-unsafe-io', 'force-unsafe-io\n')
        self.deploy_assets()
        self.guest('/bin/sh', '-ec', 'apt-get update\napt-get install -y --no-install-recommends ca-certificates curl git ncurses-bin libstdc++6')
        self.unpack(self.download('luals'), self.root / 'opt/lua-language-server')
        shutil.copyfile(self.download('omp'), self.root / 'root/.local/bin/omp')
        (self.root / 'root/.local/bin/omp').chmod(0o755)
        nvm = self.root / 'root/.nvm'
        self.unpack(self.download('nvm'), nvm, strip=True)
        node_version = self.lock['node']['version']
        node_directory = nvm / f'versions/node/v{node_version}'
        self.unpack(self.download('node'), node_directory, strip=True)
        self.put('root/.nvm/alias/default', f'v{node_version}\n')
        for name in ['node', 'npm', 'npx']:
            link = self.root / f'root/.local/bin/{name}'
            link.unlink(missing_ok=True)
            link.symlink_to(f'../../.nvm/versions/node/v{node_version}/bin/{name}')
        prompt = self.root / 'root/.local/share/bash-prompt/prompt.sh'
        prompt.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(self.download('bash-prompt'), prompt)
        self.put('root/.bashrc', 'export PATH="/root/.local/bin:$PATH"\nexport NVM_DIR="/root/.nvm"\n'
                 '[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"\n'
                 '. /root/.local/share/bash-prompt/prompt.sh\nPROMPT_DIRTRIM=1\nPS1="$(prompt_get_ps1)"\n')
        npm = self.lock['npm']
        self.guest('npm', 'install', '-g', '--no-audit', '--no-fund',
                   f'pnpm@{npm["pnpm"]}', f'@deepseek-ai/dsh@{npm["@deepseek-ai/dsh"]}')
        for name in ['pnpm', 'pnpx', 'dsh']:
            link = self.root / f'root/.local/bin/{name}'
            link.unlink(missing_ok=True)
            link.symlink_to(f'../../.nvm/versions/node/v{node_version}/bin/{name}')
        self.put('root/.dsh/profiles/web/.npmrc', 'ignore-workspace-root-check=true\n')
        for name in ['dsh-plugin', 'dsh-web-mobile']:
            self.guest('dsh', 'plugin', '--profile', 'web', 'add', f'{name}@{npm[name]}')
        self.guest('/bin/sh', f'{GUEST_SHARE}/love-check/install.sh')
        self.verify()
        self.pack()

    def verify(self, restored=False):
        if not restored:
            self.deploy_assets()
        command = ['python3', f'{GUEST_SHARE}/offline-verify.py', '--timeout', '300']
        for component in COMPONENTS_FOR_VERIFY:
            command += ['--component', component]
        self.guest(*command)
        if restored:
            print('Restored release image passed all ARM64 component checks.', flush=True)
            return
        self.put('usr/local/share/love2droid/offline-inputs.json', LOCK.read_text())
        packages = self.guest('dpkg-query', '-W', '-f=${binary:Package}\t${Version}\t${source:Package}\t${source:Version}\n', capture=True).stdout
        self.put('usr/local/share/love2droid/debian-packages.tsv', packages)
        npm_inventory = self.guest('npm', 'list', '-g', '--all', '--json', capture=True).stdout
        self.put('usr/local/share/love2droid/npm-packages.json', npm_inventory)
        # Invalidate the certificate when verification inputs change.
        (self.work / 'verified.sha256').write_text(self.verification_hash())
        print('All ARM64 components verified; Android/PRoot device interaction still requires a real device.', flush=True)

    def verification_hash(self):
        value = hashlib.sha256(LOCK.read_bytes())
        value.update(Path(__file__).read_bytes())
        for path in sorted(ASSETS.rglob('*')):
            if path.is_file():
                value.update(str(path.relative_to(ASSETS)).encode())
                value.update(path.read_bytes())
        return value.hexdigest()

    def pack(self):
        stamp = self.work / 'verified.sha256'
        if not stamp.is_file() or stamp.read_text() != self.verification_hash():
            raise RuntimeError('Run verify successfully with the current assets before packing')
        notices = self.root / 'usr/local/share/love2droid/licenses'
        notices.mkdir(parents=True, exist_ok=True)
        for name in ['OMP-LICENSE.txt', 'BASH-PROMPT-LICENSE.txt']:
            shutil.copyfile(REPO / 'licenses' / name, notices / name)
        # This is our isolated build directory, never an imported personal environment.
        # Keep /usr/share/doc copyright notices, npm licenses, installed .pnpm trees,
        # nvm and all runtime dependencies. Only disposable caches/state are removed.
        for name in ['var/cache/apt/archives', 'var/lib/apt/lists', 'var/log', 'tmp', 'var/tmp',
                     'root/.cache', 'root/.npm', 'root/.local/share/pnpm/store', 'root/.nvm/.cache',
                     'root/.omp', 'root/.bash_history', 'root/.ssh', 'root/.local/state',
                     'root/.dsh/.anonymous-user-id', 'root/.dsh/storages', 'root/.dsh/workspace.json',
                     'root/.dsh/web', 'opt/lua-language-server/log', '.host-qemu']:
            target = self.root / name
            if target.is_symlink() or target.is_file():
                target.unlink()
            elif target.is_dir():
                shutil.rmtree(target)
                target.mkdir()
        # Clear runtime Web state, but retain the profile manifest, lock and dependencies.
        for name in ['root/.dsh/sessions', 'root/.dsh/logs', 'root/.dsh/cache']:
            target = self.root / name
            if target.is_dir():
                shutil.rmtree(target)
        self.put('etc/resolv.conf', 'nameserver 8.8.8.8\nnameserver 8.8.4.4\noptions use-vc timeout:2 attempts:2\n')
        self.output.mkdir(parents=True, exist_ok=True)
        archive = self.output / 'rootfs-arm64.tar.xz'
        partial = archive.with_suffix('.xz.part')
        # 32 MiB dictionary bounds phone decoder memory; -9e keeps the expensive
        # match finder on the release machine. APK stores XZ without recompression.
        with partial.open('wb') as sink:
            compressor = subprocess.Popen(['xz', '-9e', '--threads=2', '--lzma2=dict=32MiB', '--stdout'],
                                          stdin=subprocess.PIPE, stdout=sink)
            try:
                execute(['tar', '--create', '--file=-', '--format=gnu', '--sort=name', '--mtime=@0',
                         '--owner=0', '--group=0', '--numeric-owner', '--directory', self.root, '.'],
                        stdout=compressor.stdin)
            finally:
                compressor.stdin.close()
            if compressor.wait() != 0:
                raise RuntimeError('XZ compression failed')
        partial.replace(archive)
        with tarfile.open(archive) as source:
            entries = source.getmembers()
            unpacked = sum(entry.size for entry in entries if entry.isfile())
            # Refuse device-/build-specific symlinks rather than embedding host paths.
            for entry in entries:
                if entry.issym() and entry.linkname.startswith(('/home/', '/data/', '/storage/', str(self.work))):
                    raise RuntimeError(f'Non-portable rootfs link: {entry.name} -> {entry.linkname}')
        manifest = {'formatVersion': 1, 'abi': 'arm64-v8a', 'archive': archive.name,
                    'sha256': digest(archive), 'compressedBytes': archive.stat().st_size,
                    'unpackedBytes': unpacked, 'entryCount': len(entries), 'components': COMPONENTS,
                    'versions': self.lock, 'decoderMemoryLimitKiB': 65536}
        (self.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        print(json.dumps({key: manifest[key] for key in ['sha256', 'compressedBytes', 'unpackedBytes', 'entryCount']}, indent=2), flush=True)
        print(f'Offline assets: {self.directory / "assets"}', flush=True)


COMPONENTS_FOR_VERIFY = ['base', 'luals', 'omp', 'dsh', 'love-check']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build', 'verify', 'pack', 'verify-restored', '_guest'])
    parser.add_argument('--output', type=Path, default=REPO / 'build/offline-rootfs')
    parser.add_argument('--qemu', help='Override the direct-exec BuildKit QEMU binary (not standard qemu-user)')
    parser.add_argument('--guest-root', type=Path, help=argparse.SUPPRESS)
    parser.add_argument('--command', nargs=argparse.REMAINDER, help=argparse.SUPPRESS)
    parser.add_argument('--resume', action='store_true', help='Resume only this script\'s workspace with unchanged version pins')
    args = parser.parse_args()
    image = Image(args)
    if args.action == '_guest':
        image.enter_guest(args.guest_root, args.command)
    elif args.action == 'build':
        image.build(args.resume)
    elif args.action == 'verify':
        image.verify()
    elif args.action == 'verify-restored':
        image.root = image.directory / 'restored/ubuntu'
        manifest = json.loads((image.output / 'manifest.json').read_text())
        if (image.root / '.love2droid-offline-image').read_text() != manifest['sha256']:
            raise RuntimeError('Restored image identity does not match the release archive')
        image.verify(restored=True)
    else:
        image.pack()


if __name__ == '__main__':
    main()
