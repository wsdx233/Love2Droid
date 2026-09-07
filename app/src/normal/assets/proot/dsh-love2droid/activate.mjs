import { lstatSync, mkdirSync, readlinkSync, realpathSync, renameSync, rmSync, symlinkSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Use the running CLI's profile implementation and dependency graph, not a
// second copy of DSH or a hand-maintained web profile template.
const cli = realpathSync(process.argv[2]);
const require = createRequire(cli);
const { loadProfile, readProfileManifest, writeProfileManifest, resolveProfileDir } = await import(require.resolve('@deepseek-ai/dsh-app-boot'));
const directory = fileURLToPath(new URL('.', import.meta.url)).replace(/\/$/, '');
const name = 'dsh-love2droid';
const modules = join(resolveProfileDir('web'), 'node_modules');
mkdirSync(modules, { recursive: true });
const link = join(modules, name);
let existing;
try { existing = lstatSync(link); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
if (existing && !existing.isSymbolicLink()) {
    throw new Error(`${link} is not an app-managed symlink; remove the conflicting plugin installation first`);
}
if (!existing || readlinkSync(link) !== directory) {
    const temporary = `${link}.${process.pid}.tmp`;
    try {
        symlinkSync(directory, temporary, 'dir');
        renameSync(temporary, link);
    } finally {
        rmSync(temporary, { force: true });
    }
}
const profile = loadProfile('dsh', 'web', cli);
const manifest = readProfileManifest('dsh', profile.dir);
const bundles = manifest.dsh.profile.bundles;
if (!bundles.includes(name)) {
    bundles.push(name);
    writeProfileManifest(profile.dir, manifest);
}
