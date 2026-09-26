package com.dps.teknokent.config;

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
		var builder = SesV2Client.builder().region(Region.of(region));
		if (!accessKeyId.isBlank() && !secretAccessKey.isBlank()) {
			builder.credentialsProvider(StaticCredentialsProvider.create(
					AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
		}
		return builder.build();
	}
}
