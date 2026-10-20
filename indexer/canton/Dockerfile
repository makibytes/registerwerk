# Canton open-source sandbox for local development: one participant and one synchronizer in a single
# JVM with in-memory storage. Canton 3.x images are not published on Docker Hub (the `digitalasset/
# canton-open-source` repository there stops at 2.x), so the pinned release archive is fetched from
# GitHub and checked against its SHA-256.
FROM eclipse-temurin:21-jre

ARG CANTON_VERSION=3.6.2
ARG CANTON_SHA256=b496f0d17c15323f8ba8cfbdaacf34164ff642c8b2d463d1e9170389b16da9ea

ADD https://github.com/digital-asset/canton/releases/download/v${CANTON_VERSION}/canton-open-source-${CANTON_VERSION}.tar.gz /tmp/canton.tar.gz
RUN echo "${CANTON_SHA256}  /tmp/canton.tar.gz" | sha256sum --check --strict \
 && mkdir -p /opt/canton \
 && tar -xzf /tmp/canton.tar.gz --strip-components=1 -C /opt/canton \
 && rm /tmp/canton.tar.gz \
 && useradd --system --home-dir /opt/canton --shell /usr/sbin/nologin canton \
 && mkdir -p /opt/canton/log /opt/canton/.ammonite /dars \
 && chown -R canton:canton /opt/canton/log /opt/canton/.ammonite

COPY config/sandbox.conf /opt/canton/config/registerwerk-sandbox.conf
COPY --chmod=755 docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

USER canton
WORKDIR /opt/canton
EXPOSE 5001 5002 5013
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
