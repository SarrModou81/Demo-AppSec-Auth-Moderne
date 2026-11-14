#!/usr/bin/env bash
#
# Exécution headless de la collection de sécurité bzhcamp via Newman.
#
# Usage :
#   export CLIENT_SECRET="<secret du client app-springboot>"
#   export M2M_CLIENT_SECRET="<secret du client app-m2m>"   # dossier 09 (client_credentials)
#   ./run-newman.sh
#
# Surcharges d'environnement optionnelles (CI / UAT / PREPROD) :
#   KEYCLOAK_BASE   (def. http://localhost:8080)
#   SERVICE_BASE    (def. http://localhost:8091)
#   APP_BASE        (def. http://localhost:8090)
#   EXPECTED_AUDIENCE (def. vide — validation d'audience désactivée)
#
# Produit : rapport CLI + reports/junit.xml (consommable par Jenkins `junit`).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COLLECTION="$HERE/bzhcamp-security.postman_collection.json"
ENVIRONMENT="$HERE/environment.postman_environment.json"
REPORT_DIR="$HERE/reports"
mkdir -p "$REPORT_DIR"

# --- Runner Newman : newman natif s'il existe, sinon via bunx (politique PAI : bun, jamais npm) ---
if command -v newman >/dev/null 2>&1; then
  NEWMAN=(newman)
elif command -v bunx >/dev/null 2>&1; then
  NEWMAN=(bunx newman)
elif command -v npx >/dev/null 2>&1; then
  NEWMAN=(npx --yes newman)
else
  echo "ERREUR : ni 'newman', ni 'bunx', ni 'npx' trouvés. Installez Newman : 'bun add -g newman'." >&2
  exit 127
fi

if [[ -z "${CLIENT_SECRET:-}" ]]; then
  echo "AVERTISSEMENT : CLIENT_SECRET non défini. Les dossiers 01/07/08 (grants client confidentiel) échoueront." >&2
fi

if [[ -z "${M2M_CLIENT_SECRET:-}" ]]; then
  echo "AVERTISSEMENT : M2M_CLIENT_SECRET non défini. Le dossier 09 (client_credentials) sera interrompu par son canary." >&2
  echo "                Obtenir le secret : ../demo/keycloak/apply-m2m.sh" >&2
fi

# Surcharges d'URL (valeurs par défaut = environnement local)
KEYCLOAK_BASE="${KEYCLOAK_BASE:-http://localhost:8080}"
SERVICE_BASE="${SERVICE_BASE:-http://localhost:8091}"
APP_BASE="${APP_BASE:-http://localhost:8090}"
EXPECTED_AUDIENCE="${EXPECTED_AUDIENCE:-}"

# Les secrets passent par --env-var (jamais écrits sur disque, jamais committés).
"${NEWMAN[@]}" run "$COLLECTION" \
  --environment "$ENVIRONMENT" \
  --env-var "client_secret=${CLIENT_SECRET:-}" \
  --env-var "m2m_client_secret=${M2M_CLIENT_SECRET:-}" \
  --env-var "keycloak_base=${KEYCLOAK_BASE}" \
  --env-var "service_base=${SERVICE_BASE}" \
  --env-var "app_base=${APP_BASE}" \
  --env-var "expected_audience=${EXPECTED_AUDIENCE}" \
  --delay-request 300 \
  --reporters cli,junit \
  --reporter-junit-export "$REPORT_DIR/junit.xml"

echo ""
echo "Rapport JUnit : $REPORT_DIR/junit.xml"
