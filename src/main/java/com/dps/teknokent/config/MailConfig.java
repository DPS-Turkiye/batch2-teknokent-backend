package com.dps.teknokent.config;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.DefaultRetryStrategy;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sqs.SqsClient;

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

	/**
	 * For the email center, which decides itself what may be sent again: an SDK retry after a timeout could deliver
	 * one mail twice. Injected only where it is asked for by name.
	 */
	@Bean(defaultCandidate = false)
	SesV2Client emailCenterSesClient(
			@Value("${app.mail.region:eu-central-1}") String region,
			@Value("${app.s3.access-key-id:}") String accessKeyId,
			@Value("${app.s3.secret-access-key:}") String secretAccessKey) {
		var builder = SesV2Client.builder()
				.region(Region.of(region))
				.overrideConfiguration(config -> config
						.apiCallTimeout(Duration.ofSeconds(10))
						.retryStrategy(DefaultRetryStrategy.doNotRetry()));
		if (!accessKeyId.isBlank() && !secretAccessKey.isBlank()) {
			builder.credentialsProvider(StaticCredentialsProvider.create(
					AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
		}
		return builder.build();
	}

	/** Reads the events SES publishes about email-center mails. */
	@Bean
	@ConditionalOnProperty(prefix = "app.mail.center", name = "bounce-source", havingValue = "ses-events")
	SqsClient sqsClient(
			@Value("${app.mail.region:eu-central-1}") String region,
			@Value("${app.s3.access-key-id:}") String accessKeyId,
			@Value("${app.s3.secret-access-key:}") String secretAccessKey) {
		var builder = SqsClient.builder()
				.region(Region.of(region))
				.overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30)));
		if (!accessKeyId.isBlank() && !secretAccessKey.isBlank()) {
			builder.credentialsProvider(StaticCredentialsProvider.create(
					AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
		}
		return builder.build();
	}

	/**
	 * Runs after-commit emails off the request thread. Two at a time keeps a burst under the SES send rate;
	 * closing it on shutdown lets queued emails finish.
	 */
	@Bean(destroyMethod = "close")
	ExecutorService mailExecutor() {
		return Executors.newFixedThreadPool(2, Thread.ofVirtual().name("mail-", 0).factory());
	}
}
