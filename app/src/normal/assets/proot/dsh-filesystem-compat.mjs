import { realpathSync } from 'node:fs';
import { createRequire, registerHooks } from 'node:module';
import { getSystemErrorName } from 'node:util';

// DSH uses link() only to publish a synced staging file without replacing a
// concurrent creator. Android shared storage supports neither hard nor soft
// links. Keep that contract with renameat2(RENAME_NOREPLACE), not plain rename
// or a check-then-copy fallback. Koffi is already a dependency of dsh-fs-local.
let native;

export function createPublisher(moduleUrl) {
    return async (source, destination) => {
        if (source.includes('\0') || destination.includes('\0')) {
            throw new TypeError('File paths must not contain NUL bytes');
        }
        if (!native) {
            const require = createRequire(moduleUrl);
            let koffiPath;
            try {
                koffiPath = require.resolve('koffi');
            } catch (error) {
                if (error.code !== 'MODULE_NOT_FOUND') throw error;
                // attachment-local does not declare Koffi. Under strict pnpm
                // layouts, resolve via the CLI's declared fs-local dependency,
                // not an accidentally hoisted sibling node_modules directory.
                const cliRequire = createRequire(realpathSync(process.argv[1]));
                koffiPath = createRequire(cliRequire.resolve('@deepseek-ai/dsh-fs-local')).resolve('koffi');
            }
            const koffi = require(koffiPath);
            const libc = koffi.load('libc.so.6');
            native = {
                koffi,
                libc,
                rename: libc.func('int renameat2(int, const char *, int, const char *, unsigned int)'),
            };
        }
        // One synchronous metadata syscall keeps errno on this thread. Content
        // writing, syncing and cleanup remain in DSH's asynchronous I/O path.
        if (native.rename(-100, source, -100, destination, 1) !== 0) {
            const errno = native.koffi.errno();
            const code = getSystemErrorName(-errno);
            throw Object.assign(new Error(`${code}: renameat2 '${source}' -> '${destination}'`), {
                code,
                errno: -errno,
                syscall: 'renameat2',
                path: source,
                dest: destination,
            });
        }
    };
}

// Scope the adaptation to DSH's publication primitive. Never redefine Node's
// fs.link: a real hard link must retain its source and share its inode.
if (process.platform === 'linux') {
    const publications = new Map([
        ['dsh-fs-local', [
            ['const linkFile = internals.linkFile ?? link;', 'const linkFile = internals.linkFile ?? love2droidPublish;'],
        ]],
        ['dsh-session-persistence-jsonl', [
            ['await link(tmp, finalPath);', 'await love2droidPublish(tmp, finalPath);'],
        ]],
        ['dsh-attachment-local', [
            ['await link(temporary, target);', 'await love2droidPublish(temporary, target);'],
            // A successful rename already consumed the source; EEXIST still
            // leaves it for cleanup after the upstream integrity check.
            ['await unlink(temporary);', 'await rm(temporary, { force: true });'],
        ]],
    ]);
    registerHooks({
        load(url, context, nextLoad) {
            const loaded = nextLoad(url, context);
            if (!url.startsWith('file:')) return loaded;
            const packageName = new URL(url).pathname.match(/\/@deepseek-ai\/([^/]+)\/lib\/index\.js$/)?.[1];
            const replacements = publications.get(packageName);
            if (!replacements) return loaded;
            let source = typeof loaded.source === 'string'
                ? loaded.source : Buffer.from(loaded.source).toString('utf8');
            // Do not silently run an unadapted or partially adapted upstream
            // version after an npm update changes this implementation boundary.
            for (const [original, replacement] of replacements) {
                if (source.split(original).length !== 2) {
                    throw new Error(`Love2Droid: unsupported ${packageName} publication code; the Android filesystem adapter must be updated`);
                }
                source = source.replace(original, replacement);
            }
            return {
                ...loaded,
                source: `import { createPublisher as love2droidCreatePublisher } from ${JSON.stringify(import.meta.url)};\n` +
                    'const love2droidPublish = love2droidCreatePublisher(import.meta.url);\n' +
                    source,
            };
        },
    });
}
