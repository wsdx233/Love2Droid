import { realpath, stat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const inject = ['connection'];

// Resolve in the guest, not Android: absolute symlinks and ~/projects have
// guest semantics. The app independently maps and confines the canonical path.
export async function resolveEditorPath(path, signal) {
    signal?.throwIfAborted();
    if (typeof path !== 'string' || !path || path.length > 32768 || path.includes('\0')) {
        throw new TypeError('Invalid file path');
    }
    const canonical = await realpath(resolve(path.startsWith('file:') ? fileURLToPath(path) : path));
    const info = await stat(canonical);
    signal?.throwIfAborted();
    if (!info.isFile() && !info.isDirectory()) throw new Error('Not a regular file');
    return { path: canonical, kind: info.isDirectory() ? 'directory' : 'file' };
}

export function apply(ctx) {
    ctx.effect(() => ctx.connection.rpc.handle('/love2droid', async (endpoint, payload, signal) => {
        if (endpoint !== 'resolve-file') {
            return { ok: false, error: { code: 'gateway/not-found', message: 'Unknown Love2Droid operation', details: {} } };
        }
        try {
            return { ok: true, value: await resolveEditorPath(payload?.path, signal) };
        } catch (error) {
            return {
                ok: false,
                error: {
                    code: signal.aborted ? 'gateway/cancelled' : 'gateway/bad-request',
                    message: error.message,
                    details: {},
                },
            };
        }
    }), 'love2droid: resolve editor file');
}
