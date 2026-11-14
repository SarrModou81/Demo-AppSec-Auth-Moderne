# Collection Postman — Sécurité des APIs (POC Keycloak/OIDC)

Suite de tests de **sécurité** pour le POC `bzhcamp` (Keycloak + Spring Boot + Angular).

| Fichier | Rôle |
|---|---|
| `bzhcamp-security.postman_collection.json` | La collection (9 dossiers, ~25 requêtes de test) |
| `environment.postman_environment.json` | L'environnement `Local` (URLs, identifiants, variables de jetons) |
| `run-newman.sh` | Exécution headless pour la CI (rapport CLI + JUnit XML) |

## Ce qui est testé

Chaque test prouve une propriété : **la décision d'autorisation est invariante par rapport à tout ce que le client contrôle** (jeton, en-têtes, Origin, méthode, chemin).

| Dossier | Cible | Menaces STRIDE couvertes |
|---|---|---|
| **00 — Setup / Fixtures** | Admin API `:8080` | Provisionne un utilisateur sans rôle (pour le test 403) |
| **01 — Acquisition des jetons** | Keycloak `:8080` | ROPC — obtient les jetons réutilisés ensuite |
| **02 — Authentification & intégrité** | service `:8091` | `alg:none`, rôles trafiqués, issuer étranger, jeton malformé, bearer en query |
| **03 — Autorisation / RBAC** | service `:8091` | 200 (rôle user) vs **403** (sans rôle) vs **jamais 500** (NPE) |
| **04 — Audience / confused deputy** | service `:8091` | Validation d'audience (opt-in — testée dans les deux états) |
| **05 — Transport & divulgation** | service `:8091` | CORS origine hostile, exposition Swagger, fuite de stacktrace |
| **06 — App double rôle** | app `:8090` | Protection `/planets`, largeur de la règle `/*` permitAll |
| **07 — Durcissement Keycloak** | Keycloak `:8080` | redirect_uri, PKCE, énumération d'utilisateurs, Admin API 401 |
| **08 — Session / refresh** | Keycloak `:8080` | Révocation du refresh token au logout |
| **09 — M2M / client_credentials** | Keycloak `:8080` + service `:8091` | Identité machine : jeton délivré, rôle client, audience, **et les deux 403 croisés** qui prouvent la séparation humain/machine |
| **99 — Teardown** | Admin API `:8080` | Nettoyage de la fixture (optionnel) |

### Deux niveaux de sévérité

- **`[FAIL]`** — contrôle de sécurité **dur**. Une violation fait **échouer** le test (et le build CI, exit code ≠ 0).
- **`[WARN]`** — constat **informatif** / durcissement recommandé (audience désactivée par défaut, PKCE absent, Swagger public…). N'échoue **jamais** ; le message apparaît dans la sortie (`console.warn`).

Ce modèle à deux niveaux évite qu'un durcissement « conseillé mais non bloquant » ne casse le pipeline, tout en le rendant visible.

## Prérequis

1. **Démarrer la stack** :
   ```bash
   cd demo
   docker compose up -d          # Keycloak :8080, Postgres, Mailpit
   ```
   > L'export du realm est à `demo/keycloak/import/realm-export.json` — l'emplacement attendu par le `Dockerfile` (source unique de vérité).
   >
   > ⚠️ `--import-realm` n'importe le realm qu'au **premier** démarrage, sur une base vide. Si le volume `demo_pgdata` existe déjà, toute modification de l'export est ignorée : lancer `demo/keycloak/apply-m2m.sh` (non destructif) ou `docker compose down -v` (destructif). Voir `demo/RUNBOOK-M2M.md`.

2. **Lancer les deux applications Spring Boot** (JDK 17) :
   ```bash
   cd demo/service-springboot-rest && ./mvnw spring-boot:run   # :8091
   cd demo/app-springboot        && ./mvnw spring-boot:run     # :8090  (export KEYCLOAK_CLIENT_SECRET=...)
   ```

3. **Récupérer le secret du client** `app-springboot` : console Keycloak → `bzhcamp` → Clients → `app-springboot` → Credentials → *Client secret*.

4. **Pour le dossier 09 (M2M)** : créer l'identité machine et récupérer son secret :
   ```bash
   cd demo && ./keycloak/apply-m2m.sh      # idempotent ; affiche le secret de app-m2m
   export M2M_CLIENT_SECRET='<secret affiché>'
   ```
   Renseigner `m2m_client_secret` dans l'environnement Postman (ou exporter la variable pour Newman).

## Utilisation dans Postman (interactif)

1. **Importer** les deux fichiers (`Import` → glisser les deux JSON).
2. Sélectionner l'environnement **« bzhcamp — Local (POC Keycloak) »** (coin haut-droit).
3. Renseigner la variable d'environnement **`client_secret`** (valeur *current*, pas *initial* — elle n'est pas committée).
4. Lancer la collection entière via le **Collection Runner**, **dans l'ordre des dossiers** (00 → 99).
   - Cochez *Keep variable values* pour conserver les jetons entre requêtes.

> Les dossiers 00 et 01 doivent passer **en premier** : ils provisionnent la fixture et obtiennent les jetons que les dossiers suivants réutilisent (`access_token`, `norole_access_token`, etc.).

## Exécution headless (Newman / CI)

```bash
cd postman
export CLIENT_SECRET="<secret du client app-springboot>"
./run-newman.sh
```

Le script installe Newman si besoin (via `bunx`), injecte `client_secret` sans l'écrire sur disque, et produit :
- un rapport CLI,
- un rapport **JUnit** `postman/reports/junit.xml` (consommable par Jenkins `junit`).

### Intégration au `Jenkinsfile`

Le pipeline actuel fait du SAST/SCA (OWASP DC, Sonar, Trivy) mais **aucun test d'API dynamique**. Ajouter, après le déploiement UAT, un étage :

```groovy
stage('UAT - Tests sécurité API (Newman)') {
    steps {
        sh '''
            cd postman
            CLIENT_SECRET="$KEYCLOAK_CLIENT_SECRET" \
            KEYCLOAK_BASE="http://keycloak-uat:8080" \
            SERVICE_BASE="http://poc-keycloak-service-uat:8091" \
            APP_BASE="http://poc-keycloak-app-uat:8090" \
            ./run-newman.sh
        '''
    }
    post {
        always { junit 'postman/reports/junit.xml' }
    }
}
```

Les variables `KEYCLOAK_BASE` / `SERVICE_BASE` / `APP_BASE` surchargent l'environnement pour pointer vers la bonne cible (voir `run-newman.sh`).

## Notes & limites

- **Audience (dossier 04)** : la validation est *opt-in* (`keycloak.audience` vide par défaut dans ce POC). Pour un vrai test inter-services, configurer un *Audience mapper* côté Keycloak, puis renseigner `expected_audience` + `KEYCLOAK_AUDIENCE` côté service. Voir le backlog B3 de `../ISA.md`.
- **Jetons forgés** : les scripts de pré-requête fabriquent les jetons d'attaque (`alg:none`, claims réécrits) en JavaScript natif Postman — aucun outil externe requis. Pour les jetons au payload réécrit, la signature RS256 casse de toute façon : c'est le comportement attendu (rejet 401).
- **Refresh token (dossier 08)** : consomme le refresh token via un logout. Relancer le dossier 01 avant de rejouer la suite complète.
- **Portée** : collection HTTP — ne couvre pas les menaces d'infrastructure (vol de disque Postgres, compromission d'hôte). Voir la section *QUESTIONS & ASSUMPTIONS* du threat model dans `../ISA.md`.
- **Aucun secret n'est committé** : secrets et mots de passe sont des variables d'environnement de type `secret`, à valeur vide dans le fichier versionné.
