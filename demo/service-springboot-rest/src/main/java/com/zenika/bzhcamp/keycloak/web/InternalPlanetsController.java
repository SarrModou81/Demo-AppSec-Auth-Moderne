package com.zenika.bzhcamp.keycloak.web;

import com.zenika.bzhcamp.keycloak.service.PlanetsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API <b>machine</b> du service planets — la contrepartie de {@code /planets} (API humaine).
 *
 * <p>Deux endpoints, deux identités, deux rôles :</p>
 * <ul>
 *   <li>{@code /planets} → autorisé sur {@code ROLE_realm_user} : un <b>humain</b> délègue son
 *       autorité via {@code authorization_code} (ou ROPC en test).</li>
 *   <li>{@code /internal/planets} → autorisé sur {@code ROLE_service-planets_planets.read} : un
 *       <b>service</b> agit en son propre nom via {@code client_credentials}. Il n'y a aucun
 *       utilisateur derrière ce jeton : le sujet (<i>sub</i>) est le service account du client.</li>
 * </ul>
 *
 * <p>Conséquence attendue et <b>voulue</b> : un jeton machine prend un <b>403</b> sur
 * {@code /planets}, et un jeton humain prend un <b>403</b> ici. C'est la preuve que les deux
 * identités sont réellement séparées — pas un bug à contourner.</p>
 *
 * <p>La règle d'autorisation est déclarée dans {@code SecurityConfig}, pas ici : le filtre
 * refuse la requête avant d'atteindre le contrôleur (deny-by-default sur la chaîne de filtres).</p>
 */
@RestController
public class InternalPlanetsController {

    private static final Logger logger = LoggerFactory.getLogger(InternalPlanetsController.class);

    private final PlanetsService planetsService;

    public InternalPlanetsController(PlanetsService planetsService) {
        this.planetsService = planetsService;
    }

    /**
     * Renvoie le catalogue de planètes <i>et</i> l'identité du service appelant, pour rendre la
     * démonstration lisible : l'étudiant voit dans la réponse quel client a été authentifié
     * ({@code azp}), sur quelle audience ({@code aud}) et avec quelles autorités dérivées du jeton.
     *
     * <p>On ne journalise jamais le jeton ni l'en-tête {@code Authorization} : seuls le sujet et
     * le {@code jti} sont tracés, ce qui suffit à la corrélation sans fuiter un secret porteur.</p>
     *
     * <p><b>⚠️ Choix pédagogique, à ne PAS reproduire en production.</b> Le bloc {@code caller}
     * renvoie les autorités dérivées du jeton. L'appelant ne découvre rien sur lui-même (son JWT
     * est en clair, base64), mais la réponse révèle la <b>taxonomie d'autorisation</b> du service
     * ({@code ROLE_<client>_<role>}) — du renseignement utile à un attaquant pour choisir sa cible.
     * S'y ajoutent les vecteurs indirects : logs d'accès qui capturent les corps de réponse, APM,
     * traces, captures d'écran de formation. En production : ne jamais énumérer les autorités dans
     * une réponse, ou conditionner ce bloc à un profil de démonstration.</p>
     */
    @GetMapping(value = "/internal/planets", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> handleMachineRequest(@AuthenticationPrincipal Jwt jwt,
                                                    Authentication authentication) {

        logger.info("M2M call on /internal/planets — sub={} azp={} jti={}",
                jwt.getSubject(), jwt.getClaimAsString("azp"), jwt.getId());

        Map<String, Object> caller = new LinkedHashMap<>();
        caller.put("subject", jwt.getSubject());
        caller.put("authorizedParty", jwt.getClaimAsString("azp"));
        caller.put("audience", jwt.getAudience());
        caller.put("grantedAuthorities", authentication.getAuthorities().stream().map(Object::toString).toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caller", caller);
        body.put("planets", planetsService.getPlanets());
        return body;
    }
}
