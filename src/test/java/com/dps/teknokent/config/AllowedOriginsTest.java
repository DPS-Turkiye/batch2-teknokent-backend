package com.dps.teknokent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The test profile shadows the main application.yml, so the shipped origin defaults are checked as files.
 * A wildcard origin (https://*.amplifyapp.com) would let any site on that host reach the browser-direct
 * upload, CV parse, notification and live-notes endpoints.
 */
class AllowedOriginsTest {

	@Test
	void shippedOriginDefaultsAreExactHosts() throws Exception {
		List<String> originLines = Files.readAllLines(Path.of("src/main/resources/application.yml")).stream()
				.filter(line -> line.contains("allowed-origins:"))
				.toList();
		List<String> exampleLines = Files.readAllLines(Path.of(".env.example")).stream()
				.filter(line -> line.contains("_ALLOWED_ORIGINS="))
				.toList();

		assertThat(originLines).hasSize(2);
		assertThat(exampleLines).hasSize(2);
		assertThat(originLines).noneMatch(line -> line.contains("*"));
		assertThat(exampleLines).noneMatch(line -> line.contains("*"));
	}
}
