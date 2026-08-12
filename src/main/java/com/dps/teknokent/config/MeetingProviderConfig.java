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
      @Value("${meeting.provider.api-key}") String apiKey) {

    HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    return RestClient.builder()
        .requestFactory(new JdkClientHttpRequestFactory(httpClient))
        .baseUrl(baseUrl)
        .defaultHeader("X-API-Key", apiKey)
        .build();
  }
}
