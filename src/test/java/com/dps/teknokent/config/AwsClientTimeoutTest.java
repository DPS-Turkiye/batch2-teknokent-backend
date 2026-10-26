package com.dps.teknokent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sesv2.SesV2Client;

class AwsClientTimeoutTest {

	@Test
	void sesGivesUpOnAWholeCallAfterTenSeconds() {
		try (SesV2Client ses = new MailConfig().sesV2Client("eu-central-1", "", "")) {
			assertThat(ses.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
					.contains(Duration.ofSeconds(10));
		}
	}

	@Test
	void s3GivesUpOnAWholeCallAfterThirtySeconds() {
		try (S3Client s3 = new S3Config().s3Client("eu-central-1", "", "")) {
			assertThat(s3.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
					.contains(Duration.ofSeconds(30));
		}
	}
}
