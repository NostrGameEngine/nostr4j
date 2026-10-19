import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const releaseVersion = /^v?\d+\.\d+\.\d+(?:[.-][A-Za-z0-9.-]+)?$/;

export function selectBuild(env) {
    const publishingRelease = env.EVENT_NAME === 'release';
    let ref = publishingRelease ? env.RELEASE_TAG : (env.REQUESTED_REF || 'master');
    if (ref === 'latest') throw new Error('The latest release must be resolved before selecting a build');
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

export async function resolveBuild(env, fetchRelease = fetch) {
    if (env.EVENT_NAME === 'release' || env.REQUESTED_REF !== 'latest') return selectBuild(env);

    if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(env.GITHUB_REPOSITORY || '')) {
        throw new Error('A GitHub repository is required to find the latest release');
    }
    const headers = { Accept: 'application/vnd.github+json' };
    if (env.GITHUB_TOKEN) headers.Authorization = `Bearer ${env.GITHUB_TOKEN}`;
    let release;
    for (let page = 1; ; page++) {
        const response = await fetchRelease(
            `https://api.github.com/repos/${env.GITHUB_REPOSITORY}/releases?per_page=100&page=${page}`,
            { headers, signal: AbortSignal.timeout(30_000) });
        if (!response.ok) throw new Error(`Cannot resolve the latest published release (HTTP ${response.status})`);
        const releases = await response.json();
        if (!Array.isArray(releases)) throw new Error('Invalid GitHub release list');
        for (const candidate of releases) {
            if (candidate.draft !== false || !Number.isFinite(Date.parse(candidate.published_at))) continue;
            if (!release || Date.parse(candidate.published_at) > Date.parse(release.published_at)) release = candidate;
        }
        if (releases.length < 100) break;
    }
    if (!release) throw new Error('No published release is available');
    if (typeof release.tag_name !== 'string' || !releaseVersion.test(release.tag_name) ||
        release.tag_name.toUpperCase().includes('SNAPSHOT')) {
        throw new Error('The latest release must have a published, non-snapshot version tag');
    }
    return selectBuild({ ...env, REQUESTED_REF: release.tag_name });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const settings = await resolveBuild(process.env);
    appendFileSync(process.env.GITHUB_OUTPUT,
        Object.entries(settings).map(([key, value]) => `${key}=${value}\n`).join(''));
    appendFileSync(process.env.GITHUB_ENV, `VERSION=${settings.version}\n` +
        (settings.version ? `NOSTR4J_DOC_VERSION=${settings.version}\n` : ''));
    console.log(`Library: ${settings.ref}; mode: ${settings.release_build === 'true' ? 'release' : 'snapshot'}`);
}
