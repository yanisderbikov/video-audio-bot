package ru.yanis.videoaudiobot.model;

public interface Job {
  java.util.UUID id();

  long updateId();

  long chatId();

  long userId();

  long messageId();

  String fileId();

  String fileName();

  String mimeType();

  long fileSize();

  Stage stage();

  JobStatus status();

  int attempt();

  java.time.Instant nextAttemptAt();

  java.util.UUID leaseToken();

  java.time.Instant leaseUntil();

  String lastError();

  String sourceKey();

  String audioKey();

  String transcriptKey();

  String resultKey();

  String telegramPath();

  java.time.Instant createdAt();

  java.time.Instant updatedAt();
}
