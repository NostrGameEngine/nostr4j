import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile, truncate, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { checkAssets, deploymentUrl, verifyPreview } from './deploy-preview.mjs';

test('only extracts the expected public URL, never the claim credential', () => {
    const name = 'nostr4j-preview-123-1';
    const url = `https://${name}.temporary-account.workers.dev`;
    const output = `Claim URL: https://dash.cloudflare.com/claim-preview?claimToken=secret\n${url}\n${url}`;
    assert.equal(deploymentUrl(output, name), url);
    assert.throws(() => deploymentUrl('https://other.account.workers.dev', name));
    assert.throws(() => deploymentUrl(`${url}\nhttps://${name}.second.workers.dev`, name));
});

test('checks asset limits and rejects symlinks before uploading', async () => {
    const dir = await mkdtemp(path.join(tmpdir(), 'preview-assets-test-'));
    try {
        await assert.rejects(checkAssets(dir));
        await writeFile(path.join(dir, 'index.html'), 'Nostr4j');
        await checkAssets(dir);
        await writeFile(path.join(dir, 'large.js'), '');
        await truncate(path.join(dir, 'large.js'), 5 * 1024 * 1024 + 1);
        await assert.rejects(checkAssets(dir), /5 MiB/);
        await rm(path.join(dir, 'large.js'));
        await symlink('index.html', path.join(dir, 'linked.html'));
        await assert.rejects(checkAssets(dir), /symlinks/);
        await rm(path.join(dir, 'linked.html'));
        for (let i = 0; i < 1000; i++) await writeFile(path.join(dir, `${i}.txt`), '');
        await assert.rejects(checkAssets(dir), /1,000/);
    } finally {
        await rm(dir, { recursive: true, force: true });
    }
});

test('retries a new route and verifies actual site content', async () => {
    let attempts = 0;
    let waits = 0;
    await verifyPreview('https://example.account.workers.dev', async () => {
        attempts++;
        return { ok: attempts > 1, text: async () => 'Nostr4j documentation' };
    }, async () => { waits++; });
    assert.equal(attempts, 2);
    assert.equal(waits, 1);
    await assert.rejects(verifyPreview('https://example.account.workers.dev',
        async () => ({ ok: true, text: async () => 'Unrelated page' }), async () => {}), /after retries/);
});
