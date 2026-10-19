import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const releaseVersion = /^v?\d+\.\d+\.\d+(?:[.-][A-Za-z0-9.-]+)?$/;

export function selectBuild(env) {
    const publishingRelease = env.EVENT_NAME === 'release';
    let ref = publishingRelease ? env.RELEASE_TAG : (env.REQUESTED_REF || 'master');
    if (!ref || /[\s\x00-\x1f\x7f]/.test(ref) || ref.startsWith('-')) {
        throw new Error('Invalid library ref');
    }
    const tag = ref.replace(/^refs\/tags\//, '');
    const release = releaseVersion.test(tag) && !tag.toUpperCase().includes('SNAPSHOT');
    if (publishingRelease && !release) throw new Error('A release requires a version tag without SNAPSHOT');
    if (ref.startsWith('refs/tags/') && !release) throw new Error('Invalid release tag');
    if (releaseVersion.test(tag) && !release) throw new Error('Snapshot versions must be built from a branch');
    const version = release ? tag.replace(/^v/, '') : '';
    if (release) ref = `refs/tags/${tag}`;
    return {
        ref,
        version,
        release_build: String(release),
        should_deploy: String(publishingRelease || env.DEPLOY_LIVE === 'true'),
    };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const settings = selectBuild(process.env);
    appendFileSync(process.env.GITHUB_OUTPUT,
        Object.entries(settings).map(([key, value]) => `${key}=${value}\n`).join(''));
    appendFileSync(process.env.GITHUB_ENV, `VERSION=${settings.version}\n` +
        (settings.version ? `NOSTR4J_DOC_VERSION=${settings.version}\n` : ''));
    console.log(`Library: ${settings.ref}; mode: ${settings.release_build === 'true' ? 'release' : 'snapshot'}`);
}
