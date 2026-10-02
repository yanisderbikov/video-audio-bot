package ru.yanis.videoaudiobot.model.impl;

import jakarta.persistence.*;
import ru.yanis.videoaudiobot.model.*;

@Entity(name = "Job")
@Table(name = "transcription_job")
class JobEntity implements Job {
  protected JobEntity() {}

  @Id
  @Column(name = "id")
  private java.util.UUID id;

  public java.util.UUID id() {
    return id;
  }

  @Column(name = "update_id")
  private long updateId;

  public long updateId() {
    return updateId;
  }

  @Column(name = "chat_id")
  private long chatId;

  public long chatId() {
    return chatId;
  }

  @Column(name = "user_id")
  private long userId;

  public long userId() {
    return userId;
  }

  @Column(name = "message_id")
  private long messageId;

  public long messageId() {
    return messageId;
  }

  @Column(name = "file_id")
  private String fileId;

  public String fileId() {
    return fileId;
  }

  @Column(name = "file_name")
  private String fileName;

  public String fileName() {
    return fileName;
  }

  @Column(name = "mime_type")
  private String mimeType;

  public String mimeType() {
    return mimeType;
  }

  @Column(name = "file_size")
  private long fileSize;

  public long fileSize() {
    return fileSize;
  }

  @Enumerated(EnumType.STRING)
  @Column(name = "stage")
  private Stage stage;

  public Stage stage() {
    return stage;
  }

  @Enumerated(EnumType.STRING)
  @Column(name = "status")
  private JobStatus status;

  public JobStatus status() {
    return status;
  }

  @Column(name = "attempt")
  private int attempt;

  public int attempt() {
    return attempt;
  }

  @Column(name = "next_attempt_at")
  private java.time.Instant nextAttemptAt;

  public java.time.Instant nextAttemptAt() {
    return nextAttemptAt;
  }

  @Column(name = "lease_token")
  private java.util.UUID leaseToken;

  public java.util.UUID leaseToken() {
    return leaseToken;
  }

  @Column(name = "lease_until")
  private java.time.Instant leaseUntil;

  public java.time.Instant leaseUntil() {
    return leaseUntil;
  }

  @Column(name = "last_error")
  private String lastError;

  public String lastError() {
    return lastError;
  }

  @Column(name = "source_key")
  private String sourceKey;

  public String sourceKey() {
    return sourceKey;
  }

  @Column(name = "audio_key")
  private String audioKey;

  public String audioKey() {
    return audioKey;
  }

  @Column(name = "transcript_key")
  private String transcriptKey;

  public String transcriptKey() {
    return transcriptKey;
  }

  @Column(name = "result_key")
  private String resultKey;

  public String resultKey() {
    return resultKey;
  }

  @Column(name = "telegram_path")
  private String telegramPath;

  public String telegramPath() {
    return telegramPath;
  }

  @Column(name = "created_at", insertable = false, updatable = false)
  private java.time.Instant createdAt;

  public java.time.Instant createdAt() {
    return createdAt;
  }

  @Column(name = "updated_at", insertable = false, updatable = false)
  private java.time.Instant updatedAt;

  public java.time.Instant updatedAt() {
    return updatedAt;
  }
}
