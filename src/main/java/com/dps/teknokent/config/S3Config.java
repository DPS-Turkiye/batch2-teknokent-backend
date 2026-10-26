package com.dps.teknokent.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class S3Config {

	@Bean
	S3Client s3Client(
			@Value("${app.s3.region:eu-central-1}") String region,
			@Value("${app.s3.access-key-id:}") String accessKeyId,
			@Value("${app.s3.secret-access-key:}") String secretAccessKey) {
		// The SDK default has no cap on a whole call (retries included). 30 s, not less: the backend itself uploads
		// documents of up to 50 MB.
		var builder = S3Client.builder()
				.region(Region.of(region))
				.overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30)));
		if (!accessKeyId.isBlank() && !secretAccessKey.isBlank()) {
			builder.credentialsProvider(StaticCredentialsProvider.create(
					AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
		}
		return builder.build();
	}

	@Bean
	S3Presigner s3Presigner(
			@Value("${app.s3.region:eu-central-1}") String region,
			@Value("${app.s3.access-key-id:}") String accessKeyId,
			@Value("${app.s3.secret-access-key:}") String secretAccessKey) {
		var builder = S3Presigner.builder().region(Region.of(region));
		if (!accessKeyId.isBlank() && !secretAccessKey.isBlank()) {
			builder.credentialsProvider(StaticCredentialsProvider.create(
					AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
		}
		return builder.build();
	}
}
