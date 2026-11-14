package com.zenika.bzhcamp.keycloak.appspringboot.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.zenika.bzhcamp.keycloak.appspringboot.service.M2mPlanetsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Point d'entrée de <b>démonstration</b> de la chaîne machine-à-machine.
 *
 * <p>{@code GET /m2m/planets} déclenche, sans aucune intervention humaine dans la chaîne aval :</p>
 * <ol>
 *   <li>app-springboot demande un jeton à Keycloak en {@code client_credentials} (client {@code app-m2m}) ;</li>
 *   <li>Keycloak émet un jeton dont le sujet est le <i>service account</i>, portant le rôle client
 *       {@code planets.read} et l'audience {@code service-planets} ;</li>
 *   <li>app-springboot appelle {@code /internal/planets} sur le service {@code :8091} avec ce jeton ;</li>
 *   <li>le service valide signature + issuer (+ audience si activée), mappe
 *       {@code resource_access} en autorités et autorise.</li>
 * </ol>
 *
 * <p>L'accès à cet endpoint reste soumis à la chaîne de filtres de l'application
 * ({@code anyRequest().authenticated()}) : on ne publie pas un proxy ouvert vers l'API interne.
 * C'est l'appel <i>sortant</i> qui est machine, pas l'appel entrant.</p>
 */
@RestController
public class M2mController {

    private static final Logger logger = LoggerFactory.getLogger(M2mController.class);

    private final M2mPlanetsService m2mPlanetsService;

    public M2mController(M2mPlanetsService m2mPlanetsService) {
        this.m2mPlanetsService = m2mPlanetsService;
    }

    @GetMapping("/m2m/planets")
    public Map<String, Object> callDownstreamAsMachine() {
        logger.info("Appel sortant machine-a-machine vers /internal/planets (grant client_credentials)");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("grantType", "client_credentials");
        body.put("callerIdentity", "app-m2m (service account) — aucun utilisateur delegue");
        body.put("downstreamResponse", m2mPlanetsService.fetchInternalPlanets());
        return body;
    }
}
