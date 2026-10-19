import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { selectBuild } from './site-build-settings.mjs';

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
