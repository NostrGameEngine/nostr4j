// Temporary Cloudflare Drop-style preview; never print Wrangler's claim credentials.
// https://www.cloudflare.com/drop/llms.txt
import { execFile } from 'node:child_process';
import { appendFile, mkdtemp, readdir, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';

const run = promisify(execFile);

export async function checkAssets(directory) {
    if (!(await stat(path.join(directory, 'index.html'))).isFile()) {
        throw new Error('Preview must contain index.html');
    }
    let count = 0;
    async function walk(dir) {
        for (const entry of await readdir(dir, { withFileTypes: true })) {
            const file = path.join(dir, entry.name);
            if (entry.isSymbolicLink()) throw new Error('Preview must not contain symlinks');
            if (entry.isDirectory()) await walk(file);
            else if (entry.isFile()) {
                if (++count > 1000) throw new Error('Cloudflare temporary previews allow at most 1,000 assets');
                if ((await stat(file)).size > 5 * 1024 * 1024) {
                    throw new Error(`Cloudflare temporary asset exceeds 5 MiB: ${path.relative(directory, file)}`);
                }
            }
        }
    }
    await walk(directory);
}

export function deploymentUrl(output, name) {
    const urls = output.match(/https:\/\/[a-z0-9.-]+\.workers\.dev\b/g) || [];
    const matches = [...new Set(urls)].filter(url => new URL(url).hostname.startsWith(`${name}.`));
    if (matches.length !== 1) throw new Error('Wrangler did not return one unambiguous preview URL');
    return matches[0];
}

export async function verifyPreview(url, fetcher = fetch, wait = delay) {
    for (let attempt = 0; attempt < 6; attempt++) {
        try {
            const response = await fetcher(url, { signal: AbortSignal.timeout(10000) });
            if (response.ok && (await response.text()).includes('Nostr4j')) return;
        } catch { /* A new workers.dev route may not be available immediately. */ }
        if (attempt < 5) await wait(5000);
    }
    throw new Error('Temporary preview did not serve the Nostr4j homepage after retries');
}

async function main() {
    const directory = path.resolve(process.argv[2] || '_site');
    if (!process.env.GITHUB_STEP_SUMMARY || !process.env.GITHUB_OUTPUT) {
        throw new Error('Run this script from the site preview GitHub Action');
    }
    for (const key of ['CLOUDFLARE_API_TOKEN', 'CLOUDFLARE_API_KEY', 'CF_API_TOKEN', 'CF_API_KEY']) {
        if (process.env[key]) throw new Error('Temporary previews require an unauthenticated Cloudflare environment');
    }
    await checkAssets(directory);
    const suffix = `${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT}`;
    if (!/^\d+-\d+$/.test(suffix)) throw new Error('Missing GitHub run identity');
    const name = `nostr4j-preview-${suffix}`;
    const scratch = await mkdtemp(path.join(tmpdir(), 'nostr4j-preview-'));
    try {
        let output;
        try {
            // Isolate config and logs; neither temporary tokens nor the claim URL
            // may end up in public logs, summaries, artifacts or a shared cache.
            const result = await run('npm', [
                'exec', '--yes', '--package=wrangler@4.102.0', '--', 'wrangler',
                'deploy', directory, '--name', name, '--temporary',
                '--compatibility-date', new Date().toISOString().slice(0, 10),
            ], {
                cwd: scratch,
                env: {
                    ...process.env,
                    XDG_CONFIG_HOME: path.join(scratch, 'config'),
                    WRANGLER_LOG_PATH: path.join(scratch, 'logs'),
                    WRANGLER_SEND_METRICS: 'false',
                    CI: 'true',
                    NO_COLOR: '1',
                },
                timeout: 240000,
                maxBuffer: 8 * 1024 * 1024,
            });
            output = result.stdout + '\n' + result.stderr;
        } catch {
            // execFile errors include captured stdout/stderr, potentially containing credentials.
            throw new Error('Cloudflare temporary deploy failed. Output withheld because it may contain claim credentials. Check service availability, provisioning limits and the pinned Wrangler version.');
        }
        const url = deploymentUrl(output, name);
        await verifyPreview(url);
        await appendFile(process.env.GITHUB_OUTPUT, `url=${url}\n`);
        await appendFile(process.env.GITHUB_STEP_SUMMARY,
            `## Temporary site preview\n\n[Open the preview](${url})\n\n` +
            'Cloudflare deletes this unclaimed preview after about 60 minutes. ' +
            'The claim URL is deliberately not published. Download the `site-preview` artifact for a longer-lived copy.\n');
        console.log(`Preview verified: ${url}`);
    } finally {
        await rm(scratch, { recursive: true, force: true });
    }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main().catch(async error => {
        console.error(error.message);
        if (process.env.GITHUB_STEP_SUMMARY) {
            await appendFile(process.env.GITHUB_STEP_SUMMARY,
                '\n## Temporary site preview\n\nDeployment failed; no verified preview URL is available. The `site-preview` artifact is still available from this run.\n');
        }
        process.exitCode = 1;
    });
}
