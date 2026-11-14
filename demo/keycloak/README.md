# Identité machine (`client_credentials`) — ce qui a changé et comment le déployer

> Pour le script de démonstration en direct (commandes, matrice d'accès, pièges de scène),
> voir [`../RUNBOOK-M2M.md`](../RUNBOOK-M2M.md). Ce document-ci répond à une question différente :
> **qu'est-ce qui a changé dans le repo, et comment l'appliquer sur une instance Keycloak.**

---

## 1. Le changement, en une phrase

Le realm `bzhcamp` ne connaissait que des identités **humaines** (rôle *realm*, flux
`authorization_code`/ROPC). On lui a ajouté une identité **machine** (rôle *client*, flux
`client_credentials`), avec un endpoint dédié côté service pour qu'elle reste distincte de
l'identité humaine plutôt que de s'y substituer.

## 2. Fichiers ajoutés ou modifiés

| Fichier | Avant | Après |
|---|---|---|
| `demo/realm-export.json` | existait (source du build Docker, mal placée) | **supprimé** — doublon |
| `demo/keycloak/import/realm-export.json` | n'existait pas (dossier vide) | **nouveau** — source unique, cible réelle du `COPY` du `Dockerfile` |
| `demo/keycloak/apply-m2m.sh` | n'existait pas | **nouveau** — applique le changement à un realm déjà démarré, sans le détruire |
| `demo/Dockerfile` | `CMD` JSON sur 2 lignes sans `\` → `docker compose build keycloak` échouait | corrigé (continuation de ligne) |
| `demo/service-springboot-rest/.../config/SecurityConfig.java` | une seule règle `/planets` | + règle `/internal/**` sur le rôle client machine |
| `demo/service-springboot-rest/.../web/InternalPlanetsController.java` | n'existait pas | **nouveau** — endpoint `/internal/planets` |
| `demo/service-springboot-rest/.../web/PlanetsServiceController.java` | journalisait l'en-tête `Authorization` en clair | journalise `sub`/`jti` seulement (correction de sécurité associée) |
| `demo/app-springboot/.../config/M2mClientConfig.java` | n'existait pas | **nouveau** — manager OAuth2 `client_credentials` |
| `demo/app-springboot/.../service/M2mPlanetsService.java` | n'existait pas | **nouveau** — appel sortant machine |
| `demo/app-springboot/.../web/M2mController.java` | n'existait pas | **nouveau** — endpoint de démo `/m2m/planets` |
| `demo/app-springboot/src/main/resources/application*.yml` | une seule registration Spring | + registration `keycloak-m2m` et `planets.service.internal-url` |
| `postman/bzhcamp-security.postman_collection.json` | 10 dossiers | + dossier `09 — M2M / client_credentials` (6 requêtes) |
| `postman/environment.postman_environment.json` | — | + `m2m_client_id`, `m2m_client_secret`, `m2m_access_token` |
| `postman/run-newman.sh` | — | relaie `M2M_CLIENT_SECRET` |

## 3. Objets Keycloak ajoutés dans le realm

```
app-m2m             client confidentiel, serviceAccountsEnabled=true,
                     aucun flux navigateur (standardFlow/directAccessGrants = false)
service-planets     client représentant l'API aval : porte le rôle machine, sert d'audience
  └─ rôle client    planets.read
service-account-app-m2m   utilisateur technique du client app-m2m
  └─ affectation    planets.read (sur service-planets)
mapper d'audience   "service-planets" ajouté sur app-m2m, app-springboot, keycloak-angular
```

Aucun rôle ni endpoint humain existant n'est modifié : `/planets` reste identique, le flux de
login navigateur n'est pas touché.

## 4. Comment déployer ce changement

Deux chemins, selon l'état de l'instance Keycloak visée.

### Chemin A — instance neuve (base Postgres vide)

`--import-realm` (déjà dans le `CMD` du `Dockerfile`) importe `realm-export.json` automatiquement
au premier démarrage. Rien à faire de plus que :

```bash
cd demo
docker compose up -d --build
```

### Chemin B — instance déjà démarrée (base Postgres existante)

`--import-realm` **ne rejoue jamais** sur une base déjà peuplée : modifier le fichier d'export
n'a alors aucun effet observable. Appliquer le changement via l'Admin CLI, sans toucher à la base :

```bash
cd demo
docker compose up -d           # si pas déjà lancé
./keycloak/apply-m2m.sh        # idempotent — relançable sans effet de bord
```

Le script affiche en sortie le secret généré du client `app-m2m` :

```bash
export M2M_CLIENT_SECRET='<secret affiché>'
```

Variables d'environnement reconnues par le script (valeurs par défaut entre parenthèses) :

| Variable | Rôle |
|---|---|
| `KC_CONTAINER` (`demo-keycloak-1`) | nom du conteneur Keycloak cible |
| `KC_REALM` (`bzhcamp`) | realm à modifier |
| `KC_URL` (`http://localhost:8080`) | URL de l'API Admin |
| `KC_ADMIN` / `KC_ADMIN_PASSWORD` (`admin`/`admin`) | identifiants admin du realm `master` |

> Si `admin`/`admin` est refusé (`invalid_user_credentials`), le mot de passe a été changé depuis
> le premier démarrage — fournir le vrai mot de passe via `KC_ADMIN_PASSWORD`, ou reconstruire
> avec `docker compose down -v && docker compose up -d --build` (⚠️ détruit la base existante).

### Dans les deux cas — démarrer les applications

```bash
export M2M_CLIENT_SECRET='<secret app-m2m>'
export KEYCLOAK_CLIENT_SECRET='<secret app-springboot>'

cd demo/service-springboot-rest && mvn spring-boot:run   # :8091 — expose /internal/planets
cd demo/app-springboot         && mvn spring-boot:run    # :8090 — expose /m2m/planets
```

## 5. Vérifier que le changement est bien appliqué

```bash
curl -s -X POST http://localhost:8080/realms/bzhcamp/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=app-m2m -d client_secret="$M2M_CLIENT_SECRET" \
  | jq .access_token
```

Une chaîne JWT en retour = le changement est actif. Une erreur `unauthorized_client` ou
`invalid_client` = revoir le chemin A/B ci-dessus. Table de dépannage complète dans le runbook,
§6.

## 6. Rétrocompatibilité

- Aucun changement par défaut pour les flux existants : `/planets`, le login navigateur, et le
  client `keycloak-angular` ne sont pas modifiés.
- La validation d'audience du service reste **opt-in** (`KEYCLOAK_AUDIENCE` vide = désactivée,
  comme avant) ; ce changement rend seulement cette option utilisable (mappers en place).
- `demo/realm-export.json` a été supprimé — toute référence externe à ce chemin (scripts,
  documentation) doit être mise à jour vers `demo/keycloak/import/realm-export.json`.
