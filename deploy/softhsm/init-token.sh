#!/bin/sh
set -eu

mkdir -p /tokens
export SOFTHSM2_CONF=/etc/softhsm2.conf

if ! softhsm2-util --show-slots | grep -q 'Label: *registerwerk-demo'; then
  softhsm2-util --init-token --free --label registerwerk-demo \
    --so-pin "${HSM_SO_PIN}" --pin "${HSM_USER_PIN}"
fi

if ! pkcs11-tool --module /usr/lib/softhsm/libsofthsm2.so --token-label registerwerk-demo \
    --login --pin "${HSM_USER_PIN}" --list-objects --type privkey | grep -q 'registerwerk-operator'; then
  # This is Anvil's documented first development key. It is intentionally public and MUST NEVER
  # be used beyond this disposable demo. Importing it makes the demo exercise the exact PKCS#11
  # path while retaining ownership of the contracts deployed by the same Anvil fixture account.
  openssl ec -in /demo/demo-operator-key.pem -outform DER -out /tmp/operator-key.der
  openssl req -new -x509 -key /demo/demo-operator-key.pem -subj /CN=registerwerk-demo-operator \
    -days 3650 -outform DER -out /tmp/operator-cert.der
  pkcs11-tool --module /usr/lib/softhsm/libsofthsm2.so --token-label registerwerk-demo \
    --login --pin "${HSM_USER_PIN}" --write-object /tmp/operator-key.der --type privkey \
    --label registerwerk-operator --id 01 --private --sensitive --usage-sign
  pkcs11-tool --module /usr/lib/softhsm/libsofthsm2.so --token-label registerwerk-demo \
    --login --pin "${HSM_USER_PIN}" --write-object /tmp/operator-cert.der --type cert \
    --label registerwerk-operator --id 01
fi

# SunPKCS11 runs inside the unprivileged backend container and accesses this shared token volume
# directly. A fixed cross-container GID keeps the token private from other users while permitting
# both processes to read and update SoftHSM's object store.
chgrp -R 2000 /tokens
chmod -R g+rwX,o-rwx /tokens
touch /tokens/.ready
exec tail -f /dev/null
