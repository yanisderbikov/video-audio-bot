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

  /**
   * When the user may submit again if their non-failed jobs in the window already reach limit;
   * empty if they are under the limit.
   */
  Optional<java.time.Instant> quotaResetAt(long userId, Duration window, int limit);

  /** An earlier job of this user for the same file that has not failed. */
  Optional<Job> duplicate(long chatId, long userId, String fileUniqueId);

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

  void delivered(UUID id, UUID token, String item, long messageId);

  List<Job> pendingNotifications(int limit);

  Notification notification(UUID id);

  void notified(UUID id, Long messageId, String state);

  void notificationFailed(UUID id, Duration baseDelay, Duration maxDelay);

  List<Job> expired(Duration retention, int limit);

  boolean canDeleteTelegramFile(Job job);

  void deleted(UUID id, Duration retention);
}
