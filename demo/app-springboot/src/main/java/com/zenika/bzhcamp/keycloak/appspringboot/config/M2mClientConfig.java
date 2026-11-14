package com.zenika.bzhcamp.keycloak.appspringboot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Chaîne OAuth2 <b>machine-à-machine</b> (grant {@code client_credentials}).
 *
 * <h2>Pourquoi une configuration séparée du {@code WebClient} existant</h2>
 * Le {@code webClient} de {@link SecurityConfig} s'appuie sur le
 * {@code OAuth2AuthorizedClientManager} par défaut de Spring Boot, adossé à un
 * {@code OAuth2AuthorizedClientRepository} <b>lié à la session HTTP de l'utilisateur</b>.
 * C'est exactement ce qu'il faut pour propager le jeton d'un humain connecté — et exactement
 * ce qui ne peut pas marcher pour un appel machine : il n'y a ni session, ni utilisateur.
 *
 * <p>D'où {@link AuthorizedClientServiceOAuth2AuthorizedClientManager} : il stocke le jeton
 * autorisé dans un {@link OAuth2AuthorizedClientService} applicatif (ici en mémoire) et non
 * dans la session. Le même code fonctionne donc depuis un contrôleur, un {@code @Scheduled},
 * un consumer Kafka ou un job batch.</p>
 *
 * <h2>Le piège évité ici — à expliquer en formation</h2>
 * La tentation est d'enrober ce manager dans un
 * {@code ServletOAuth2AuthorizedClientExchangeFilterFunction} pour que le jeton soit posé
 * automatiquement, comme pour le flux humain. <b>Ne pas le faire pour du M2M pur</b> :
 * <ul>
 *   <li>ce filtre lit le principal dans le {@code SecurityContext} de la requête <i>entrante</i>,
 *       et le manager indexe le jeton sur {@code (registrationId, principalName)} → un jeton
 *       mis en cache <b>par appelant entrant</b>, donc un aller-retour vers Keycloak par
 *       appelant distinct au lieu d'un seul jeton partagé ;</li>
 *   <li>hors requête HTTP ({@code @Scheduled}, {@code @Async}, consumer de messages) il n'y a
 *       aucune {@code Authentication} et la chaîne échoue ;</li>
 *   <li>si le {@code registrationId} par défaut n'est pas positionné, le filtre n'attache
 *       <b>rien, sans erreur</b> : on obtient un 401 en aval et on cherche le problème côté
 *       Keycloak pendant une heure.</li>
 * </ul>
 * On demande donc le jeton <b>explicitement</b>, avec un principal constant — voir
 * {@code M2mPlanetsService}. Plus verbeux, mais déterministe et lisible.
 *
 * <h2>Ce que le manager fait pour vous</h2>
 * <ul>
 *   <li>appel du <i>token endpoint</i> avec {@code client_id}/{@code client_secret}
 *       (HTTP Basic) et {@code grant_type=client_credentials} ;</li>
 *   <li><b>mise en cache</b> du jeton jusqu'à son expiration, puis renouvellement ;</li>
 *   <li>aucun {@code refresh_token} : en {@code client_credentials} il n'y en a pas, et il n'en
 *       faut pas — le client peut toujours se réauthentifier seul.</li>
 * </ul>
 *
 * <p><b>Attention à l'horloge :</b> le provider {@code client_credentials} applique un
 * {@code clockSkew} de 60 s par défaut. Si l'on réduit la durée de vie des jetons sous 60 s pour
 * démontrer l'expiration, chaque jeton est considéré comme déjà expiré et un nouvel appel est
 * émis à chaque requête — le cache semble alors « ne pas fonctionner ».</p>
 *
 * <h2>Ce qu'il ne fait PAS</h2>
 * Il ne transforme jamais un jeton utilisateur en jeton machine, ni l'inverse. Les deux identités
 * restent distinctes de bout en bout — pas d'usurpation implicite. Pour agir *au nom* d'un
 * utilisateur, le sujet est le token exchange (RFC 8693), pas ce flux.
 */
@Configuration
public class M2mClientConfig {

    /** Identifiant de la registration déclarée dans {@code application.yml}. */
    public static final String M2M_REGISTRATION_ID = "keycloak-m2m";

    /**
     * Principal constant sous lequel le jeton machine est mis en cache. Constant par conception :
     * l'identité est celle du client, elle ne dépend d'aucun appelant entrant.
     */
    public static final String M2M_PRINCIPAL = "app-m2m";

    /**
     * Stockage des jetons autorisés hors session HTTP. En mémoire ici (POC) ; en production une
     * implémentation partagée (JDBC/Redis) évite qu'un redémarrage ou chaque réplique ne
     * redemande inutilement un jeton.
     */
    @Bean
    public OAuth2AuthorizedClientService m2mAuthorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    /**
     * Manager volontairement restreint au seul grant {@code client_credentials} : si la
     * registration était mal configurée, l'échec est immédiat et explicite plutôt que
     * silencieusement rattrapé par un autre flux.
     */
    @Bean
    public AuthorizedClientServiceOAuth2AuthorizedClientManager m2mAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService m2mAuthorizedClientService) {

        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, m2mAuthorizedClientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }
}
