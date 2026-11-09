package com.dps.teknokent.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.dps.teknokent.filter.RateLimitingFilter;
import com.dps.teknokent.security.FirebaseBearerFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private static final List<String> DEV_CORS_ORIGIN_PATTERNS = List.of(
			"http://localhost:*",
			"http://127.0.0.1:*");

	private static final String[] PUBLIC_PATHS = {
			"/api/health",
			"/api/lti/jwks",
			"/ws/**",
			"/api/docs",
			"/api/docs/**",
			"/api/swagger-ui/**",
			"/api/v3/api-docs",
			"/api/v3/api-docs/**"
	};

	/**
	 * The Amplify-hosted frontend's own edge blocks any request body over ~8KB
	 * on its proxy routes, so every path that carries an uploaded file goes
	 * straight from the browser to this backend instead. Reuses the realtime
	 * origin list by default since it is already the set of trusted frontend
	 * origins.
	 */
	@Value("${app.documents.allowed-origins}")
	private String[] documentsAllowedOrigins;

	/**
	 * CV parsing cannot use a presigned S3 URL the way a plain document could,
	 * because the parser consumes the bytes in-request, so it has to reach this
	 * backend from the browser itself.
	 */
	private static final String[] BROWSER_UPLOAD_PATHS = {
			"/api/documents",
			"/api/profile-analyses",
			"/api/profile-analyses/preview",
			"/api/profile-analyses/preview/documents/*",
	};

	/** spring.security.user.roles gives the frontend's Basic credential this role. */
	static final String SERVICE_ROLE = "SERVICE";

	private static final String[] SERVICE_ONLY_PATHS = {
			"/api/lti/**",
			"/api/office-hours/**"
	};

	private static final String NOTIFICATION_PATH = "/api/notifications/**";

	/**
	 * Sign-up and onboarding bind the new account to the caller's Firebase login. On the shared service
	 * credentials there is no login to bind, so a row made there could later be claimed by anyone who signs up
	 * with its address. FirebaseBearerFilter grants REGISTERING here to any valid Firebase token.
	 */
	private static final String[] PRE_ACCOUNT_PATHS = {
			"/api/auth/register/**",
			"/api/onboarding/**"
	};

	/**
	 * The only password Spring checks is the shared Basic credential the frontend proxy sends on
	 * every call, and it already sits in plain text in the environment. With the default encoder,
	 * Spring upgrades it to bcrypt after the first login and then runs a bcrypt verify per request,
	 * which load tests showed to be almost all of the backend's CPU. A constant-time comparison
	 * keeps timing safety without the hashing.
	 */
	@Bean
	PasswordEncoder serviceCredentialEncoder() {
		return new PasswordEncoder() {
			@Override
			public String encode(CharSequence rawPassword) {
				return rawPassword.toString();
			}

			@Override
			public boolean matches(CharSequence rawPassword, String encodedPassword) {
				return encodedPassword != null && MessageDigest.isEqual(
						rawPassword.toString().getBytes(StandardCharsets.UTF_8),
						encodedPassword.getBytes(StandardCharsets.UTF_8));
			}
		};
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(Environment environment) {
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

		if (environment.matchesProfiles("dev | local")) {
			CorsConfiguration configuration = new CorsConfiguration();
			configuration.setAllowedOriginPatterns(DEV_CORS_ORIGIN_PATTERNS);
			configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
			configuration.setAllowedHeaders(List.of("*"));
			configuration.setAllowCredentials(true);
			configuration.setExposedHeaders(List.of(RateLimitingFilter.RETRY_AFTER_HEADER));
			source.registerCorsConfiguration("/**", configuration);
		}

		CorsConfiguration documentsConfig = new CorsConfiguration();
		documentsConfig.setAllowedOriginPatterns(Arrays.asList(documentsAllowedOrigins));
		documentsConfig.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
		documentsConfig.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		documentsConfig.setAllowCredentials(false);
		documentsConfig.setExposedHeaders(List.of(RateLimitingFilter.RETRY_AFTER_HEADER));
		for (String path : BROWSER_UPLOAD_PATHS) {
			source.registerCorsConfiguration(path, documentsConfig);
		}

		CorsConfiguration notificationConfig = new CorsConfiguration();
		notificationConfig.setAllowedOriginPatterns(Arrays.asList(documentsAllowedOrigins));
		notificationConfig.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		notificationConfig.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		notificationConfig.setAllowCredentials(false);
		source.registerCorsConfiguration(NOTIFICATION_PATH, notificationConfig);

		return source;
	}

	@Bean
	SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			ObjectProvider<FirebaseBearerFilter> firebaseBearerFilter) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(PUBLIC_PATHS).permitAll()
						// Office hours trust only the frontend's server: the Basic service credential carries
						// ROLE_SERVICE, which no Firebase-signed Menton account ever gets.
						.requestMatchers(SERVICE_ONLY_PATHS).hasRole(SERVICE_ROLE)
						.requestMatchers(PRE_ACCOUNT_PATHS).hasRole("REGISTERING")
						.anyRequest().authenticated())
				.httpBasic(Customizer.withDefaults())
				.cors(Customizer.withDefaults());

		FirebaseBearerFilter bearerFilter = firebaseBearerFilter.getIfAvailable();
		if (bearerFilter != null) {
			http.addFilterBefore(bearerFilter, BasicAuthenticationFilter.class);
		}

		return http.build();
	}

}
