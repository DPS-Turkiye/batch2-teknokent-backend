package com.dps.teknokent.config;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Configuration
public class MailConfig {

	@Bean
	SesV2Client sesV2Client(
			@Value("${app.mail.region:eu-central-1}") String region,
			@Value("${app.s3.access-key-id:}") String accessKeyId,
			@Value("${app.s3.secret-access-key:}") String secretAccessKey) {
		// The SDK default has no cap on a whole call (retries included); an email is small, 10 s is plenty.
		var builder = SesV2Client.builder()
				.region(Region.of(region))
				.overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10)));
		if (!accessKeyId.isBlank() && !secretAccessKey.isBlank()) {
			builder.credentialsProvider(StaticCredentialsProvider.create(
					AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
		}
		return builder.build();
	}

	/** Runs after-commit emails off the request thread; closing it on shutdown lets queued emails finish. */
	@Bean(destroyMethod = "close")
	ExecutorService mailExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}
}
