# Documentation server — builds the MkDocs site once at image-build time and serves the static
# output with nginx.
#
# This replaces `mkdocs serve` against a bind mount, which was the single largest source of idle
# CPU on the demo host. MkDocs' livereload uses watchdog's PollingObserver (deliberately, so it
# works across Docker and network mounts), which re-stats the ENTIRE docs_dir tree twice a second
# for the life of the container — and it does so regardless of `exclude_docs`, because exclusions
# only affect what is rendered, not what is watched. Building once removes the watcher, the
# rebuild loop, and the bind mount together; the running container is then plain static nginx.
#
# Build context is the repo root, because mkdocs.yml lives there and docs/hooks.py is referenced
# from it as `docs/hooks.py`.

FROM squidfunk/mkdocs-material:9.7.7 AS build
WORKDIR /docs
COPY mkdocs.yml ./
COPY docs/ ./docs/

# hooks.py substitutes {{ backend_url }} into pages. This used to be read from the environment at
# serve time; now that the site is prebuilt it is necessarily a build-time value. Override with
# `--build-arg BACKEND_URL=...` (compose passes it through, see docker-compose.yml).
ARG BACKEND_URL=http://localhost:8080
ENV BACKEND_URL=${BACKEND_URL}

# --strict so a broken link fails the image build rather than shipping quietly.
RUN mkdocs build --strict

FROM nginx:1.30.4-alpine
COPY --from=build /docs/site /usr/share/nginx/html
COPY docs/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
