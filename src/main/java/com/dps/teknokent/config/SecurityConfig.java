package com.dps.teknokent.config;

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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.dps.teknokent.security.FirebaseBearerFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private static final List<String> DEV_CORS_ORIGIN_PATTERNS = List.of(
			"http://localhost:*",
			"http://127.0.0.1:*");

	private static final String[] PUBLIC_PATHS = {
			"/api/health",
			"/api/assistant/**",
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
	};

	@Bean
	CorsConfigurationSource corsConfigurationSource(Environment environment) {
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

		if (environment.matchesProfiles("dev | local")) {
			CorsConfiguration configuration = new CorsConfiguration();
			configuration.setAllowedOriginPatterns(DEV_CORS_ORIGIN_PATTERNS);
			configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
			configuration.setAllowedHeaders(List.of("*"));
			configuration.setAllowCredentials(true);
			source.registerCorsConfiguration("/**", configuration);
		}

		CorsConfiguration documentsConfig = new CorsConfiguration();
		documentsConfig.setAllowedOrigins(Arrays.asList(documentsAllowedOrigins));
		documentsConfig.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
		documentsConfig.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		documentsConfig.setAllowCredentials(false);
		for (String path : BROWSER_UPLOAD_PATHS) {
			source.registerCorsConfiguration(path, documentsConfig);
		}

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
