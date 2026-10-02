package ru.yanis.videoaudiobot.config.impl;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import ru.yanis.videoaudiobot.config.AppProperties;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
class StorageConfig {
  @Bean
  AwsCredentialsProvider awsCredentials(AppProperties p) {
    var s = p.s3();
    if (s.accessKey() != null && !s.accessKey().isBlank()) {
      if (s.secretKey() == null || s.secretKey().isBlank())
        throw new IllegalArgumentException("S3_SECRET_KEY is required with S3_ACCESS_KEY");
      return StaticCredentialsProvider.create(
          AwsBasicCredentials.create(s.accessKey(), s.secretKey()));
    }
    return DefaultCredentialsProvider.create();
  }

  @Bean
  S3Client s3Client(
      AppProperties p,
      AwsCredentialsProvider credentials,
      @Value("${HTTP_CONNECT_TIMEOUT:15s}") Duration connectTimeout) {
    var s = p.s3();
    var b =
        S3Client.builder()
            .credentialsProvider(credentials)
            .region(Region.of(s.region()))
            .serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(s.pathStyle()).build())
            .overrideConfiguration(
                c -> c.apiCallTimeout(s.timeout()).apiCallAttemptTimeout(s.timeout()))
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(connectTimeout)
                    .socketTimeout(s.timeout()));
    if (s.endpoint() != null && !s.endpoint().isBlank())
      b.endpointOverride(URI.create(s.endpoint()));
    return b.build();
  }

  @Bean
  S3Presigner s3Presigner(AppProperties p, AwsCredentialsProvider credentials) {
    var s = p.s3();
    var b =
        S3Presigner.builder()
            .credentialsProvider(credentials)
            .region(Region.of(s.region()))
            .serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(s.pathStyle()).build());
    String endpoint =
        s.publicEndpoint() == null || s.publicEndpoint().isBlank()
            ? s.endpoint()
            : s.publicEndpoint();
    if (endpoint != null && !endpoint.isBlank()) b.endpointOverride(URI.create(endpoint));
    return b.build();
  }
}
