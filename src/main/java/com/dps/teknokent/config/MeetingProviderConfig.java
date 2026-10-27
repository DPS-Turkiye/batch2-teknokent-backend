package com.dps.teknokent.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class MeetingProviderConfig {

  @Bean
  RestClient meetingProviderRestClient(
      @Value("${meeting.provider.base-url}") String baseUrl,
      @Value("${meeting.provider.api-key}") String apiKey,
      @Value("${meeting.provider.read-timeout:20s}") Duration readTimeout) {

    HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(15))
        .build();
    // Without a read timeout a Vexa that accepts the connection but never answers blocks the scheduler thread forever.
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);

    return RestClient.builder()
        .requestFactory(requestFactory)
        .baseUrl(baseUrl)
        .defaultHeader("X-API-Key", apiKey)
        .build();
  }
}
