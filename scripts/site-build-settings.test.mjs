import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolveBuild, selectBuild } from './site-build-settings.mjs';

const latestRequest = {
    EVENT_NAME: 'workflow_dispatch', REQUESTED_REF: 'latest',
    GITHUB_REPOSITORY: 'NostrGameEngine/nostr4j', DEPLOY_LIVE: 'true',
};
const publishedRelease = {
    tag_name: '0.4.0', draft: false, prerelease: false, published_at: '2026-10-19T08:56:26Z',
};

for (const tag of ['0.4.0', 'v0.4.0']) {
    test(`manual redeployment resolves latest once and pins ${tag} in release mode`, async () => {
        let calls = 0;
        const result = await resolveBuild({ ...latestRequest, GITHUB_TOKEN: 'test-token' }, async (url, options) => {
            calls++;
            assert.equal(url, 'https://api.github.com/repos/NostrGameEngine/nostr4j/releases?per_page=100&page=1');
            assert.equal(options.headers.Authorization, 'Bearer test-token');
            assert.equal(options.headers.Accept, 'application/vnd.github+json');
            assert.ok(options.signal instanceof AbortSignal);
            return { ok: true, json: async () => [{ ...publishedRelease, tag_name: tag }] };
        });
        assert.equal(calls, 1);
        assert.deepEqual(result, {
            ref: `refs/tags/${tag}`, version: '0.4.0', release_build: 'true', should_deploy: 'true',
        });
    });
}

test('latest release can also be built as a temporary preview', async () => {
    const result = await resolveBuild({ ...latestRequest, DEPLOY_LIVE: 'false' }, async () => ({
        ok: true, json: async () => [publishedRelease],
    }));
    assert.equal(result.release_build, 'true');
    assert.equal(result.should_deploy, 'false');
});

for (const status of [403, 404, 500]) {
    test(`latest release HTTP ${status} stops the build without a snapshot fallback`, async () => {
        await assert.rejects(resolveBuild(latestRequest, async () => ({ ok: false, status })),
            new RegExp(`HTTP ${status}`));
    });
}

test('latest release network failure stops the build', async () => {
    await assert.rejects(resolveBuild(latestRequest, async () => { throw new Error('Network unavailable'); }),
        /Network unavailable/);
});

for (const release of [
    { ...publishedRelease, tag_name: 'master' },
    { ...publishedRelease, tag_name: '0.4.0-SNAPSHOT' },
    { ...publishedRelease, tag_name: '0.4.0\nVERSION=injected' },
    { ...publishedRelease, tag_name: undefined },
]) {
    test(`invalid latest release metadata ${JSON.stringify(release)} is rejected`, async () => {
        await assert.rejects(resolveBuild(latestRequest, async () => ({ ok: true, json: async () => [release] })),
            /published, non-snapshot version tag/);
    });
}

test('latest means most recently published, including prereleases, rather than GitHub latest stable', async () => {
    const result = await resolveBuild(latestRequest, async () => ({ ok: true, json: async () => [
        { ...publishedRelease, tag_name: '0.3.1', published_at: '2026-06-08T11:49:42Z' },
        { ...publishedRelease, prerelease: true },
        { ...publishedRelease, tag_name: '0.5.0', draft: true, published_at: '2026-10-20T12:00:00Z' },
    ] }));
    assert.equal(result.ref, 'refs/tags/0.4.0');
});

test('release selection follows pagination before pinning the most recently published tag', async () => {
    const urls = [];
    const result = await resolveBuild(latestRequest, async url => {
        urls.push(url);
        return { ok: true, json: async () => urls.length === 1
            ? Array.from({ length: 100 }, () => ({ ...publishedRelease, published_at: '2026-01-01T00:00:00Z' }))
            : [{ ...publishedRelease, tag_name: 'v0.4.1' }] };
    });
    assert.equal(urls.length, 2);
    assert.ok(urls[1].endsWith('&page=2'));
    assert.equal(result.ref, 'refs/tags/v0.4.1');
});

for (const releases of [[], [{ ...publishedRelease, draft: true }],
    [{ ...publishedRelease, published_at: null }]]) {
    test(`no published release in ${JSON.stringify(releases)} stops the build`, async () => {
        await assert.rejects(resolveBuild(latestRequest, async () => ({ ok: true, json: async () => releases })),
            /No published release/);
    });
}

test('malformed release API response stops the build', async () => {
    await assert.rejects(resolveBuild(latestRequest, async () => ({ ok: true, json: async () => ({ message: 'Error' }) })),
        /Invalid GitHub release list/);
});

test('latest resolution validates the repository before making a request', async () => {
    await assert.rejects(resolveBuild({ ...latestRequest, GITHUB_REPOSITORY: '../other/repo' },
        async () => { assert.fail('Must not request an invalid repository'); }), /repository is required/);
    assert.throws(() => selectBuild(latestRequest), /must be resolved/);
});

test('normal builds and release-triggered builds do not look up latest', async () => {
    for (const event of [
        { EVENT_NAME: 'push' },
        { EVENT_NAME: 'workflow_dispatch', REQUESTED_REF: '0.3.1' },
        { EVENT_NAME: 'release', RELEASE_TAG: '0.3.1', REQUESTED_REF: 'latest' },
    ]) {
        assert.deepEqual(await resolveBuild(event, async () => { assert.fail('Unexpected release API request'); }),
            selectBuild(event));
    }
});

test('ordinary site pushes build master in snapshot mode', () => {
    assert.deepEqual(selectBuild({ EVENT_NAME: 'push' }), {
        ref: 'master', version: '', release_build: 'false', should_deploy: 'false',
    });
});

for (const tag of ['0.4.0', 'v0.4.0', 'v0.4.0-rc.1']) {
    test(`release ${tag} builds the exact tag with a normalized Gradle version`, () => {
        assert.deepEqual(selectBuild({ EVENT_NAME: 'release', RELEASE_TAG: tag }), {
            ref: `refs/tags/${tag}`, version: tag.replace(/^v/, ''),
            release_build: 'true', should_deploy: 'true',
        });
    });
}

test('manual tag preview uses release dependencies without deploying live', () => {
    assert.deepEqual(selectBuild({ EVENT_NAME: 'workflow_dispatch', REQUESTED_REF: 'v0.4.0' }), {
        ref: 'refs/tags/v0.4.0', version: '0.4.0', release_build: 'true', should_deploy: 'false',
    });
});

test('an explicit tag ref is not prefixed twice', () => {
    assert.equal(selectBuild({ REQUESTED_REF: 'refs/tags/0.4.0' }).ref, 'refs/tags/0.4.0');
});

test('branch builds stay snapshot and honor explicit deployment', () => {
    const result = selectBuild({ REQUESTED_REF: 'work/example', DEPLOY_LIVE: 'true' });
    assert.equal(result.version, '');
    assert.equal(result.ref, 'work/example');
    assert.equal(result.should_deploy, 'true');
});

for (const tag of ['', 'master', 'v0.4.0-SNAPSHOT', 'v0.4.0\nVERSION=injected']) {
    test(`invalid release tag ${JSON.stringify(tag)} fails before checkout`, () => {
        assert.throws(() => selectBuild({ EVENT_NAME: 'release', RELEASE_TAG: tag }));
    });
}

test('manual refs cannot inject workflow commands', () => {
    assert.throws(() => selectBuild({ REQUESTED_REF: 'master\nVERSION=injected' }));
    assert.throws(() => selectBuild({ REQUESTED_REF: '--help' }));
});

for (const event of [
    { EVENT_NAME: 'push' },
    { EVENT_NAME: 'release', RELEASE_TAG: 'v0.4.0' },
]) {
    test(`CLI persists Gradle VERSION and deployment outputs for ${event.EVENT_NAME}`, () => {
        const directory = mkdtempSync(join(tmpdir(), 'nostr4j-build-settings-'));
        try {
            const output = join(directory, 'output');
            const environment = join(directory, 'env');
            const result = spawnSync(process.execPath,
                [fileURLToPath(new URL('./site-build-settings.mjs', import.meta.url))], {
                    env: { PATH: process.env.PATH, VERSION: 'stale-version', ...event,
                        GITHUB_OUTPUT: output, GITHUB_ENV: environment },
                    encoding: 'utf8',
                });
            assert.equal(result.status, 0, result.stderr);
            const expected = selectBuild(event);
            assert.equal(readFileSync(environment, 'utf8'),
                `VERSION=${expected.version}\n` +
                (expected.version ? `NOSTR4J_DOC_VERSION=${expected.version}\n` : ''));
            assert.match(readFileSync(output, 'utf8'), new RegExp(`release_build=${expected.release_build}\\n`));
            assert.match(readFileSync(output, 'utf8'), new RegExp(`should_deploy=${expected.should_deploy}\\n`));
        } finally {
            rmSync(directory, { recursive: true, force: true });
        }
    });
}
