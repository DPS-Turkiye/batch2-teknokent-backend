package com.dps.teknokent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.security.DisabledFirebaseTokenVerifier;
import com.dps.teknokent.security.DisabledLoginProvisioner;
import com.dps.teknokent.security.FirebaseAdminTokenVerifier;
import com.dps.teknokent.security.FirebaseBearerFilter;
import com.dps.teknokent.security.FirebaseLoginProvisioner;
import com.dps.teknokent.security.FirebaseTokenVerifier;
import com.dps.teknokent.security.LoginProvisioner;
import com.google.firebase.auth.FirebaseAuth;

@Configuration
@EnableConfigurationProperties(FirebaseProperties.class)
public class FirebaseAuthConfig {

	@Bean
	FirebaseTokenVerifier firebaseTokenVerifier(FirebaseProperties properties) {
		if (!properties.enabled()) {
			return new DisabledFirebaseTokenVerifier();
		}
		return new FirebaseAdminTokenVerifier(properties);
	}

	@Bean
	LoginProvisioner loginProvisioner(FirebaseProperties properties) {
		if (!properties.enabled()) {
			return new DisabledLoginProvisioner();
		}
		return new FirebaseLoginProvisioner(FirebaseAuth.getInstance(FirebaseApps.named(properties)));
	}

	@Bean
	FirebaseBearerFilter firebaseBearerFilter(
			FirebaseTokenVerifier firebaseTokenVerifier,
			AppUserRepository appUserRepository) {
		return new FirebaseBearerFilter(firebaseTokenVerifier, appUserRepository);
	}

	@Bean
	FilterRegistrationBean<FirebaseBearerFilter> firebaseBearerFilterRegistration(FirebaseBearerFilter filter) {
		FilterRegistrationBean<FirebaseBearerFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}
}
