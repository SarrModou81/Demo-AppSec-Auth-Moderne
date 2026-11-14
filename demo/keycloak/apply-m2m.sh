#!/usr/bin/env bash
#
# apply-m2m.sh — crée l'identité machine (grant_type=client_credentials) dans un realm
#                Keycloak DÉJÀ DÉMARRÉ, sans toucher à la base.
#
# POURQUOI CE SCRIPT EXISTE
#   `--import-realm` n'importe le realm qu'au PREMIER démarrage, sur une base vide.
#   Si le volume `demo_pgdata` existe déjà, modifier `keycloak/import/realm-export.json`
#   n'a AUCUN effet : Keycloak ne réimporte pas. Deux options :
#     1. `docker compose down -v` (DÉTRUIT la base et tout travail manuel) puis `up -d --build`
#     2. ce script, qui applique les mêmes objets via l'Admin CLI (kcadm) — non destructif
#   Utiliser l'option 2 avant une démo. Toujours.
#
# CE QUE LE SCRIPT CRÉE (idempotent — relançable sans effet de bord)
#   - client `service-planets` : représente l'API aval, porte les rôles machine, sert d'audience
#   - rôle client `planets.read` sur `service-planets`
#   - client `app-m2m` : confidentiel, serviceAccountsEnabled=true, AUCUN flux navigateur/ROPC
#   - mapper d'audience `service-planets` sur app-m2m, app-springboot et keycloak-angular
#   - affectation de `planets.read` au service account de `app-m2m`
#
# USAGE
#   ./demo/keycloak/apply-m2m.sh
#   KC_CONTAINER=demo-keycloak-1 KC_REALM=bzhcamp ./demo/keycloak/apply-m2m.sh
#
set -euo pipefail

KC_CONTAINER="${KC_CONTAINER:-demo-keycloak-1}"
KC_REALM="${KC_REALM:-bzhcamp}"
KC_URL="${KC_URL:-http://localhost:8080}"
KC_ADMIN="${KC_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
CFG=/tmp/kcadm-m2m.json

AUDIENCE_CLIENT="service-planets"
MACHINE_CLIENT="app-m2m"
MACHINE_ROLE="planets.read"
MAPPER_NAME="audience-${AUDIENCE_CLIENT}"

say() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m  ✓\033[0m %s\n' "$*"; }
skip(){ printf '\033[1;33m  ~\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m  ✗\033[0m %s\n' "$*" >&2; exit 1; }

kc() { docker exec -i "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@" --config "$CFG"; }

# id interne d'un client à partir de son clientId — chaîne vide si le client n'existe pas.
# `|| true` est indispensable : sans lui, `grep` renvoie 1 quand le client est absent et
# `set -e` interrompt le script dans le cas même qu'il doit gérer (la création).
client_id_of() {
  kc get clients -r "$KC_REALM" -q "clientId=$1" --fields id --format csv --noquotes 2>/dev/null \
    | tr -d '\r' | grep -v '^$' | head -1 || true
}

docker inspect "$KC_CONTAINER" >/dev/null 2>&1 \
  || die "conteneur '$KC_CONTAINER' introuvable — lancer d'abord: cd demo && docker compose up -d"

say "Authentification Admin CLI sur $KC_URL (realm master)"
docker exec -i "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh config credentials \
  --config "$CFG" --server "$KC_URL" --realm master \
  --user "$KC_ADMIN" --password "$KC_ADMIN_PASSWORD" >/dev/null \
  || die "échec d'authentification admin (KC_ADMIN/KC_ADMIN_PASSWORD)"
ok "authentifié"

# ---------------------------------------------------------------------------
say "1/5 — client '$AUDIENCE_CLIENT' (API aval : porte les rôles machine + audience)"
AUD_ID="$(client_id_of "$AUDIENCE_CLIENT")"
if [[ -z "$AUD_ID" ]]; then
  kc create clients -r "$KC_REALM" \
    -s "clientId=$AUDIENCE_CLIENT" \
    -s 'name=service-planets (resource server /8091)' \
    -s enabled=true \
    -s publicClient=false \
    -s bearerOnly=false \
    -s standardFlowEnabled=false \
    -s implicitFlowEnabled=false \
    -s directAccessGrantsEnabled=false \
    -s serviceAccountsEnabled=false >/dev/null
  AUD_ID="$(client_id_of "$AUDIENCE_CLIENT")"
  ok "créé (id=$AUD_ID)"
else
  skip "déjà présent (id=$AUD_ID)"
fi

# ---------------------------------------------------------------------------
say "2/5 — rôle client '$MACHINE_ROLE' sur '$AUDIENCE_CLIENT'"
if kc get "clients/$AUD_ID/roles/$MACHINE_ROLE" -r "$KC_REALM" >/dev/null 2>&1; then
  skip "déjà présent"
else
  kc create "clients/$AUD_ID/roles" -r "$KC_REALM" \
    -s "name=$MACHINE_ROLE" \
    -s 'description=Lecture machine du catalogue de planetes (/internal/planets)' >/dev/null
  ok "créé"
fi

# ---------------------------------------------------------------------------
say "3/5 — client machine '$MACHINE_CLIENT' (serviceAccountsEnabled, aucun flux humain)"
M2M_ID="$(client_id_of "$MACHINE_CLIENT")"
if [[ -z "$M2M_ID" ]]; then
  kc create clients -r "$KC_REALM" \
    -s "clientId=$MACHINE_CLIENT" \
    -s 'name=app-m2m (identite machine)' \
    -s enabled=true \
    -s publicClient=false \
    -s bearerOnly=false \
    -s standardFlowEnabled=false \
    -s implicitFlowEnabled=false \
    -s directAccessGrantsEnabled=false \
    -s serviceAccountsEnabled=true >/dev/null
  M2M_ID="$(client_id_of "$MACHINE_CLIENT")"
  ok "créé (id=$M2M_ID)"
else
  # idempotence : on force les propriétés qui comptent, au cas où le client existe mal configuré
  kc update "clients/$M2M_ID" -r "$KC_REALM" \
    -s serviceAccountsEnabled=true \
    -s publicClient=false \
    -s standardFlowEnabled=false \
    -s directAccessGrantsEnabled=false >/dev/null
  skip "déjà présent (id=$M2M_ID) — propriétés machine réaffirmées"
fi

# ---------------------------------------------------------------------------
say "4/5 — mapper d'audience '$MAPPER_NAME' (rend la validation d'audience activable)"
add_audience_mapper() {
  local client_id="$1" client_name="$2"
  local existing
  existing="$(kc get "clients/$client_id/protocol-mappers/models" -r "$KC_REALM" \
    --fields name --format csv --noquotes 2>/dev/null | tr -d '\r' | grep -Fx "$MAPPER_NAME" || true)"
  if [[ -n "$existing" ]]; then
    skip "$client_name : mapper déjà présent"
    return
  fi
  docker exec -i "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh \
    create "clients/$client_id/protocol-mappers/models" -r "$KC_REALM" --config "$CFG" -f - >/dev/null <<JSON
{
  "name": "$MAPPER_NAME",
  "protocol": "openid-connect",
  "protocolMapper": "oidc-audience-mapper",
  "consentRequired": false,
  "config": {
    "included.client.audience": "$AUDIENCE_CLIENT",
    "id.token.claim": "false",
    "access.token.claim": "true",
    "introspection.token.claim": "true"
  }
}
JSON
  ok "$client_name : mapper ajouté"
}

add_audience_mapper "$M2M_ID" "$MACHINE_CLIENT"
for human in app-springboot keycloak-angular; do
  hid="$(client_id_of "$human")"
  if [[ -n "$hid" ]]; then add_audience_mapper "$hid" "$human"; else skip "$human absent du realm"; fi
done

# ---------------------------------------------------------------------------
say "5/5 — affectation de '$MACHINE_ROLE' au service account de '$MACHINE_CLIENT'"
SA_USER="service-account-${MACHINE_CLIENT}"
if kc add-roles -r "$KC_REALM" --uusername "$SA_USER" \
     --cclientid "$AUDIENCE_CLIENT" --rolename "$MACHINE_ROLE" >/dev/null 2>&1; then
  ok "rôle affecté à $SA_USER"
else
  # add-roles échoue si le rôle est déjà affecté : on vérifie plutôt que de supposer
  if kc get-roles -r "$KC_REALM" --uusername "$SA_USER" --cclientid "$AUDIENCE_CLIENT" \
       --fields name --format csv --noquotes 2>/dev/null | tr -d '\r' | grep -Fqx "$MACHINE_ROLE"; then
    skip "rôle déjà affecté à $SA_USER"
  else
    die "impossible d'affecter $MACHINE_ROLE à $SA_USER"
  fi
fi

# ---------------------------------------------------------------------------
SECRET="$(kc get "clients/$M2M_ID/client-secret" -r "$KC_REALM" \
  --fields value --format csv --noquotes 2>/dev/null | tr -d '\r' | grep -v '^$' | head -1)"

printf '\n\033[1;32mIdentité machine prête.\033[0m\n\n'
cat <<EOF
  Client machine      : $MACHINE_CLIENT
  API cible / audience : $AUDIENCE_CLIENT
  Rôle machine         : $MACHINE_ROLE
  Service account      : $SA_USER

Secret du client (à exporter, jamais à committer) :

  export M2M_CLIENT_SECRET='$SECRET'

Jeton machine :

  curl -s -X POST $KC_URL/realms/$KC_REALM/protocol/openid-connect/token \\
    -d grant_type=client_credentials \\
    -d client_id=$MACHINE_CLIENT \\
    -d client_secret="\$M2M_CLIENT_SECRET"

Démonstration complète : voir demo/RUNBOOK-M2M.md
EOF
