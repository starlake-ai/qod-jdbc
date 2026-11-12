#!/usr/bin/env bash
# Keycloak 26.4 on 127.0.0.1:8180 with realm qod: users alice, mallory (no QoD row) and
# acme-admin; clients acme-console (a tenant's SSO) and qod-flightsql (the data-plane provider).
set -euo pipefail
docker rm -f qod-e2e-kc >/dev/null 2>&1 || true
docker run -d --name qod-e2e-kc -p 127.0.0.1:8180:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.4 start-dev >/dev/null
until curl -sf http://localhost:8180/realms/master/.well-known/openid-configuration >/dev/null; do sleep 1; done
K="docker exec qod-e2e-kc /opt/keycloak/bin/kcadm.sh"
$K config credentials --server http://localhost:8080 --realm master --user admin --password admin
$K create realms -s realm=qod -s enabled=true
for u in alice:demo-alice-kc mallory:mallory-pw acme-admin:acme-admin-kc; do
  name=${u%%:*}
  $K create users -r qod -s username=$name -s email=$name@acme.io -s emailVerified=true \
    -s firstName=$name -s lastName=e2e -s enabled=true
  $K set-password -r qod --username $name --new-password ${u#*:}
done
$K create clients -r qod -s clientId=acme-console -s publicClient=false -s secret=acme-console-secret \
  -s standardFlowEnabled=true -s 'redirectUris=["http://localhost:20900/api/auth/oidc/callback"]'
$K create clients -r qod -s clientId=qod-flightsql -s publicClient=false -s secret=qod-flightsql-secret \
  -s standardFlowEnabled=true -s directAccessGrantsEnabled=true \
  -s 'redirectUris=["http://localhost:20900/api/auth/sql-token/callback"]'
