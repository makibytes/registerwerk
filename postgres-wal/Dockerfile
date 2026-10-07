# syntax=docker/dockerfile:1
#
# PostgreSQL 18 + WAL-G, for continuous WAL archiving and point-in-time recovery (T7-04).
#
# Same base (postgres:18.6-alpine, musl) as the stock `postgres` service on purpose: switching the
# libc under an existing data volume would change collation behaviour, so the opt-in WAL overlay
# (docker-compose.wal.yml) can be applied to an existing pg18data volume without a dump/restore.
# WAL-G publishes only glibc release binaries, so it is built from the pinned tag here.
ARG POSTGRES_IMAGE=postgres:18.6-alpine

FROM golang:1.25-alpine AS walg
ARG WALG_VERSION=v3.0.9
RUN apk add --no-cache git
RUN git clone --depth 1 --branch "${WALG_VERSION}" https://github.com/wal-g/wal-g.git /src
WORKDIR /src/main/pg
# WAL-G v3.0.x imports encoding/json/v2, which Go 1.25 only ships behind GOEXPERIMENT=jsonv2.
RUN GOEXPERIMENT=jsonv2 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X github.com/wal-g/wal-g/cmd/pg.walgVersion=${WALG_VERSION}" -o /out/wal-g .

FROM ${POSTGRES_IMAGE}
COPY --from=walg /out/wal-g /usr/local/bin/wal-g
COPY walg-env.sh pg-backup.sh postgres-wal-entrypoint.sh /usr/local/bin/
# Default local archive. A named volume mounted here inherits postgres ownership on first use.
RUN chmod 0755 /usr/local/bin/walg-env.sh /usr/local/bin/pg-backup.sh /usr/local/bin/postgres-wal-entrypoint.sh \
 && mkdir -p /wal-archive && chown postgres:postgres /wal-archive
ENV WALG_FILE_PREFIX=/wal-archive
ENTRYPOINT ["/usr/local/bin/postgres-wal-entrypoint.sh"]
CMD ["postgres"]
