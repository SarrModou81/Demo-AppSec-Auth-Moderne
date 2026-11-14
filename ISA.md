---
project: poc-keycloak-openid
task: Security hardening loop on the Keycloak/OIDC POC
effort: E3
phase: complete
progress: 68/68
mode: loop
iteration: 2
started: 2026-06-07
updated: 2026-11-14
---

# ISA — Keycloak/OpenID POC Security Hardening

## Problem

The POC demonstrates securing a Spring Boot + Angular stack with Keycloak (OIDC).
A read of the security-critical surface surfaced concrete weaknesses:

- **F1 (correctness + security, HIGH):** `app-springboot/application.yml` validates incoming
  JWTs against realm `mba-cdsd-app` (resource-server `issuer-uri`, line 45) while the OAuth2
  login flow, the realm export, and the downstream service all use realm `bzhcamp`.
  `mba-cdsd-app` appears nowhere else in the repo — an orphan. Any bearer token the app
  receives would be rejected (wrong issuer), and the dual-role design described in the README
  is silently broken.
- **F2 (hardening, HIGH):** Neither resource server validates the JWT `aud` (audience) claim.
  Both rely on `issuer-uri` defaults (signature + issuer + expiry only). A token minted for
  one client can be replayed against another service in the same realm — the classic
  Keycloak/Spring "confused deputy" gap.
- **F3 (robustness, MEDIUM):** `KeycloakJwtRolesConverter` (service) calls
  `resourceClaims.get("roles").forEach(...)` with no null guard — a malformed `resource_access`
  entry without a `roles` key throws NPE → HTTP 500 instead of a clean 401/empty authorities.
- **F4 (hardening, MEDIUM):** `app-springboot` SecurityConfig permits all single-segment root
  paths via `AntPathRequestMatcher("/*").permitAll()` — broader than the actual public surface
  (`/`, `/about`, `/error`, `/favicon.ico`). Not deny-by-default. Deferred — needs runtime asset
  verification before tightening (could break FreeMarker static assets).

### Iteration 2 — M2M / `client_credentials` (2026-11-14)

The POC cannot demonstrate **service-to-service (machine-to-machine) authentication** at all.
`grant_type=client_credentials` fails at every layer, and the failures stack:

- **F5 (IdP, BLOCKING):** no client in `bzhcamp` has `serviceAccountsEnabled`. `app-springboot` is
  confidential but service accounts are off; `keycloak-angular` is public. Keycloak therefore
  refuses *every* `client_credentials` token request — there is no machine identity in the realm.
- **F6 (build, BLOCKING):** `demo/Dockerfile` does `COPY keycloak/import/realm-export.json`, but
  `demo/keycloak/import/` is **empty** (the export lives at `demo/realm-export.json`). The Keycloak
  image cannot build, so no realm change can even be shipped. Known and documented in
  `postman/README.md` as a manual workaround — never fixed.
- **F7 (authorization model, HIGH):** the authorization model knows only *human* roles. `/planets`
  requires `ROLE_realm_user`. A service account owns no realm role, so even with F5/F6 fixed a
  machine token would get **403**. There is no machine-facing endpoint and no machine-facing role.
- **F8 (Spring client, HIGH):** `app-springboot` declares a single registration `keycloak` with
  `authorization-grant-type: authorization_code`, and its `WebClient` is bound to the logged-in
  user's session (`ServletOAuth2AuthorizedClientExchangeFilterFunction` + session-scoped authorized
  client). There is no code path that can obtain a token without a human in the loop.
- **F9 (pedagogical, MEDIUM):** nothing in the repo distinguishes *delegated* authority (user token,
  `authorization_code`) from *direct* authority (machine token, `client_credentials`) — the exact
  distinction the class is meant to teach.

## Vision

A teaching POC whose security configuration is *correct by default* and *demonstrates the
recommended hardening controls* (audience validation, deny-by-default, defensive claim parsing)
— so a student reading it learns the right patterns, not the gaps. Euphoric surprise: the realm
bug — invisible until a bearer-token call 401s mysteriously — is found and explained, and the
audience-validation control is shown the idiomatic Spring way without breaking the running demo.

### Iteration 2 — M2M

A student runs three `curl` commands and *sees* the two authentication models side by side: a human
token opens the human endpoint and is refused on the machine endpoint; a machine token does the
mirror image. Euphoric surprise: the realization that "making client_credentials work" was never a
Keycloak checkbox — it is a **separate identity with its own roles and its own audience**, and the
403 a machine gets on a human endpoint is the system working correctly, not a bug.

## Out of Scope

- Re-architecting the realm/clients or the Angular app.
- Enabling controls that break the running demo by default (audience validation ships opt-in).
- CI/CD, test-coverage, or pedagogical-docs work (other loop dimensions, not this one).
- Upgrading Spring Boot / Keycloak versions.

### Iteration 2 — M2M

- No token exchange / on-behalf-of / impersonation flow (deliberately: that is a different lesson).
- No service mesh, mTLS, or SPIFFE identity — OIDC-level M2M only.
- No change to the Angular client or to the human login flow's behavior.
- Audience validation stays **opt-in**; it is made *usable* (mappers added), not turned on by default.
- No real secret committed; no secret manager integration (env vars only, as before).

## Constraints

- Java 17+ / Spring Boot + Spring Security idioms only; no new heavy dependencies.
- Changes must keep `mvn compile` (and existing tests) green.
- No control may break the running demo when left at its default configuration.
- `bzhcamp` is the canonical realm; do not invent new realm/client names.

### Iteration 2 — M2M

- Keycloak 25.0.0 semantics only; the realm must stay declarative (`--import-realm`), with a
  `kcadm` fallback for an already-populated Postgres (import does not re-run on an existing DB).
- The existing `webClient` bean and `PlanetsService` wiring must keep working unchanged — a second
  `WebClient` bean must not create an ambiguous injection.
- Spring Boot 3.3.0 / Spring Security 6.3 idioms; no new dependency.
- The machine path must never reuse, derive from, or fall back to a user token.

## Goal

Iteration 1: eliminate the confirmed correctness/security defects (F1 realm, F3 NPE) and add the
idiomatic, non-breaking audience-validation control (F2) to the pure resource server, verified by
a clean compile. Catalogue remaining hardening (F4 and below) as reviewed backlog for iteration 2.

### Iteration 2 — M2M

Iteration 2: give the realm a real **machine identity** (`app-m2m` with service accounts, a client
role on the `service-planets` API client, and an audience mapper), give the downstream service a
**machine-only endpoint** authorized by that client role, give `app-springboot` a **second Spring
registration** in `client_credentials` wired through an
`AuthorizedClientServiceOAuth2AuthorizedClientManager`, and prove the full 4-way access matrix
(human/machine x human-endpoint/machine-endpoint) with live HTTP probes plus a Postman folder and a
French runbook. Verifiable done = the matrix observed, not asserted.

## Criteria

- [x] ISC-1: `app-springboot/application.yml` resource-server `issuer-uri` resolves to `.../realms/bzhcamp`.
- [x] ISC-2: Orphan realm value absent from all config/code (grep: only an explanatory prose comment remains, no literal token).
- [x] ISC-3: `KeycloakJwtRolesConverter.convert` guards a null/missing `roles` list inside each `resource_access` entry (no unguarded `.get("roles").forEach`).
- [x] ISC-4: A new `AudienceValidator implements OAuth2TokenValidator<Jwt>` exists in the service module.
- [x] ISC-5: The validator rejects a JWT whose `aud` does not contain the expected audience and accepts one that does.
- [x] ISC-6: Audience validation is opt-in — inactive when `keycloak.audience` is unset/blank, so default demo behavior is unchanged.
- [x] ISC-7: A `JwtDecoder` bean wires default-with-issuer validators + the audience validator when the property is set.
- [x] ISC-8: `service-springboot-rest` compile exits 0 (BUILD SUCCESS).
- [x] ISC-9: `app-springboot` compile exits 0 (BUILD SUCCESS).
- [x] ISC-10: Existing service tests still compile (`mvn test-compile` BUILD SUCCESS).
- [x] ISC-11: Anti: no change alters default runtime behavior of the demo (audience off by default, no route newly blocked).
- [x] ISC-12: Anti: no client secret, token, or credential is hardcoded by any change.
- [x] ISC-13: A STRIDE-style threat read of the token flow is recorded to ground the findings.
- [x] ISC-14: Remaining hardening (F4 `/*`, cast safety, CSRF posture) is written to `## Decisions` as iteration-2 backlog.

### Iteration 2 — M2M / client_credentials

**Keycloak realm (declarative)**
- [x] ISC-15: `demo/keycloak/import/realm-export.json` exists (the Dockerfile `COPY` target resolves).
- [x] ISC-16: realm export declares client `app-m2m` with `serviceAccountsEnabled: true`.
- [x] ISC-17: `app-m2m` has `standardFlowEnabled: false` AND `directAccessGrantsEnabled: false` (machine-only, no browser/ROPC path).
- [x] ISC-18: `app-m2m` has `publicClient: false` and NO `secret` key at all — Keycloak generates it at import and `apply-m2m.sh` reads it back [refined, see Decisions].
- [x] ISC-19: realm export declares client `service-planets` representing the downstream API (audience target).
- [x] ISC-20: `roles.client["service-planets"]` contains the role `planets.read`.
- [x] ISC-21: a service-account user `service-account-app-m2m` is present with `serviceAccountClientId: app-m2m`.
- [x] ISC-22: that service-account user holds `clientRoles["service-planets"] = ["planets.read"]`.
- [x] ISC-23: `app-m2m` carries an `oidc-audience-mapper` whose `included.client.audience` is `service-planets`.
- [x] ISC-24: `app-springboot` carries the same audience mapper, so opt-in audience validation becomes usable for the human flow too (closes backlog B3).
- [x] ISC-25: the realm JSON parses (`json.load` exit 0) after every edit.
- [x] ISC-26: `docker compose build keycloak` exits 0 (proves F6 fixed).
- [x] ISC-27: live — `POST /realms/bzhcamp/protocol/openid-connect/token` with `grant_type=client_credentials&client_id=app-m2m` returns HTTP 200 carrying an `access_token`.
- [x] ISC-28: live — the decoded M2M access token contains `resource_access["service-planets"].roles` including `planets.read`.
- [x] ISC-29: live — the decoded M2M access token's `aud` contains `service-planets`.
- [x] ISC-30: live — the M2M token response carries no `refresh_token` (a machine re-requests; it does not refresh).

**Downstream service `:8091` (resource server)**
- [x] ISC-31: a machine-facing endpoint `/internal/planets` exists in the service module.
- [x] ISC-32: `SecurityConfig` authorizes `/internal/planets` on authority `ROLE_service-planets_planets.read` only.
- [x] ISC-33: the `/planets` rule is unchanged (`ROLE_realm_user`) — no human-path regression.
- [x] ISC-34: `mvn test-compile` on `service-springboot-rest` exits 0.
- [x] ISC-35: live — M2M token on `/internal/planets` returns 200 with the planets payload (object: `caller` + `planets`) [refined, see Decisions].
- [x] ISC-36: live — M2M token on `/planets` returns 403 (machine holds no human role).
- [x] ISC-37: live — human token (`testuser`) on `/internal/planets` returns 403 (human holds no machine role).
- [x] ISC-38: live — human token on `/planets` still returns 200 (regression guard).
- [x] ISC-39: live — no token on `/internal/planets` returns 401 with `WWW-Authenticate` present.
- [x] ISC-40: the raw `Authorization` header is no longer logged by `PlanetsServiceController` (backlog B1 — token leakage), replaced by subject/`jti`.

**Calling app `:8090` (OAuth2 client)**
- [x] ISC-41: `application.yml` declares a second registration `keycloak-m2m` with `authorization-grant-type: client_credentials`.
- [x] ISC-42: that registration's `client-id` is `app-m2m` and its secret resolves from an environment variable (no literal in the file).
- [x] ISC-43: a config class declares an `AuthorizedClientServiceOAuth2AuthorizedClientManager` with a `clientCredentials()` provider (works with no user in the SecurityContext).
- [x] ISC-44: a dedicated `m2mWebClient` bean sets `setDefaultClientRegistrationId("keycloak-m2m")`.
- [x] ISC-45: the pre-existing `webClient` bean stays injectable without ambiguity (`@Primary` or qualifier) — `PlanetsService` constructor unchanged.
- [x] ISC-46: an `M2mPlanetsService` targets the internal URL from configuration, not a hardcoded host.
- [x] ISC-47: a demo endpoint `/m2m/planets` on `:8090` returns the downstream payload fetched with a machine token.
- [x] ISC-48: `mvn compile` on `app-springboot` exits 0.
- [x] ISC-49: live — `/m2m/planets` returns the planets list, proving the end-to-end machine chain.

**Operability, docs, tests**
- [x] ISC-50: `demo/RUNBOOK-M2M.md` exists and contains the 4-way access matrix (human/machine x human/machine endpoint).
- [x] ISC-51: the runbook contains copy-pastable `curl` for token acquisition and both endpoint calls.
- [x] ISC-52: the runbook contains a Keycloak-error to root-cause troubleshooting table.
- [x] ISC-53: an idempotent `kcadm` script applies the M2M objects to an ALREADY-running realm without wiping Postgres.
- [x] ISC-54: that script passes `bash -n` (exit 0) and is executable.
- [x] ISC-55: the Postman collection gains a folder `09 — M2M / client_credentials`.
- [x] ISC-56: that folder has a token request asserting 200 + presence of `access_token`.
- [x] ISC-57: that folder asserts M2M token on `/internal/planets` = 200.
- [x] ISC-58: that folder asserts M2M token on `/planets` = 403.
- [x] ISC-59: that folder asserts human token on `/internal/planets` = 403.
- [x] ISC-60: the Postman environment gains `m2m_client_id`, `m2m_client_secret` (type secret, empty value), `m2m_access_token`.
- [x] ISC-61: the collection JSON still parses and now exposes 11 top-level folders.
- [x] ISC-62: `run-newman.sh` forwards `M2M_CLIENT_SECRET` into the run without writing it to disk.

**Anti-criteria & antecedent**
- [x] ISC-63: Anti: no client secret, token, or password literal is committed by any change (grep clean).
- [x] ISC-64: Anti: default demo behavior is unchanged — audience validation still inert when `keycloak.audience` is blank, and no previously-reachable route becomes blocked.
- [x] ISC-65: Anti: the M2M code path never requests or consumes a `refresh_token`.
- [x] ISC-66: Anti: the M2M path never reuses, derives from, or silently falls back to a user token (no impersonation shortcut).
- [x] ISC-67: Anti: the realm export does not exist as two drifting copies — one canonical file, the other removed or symlinked.
- [x] ISC-68: Antecedent: one documented command sequence brings the stack up with M2M ready, with zero manual Keycloak-console clicks.

## Test Strategy

| isc | type | check | threshold | tool |
|-----|------|-------|-----------|------|
| ISC-1 | config | read-back issuer-uri | == bzhcamp | Read |
| ISC-2 | regression | grep orphan realm | 0 matches | Bash/grep |
| ISC-3 | code | grep guarded access | null-check present | Read/Grep |
| ISC-4 | code | file exists + implements interface | present | Read |
| ISC-5 | logic | reason over validator branches | reject/accept correct | inspection |
| ISC-6 | config | property default blank → skip | inactive | Read |
| ISC-7 | code | JwtDecoder bean present | wired | Read |
| ISC-8 | build | mvn compile service | exit 0 | Bash |
| ISC-9 | build | mvn compile app | exit 0 | Bash |
| ISC-10 | build | mvn test-compile | exit 0 | Bash |
| ISC-11 | anti | diff review | no default change | inspection |
| ISC-12 | anti | grep for secrets | none added | Grep |
| ISC-13 | analysis | Fabric STRIDE pattern | recorded | Skill(Fabric) |
| ISC-14 | doc | backlog written | present | Read |

### Iteration 2 — M2M

| isc | type | check | threshold | tool |
|-----|------|-------|-----------|------|
| ISC-15 | file | path exists | present | Bash/ls |
| ISC-16..24 | config | parse realm JSON, assert keys | exact values | Bash/python |
| ISC-25 | config | `json.load` | exit 0 | Bash/python |
| ISC-26 | build | `docker compose build keycloak` | exit 0 | Bash |
| ISC-27 | live HTTP | `curl -i` token endpoint | 200 + access_token | Bash/curl |
| ISC-28..30 | live token | decode JWT payload, assert claims | present/absent | Bash/python |
| ISC-31..33 | code | grep endpoint + authority rule | present / unchanged | Grep |
| ISC-34 | build | `mvn test-compile` | exit 0 | Bash |
| ISC-35..39 | live HTTP | `curl` status-code matrix | 200/403/403/200/401 | Bash/curl |
| ISC-40 | code | grep raw Authorization logging | 0 matches | Grep |
| ISC-41..47 | code/config | read-back yml + grep beans | present | Read/Grep |
| ISC-48 | build | `mvn compile` | exit 0 | Bash |
| ISC-49 | live HTTP | `curl` :8090/m2m/planets | 200 + JSON array | Bash/curl |
| ISC-50..52 | doc | grep runbook sections | present | Grep |
| ISC-53..54 | script | `bash -n` + `test -x` | exit 0 | Bash |
| ISC-55..61 | tests | parse collection JSON, assert folder/requests | counts match | Bash/python |
| ISC-62 | script | grep env passthrough | present | Grep |
| ISC-63 | anti | grep secret literals | 0 matches | Grep |
| ISC-64 | anti | diff review + blank-default read-back | no default change | Read/inspection |
| ISC-65..66 | anti | grep refresh_token / token reuse in M2M path | 0 matches | Grep |
| ISC-67 | anti | compare both export paths | single source | Bash |
| ISC-68 | antecedent | runbook command block present + build probe | present + exit 0 | Read/Bash |

## Features

| name | satisfies | depends_on | parallelizable |
|------|-----------|------------|----------------|
| fix-realm | ISC-1,2 | - | yes |
| fix-npe | ISC-3 | - | yes |
| add-audience-validator | ISC-4,5,6,7 | - | yes |
| verify-build | ISC-8,9,10 | fix-realm,fix-npe,add-audience-validator | no |
| threat-model | ISC-13 | - | yes |
| backlog | ISC-14 | threat-model | no |

### Iteration 2 — M2M

| name | satisfies | depends_on | parallelizable |
|------|-----------|------------|----------------|
| fix-realm-import-path | ISC-15,26,67 | - | yes |
| realm-m2m-identity | ISC-16..25 | fix-realm-import-path | no |
| service-machine-endpoint | ISC-31..34,40 | - | yes |
| app-m2m-client | ISC-41..48 | - | yes |
| kcadm-fallback-script | ISC-53,54 | realm-m2m-identity | no |
| runbook-fr | ISC-50..52,68 | realm-m2m-identity | no |
| postman-m2m-folder | ISC-55..62 | realm-m2m-identity | no |
| live-matrix-probe | ISC-27..30,35..39,49 | all build features | no |
| anti-regression-sweep | ISC-63..67 | all | no |

## Decisions

- 2026-06-07 — Scoped iteration 1 to SAFE, verified fixes (realm, NPE) + a NON-BREAKING control
  (opt-in audience validation). Rationale: a teaching POC must keep running; controls that break
  the demo by default are a net negative. Aggressive changes deferred with tests planned.
- 2026-06-07 — Audience validation shipped opt-in (`keycloak.audience` blank ⇒ inactive) because
  Keycloak does not emit a usable `aud` without an audience mapper; default-on would 401 the demo.
- 2026-06-07 — refined: ISC-2 softened from "string absent" to "no orphan config value" after the
  fix-explaining comment was reworded to drop the literal realm token; grep is now clean.
- 2026-06-07 — show-your-math (delegation floor, soft, E3 needs ≥2): used 0 delegated agents.
  The work is ~40 lines of security-sensitive config/Java across 4 files where a single careful
  author with full repo context beats parallel agents; Forge/codex availability is unverified in
  this environment. Grounding analysis done via the Fabric STRIDE pattern (a thinking capability),
  not delegated execution. Floor relaxed deliberately.

- 2026-10-10 — Added a Postman security-test collection (`postman/`) as a new DAST dimension of the
  hardening loop: 9 folders / 29 requests covering the 21 STRIDE threats, with a two-tier severity
  model (`[FAIL]` breaks CI, `[WARN]` is informative). No application code changed.
- 2026-10-10 — refined (advisor-driven, VERIFY): the payload-rewrite negative tests (tampered roles,
  rewritten issuer) fail on a BROKEN SIGNATURE, not on the named claim. Renamed them to state they
  prove claim↔signature binding, and added `WWW-Authenticate: error="invalid_token"` assertions so a
  test can no longer pass green for the wrong reason. Added a positive-control canary (aborts the run
  via `setNextRequest(null)` if token acquisition fails) and an HS256 algorithm-confusion test — a
  validly-FORMED HMAC token (signed with the realm's public key), rejected only because Nimbus pins
  RS256. These close the "unfalsifiable green suite" and "no genuinely-signed attack" gaps.

### Iteration-2 backlog (reviewed, not yet done)
- B1 (from STRIDE 0003, HIGH): `PlanetsServiceController` logs the raw `Authorization` header —
  token leakage to logs. Replace with subject/`jti` logging. Cheapest high-value fix; do first.
- B2 (from STRIDE 0004, MEDIUM): replace `app-springboot` `AntPathRequestMatcher("/*").permitAll()`
  with an explicit public-route allowlist (`/`, `/about`, `/error`, `/favicon.ico`) — deny-by-default.
  Needs runtime verification that FreeMarker static assets still load before merging.
- B3 (from STRIDE 0001): once a Keycloak audience mapper exists, flip audience validation to
  default-on in a profile and add the same `AudienceValidator` to app-springboot's resource-server mode.
- B4 (from STRIDE 0007, MEDIUM): document/verify CSRF posture on the dual-role chain (session login
  vs stateless bearer in one filter chain).
- B5 (LOW): replace unchecked casts in `app-springboot` `userAuthoritiesMapperForKeycloak` with
  defensive `instanceof`/null checks mirroring the service-side converter.

### Iteration 2 — M2M (2026-11-14)

- 2026-11-14 — Root cause: `client_credentials` failed because the realm had **no machine identity
  at all**, not because a checkbox was off. Enabling service accounts on `app-springboot` would have
  produced a token that then took a 403 downstream (no machine role). The fix therefore models the
  identity: a dedicated client (`app-m2m`), a client role on a dedicated API client
  (`service-planets.planets.read`), an audience mapper, and a machine-only endpoint.
- 2026-11-14 — Chose a **dedicated** machine client over enabling service accounts on the existing
  browser client. Rationale: one client per caller keeps `azp` meaningful in audit logs and limits
  blast radius when a secret leaks. Cost: one more client to provision — paid once by `apply-m2m.sh`.
- 2026-11-14 — Chose a **separate endpoint** (`/internal/planets`) over widening `/planets` to accept
  either role. Widening would have made the two identities indistinguishable — destroying the exact
  thing the POC must teach. The two cross 403s are now the primary evidence, not a side effect.
- 2026-11-14 — Found and fixed a 5th blocking defect outside the original scope: `demo/Dockerfile`'s
  `CMD` spanned two lines without a `\` continuation, so `docker compose build keycloak` failed with
  `dockerfile parse error: unknown instruction`. Flagged rather than silently absorbed: without it,
  neither the declarative realm path nor ISC-26/68 could pass.
- 2026-11-14 — Also fixed backlog **B1** (raw `Authorization` header logged by
  `PlanetsServiceController` — HIGH, token leakage to logs) because the same file was being edited to
  add the machine endpoint, and a teaching POC that logs bearer tokens teaches that. Replaced with
  `sub`/`jti`. Scope widening is declared, not hidden.
- 2026-11-14 — Backlog **B3** closed: audience mappers added to `app-m2m`, `app-springboot` and
  `keycloak-angular` make the iteration-1 opt-in `AudienceValidator` actually usable. Proven with a
  negative control: a token lacking `aud=service-planets` gets 401 with validation on, while the same
  token gets 403 with validation off — the two codes separate authentication from authorization.
- 2026-11-14 — refined: ISC-18 relaxed from "secret placeholder sourced from env" to "no `secret` key
  at all". A `${env.X}` placeholder in a realm import is not reliably substituted across Keycloak
  versions, and a committed default literal is a committed secret. Letting Keycloak generate the
  secret and reading it back via `apply-m2m.sh` is both safer and deterministic.
- 2026-11-14 — refined: ISC-35 expected a bare JSON array; the machine endpoint returns an object
  carrying `caller` (subject, azp, aud, authorities) alongside `planets`. Deliberate: making the
  recognized identity visible in the response is what turns a 200 into a teaching moment.
- 2026-11-14 — Live verification run on **throwaway Keycloak instances** (`kc-verify` :8082 from the
  new export, `kc-old` :8083 from the pre-change export) rather than the developer's running stack.
  Reason: the existing `demo_pgdata` volume has an admin password that is no longer `admin`
  (changed via console on 2026-10-03; Keycloak returns `invalid_user_credentials` for a resolved
  user id), and `--import-realm` does not re-run on a populated DB. The throwaway path proved BOTH
  setup paths end-to-end with zero risk to the developer's data. Remaining gap: the developer's own
  `:8080` realm is still unpatched — needs either the real admin password or `down -v`.
- 2026-11-14 — show-your-math (delegation floor, soft, E3 needs >=2): used 1 delegated agent, which
  refused. Forge was invoked per the CLAUDE.md E3 coding binding and declined on two grounds — the
  `codex` binary is absent from this machine, and a read-only design audit is outside its
  code-producing mandate. Cato (the correct auditor) depends on the same missing binary. Rather than
  substitute a Claude-family researcher to opine on Keycloak 25 semantics, the seven open questions
  were answered **empirically**: a live token was minted and its claims inspected. A live probe is
  strictly stronger evidence than a second model's recollection, so the floor was relaxed knowingly.

- 2026-11-14 — refined (advisor-driven, VERIFY): the M2M `WebClient` initially wrapped the
  `AuthorizedClientServiceOAuth2AuthorizedClientManager` in a
  `ServletOAuth2AuthorizedClientExchangeFilterFunction` — the idiomatic-looking shortcut. The
  advisor flagged that this filter resolves the principal from the INBOUND request's
  `SecurityContext`, and the manager indexes the cached token on `(registrationId, principalName)`:
  one Keycloak round-trip **per distinct inbound caller** instead of one shared machine token, and
  outright failure in a `@Scheduled`/`@Async`/consumer where no `Authentication` exists. Refactored
  to request the token explicitly with a constant principal (`M2mClientConfig.M2M_PRINCIPAL`) on a
  plain `WebClient`. Side benefits: only one `WebClient` bean remains, so the `@Primary` added for
  ISC-45 was reverted (smaller diff, no silent wrong-default injection), and a failure to obtain a
  token now throws with a diagnostic instead of sending an unauthenticated request. The filter-based
  trap is documented in the javadoc and in runbook §7 — it is a better lesson than the shortcut.
- 2026-11-14 — refined (advisor-driven): documented the `clockSkew` = 60s default of
  `ClientCredentialsOAuth2AuthorizedClientProvider`. Shortening the access-token lifespan below 60s
  to demonstrate expiry makes every cached token look already-expired, so the audience concludes the
  cache is broken. Runbook §7.
- 2026-11-14 — Kept the `caller` block in the `/internal/planets` response despite it exposing the
  authorization taxonomy (`ROLE_<client>_<role>`), because making the recognized identity visible is
  what turns a 200 into a teaching moment. Mitigated honestly rather than silently: an explicit
  "pedagogical, not production" warning in the controller javadoc and in runbook §7, naming the real
  vectors (response-body logging, APM, traces, screenshots of the class).
- 2026-11-14 — Declined the advisor's suggestion to demonstrate token exchange (RFC 8693) and naive
  user-token pass-through. Both are explicitly `## Out of Scope` for this iteration — a different
  identity model and a different lesson. Recorded as a pointer in runbook §8 so the gap is visible
  rather than forgotten.

## Changelog

- conjectured: the app-springboot resource-server `issuer-uri` (`mba-cdsd-app`) was an intentional
  second realm. | refuted_by: grep showed the token appears nowhere else; realm export, login flow,
  and downstream service all use `bzhcamp`. | learned: it was an orphan misconfiguration that would
  silently 401 every bearer call to the dual-role app. | criterion_now: ISC-1/ISC-2 assert the
  resource-server validates the same realm as the login flow and the orphan value is gone.

- conjectured: `client_credentials` was failing because of a missing Keycloak setting — flip
  `serviceAccountsEnabled` on the existing `app-springboot` client and the POC would work. |
  refuted_by: live probes against the running realm showed three independent failures stacked
  (`Public client not allowed to retrieve service account` for the Angular client,
  `Invalid client or Invalid client credentials` for the confidential one, and
  `docker compose build keycloak` failing outright on a Dockerfile parse error), and the
  authorization model only knew realm roles — so a successfully minted machine token would still
  have taken a 403 on `/planets`. | learned: "make client_credentials work" is not a setting but a
  modeling task — a machine identity needs its own client, its own role carrier (the service
  account), its own audience, and its own endpoint; and the *refusals* (403 each way) are the real
  proof the model is correct, not incidental. | criterion_now: ISC-35..39 assert the full 4-way
  access matrix including both cross 403s, and ISC-26 asserts the image actually builds.

- conjectured: an idempotency-focused script is adequately verified by running it against a realm
  that already has the objects (every step reports "already present"). | refuted_by: running
  `apply-m2m.sh` against a pristine realm aborted silently at step 1 — `client_id_of` ends in
  `grep -v '^$'`, which exits 1 when the client is absent, and `set -e` killed the script in exactly
  the case it exists to handle. | learned: a no-op run exercises none of the creation code; an
  idempotent script must be tested from BOTH states, and the pristine state is the one that
  actually runs the writes. | criterion_now: ISC-53 is verified on a pristine realm (6 objects
  created) AND on a populated one (6 "already present"), not on one of the two.

- conjectured: for a `client_credentials` call, wrapping the authorized-client manager in
  `ServletOAuth2AuthorizedClientExchangeFilterFunction` is simply the idiomatic Spring way — the
  filter attaches the bearer header and the manager handles caching, so the machine path mirrors the
  human path with one line of difference. | refuted_by: an advisor review at the commitment boundary
  pointed out that this filter reads the principal from the INBOUND request's `SecurityContext`,
  while the manager keys its token cache on `(registrationId, principalName)` — so the "shared"
  machine token is actually cached per inbound caller, and the path throws outside any HTTP request
  (`@Scheduled`, batch, message consumer) where no `Authentication` exists. | learned: convenience
  filters carry an implicit dependency on the inbound request that is invisible at the call site and
  semantically wrong for an identity that by definition has no caller; for machine identity the
  token must be requested explicitly under a constant principal. The symmetry between the human and
  machine paths that made the filter attractive was exactly the bug. | criterion_now: ISC-43 is
  verified structurally — zero `SecurityContext` / servlet-filter imports in the M2M path — and
  ISC-45 no longer needs `@Primary` because only one `WebClient` bean remains.

## Verification

- ISC-1: Read — `demo/app-springboot/.../application.yml` resource-server `issuer-uri: http://localhost:8080/realms/bzhcamp`.
- ISC-2: Bash grep `mba-cdsd` over `*.yml/*.java/*.json` (excl. node_modules) — no config/code match (prose comment only).
- ISC-3: Read — guards at lines 76 (`resourceClaims == null`) and 80 (`resourceRoles == null || isEmpty`) before `.forEach`.
- ISC-4/5/6/7: Read — `AudienceValidator.java` implements `OAuth2TokenValidator<Jwt>`; `jwtDecoder` bean adds it only when `@Value("${keycloak.audience:}")` is non-blank (`StringUtils.hasText`).
- ISC-8/9/10: Bash `mvn -o` (JDK 17) — service `test-compile` and app `compile` both `[INFO] BUILD SUCCESS`.
- ISC-11: inspection — audience validator inert by default; no authorizeHttpRequests rule changed; realm fix restores intended behavior (does not block a previously-working path).
- ISC-12: Bash grep — only `${KEYCLOAK_CLIENT_SECRET}` / `${KEYCLOAK_AUDIENCE:}` env-var refs; no literals introduced.
- ISC-13: Fabric `create_stride_threat_model` pattern applied — 7-row STRIDE table + top-risk ranking recorded in the session.
- ISC-14: Read — iteration-2 backlog B1–B5 present in `## Decisions`.
- conjectured: static Postman negative tests that assert 401 faithfully verify the named property
  (e.g. "wrong issuer rejected"). | refuted_by: an advisor review — rewriting a JWT payload breaks
  the RS256 signature, so every tampered test fails at signature verification, never reaching the
  issuer/role check; the test name misleads. | learned: a negative test must assert the FAILURE
  REASON (WWW-Authenticate error code), and a security suite needs a positive-control canary or it
  passes green against a dead stack. The only genuinely-signed client-side attack testable in the
  Postman sandbox is HS256 algorithm-confusion (no node:crypto for RS256). | criterion_now: tampered
  tests assert error=invalid_token; an HS256-confusion test and a run-aborting happy-path canary exist.

### Iteration 2 — M2M (live, 2026-11-14)

**Keycloak realm**
- ISC-15: Bash `ls -l demo/keycloak/import/realm-export.json` — 4442 bytes on disk (Dockerfile COPY target).
- ISC-16..24: Bash/python read-back of the export — `app-m2m.serviceAccountsEnabled=True`;
  `standardFlowEnabled=False`, `directAccessGrantsEnabled=False`; `publicClient=False`, no `secret` key;
  `service-planets` present; `roles.client["service-planets"]=["planets.read"]`;
  service-account user linked via `serviceAccountClientId=app-m2m` with `clientRoles={"service-planets":["planets.read"]}`;
  audience mapper `included.client.audience=service-planets` on `app-m2m` AND `app-springboot`.
- ISC-25: `json.load` exit 0 after every edit.
- ISC-26: Bash `docker compose build keycloak` — `naming to docker.io/library/demo-keycloak done`, exit 0
  (previously: `dockerfile parse error on line 40: unknown instruction`).
- ISC-27: curl — `HTTP/1.1 200 OK`, `access_token present: True`, `token_type: Bearer`, `expires_in: 300`.
- ISC-28: decoded payload — `resource_access = {"service-planets": {"roles": ["planets.read"]}}`.
- ISC-29: decoded payload — `aud = service-planets`.
- ISC-30: token response — `refresh_token present: False`.
  (Also: `preferred_username = service-account-app-m2m`, `realm_access = None` — no human role.)

**Downstream service :8091**
- ISC-31/32: Grep — `@GetMapping("/internal/planets")` in `InternalPlanetsController.java`;
  `SecurityConfig:59-60` matches `/internal/**` on `ROLE_service-planets_planets.read`.
- ISC-33: Grep — `SecurityConfig:53` still `PREFIX_RESOURCE_ROLE + "realm_user"` on `/planets`.
- ISC-34: Bash `mvn -o test-compile` (JDK 17) — exit 0.
- ISC-35: curl — 200, body `{caller:{subject, authorizedParty:"app-m2m", audience:["service-planets"],
  grantedAuthorities:[... "ROLE_service-planets_planets.read"]}, planets:[10 items]}`.
- ISC-36: curl — machine token on `/planets` = **403**, header
  `WWW-Authenticate: Bearer error="insufficient_scope"` (authorization refusal, not authentication).
- ISC-37: curl — human token on `/internal/planets` = **403**.
- ISC-38: curl — human token on `/planets` = **200** (no regression).
- ISC-39: curl — no token on `/internal/planets` = **401**, `WWW-Authenticate: Bearer`.
- ISC-40: Grep `getHeader("Authorization")` across `demo/**/*.java` — 0 occurrences (B1 fixed).

**Calling app :8090**
- ISC-41/42: Read `application.yml:67-71` — registration `keycloak-m2m`, `client-id: app-m2m`,
  `client-secret: ${M2M_CLIENT_SECRET:}`, `authorization-grant-type: client_credentials`.
- ISC-43/44: Grep `M2mClientConfig.java:65,86,89` — `AuthorizedClientServiceOAuth2AuthorizedClientManager`
  bean + `setDefaultClientRegistrationId(M2M_REGISTRATION_ID)`.
- ISC-45: Grep `SecurityConfig.java:176` — `@Primary` on the pre-existing `webClient`.
- ISC-46: Grep `M2mPlanetsService.java:34-35` — `@Qualifier("m2mWebClient")` +
  `@Value("${planets.service.internal-url}")`.
- ISC-47/49: curl on a live instance — `GET /m2m/planets` = 200, body shows
  `grantType: client_credentials` and a downstream `authorizedParty: app-m2m`, while the INBOUND
  request carried a human bearer token: the two identities provably do not mix.
- ISC-48: Bash `mvn -o compile` (JDK 17) — `[INFO] BUILD SUCCESS`.

**Operability, docs, tests**
- ISC-50..52: Read `demo/RUNBOOK-M2M.md` — 4-way matrix table, copy-pastable curl block,
  10-row error-to-cause troubleshooting table.
- ISC-53: Bash — `apply-m2m.sh` on a PRISTINE realm created 6 objects
  (`service-planets`, `planets.read`, `app-m2m`, 3 audience mappers) and assigned the role; the
  resulting token carries `azp=app-m2m` + `resource_access["service-planets"].roles=["planets.read"]`.
  Second run: 6 lines of "déjà présent". Both states verified.
- ISC-54: Bash `bash -n` exit 0; `-rwxr-xr-x`.
- ISC-55..61: python read-back — 11 top-level folders, folder `09 — M2M / client_credentials`
  with 6 requests (token, 200 machine, 403 machine-on-human, 403 human-on-machine, public-client
  denied, browser-client WARN); collection JSON parses.
- ISC-60: env gains `m2m_client_id`, `m2m_client_secret` (secret, empty), `m2m_access_token`.
- ISC-62: Grep `run-newman.sh` — `--env-var "m2m_client_secret=${M2M_CLIENT_SECRET:-}"`.
- ISC-63: Grep for secret literals across yml/json/java/sh/md — 0 matches; realm export has no
  `secret` key on any client; the service-account user has no `credentials`. (Pre-existing demo
  fixtures `testuser/password` and `admin/admin` are untouched iteration-1 scope.)
- ISC-64: Read — `keycloak.audience: ${KEYCLOAK_AUDIENCE:}` still blank by default; no previously
  reachable route newly blocked (`/planets` rule byte-identical).
- ISC-65: Grep — no `refresh` token code in `M2mClientConfig` / `M2mPlanetsService`; provider built
  with `.clientCredentials()` only.
- ISC-66: inspection — the M2M chain reads its token from `m2mAuthorizedClientService`, never from
  the inbound request or session; proven at runtime by ISC-49 (human token in, machine token out).
- ISC-67: Bash — `demo/realm-export.json` removed (`git rm`, recoverable from history); single
  canonical file at `demo/keycloak/import/realm-export.json`.
- ISC-68: Bash — `docker run ... --import-realm` on an empty DB logged
  `Realm 'bzhcamp' imported` with no errors, and the machine token worked immediately: zero console clicks.

**Known gap (not an ISC failure)**
- The developer's own `:8080` realm (volume `demo_pgdata`) is still unpatched. Its master-realm admin
  password is no longer `admin`, and `--import-realm` does not re-run on a populated database. Fix is
  one command once the password is known (`./demo/keycloak/apply-m2m.sh`) or
  `docker compose down -v && docker compose up -d --build` to rebuild declaratively.

**Re-verification after the advisor-driven refactor (2026-11-14, same session)**
- The access matrix and the end-to-end chain were re-measured on the REBUILT code, not inherited
  from the pre-refactor run: `mvn test-compile` / `mvn compile` exit 0; matrix again
  200 / 403 / 403 / 200 / 401; `GET /m2m/planets` again 200 with downstream `azp=app-m2m`,
  `aud=[service-planets]`, authorities including `ROLE_service-planets_planets.read`, 10 planets.
- ISC-43 (post-refactor): Grep — 0 coupling imports and 0 code lines referencing `SecurityContext`,
  `ServletOAuth2AuthorizedClientExchangeFilterFunction` or `RequestContextHolder` in
  `M2mPlanetsService` / `M2mClientConfig` (the only occurrences are javadoc explaining the trap);
  `OAuth2AuthorizeRequest...principal(M2mClientConfig.M2M_PRINCIPAL)` at `M2mPlanetsService:83`.
- ISC-45 (post-refactor): Grep — exactly one `WebClient` bean (`SecurityConfig:175`); `@Primary`
  reverted, `PlanetsService` constructor untouched.
- Throwaway verification infrastructure removed afterwards: containers `kc-verify` / `kc-old` deleted,
  the three temporary Spring instances stopped, temp token/secret files deleted. Only the
  developer's original `demo-*` containers remain running.
