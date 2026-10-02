package ru.yanis.videoaudiobot.repo;

import java.time.Duration;
import java.util.*;
import ru.yanis.videoaudiobot.dto.*;
import ru.yanis.videoaudiobot.model.*;

public interface JobRepository {
  Optional<UUID> ingest(long updateId, IncomingFile file);

  long cursor();

  Optional<Job> find(UUID id);

  Optional<Job> latest(long chatId, long userId);

  Optional<Job> claim(Stage stage, UUID token, Duration lease);

  boolean heartbeat(UUID id, UUID token, Duration lease);

  boolean active(UUID id, UUID token);

  void complete(Job job, UUID token, StageOutput output);

  void fail(Job job, UUID token, String error, boolean retryable, int maxAttempts, Duration delay);

  boolean retry(UUID id, long chatId, long userId);

  void telegramPath(UUID id, UUID token, String path);

  Optional<ChunkResult> checkpoint(UUID id, int part);

  void checkpoint(UUID id, UUID token, ChunkResult chunk);

  boolean delivered(UUID id, String item);

  boolean recentlyDelivered(UUID id, String item, Duration maxAge);

  void delivered(UUID id, UUID token, String item, long messageId);

  List<Job> pendingNotifications(int limit);

  Notification notification(UUID id);

  void notified(UUID id, Long messageId, String state);

  void notificationFailed(UUID id, Duration baseDelay, Duration maxDelay);

  List<Job> expired(Duration retention, int limit);

  boolean canDeleteTelegramFile(Job job);

  void deleted(UUID id, Duration retention);
}
