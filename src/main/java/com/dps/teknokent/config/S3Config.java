package com.dps.teknokent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class S3Config {

	@Bean
	S3Client s3Client(
			@Value("${app.s3.region:eu-central-1}") String region,
			@Value("${app.s3.access-key-id:}") String accessKeyId,
			@Value("${app.s3.secret-access-key:}") String secretAccessKey) {
		var builder = S3Client.builder().region(Region.of(region));
		if (!accessKeyId.isBlank() && !secretAccessKey.isBlank()) {
			builder.credentialsProvider(StaticCredentialsProvider.create(
					AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
		}
		return builder.build();
	}
}
