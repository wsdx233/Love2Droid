import assert from 'node:assert/strict';
import { test } from 'node:test';
import fs from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { runInNewContext } from 'node:vm';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolveEditorPath } from '../app/src/normal/assets/proot/dsh-love2droid/index.mjs';

const pluginRoot = fileURLToPath(new URL('../app/src/normal/assets/proot/dsh-love2droid/', import.meta.url));
const clientSource = await fs.readFile(join(pluginRoot, 'client.js'), 'utf8');
const fixture = resolve(process.env.DSH_FS_FIXTURE ?? '.proot-debug/dsh-fs-verification');
const cli = join(fixture, 'node_modules/@deepseek-ai/dsh/lib/bin.js');
const execute = promisify(execFile);

function client(bridge) {
    let plugin;
    const calls = [];
    const disposers = [];
    const window = { Love2DroidFiles: bridge, __ModuleLoader__: { load: entry => { plugin = entry.factory(); } } };
    window.top = window;
    runInNewContext(clientSource, { window });
    const original = async (channel, endpoint, payload, signal) => {
        calls.push({ channel, endpoint, payload });
        if (channel === '/love2droid') {
            try { return { ok: true, value: await resolveEditorPath(payload.path, signal) }; }
            catch (error) { return { ok: false, error: { code: 'gateway/bad-request', message: error.message, details: {} } }; }
        }
        return { ok: true, value: endpoint === 'session/canOpenWorkspacePath' ? false : { desktop: true } };
    };
    let messages;
    const ctx = {
        connection: { rpc: { call: original } },
        locale: {
            register: (_, resources) => { messages = resources.en; return () => {}; },
            bind: () => key => messages[key],
        },
        effect: setup => disposers.push(setup()),
    };
    plugin.apply(ctx);
    return {
        calls,
        rpc: ctx.connection.rpc,
        dispose: () => disposers.reverse().forEach(dispose => dispose()),
        open: (path, signal) => ctx.connection.rpc.call('/api', 'session/openWorkspacePath', { args: { request: { path } } }, signal),
    };
}

async function directory(t) {
    const root = await fs.mkdtemp(join(tmpdir(), 'dsh-web-open-'));
    t.after(() => fs.rm(root, { recursive: true, force: true }));
    return root;
}

function nativeBridge() {
    const requests = [];
    const waiters = [];
    const bridge = {
        onmessage: null,
        postMessage: data => {
            const request = JSON.parse(data);
            requests.push(request);
            waiters.shift()?.(request);
        },
        next: () => new Promise(resolve => waiters.push(resolve)),
        reply: (request, error) => bridge.onmessage?.({ data: JSON.stringify({ id: request.id, error }) }),
    };
    return { bridge, requests };
}

test('desktop browsers preserve native opening and capability checks', async t => {
    const c = client();
    t.after(c.dispose);
    assert.equal((await c.open('/file.lua')).value.desktop, true);
    assert.equal((await c.rpc.call('/api', 'session/canOpenWorkspacePath', {})).value, false);
    assert.equal(c.calls.length, 2);
    assert.equal(c.calls[0].endpoint, 'session/openWorkspacePath');
});

test('WebView file links resolve aliases and await native acceptance without invoking xdg-open', async t => {
    const root = await directory(t);
    const file = join(root, "音频 file's.lua");
    const alias = join(root, 'alias.lua');
    await fs.writeFile(file, 'return {}');
    await fs.symlink(file, alias);
    const { bridge, requests } = nativeBridge();
    const c = client(bridge);
    t.after(c.dispose);
    assert.equal((await c.rpc.call('/api', 'session/canOpenWorkspacePath', {})).value, true);
    const arrived = bridge.next();
    const opening = c.open(pathToFileURL(alias).href);
    const request = await arrived;
    assert.equal(request.path, await fs.realpath(file));
    bridge.reply(request);
    assert.equal((await opening).value.opened, true);
    assert.equal(requests.length, 1);
    assert.deepEqual(c.calls.map(call => call.channel), ['/love2droid']);
});

test('directories, URLs, and unrelated RPC methods retain their original behavior', async t => {
    const root = await directory(t);
    const { bridge, requests } = nativeBridge();
    const c = client(bridge);
    t.after(c.dispose);
    assert.equal((await c.open(root)).value.desktop, true);
    assert.equal((await c.open('https://example.com')).value.desktop, true);
    assert.equal((await c.rpc.call('/api', 'settings/openConfig', {})).value.desktop, true);
    assert.equal(requests.length, 0);
});

test('missing files, native rejection, cancellation and concurrent replies remain distinct', async t => {
    const root = await directory(t);
    const file = join(root, 'a.lua');
    await fs.writeFile(file, 'a');
    const { bridge, requests } = nativeBridge();
    const c = client(bridge);
    t.after(c.dispose);
    assert.equal((await c.open(join(root, 'missing'))).ok, false);
    assert.equal(requests.length, 0);
    const arrivalA = bridge.next();
    const openingA = c.open(file);
    const requestA = await arrivalA;
    const arrivalB = bridge.next();
    const openingB = c.open(file);
    const requestB = await arrivalB;
    bridge.reply(requestB, '二进制文件不可编辑');
    bridge.reply(requestA);
    assert.equal((await openingB).error.message, '二进制文件不可编辑');
    assert.equal((await openingA).value.opened, true);
    const controller = new AbortController();
    const arrivalC = bridge.next();
    const openingC = c.open(file, controller.signal);
    const requestC = await arrivalC;
    controller.abort();
    assert.equal((await openingC).error.code, 'gateway/cancelled');
    bridge.reply(requestC);
});

test('plugin disposal settles pending opens and restores the desktop transport', async t => {
    const root = await directory(t);
    const file = join(root, 'a.lua');
    await fs.writeFile(file, 'a');
    const { bridge } = nativeBridge();
    const c = client(bridge);
    const arrival = bridge.next();
    const opening = c.open(file);
    await arrival;
    c.dispose();
    assert.equal((await opening).error.code, 'gateway/cancelled');
    assert.equal((await c.open(file)).value.desktop, true);
});

test('real DSH activation is offline, idempotent, preserves user settings and repairs removed links', async t => {
    const home = await directory(t);
    const env = { ...process.env, DSH_HOME: home, NODE_OPTIONS: '' };
    const activate = () => execute(process.execPath, [join(pluginRoot, 'activate.mjs'), cli], { env });
    await activate();
    const profile = join(home, 'profiles/web');
    const manifestPath = join(profile, 'package.json');
    const manifest = JSON.parse(await fs.readFile(manifestPath, 'utf8'));
    manifest.custom = { keep: 'user choice' };
    await fs.writeFile(manifestPath, JSON.stringify(manifest));
    const before = await fs.readFile(manifestPath, 'utf8');
    await activate();
    assert.equal(await fs.readFile(manifestPath, 'utf8'), before);
    assert.equal(manifest.dsh.profile.bundles.filter(name => name === 'dsh-love2droid').length, 1);
    const link = join(profile, 'node_modules/dsh-love2droid');
    await fs.unlink(link);
    await activate();
    assert.equal(await fs.realpath(link), pluginRoot.replace(/\/$/, ''));
    const config = await execute(process.execPath, [cli, '--profile', 'web', '--dump-default-config'], { env, maxBuffer: 1024 * 1024 });
    assert.match(config.stdout, /name: dsh-love2droid/);
    await fs.unlink(link);
    await fs.mkdir(link);
    await fs.writeFile(join(link, 'user.txt'), 'preserve');
    await assert.rejects(activate(), /not an app-managed symlink/);
    assert.equal(await fs.readFile(join(link, 'user.txt'), 'utf8'), 'preserve');
});
