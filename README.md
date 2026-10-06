# Piped-Backend

An advanced open-source privacy friendly alternative to YouTube, crafted with the help of [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor).

## Official Frontend

-   VueJS frontend - [Piped](https://github.com/TeamPiped/Piped)

## Community Projects

-   See https://github.com/TeamPiped/Piped#made-with-piped

## Using this fork with Piped-Docker

The official `1337kavin/piped:latest` image ships an outdated NewPipeExtractor, which can make `/streams` return a single progressive stream (itag 18) with no audio streams, so videos are only available in 360p. This fork updates NewPipeExtractor to upstream commit `01fdde0` (Piped-Backend PR #895).

It also fixes three security problems in the official backend:

-   A server-side request forgery (SSRF) vulnerability, which lets anyone who can reach the API, without logging in, make the backend send requests to other addresses, such as devices on your local network.
-   Libraries with 23 known vulnerabilities (Jackson, Bouncy Castle, the PostgreSQL driver, jsoup and the MinIO client), which are upgraded to fixed releases.
-   No limit on request size. A single huge request, such as a 100 MB login, was read fully into memory before being rejected, and larger ones left the connection hanging. Requests over 5 MiB (16 KiB for login and registration) are now rejected with `413`; the limit can be changed with `MAX_BODY_SIZE` in `config.properties`.

A multi-architecture image (amd64 and arm64) is published to GitHub Container Registry on every push to `master`. It is a drop-in replacement for the official image in a [Piped-Docker](https://github.com/TeamPiped/Piped-Docker) installation, and it reads the same `config.properties`.

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
