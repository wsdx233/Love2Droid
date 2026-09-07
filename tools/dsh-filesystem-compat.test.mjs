import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import * as fs from 'node:fs/promises';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';

// Install the real published provider in ignored storage; see doc/verification.md.
const fixtureRoot = resolve(process.env.DSH_FS_FIXTURE ?? '.proot-debug/dsh-fs-verification');
const requireFixture = createRequire(join(fixtureRoot, 'package.json'));
const providerUrl = pathToFileURL(requireFixture.resolve('@deepseek-ai/dsh-fs-local')).href;
const cordisUrl = pathToFileURL(requireFixture.resolve('@deepseek-ai/cordis')).href;
const adapterUrl = new URL('../app/src/normal/assets/proot/dsh-filesystem-compat.mjs', import.meta.url);
const adapterPath = fileURLToPath(adapterUrl);
const { createPublisher } = await import(adapterUrl);
const { LocalFileSystem } = await import(providerUrl);
const { Context } = await import(cordisUrl);

async function workspace(t) {
    const directory = await fs.mkdtemp(join(tmpdir(), 'dsh-fs-compat-'));
    t.after(() => fs.rm(directory, { recursive: true, force: true }));
    const backend = new LocalFileSystem(new Context(), { cwd: directory, diffBasisMaxBytes: 1048576 });
    return { directory, backend, target: await backend.resolve('audio.lua') };
}

function child(script, args = [], env = {}) {
    const process = spawn(globalThis.process.execPath, [...args, '--input-type=module', '-e', script], {
        env: { ...globalThis.process.env, ...env },
        stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
    });
    let stdout = '';
    let stderr = '';
    process.stdout.on('data', data => { stdout += data; });
    process.stderr.on('data', data => { stderr += data; });
    const completed = once(process, 'close').then(([code]) => {
        assert.equal(code, 0, stderr);
        return stdout;
    });
    return { process, completed };
}

test('real DSH fails without the adapter and publishes with hard links denied after adaptation', async t => {
    const { directory } = await workspace(t);
    const script = `
        import fs from 'node:fs/promises';
        import { syncBuiltinESMExports } from 'node:module';
        fs.link = async () => { throw Object.assign(new Error('link denied by mount'), {code:'EPERM'}); };
        syncBuiltinESMExports();
        const { LocalFileSystem } = await import(${JSON.stringify(providerUrl)});
        const { Context } = await import(${JSON.stringify(cordisUrl)});
        const backend = new LocalFileSystem(new Context(), {cwd:${JSON.stringify(directory)}, diffBasisMaxBytes:1048576});
        const target = await backend.resolve('src/audio.lua');
        try {
            await backend.writeText(target, 'complete', {kind:'createIfAbsent'});
            console.log('created');
        } catch (error) { console.log(error.code, error.cause?.code); }
    `;
    assert.equal((await child(script, [], { NODE_OPTIONS: '' }).completed).trim(), 'FS_IO_ERROR EPERM');
    assert.deepEqual(await fs.readdir(join(directory, 'src')), []);
    assert.equal((await child(script, [], { NODE_OPTIONS: `--import=${adapterPath}` }).completed).trim(), 'created');
    assert.equal(await fs.readFile(join(directory, 'src/audio.lua'), 'utf8'), 'complete');
    assert.deepEqual(await fs.readdir(join(directory, 'src')), ['audio.lua']);
});

test('new files, empty files, nested paths and non-ASCII paths publish complete bytes without staging leftovers', async t => {
    const { backend, directory } = await workspace(t);
    for (const [path, content] of [['audio.lua', '音频\r\n'.repeat(65536)], ['empty.lua', ''], ["子目录/space ' file.lua", 'return {}\n']]) {
        const target = await backend.resolve(path);
        const result = await backend.writeText(target, content, { kind: 'createIfAbsent' });
        assert.equal(result.operation, 'create');
        assert.equal(await fs.readFile(target.targetKey, 'utf8'), content);
        assert.equal((await fs.lstat(target.targetKey)).isSymbolicLink(), false);
    }
    assert.deepEqual((await fs.readdir(directory)).sort(), ['audio.lua', 'empty.lua', '子目录']);
    assert.deepEqual(await fs.readdir(join(directory, '子目录')), ["space ' file.lua"]);
});

test('existing files and a creator racing final publication are never overwritten', async t => {
    const { backend, target, directory } = await workspace(t);
    backend.internals.inspectTemp = () => fs.writeFile(target.targetKey, 'concurrent winner', { flag: 'wx' });
    await assert.rejects(backend.writeText(target, 'loser', { kind: 'createIfAbsent' }), { code: 'FS_NOT_OBSERVED' });
    assert.equal(await fs.readFile(target.targetKey, 'utf8'), 'concurrent winner');
    assert.deepEqual(await fs.readdir(directory), ['audio.lua']);
    backend.internals = {};
    await assert.rejects(backend.writeText(target, 'another loser', { kind: 'createIfAbsent' }), { code: 'FS_NOT_OBSERVED' });
    assert.equal(await fs.readFile(target.targetKey, 'utf8'), 'concurrent winner');
});

test('a directory or dangling symlink racing publication is preserved', async t => {
    for (const kind of ['directory', 'symlink']) {
        const { backend, target, directory } = await workspace(t);
        backend.internals.inspectTemp = () => kind === 'directory'
            ? fs.mkdir(target.targetKey)
            : fs.symlink(join(directory, 'absent'), target.targetKey);
        await assert.rejects(backend.writeText(target, 'loser', { kind: 'createIfAbsent' }), { code: 'FS_NOT_REGULAR_FILE' });
        assert.equal((await fs.lstat(target.targetKey))[kind === 'directory' ? 'isDirectory' : 'isSymbolicLink'](), true);
        assert.deepEqual(await fs.readdir(directory), ['audio.lua']);
    }
});

test('replacement, edit, CRLF preservation and stale-version guards remain intact', async t => {
    const { backend, target, directory } = await workspace(t);
    const initial = await backend.writeText(target, 'volume = 1\r\n', { kind: 'createIfAbsent' });
    const edit = await backend.editText(target, { oldString: '1', newString: '2', replaceAll: false }, { version: initial.version });
    assert.equal(await fs.readFile(target.targetKey, 'utf8'), 'volume = 2\r\n');
    await assert.rejects(backend.writeText(target, 'stale', { kind: 'replaceIfVersion', version: initial.version }), { code: 'FS_STALE_VERSION' });
    await assert.rejects(backend.editText(target, { oldString: '2', newString: '3', replaceAll: false }, { version: initial.version }), { code: 'FS_STALE_VERSION' });
    const update = await backend.writeText(target, 'replacement\n', { kind: 'replaceIfVersion', version: edit.version });
    assert.equal(update.operation, 'update');
    assert.equal(await fs.readFile(target.targetKey, 'utf8'), 'replacement\n');
    assert.deepEqual(await fs.readdir(directory), ['audio.lua']);
});

test('cancellation after staging leaves no target or temporary files', async t => {
    const { backend, target, directory } = await workspace(t);
    const controller = new AbortController();
    backend.internals.inspectTemp = () => controller.abort();
    await assert.rejects(backend.writeText(target, 'cancelled', { kind: 'createIfAbsent' }, controller.signal), { code: 'FS_ABORTED' });
    assert.deepEqual(await fs.readdir(directory), []);
});

test('ordinary Node hard links still preserve their source and share an inode', async t => {
    const { directory } = await workspace(t);
    const source = join(directory, 'source');
    const destination = join(directory, 'destination');
    await fs.writeFile(source, 'original');
    await fs.link(source, destination);
    assert.equal((await fs.stat(source)).ino, (await fs.stat(destination)).ino);
    await fs.writeFile(source, 'updated');
    assert.equal(await fs.readFile(destination, 'utf8'), 'updated');
});

test('native publication preserves source and target on real syscall failures', async t => {
    const { directory } = await workspace(t);
    const publish = createPublisher(providerUrl);
    const source = join(directory, 'source');
    await fs.writeFile(source, 'keep');
    await assert.rejects(publish(source, join(directory, 'missing/target')), { code: 'ENOENT', syscall: 'renameat2' });
    assert.equal(await fs.readFile(source, 'utf8'), 'keep');
    await assert.rejects(publish(source, join(directory, 'truncated\0suffix')), TypeError);
    assert.equal(await fs.readFile(source, 'utf8'), 'keep');
    const otherMount = await fs.mkdtemp('/dev/shm/dsh-fs-compat-');
    t.after(() => fs.rm(otherMount, { recursive: true, force: true }));
    assert.notEqual((await fs.stat(directory)).dev, (await fs.stat(otherMount)).dev);
    await assert.rejects(publish(source, join(otherMount, 'target')), { code: 'EXDEV', syscall: 'renameat2' });
    assert.equal(await fs.readFile(source, 'utf8'), 'keep');
    assert.deepEqual(await fs.readdir(otherMount), []);
});

test('multiple DSH processes racing publication produce exactly one complete winner', async t => {
    const { directory, target } = await workspace(t);
    const children = Array.from({ length: 4 }, (_, index) => {
        const content = `winner-${index}\n`.repeat(16384);
        const script = `
            import { once } from 'node:events';
            const { LocalFileSystem } = await import(${JSON.stringify(providerUrl)});
            const { Context } = await import(${JSON.stringify(cordisUrl)});
            const backend = new LocalFileSystem(new Context(), {cwd:${JSON.stringify(directory)}, diffBasisMaxBytes:1048576});
            backend.internals.inspectTemp = async () => {
                const release = once(process, 'message');
                process.send('ready');
                await release;
            };
            const target = await backend.resolve('audio.lua');
            try { await backend.writeText(target, ${JSON.stringify(`winner-${index}\n`)}.repeat(16384), {kind:'createIfAbsent'}); console.log('created'); }
            catch (error) { console.log(error.code); }
            process.disconnect();
        `;
        const running = child(script, [], { NODE_OPTIONS: `--import=${adapterPath}` });
        t.after(() => { if (running.process.exitCode === null) running.process.kill(); });
        return { ...running, content, ready: once(running.process, 'message') };
    });
    await Promise.all(children.map(running => Promise.race([
        running.ready,
        running.completed.then(() => { throw new Error('DSH child exited before staging'); }),
    ])));
    for (const running of children) running.process.send('publish');
    const results = await Promise.all(children.map(async running => (await running.completed).trim()));
    assert.equal(results.filter(result => result === 'created').length, 1);
    assert.equal(results.filter(result => result === 'FS_NOT_OBSERVED').length, 3);
    assert.equal(await fs.readFile(target.targetKey, 'utf8'), children[results.indexOf('created')].content);
    assert.deepEqual(await fs.readdir(directory), ['audio.lua']);
});

test('NODE_OPTIONS activates the same adapter in worker threads', async t => {
    const { directory } = await workspace(t);
    const workerCode = `
        Promise.all([import('node:fs/promises'), import('node:module')]).then(([{default:fs}, module]) => {
            fs.link = async () => { throw Object.assign(new Error('link denied by mount'), {code:'EPERM'}); };
            module.syncBuiltinESMExports();
            return import(${JSON.stringify(providerUrl)});
        }).then(async ({LocalFileSystem}) => {
            const {Context} = await import(${JSON.stringify(cordisUrl)});
            const backend = new LocalFileSystem(new Context(), {cwd:${JSON.stringify(directory)}, diffBasisMaxBytes:1048576});
            await backend.writeText(await backend.resolve('worker.lua'), 'worker', {kind:'createIfAbsent'});
        }).catch(error => {console.error(error); process.exitCode=1;});
    `;
    const script = `
        import { Worker } from 'node:worker_threads';
        import { once } from 'node:events';
        const worker = new Worker(${JSON.stringify(workerCode)}, {eval:true});
        const [code] = await once(worker, 'exit');
        if (code !== 0) process.exitCode = code;
    `;
    await child(script, [], { NODE_OPTIONS: `--import=${adapterPath}` }).completed;
    assert.equal(await fs.readFile(join(directory, 'worker.lua'), 'utf8'), 'worker');
});

test('an incompatible upstream publication implementation fails explicitly', async () => {
    const script = `
        import { registerHooks } from 'node:module';
        registerHooks({load(url, context, nextLoad) {
            const loaded = nextLoad(url, context);
            if (url === ${JSON.stringify(providerUrl)}) {
                loaded.source = String(loaded.source).replace('const linkFile = internals.linkFile ?? link;', 'const linkFile = link;');
            }
            return loaded;
        }});
        await import(${JSON.stringify(adapterUrl.href)});
        try { await import(${JSON.stringify(providerUrl)}); process.exitCode = 1; }
        catch (error) { console.log(error.message); }
    `;
    const output = await child(script, [], { NODE_OPTIONS: '' }).completed;
    assert.match(output, /unsupported dsh-fs-local publication code/);
});

test('session logs and attachments publish without links and preserve collisions and integrity checks', async t => {
    const { directory } = await workspace(t);
    const script = `
        import assert from 'node:assert/strict';
        import fs from 'node:fs/promises';
        import { createHash } from 'node:crypto';
        import { createRequire, syncBuiltinESMExports } from 'node:module';
        import { join } from 'node:path';
        fs.link = async () => { throw Object.assign(new Error('link denied by mount'), {code:'EPERM'}); };
        syncBuiltinESMExports();
        const require = createRequire(${JSON.stringify(join(fixtureRoot, 'package.json'))});
        const { Context } = await import(${JSON.stringify(cordisUrl)});
        const { SessionStore } = await import(require.resolve('@deepseek-ai/dsh-session'));
        const { JsonlSessionPersistence } = await import(require.resolve('@deepseek-ai/dsh-session-persistence-jsonl'));
        const ctx = new Context();
        new SessionStore(ctx);
        const directory = ${JSON.stringify(directory)};
        const root = join(directory, 'sessions');
        const project = join(root, 'project');
        const sessionDir = join(project, 'one');
        const target = join(sessionDir, 'session.jsonl');
        const backend = new JsonlSessionPersistence(ctx, {root, compression:'none'});
        const content = Buffer.from('{"session":"one"}\\n');
        await backend.materializePosix(project, sessionDir, target, 'one', content);
        assert.deepEqual(await fs.readFile(target), content);
        assert.deepEqual(await fs.readdir(sessionDir), ['session.jsonl']);
        await assert.rejects(backend.materializePosix(project, sessionDir, target, 'one', Buffer.from('loser')));
        assert.deepEqual(await fs.readFile(target), content);
        await fs.unlink(target);
        const writeTemp = backend.writeSyncedTempFile.bind(backend);
        backend.writeSyncedTempFile = async (...args) => {
            const temporary = await writeTemp(...args);
            await fs.writeFile(target, 'concurrent winner', {flag:'wx'});
            return temporary;
        };
        await assert.rejects(backend.materializePosix(project, sessionDir, target, 'one', content), {code:'EEXIST'});
        assert.equal(await fs.readFile(target, 'utf8'), 'concurrent winner');
        assert.deepEqual(await fs.readdir(sessionDir), ['session.jsonl']);
        const {commitPreparedImageFile, readImageFile} = await import(require.resolve('@deepseek-ai/dsh-attachment-local'));
        const data = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0GsAAAAASUVORK5CYII=', 'base64');
        const digest = createHash('sha256').update(data).digest('hex');
        const prepared = {data, ref:{attachmentId:'sha256:'+digest,mediaType:'image/png',width:1,height:1,bytes:data.length}};
        const attachments = join(directory, 'attachments/v1');
        const ref = await commitPreparedImageFile(attachments, prepared);
        assert.deepEqual(await commitPreparedImageFile(attachments, prepared), ref);
        assert.deepEqual(Buffer.from((await readImageFile(attachments, ref)).data), data);
        assert.deepEqual(await fs.readdir(join(attachments, 'tmp')), []);
        const imagePath = join(attachments, 'objects', digest.slice(0, 2), digest);
        await fs.chmod(imagePath, 384);
        await fs.writeFile(imagePath, 'corrupted');
        await assert.rejects(commitPreparedImageFile(attachments, prepared), {code:'ATTACHMENT_CORRUPT'});
        assert.equal(await fs.readFile(imagePath, 'utf8'), 'corrupted');
        assert.deepEqual(await fs.readdir(join(attachments, 'tmp')), []);
        console.log('session and attachment publication passed');
    `;
    assert.match(await child(script, [], { NODE_OPTIONS: `--import=${adapterPath}` }).completed, /publication passed/);
});

test('attachment publication resolves native dependencies through the CLI when not hoisted', async t => {
    const { directory } = await workspace(t);
    const source = join(directory, 'source');
    const destination = join(directory, 'destination');
    await fs.writeFile(source, 'complete');
    const script = `
        import {createRequire} from 'node:module';
        import {dirname, join} from 'node:path';
        const require = createRequire(${JSON.stringify(join(fixtureRoot, 'package.json'))});
        process.argv[1] = join(dirname(require.resolve('@deepseek-ai/dsh/package.json')), 'lib/bin.js');
        const {createPublisher} = await import(${JSON.stringify(adapterUrl.href)});
        const publish = createPublisher(${JSON.stringify(pathToFileURL(join(directory, 'isolated-package/index.js')).href)});
        await publish(${JSON.stringify(source)}, ${JSON.stringify(destination)});
    `;
    await child(script, [], { NODE_OPTIONS: '' }).completed;
    assert.equal(await fs.readFile(destination, 'utf8'), 'complete');
    await assert.rejects(fs.stat(source), { code: 'ENOENT' });
});
