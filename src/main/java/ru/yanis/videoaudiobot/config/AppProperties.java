package ru.yanis.videoaudiobot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app")
@Validated
public record AppProperties(
    @Valid Telegram telegram,
    @Valid Openai openai,
    @Valid S3 s3,
    @Valid Pipeline pipeline,
    @Valid Media media,
    @Valid Cleanup cleanup,
    @NotNull Path workDirectory) {
  public record Telegram(
      @NotBlank String token,
      @NotBlank String baseUrl,
      @NotBlank String mode,
      String webhookSecret,
      @NotNull Path localDirectory,
      @NotNull Path serverDirectory,
      String fileServerUrl,
      String fileServerToken,
      @NotNull Duration requestTimeout,
      @Min(1) @Max(50) int pollTimeoutSeconds,
      @Min(1) long maxFileBytes,
      Set<String> unlimitedUsernames,
      @Min(1) int dailyLimit) {}

  public record Openai(
      @NotBlank String apiKey,
      @NotBlank String baseUrl,
      @NotBlank String model,
      @NotNull Duration timeout,
      @Min(100000) @Max(25000000) long maxUploadBytes,
      @Min(0) @Max(4) int maxSpeakerReferences) {}

  public record S3(
      @NotBlank String bucket,
      @NotBlank String region,
      String endpoint,
      String publicEndpoint,
      String accessKey,
      String secretKey,
      boolean pathStyle,
      @NotBlank String prefix,
      @NotNull Duration timeout,
      @NotNull Duration linkTtl,
      boolean purgeVersions) {}

  public record Pipeline(
      boolean enabled,
      @NotNull Duration pollInterval,
      @NotNull Duration leaseDuration,
      @NotNull Duration heartbeatInterval,
      @Min(1) int maxAttempts,
      @NotNull Duration retryBase,
      @NotNull Duration retryMax,
      @NotNull Duration shutdownTimeout) {}

  public record Media(
      @NotBlank String ffmpeg,
      @NotBlank String ffprobe,
      @NotNull Duration timeout,
      @Min(16) @Max(128) int bitrateKbps,
      @Min(8000) int sampleRate,
      @Min(30) int chunkSeconds,
      @DecimalMin("0") double overlapSeconds,
      @Min(1) int maxDurationSeconds) {}

  public record Cleanup(
      boolean enabled,
      @NotNull Duration interval,
      @NotNull Duration retention,
      @Min(1) int batchSize) {}

  @AssertTrue(message = "Lease, heartbeat, retention and link TTL configuration is inconsistent")
  public boolean isTimingValid() {
    return pipeline.heartbeatInterval.toMillis() > 0
        && pipeline.leaseDuration.compareTo(pipeline.heartbeatInterval.multipliedBy(3)) >= 0
        && cleanup.retention.toSeconds() > 0
        && cleanup.interval.toMillis() > 0
        && s3.linkTtl.compareTo(cleanup.retention) <= 0
        && s3.linkTtl.compareTo(Duration.ofDays(7)) <= 0
        && s3.linkTtl.toSeconds() > 0
        && media.overlapSeconds < media.chunkSeconds / 2.0
        && pipeline.pollInterval.toMillis() > 0
        && pipeline.retryBase.toSeconds() > 0
        && pipeline.retryMax.compareTo(pipeline.retryBase) >= 0
        && pipeline.shutdownTimeout.toMillis() > 0;
  }

  @AssertTrue(
      message = "Telegram mode must be polling or webhook; webhook requires a non-empty secret")
  public boolean isTelegramModeValid() {
    return "polling".equals(telegram.mode)
        || ("webhook".equals(telegram.mode)
            && telegram.webhookSecret != null
            && !telegram.webhookSecret.isBlank());
  }
}
