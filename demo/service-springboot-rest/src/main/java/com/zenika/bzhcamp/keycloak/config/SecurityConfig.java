package com.zenika.bzhcamp.keycloak.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.DelegatingJwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain clientFilterChain(HttpSecurity http) throws Exception {

		DelegatingJwtGrantedAuthoritiesConverter authoritiesConverter =
				// Using the delegating converter multiple converters can be combined
				new DelegatingJwtGrantedAuthoritiesConverter(
						// First add the default converter
						new JwtGrantedAuthoritiesConverter(),
						// Second add our custom Keycloak specific converter
						new KeycloakJwtRolesConverter());

		http.cors(Customizer.withDefaults());

		http.authorizeHttpRequests(
				authorize -> authorize
						.requestMatchers(new AntPathRequestMatcher("/public")).permitAll()
						.requestMatchers(new AntPathRequestMatcher("/swagger-ui/**")).permitAll()
						.requestMatchers(new AntPathRequestMatcher("/v3/api-docs/**")).permitAll()
						// API HUMAINE : autorité dérivée d'un rôle REALM porté par un utilisateur.
						// Jeton obtenu par authorization_code (navigateur) ou ROPC (tests).
						.requestMatchers(new AntPathRequestMatcher("/planets"))
						.hasAuthority(KeycloakJwtRolesConverter.PREFIX_RESOURCE_ROLE + "realm_user")
						// API MACHINE : autorité dérivée d'un rôle CLIENT porté par le service
						// account du client appelant. Jeton obtenu par client_credentials.
						// Format produit par KeycloakJwtRolesConverter : ROLE_<clientId>_<role>
						// => resource_access["service-planets"].roles = ["planets.read"]
						// Un jeton humain n'a pas ce rôle : il prend un 403 ici, et c'est voulu.
						.requestMatchers(new AntPathRequestMatcher("/internal/**"))
						.hasAuthority(KeycloakJwtRolesConverter.PREFIX_RESOURCE_ROLE + "service-planets_planets.read")
						.anyRequest().authenticated());

		http.oauth2ResourceServer(
				oAuth2ResourceServerConfigurer -> oAuth2ResourceServerConfigurer.jwt(
						jwtConfigurer -> jwtConfigurer.jwtAuthenticationConverter(
								jwt -> new JwtAuthenticationToken(jwt, authoritiesConverter.convert(jwt)))));

		http.sessionManagement((sessionManagement) -> sessionManagement
				.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

		return http.build();
	}

	/**
	 * Décodeur JWT explicite afin d'ajouter la validation d'audience aux validations par défaut
	 * (signature, issuer, expiration).
	 *
	 * <p>La validation d'audience est <b>opt-in</b> : elle ne s'active que si la propriété
	 * {@code keycloak.audience} est renseignée (ex. {@code export KEYCLOAK_AUDIENCE=service-planets}).
	 * Laissée vide, le comportement par défaut de la démo est strictement inchangé.</p>
	 */
	@Bean
	public JwtDecoder jwtDecoder(
			@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
			@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
			@Value("${keycloak.audience:}") String expectedAudience) {

		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

		List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
		// Validations standard : issuer attendu + fenêtre temporelle (exp/nbf).
		validators.add(JwtValidators.createDefaultWithIssuer(issuerUri));
		if (StringUtils.hasText(expectedAudience)) {
			validators.add(new AudienceValidator(expectedAudience));
		}
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
		return decoder;
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(List.of(
				"http://localhost:8090",
				"http://localhost:4200",
				"http://localhost:8081"
		));
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
