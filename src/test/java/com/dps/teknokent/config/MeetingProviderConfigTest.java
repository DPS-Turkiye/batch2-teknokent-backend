package com.dps.teknokent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

class MeetingProviderConfigTest {

	private final CountDownLatch release = new CountDownLatch(1);
	private HttpServer silentProvider;

	@BeforeEach
	void startSilentProvider() throws Exception {
		silentProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		silentProvider.createContext("/", exchange -> {
			try {
				release.await();
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});
		silentProvider.start();
	}

	@AfterEach
	void stopSilentProvider() {
		release.countDown();
		silentProvider.stop(0);
	}

	@Test
	void givesUpOnAProviderThatAcceptsTheConnectionButNeverAnswers() {
		String baseUrl = "http://127.0.0.1:" + silentProvider.getAddress().getPort();
		RestClient client = new MeetingProviderConfig()
				.meetingProviderRestClient(baseUrl, "key", Duration.ofMillis(300));

		long started = System.nanoTime();
		assertThatThrownBy(() -> client.get().uri("/bots/status").retrieve().body(String.class))
				.isInstanceOf(ResourceAccessException.class);
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
	}
}
