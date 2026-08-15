FROM alpine:3.22.1

RUN apk add --no-cache softhsm opensc openssl \
    && addgroup -S -g 2000 registerwerk-hsm
COPY softhsm2.conf /etc/softhsm2.conf
COPY init-token.sh /usr/local/bin/init-token.sh
COPY demo-operator-key.pem /demo/demo-operator-key.pem
RUN chmod 0755 /usr/local/bin/init-token.sh

ENTRYPOINT ["/usr/local/bin/init-token.sh"]
