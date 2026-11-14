package com.zenika.bzhcamp.keycloak.web;

import com.zenika.bzhcamp.keycloak.service.PlanetsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class PlanetsServiceController {
    private static final Logger logger = LoggerFactory.getLogger(PlanetsServiceController.class);

	@Autowired
	private PlanetsService planetsService;

	/**
	 * API HUMAINE : exige le rôle realm {@code user} (voir SecurityConfig).
	 * La contrepartie machine est {@code /internal/planets} (rôle client, client_credentials).
	 *
	 * <p>On ne journalise jamais l'en-tête {@code Authorization} : un jeton porteur dans les logs
	 * est un secret rejouable, exporté vers la stack de logs et les sauvegardes. Seuls le sujet et
	 * le {@code jti} sont tracés — suffisant pour corréler, inutilisable pour rejouer.</p>
	 */
	@GetMapping(value = "/planets", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<String> handlePlanetsRequest(@AuthenticationPrincipal Jwt jwt) {
		logger.info("Human call on /planets — sub={} preferred_username={} jti={}",
				jwt.getSubject(), jwt.getClaimAsString("preferred_username"), jwt.getId());
		return planetsService.getPlanets();
	}
}
