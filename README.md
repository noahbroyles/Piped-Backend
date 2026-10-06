# Piped-Backend

An advanced open-source privacy friendly alternative to YouTube, crafted with the help of [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor).

## Official Frontend

-   VueJS frontend - [Piped](https://github.com/TeamPiped/Piped)

## Community Projects

-   See https://github.com/TeamPiped/Piped#made-with-piped

## Using this fork with Piped-Docker

The official `1337kavin/piped:latest` image ships an outdated NewPipeExtractor, which can make `/streams` return a single progressive stream (itag 18) with no audio streams, so videos are only available in 360p. This fork updates NewPipeExtractor to upstream commit `01fdde0` (Piped-Backend PR #895).

It also fixes six security problems in the official backend:

-   A server-side request forgery (SSRF) vulnerability, which lets anyone who can reach the API, without logging in, make the backend send requests to other addresses, such as devices on your local network.
-   Libraries with 23 known vulnerabilities (Jackson, Bouncy Castle, the PostgreSQL driver, jsoup and the MinIO client), which are upgraded to fixed releases.
-   No limit on request size. A single huge request, such as a 100 MB login, was read fully into memory before being rejected, and larger ones left the connection hanging. Requests over 5 MiB (16 KiB for login and registration) are now rejected with `413`; the limit can be changed with `MAX_BODY_SIZE` in `config.properties`.
-   Error responses included the full Java stack trace, which showed anyone the server's internal code paths, library versions and other details. Errors now return only a short message: YouTube's reason when a video, channel or playlist cannot be loaded (such as `This video is private.`), and a generic message for anything else. The full stack trace is still written to the server log (or Sentry).
-   No limit on the channel lists sent to the endpoints for users without an account (`/feed/unauthenticated` and `/subscriptions/unauthenticated`). Every unknown channel ID in a request was stored in the database and looked up on YouTube, so a single request with thousands of made-up IDs could fill the database and make the backend flood YouTube with requests. Requests with more than 1000 channels are now rejected with `400`, and at most 25 unknown channels are stored and looked up per request (the rest are picked up by later requests). The limits can be changed with `MAX_UNAUTHENTICATED_CHANNELS` and `MAX_UNKNOWN_CHANNELS` in `config.properties`.
-   The PubSub webhook (`/webhooks/pubsub`), which receives new-video notifications from YouTube, trusted anyone. Its subscription check confirmed any request and wrote whatever channel ID it was given to the database, and fake notifications could make the backend fetch arbitrary videos from YouTube through a queue with no size limit. The check now only confirms subscriptions this backend asked for, the queue and notification size are capped, and notifications are verified with a shared secret. **Set `PUBSUB_SECRET` in `config.properties` to a long random string (for example the output of `openssl rand -hex 32`) to turn on that verification.** Without it, the backend logs a warning at startup and accepts unsigned notifications. Existing subscriptions start using the secret when they are renewed, within about 4 days; until then their notifications arrive unsigned and are dropped, so some new videos may be missing from feeds during that time.

A multi-architecture image (amd64 and arm64) is published to GitHub Container Registry on every push to `master`. It is a drop-in replacement for the official image in a [Piped-Docker](https://github.com/TeamPiped/Piped-Docker) installation, and it reads the same `config.properties`.

### Easiest: use the Piped-Docker fork

[noahbroyles/Piped-Docker](https://github.com/noahbroyles/Piped-Docker) sets up a complete instance the same way as the official Piped-Docker, but with this backend and the fixed frontend from [noahbroyles/Piped](https://github.com/noahbroyles/Piped), which makes live streams play instead of spinning forever. You get every fix in both forks without editing any images yourself. Its `configure-instance.sh` also generates a random `PUBSUB_SECRET` for you, and offers automatic updates with Watchtower for every stack type.

For a new instance, use it in place of the official repository:

```sh
git clone https://github.com/noahbroyles/Piped-Docker
cd Piped-Docker
./configure-instance.sh
docker compose up -d
```

For an existing installation, follow [Switching an existing installation](https://github.com/noahbroyles/Piped-Docker#switching-an-existing-installation) in its README to switch both images, or switch only the backend as described below.

### Switch the backend image

In the `docker-compose.yml` of your Piped-Docker installation, find the backend service (the one that uses the `1337kavin/piped` image) and change only its `image` line. Keep its volumes, including the mount of your `config.properties` at `/app/config.properties`, and everything else as it is.

```diff
-        image: 1337kavin/piped:latest
+        image: ghcr.io/noahbroyles/piped-backend:latest
```

Then pull the new image and recreate only the backend container. The commands use `piped` as the service name, so replace it if your compose file names the backend service differently.

```sh
docker compose pull piped
docker compose up -d --no-deps piped
```

`--no-deps` leaves the other services (such as the database) as they are.

If it does not work, please [open an issue](https://github.com/noahbroyles/Piped-Backend/issues) on this repository.

### Pinning a version or building it yourself

The image is published with two kinds of tags:

-   `latest` follows the `master` branch.
-   `sha-<commit>` pins a specific build, for example `sha-a4f0a9e`.

The package is public, so no login is needed.

To build the image yourself instead, clone this repository and replace the `image` line of the backend service with a `build` line that points at the clone. The build runs Gradle inside the image, so only Docker is needed.

```yaml
        build: /path/to/Piped-Backend
```

Then run `docker compose build piped` followed by `docker compose up -d --no-deps piped`.
