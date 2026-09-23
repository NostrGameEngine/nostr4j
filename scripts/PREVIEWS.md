# Temporary documentation previews

The `Build and deploy site` workflow builds each push to `gh-pages` against
`master`. Each successful build publishes `_site` through Wrangler's temporary
Cloudflare account flow, the CLI route recommended by Cloudflare Drop:
https://www.cloudflare.com/drop/llms.txt

Open **Actions → Build and deploy site → the run → Summary → Temporary site
preview**. The link is added only after the deployed homepage responds with the
site content. Manual builds with `deploy_live: false` and tag preview builds
use the same path. Release publication and `deploy_live: true` retain the
GitHub Pages deployment.

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
