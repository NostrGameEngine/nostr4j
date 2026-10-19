# Temporary documentation previews

The `Build and deploy site` workflow builds each push to `gh-pages` against
`master`. Each successful build publishes `_site` through Wrangler's temporary
Cloudflare account flow, the CLI route recommended by Cloudflare Drop:
https://www.cloudflare.com/drop/llms.txt

Open **Actions → Build and deploy site → the run → Summary → Temporary site
preview**. The link is added only after the deployed homepage responds with the
site content. Manual builds with `deploy_live: false` use the same path.
For a release preview, select its tag as `library_ref`: the library and demos
use release dependencies even though the preview is temporary.

Publishing a GitHub release runs the library's `Build and Publish` workflow
from the tagged commit. After its build and publication succeed, it calls the
reusable website workflow on `gh-pages`, which checks out that exact library
tag and deploys the complete site to GitHub Pages. This wiring must be present
in the commit you tag. It does not create another GitHub release or tag on the
website branch.

To republish the site without creating a library release, open **Actions →
Redeploy website → Run workflow**, leave the branch set to `master`, and run it.
This uses the current website sources from `gh-pages` and resolves GitHub's latest
published release once (including prereleases), then builds the library and demos from that exact
tag with release dependencies. It deploys to the live GitHub Pages site without
creating a tag, creating a release, or republishing Maven artifacts. If no release
is available, the build fails instead of falling back to master or snapshots.
The entry-point workflow lives on `master` so GitHub can offer its manual button;
the website and its build remain on `gh-pages`.

The website workflow also accepts `latest` as `library_ref`; combine it with
`deploy_live: false` to preview the latest release without replacing the live site.

Release builds pass the tag version, without an optional `v` prefix, as Gradle's
`VERSION`. Browser demos and the JVM backend select the same NGE Platform
release from that library checkout's catalog. They resolve published artifacts
and reject snapshot dependencies or a mismatched platform version. Ordinary
branch previews retain snapshot mode and compile the current platform sources.
An explicit `deploy_live: true` also deploys to GitHub Pages.

No Cloudflare secret is needed. The preview is public and is deleted after
about 60 minutes. The bearer claim URL is not exposed in a public summary,
logs or artifacts; these previews are intentionally left unclaimed. The
`site-preview` download remains available for 14 days. Cloudflare rate limits
or provisioning failures fail the preview step, with an explanation in the
summary and the previously uploaded artifact still available.

The script pins Wrangler 4.102.0, isolates its configuration and logs in a
throwaway directory, and checks the temporary-account limits of 1,000 assets
and 5 MiB per asset before deployment. It uploads only `_site`, not sources
or the Gradle build directories. Temporary static hosting does not run the
JVM demo backend; demos requiring that backend still need a reachable,
appropriately configured backend.

Run `node --test scripts/deploy-preview.test.mjs` to check parsing, asset
validation and URL verification without provisioning an account. The deploy
script itself is intended to run in GitHub Actions. A push to the library's
`master` branch alone does not trigger this website-branch workflow.
