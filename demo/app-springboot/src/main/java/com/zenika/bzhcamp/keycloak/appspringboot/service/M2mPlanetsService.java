package com.zenika.bzhcamp.keycloak.appspringboot.service;

import java.util.Map;

import com.zenika.bzhcamp.keycloak.appspringboot.config.M2mClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Appel <b>machine-à-machine</b> du service aval : aucun utilisateur n'est impliqué.
 *
 * <p>À comparer avec {@link PlanetsService}, qui propage le jeton de l'utilisateur connecté :</p>
 * <ul>
 *   <li>{@link PlanetsService} → jeton lu en session, obtenu par {@code authorization_code}
 *       (autorité <b>déléguée</b> : l'app agit au nom de quelqu'un) ;</li>
 *   <li>{@code M2mPlanetsService} → jeton demandé par le client lui-même en
 *       {@code client_credentials} (autorité <b>propre</b> : l'app agit en son nom).</li>
 * </ul>
 *
 * <h2>Pourquoi le jeton est demandé explicitement</h2>
 * Le jeton est obtenu via {@code manager.authorize(...)} avec un <b>principal constant</b>
 * ({@link M2mClientConfig#M2M_PRINCIPAL}) plutôt que par un filtre {@code WebClient} :
 * <ul>
 *   <li>le cache est partagé — <b>un seul</b> jeton pour toute l'application, et non un par
 *       appelant entrant ;</li>
 *   <li>le code fonctionne à l'identique hors requête HTTP ({@code @Scheduled}, batch,
 *       consumer) : il ne lit aucun {@code SecurityContext} ;</li>
 *   <li>un échec d'obtention lève une exception explicite au lieu de partir sans en-tête
 *       {@code Authorization} et de produire un 401 inexplicable en aval.</li>
 * </ul>
 * Le manager conserve toute la mécanique utile : appel du token endpoint, mise en cache jusqu'à
 * expiration, renouvellement. On ne réimplémente pas OAuth2 — on retire seulement le liage
 * implicite à la requête entrante.
 *
 * <p>Le jeton machine ne porte pas le rôle realm {@code user} : cet appel visera donc toujours
 * {@code /internal/planets} et prendrait un 403 sur {@code /planets}. Voulu.</p>
 */
@Component
public class M2mPlanetsService {

    private final AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager;
    private final WebClient webClient;
    private final String internalEndpoint;

    public M2mPlanetsService(AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager,
                             WebClient.Builder webClientBuilder,
                             @Value("${planets.service.internal-url}") String internalEndpoint) {
        this.authorizedClientManager = authorizedClientManager;
        // WebClient nu : aucun filtre OAuth2, l'en-tête est posé explicitement ci-dessous.
        this.webClient = webClientBuilder.build();
        this.internalEndpoint = internalEndpoint;
    }

    /**
     * Récupère la charge utile de l'API machine.
     *
     * @throws IllegalStateException si Keycloak refuse de délivrer un jeton (secret absent ou
     *         erroné, service accounts désactivés, client inexistant) — échec explicite, de
     *         préférence à un appel sortant sans en-tête d'autorisation.
     */
    public Map<String, Object> fetchInternalPlanets() {
        return webClient
                .get()
                .uri(internalEndpoint)
                .headers(headers -> headers.setBearerAuth(machineAccessToken()))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    /**
     * Jeton d'accès du client lui-même. Servi depuis le cache du manager tant qu'il est valide ;
     * un nouvel appel au token endpoint n'a lieu qu'à l'expiration.
     */
    private String machineAccessToken() {
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId(M2mClientConfig.M2M_REGISTRATION_ID)
                .principal(M2mClientConfig.M2M_PRINCIPAL)
                .build();

        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(request);
        if (authorizedClient == null) {
            throw new IllegalStateException(
                    "Impossible d'obtenir un jeton client_credentials pour la registration '"
                            + M2mClientConfig.M2M_REGISTRATION_ID
                            + "'. Vérifier M2M_CLIENT_SECRET et que le client Keycloak a "
                            + "« Service accounts roles » activé (voir demo/keycloak/apply-m2m.sh).");
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }
}
