package com.dps.teknokent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dps.teknokent.repository.PushTokenRepository;
import com.dps.teknokent.service.DisabledPushSender;
import com.dps.teknokent.service.FirebasePushSender;
import com.dps.teknokent.service.PushSender;
import com.google.firebase.messaging.FirebaseMessaging;

@Configuration
public class PushConfig {

	@Bean
	PushSender pushSender(FirebaseProperties properties, PushTokenRepository pushTokenRepository) {
		if (!properties.enabled()) {
			return new DisabledPushSender();
		}
		return new FirebasePushSender(
				FirebaseMessaging.getInstance(FirebaseApps.named(properties)), pushTokenRepository);
	}
}
