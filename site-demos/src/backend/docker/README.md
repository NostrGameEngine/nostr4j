# Nostr4J demo backend (DemoServer)

This is the backend that powers the live demos on the site. It is a single
Java program (`org.ngengine.site.demos.backend.DemoServer`) that serves the
static site and the demo APIs on one port, or serves only the APIs when the
site is hosted separately:

- `GET /api/config` - relays, TURN URI and lobby info for the demos.
- `POST /api/ping` - allocates an isolated ping room: the server spawns a
  disposable JVM peer, the visitor's browser joins the same room and measures
  round-trip time against it. Sessions expire after 90 seconds.
- `POST /api/game` - starts the shared Sats Seas lobby bots, or allocates a
  private practice room with two bots. Private rooms expire after 3 minutes.

Ping visitors get their own room and throwaway peer. Game visitors either
join the shared lobby or get a private room with two simulated captains.
Visitors cannot choose network destinations - only the operator's
fixed relays and TURN server are used. Ping sessions are capped (8
concurrent, per-IP rate limit) and the server answers 429 when busy.

Signaling and TURN fallback use outbound WebSockets over `wss://`.
Direct WebRTC also requires UDP connectivity for the peers' ICE candidates;
publishing the HTTP port alone does not expose those UDP paths. For a Linux
LAN preview, host networking lets the JVM advertise the host's reachable
interfaces; allow UDP only from the networks that should reach it. Otherwise,
use a reachable, protocol-compatible TURN fallback.

## Build

From the gh-pages branch root (the image builds the static site and the
backend jar itself):

```bash
docker build -f site-demos/src/backend/docker/Dockerfile -t nostr4j-demo-backend .
```

To build the demos, API reference and backend locally, run this from the
gh-pages root. `build.sh` fetches the library and platform sources, then
compiles them with JDK 25 in Podman. Run the resulting backend with Java 21+:

```bash
./build.sh all master
DEMO_ROOT="$PWD/_site" DEMO_HOSTS=localhost:8000,127.0.0.1:8000 \
  java -jar .cache/master/site-demos/build/libs/demo-backend-0.3.0-SNAPSHOT.jar
```

Open `http://localhost:8000/demos.html`. On macOS hosts where Podman cannot
mount the checkout directly, the build cache is under
`/private/tmp/nostr4j-build-cache` instead of `.cache`.

## Run

```bash
docker run -d --name demo-backend --restart unless-stopped \
  -p 8000:8000 \
  --read-only --tmpfs /tmp:rw,nosuid,nodev,size=16m \
  --cap-drop ALL --security-opt no-new-privileges \
  --memory 1g --cpus 2 \
  -e DEMO_HOSTS=demo.example.com \
  -e TURN_URI=wss://turn.ngengine.org/turn \
  nostr4j-demo-backend
```

> **Set `DEMO_HOSTS`.** The server only answers requests whose `Host` header
> matches this list, otherwise it returns 403. List every public hostname the
> site is reached at (comma-separated, with port when non-standard, e.g.
> `demo.example.com,demo.example.com:8443`).

## Separate site and API hosting

The site uses `NOSTR4J_DEMO_API_BASE_URL` to locate its API. GitHub Pages and
CI previews default to `https://nostr4jdemo.ngengine.org`; `build.sh build`
and `build.sh serve` default to the same origin for local development. Set
the variable to another absolute API URL when needed. An empty value keeps
requests on the page's own origin.

To build just the backend:

```bash
docker build --target backend-only -f site-demos/src/backend/docker/Dockerfile \
  -t nostr4j-demo-api .
```

That target defaults to port 8080 with `DEMO_API_ONLY=true`, so it serves no
static files. Set `DEMO_HOSTS` to the API's hostname and `DEMO_ORIGINS` to the
site origins that may create or close demo sessions. Include the scheme and
port in each origin, but no path. Requests from other origins and unsupported
CORS preflights are rejected. Prefer exact origins when you control the site.

`compose.yaml` runs this target through Cloudflare Tunnel. Put the tunnel
token in the ignored `.env` file using the variable in `.env.example`; never
commit a real token. The tunnel handles HTTP/WebSockets, not the UDP paths
needed for direct WebRTC.

Check the logs:

```bash
docker logs -f demo-backend
# Demo server listening on port 8000
```

For public hosting, terminate TLS in front of the container (reverse proxy):
the server itself speaks plain HTTP.

## Configuration (environment variables)

| Variable         | Default                                  | Meaning                                                        |
|------------------|------------------------------------------|----------------------------------------------------------------|
| `DEMO_PORT`      | `8000`                                   | HTTP port to listen on.                                        |
| `DEMO_HOSTS`     | Loopback plus subdomains of `ngengine.org`, `nostrverse.org`, `rblb.it` and `workers.dev` | Allowed `Host` headers (comma-separated). Override with exact hostnames for a deployment. |
| `DEMO_ORIGINS`   | GitHub Pages origin and HTTPS subdomains of `rblb.it`, `ngengine.org`, `nostrverse.org` and two-level `workers.dev` hosts | Allowed site origins for split hosting. Each `*` matches one hostname component; same-origin requests also work. |
| `DEMO_API_ONLY`  | `false` (`true` in the backend-only image) | Serve only `/api/*` without requiring a site directory. |
| `DEMO_ROOT`      | `_lan-preview` (`/site` in the image)    | Directory with the built static site to serve.                 |
| `TURN_URI`       | `wss://turn.ngengine.org/turn`           | TURN server URI handed to the demo peers.                      |
| `DEMO_GAME_BOTS` | enabled unless `false`                   | Set to `false` to disable the Sats Seas game endpoints.        |

## Hardening notes

The image runs as an unprivileged user and the `docker run` example above
removes Linux capabilities, forbids privilege escalation and makes the root
filesystem read-only. `/tmp` must remain writable: `SaferAlloc` extracts its
pinned JNI library there at startup; it is still an isolated, size-limited
`nosuid,nodev` tmpfs. The server writes nothing else to disk - sessions and
game rooms live in memory and expire automatically.
