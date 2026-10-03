package ru.yanis.videoaudiobot.repo.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.yanis.videoaudiobot.dto.*;
import ru.yanis.videoaudiobot.model.*;
import ru.yanis.videoaudiobot.repo.JobRepository;
import ru.yanis.videoaudiobot.service.ProcessingException;

@Repository
@Transactional
class HibernateJobRepository implements JobRepository {
  private final EntityManager em;
  private final ObjectMapper json;

  HibernateJobRepository(EntityManager em, ObjectMapper json) {
    this.em = em;
    this.json = json;
  }

  public Optional<UUID> ingest(long updateId, IncomingFile f) {
    UUID id = UUID.randomUUID();
    boolean inserted = false;
    if (f != null) {
      inserted =
          em.createNativeQuery(
                      """
INSERT INTO transcription_job(id,update_id,chat_id,user_id,message_id,file_id,file_unique_id,file_name,mime_type,file_size,stage,status)
SELECT :id,:updateId,:chatId,:userId,:messageId,:fileId,:fileUniqueId,:fileName,:mimeType,:fileSize,'UPLOAD','READY'
WHERE CAST(:fileUniqueId AS varchar) IS NULL OR NOT EXISTS (
  SELECT 1 FROM transcription_job WHERE chat_id=:chatId AND user_id=:userId
  AND file_unique_id=:fileUniqueId AND status<>'FAILED')
ON CONFLICT(update_id) DO NOTHING
""")
                  .setParameter("id", id)
                  .setParameter("updateId", updateId)
                  .setParameter("chatId", f.chatId())
                  .setParameter("userId", f.userId())
                  .setParameter("messageId", f.messageId())
                  .setParameter("fileId", f.fileId())
                  .setParameter("fileUniqueId", f.fileUniqueId())
                  .setParameter("fileName", f.fileName())
                  .setParameter("mimeType", f.mimeType())
                  .setParameter("fileSize", f.fileSize())
                  .executeUpdate()
              == 1;
      if (inserted)
        em.createNativeQuery("INSERT INTO job_notification(job_id) VALUES(:id)")
            .setParameter("id", id)
            .executeUpdate();
    }
    em.createNativeQuery(
            "UPDATE bot_cursor SET next_update_id=greatest(next_update_id,:next) WHERE id=1")
        .setParameter("next", updateId + 1)
        .executeUpdate();
    return inserted ? Optional.of(id) : Optional.empty();
  }

  public long cursor() {
    return ((Number)
            em.createNativeQuery("SELECT next_update_id FROM bot_cursor WHERE id=1")
                .getSingleResult())
        .longValue();
  }

  public Optional<Job> find(UUID id) {
    return em.createQuery("from Job j where j.id=:id", Job.class)
        .setParameter("id", id)
        .getResultStream()
        .findFirst();
  }

  public Optional<Job> latest(long chatId, long userId) {
    return em.createQuery(
            "from Job j where j.chatId=:chat and j.userId=:user order by j.createdAt desc",
            Job.class)
        .setParameter("chat", chatId)
        .setParameter("user", userId)
        .setMaxResults(1)
        .getResultStream()
        .findFirst();
  }

  public Optional<java.time.Instant> quotaResetAt(long userId, Duration window, int limit) {
    // The limit-th most recent job leaves the window first.
    return em
        .createNativeQuery(
            "SELECT created_at+:seconds*interval '1 second' FROM transcription_job WHERE"
                + " user_id=:user AND status<>'FAILED' AND"
                + " created_at>clock_timestamp()-:seconds*interval '1 second'"
                + " ORDER BY created_at DESC OFFSET :skip LIMIT 1",
            java.time.Instant.class)
        .setParameter("user", userId)
        .setParameter("seconds", window.toSeconds())
        .setParameter("skip", limit - 1)
        .getResultStream()
        .findFirst()
        .map(java.time.Instant.class::cast);
  }

  public Optional<Job> duplicate(long chatId, long userId, String fileUniqueId) {
    if (fileUniqueId == null) return Optional.empty();
    return em
        .createNativeQuery(
            "SELECT CAST(id AS varchar) FROM transcription_job WHERE chat_id=:chat AND"
                + " user_id=:user AND file_unique_id=:file AND status<>'FAILED'"
                + " ORDER BY created_at LIMIT 1")
        .setParameter("chat", chatId)
        .setParameter("user", userId)
        .setParameter("file", fileUniqueId)
        .getResultStream()
        .findFirst()
        .flatMap(id -> find(UUID.fromString((String) id)));
  }

  public Optional<Job> claim(Stage stage, UUID token, Duration lease) {
    List<?> ids =
        em.createNativeQuery(
                """
                SELECT id FROM transcription_job WHERE stage=:stage AND
                ((status IN ('READY','RETRY') AND next_attempt_at<=clock_timestamp()) OR
                 (status='RUNNING' AND lease_until<clock_timestamp()))
                ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                """)
            .setParameter("stage", stage.name())
            .getResultList();
    if (ids.isEmpty()) return Optional.empty();
    UUID id = (UUID) ids.get(0);
    em.createNativeQuery(
            """
UPDATE transcription_job SET status='RUNNING',lease_token=:token,
lease_until=clock_timestamp()+:seconds*interval '1 second',attempt=attempt+1 WHERE id=:id
""")
        .setParameter("id", id)
        .setParameter("token", token)
        .setParameter("seconds", lease.toSeconds())
        .executeUpdate();
    em.clear();
    return find(id);
  }

  public boolean heartbeat(UUID id, UUID token, Duration lease) {
    return em.createNativeQuery(
                """
UPDATE transcription_job SET lease_until=clock_timestamp()+:seconds*interval '1 second'
WHERE id=:id AND lease_token=:token AND status='RUNNING' AND lease_until>clock_timestamp()
""")
            .setParameter("id", id)
            .setParameter("token", token)
            .setParameter("seconds", lease.toSeconds())
            .executeUpdate()
        == 1;
  }

  public boolean active(UUID id, UUID token) {
    return !em.createNativeQuery(
            "SELECT id FROM transcription_job WHERE id=:id AND lease_token=:token AND"
                + " status='RUNNING' AND lease_until>clock_timestamp()")
        .setParameter("id", id)
        .setParameter("token", token)
        .getResultList()
        .isEmpty();
  }

  private void requireActive(UUID id, UUID token) {
    if (em.createNativeQuery(
            "SELECT id FROM transcription_job WHERE id=:id AND lease_token=:token AND"
                + " status='RUNNING' AND lease_until>clock_timestamp() FOR UPDATE")
        .setParameter("id", id)
        .setParameter("token", token)
        .getResultList()
        .isEmpty()) throw new ProcessingException("LEASE_LOST", true);
  }

  public void complete(Job job, UUID token, StageOutput out) {
    requireActive(job.id(), token);
    String column =
        switch (job.stage()) {
          case UPLOAD -> "source_key";
          case CONVERT -> "audio_key";
          case TRANSCRIBE -> "transcript_key";
          case FORMAT, DELIVER -> "result_key";
        };
    Stage next =
        job.stage() == Stage.DELIVER ? Stage.DELIVER : Stage.values()[job.stage().ordinal() + 1];
    em.createNativeQuery(
            "UPDATE transcription_job SET "
                + column
                + "=:key,stage=:stage,status=:status,attempt=0,last_error=NULL,lease_token=NULL,lease_until=NULL,next_attempt_at=clock_timestamp()"
                + " WHERE id=:id")
        .setParameter("key", out.key())
        .setParameter("stage", next.name())
        .setParameter("status", job.stage() == Stage.DELIVER ? "COMPLETED" : "READY")
        .setParameter("id", job.id())
        .executeUpdate();
  }

  public void fail(
      Job job, UUID token, String error, boolean retryable, int maxAttempts, Duration delay) {
    em.createNativeQuery(
            """
UPDATE transcription_job SET status=:status,last_error=:error,lease_token=NULL,lease_until=NULL,
next_attempt_at=clock_timestamp()+:seconds*interval '1 second'
WHERE id=:id AND lease_token=:token AND status='RUNNING' AND lease_until>clock_timestamp()
""")
        .setParameter("status", retryable && job.attempt() < maxAttempts ? "RETRY" : "FAILED")
        .setParameter("error", error)
        .setParameter("seconds", delay.toSeconds())
        .setParameter("id", job.id())
        .setParameter("token", token)
        .executeUpdate();
  }

  public boolean retry(UUID id, long chatId, long userId) {
    return em.createNativeQuery(
                """
UPDATE transcription_job SET status='READY',attempt=0,last_error=NULL,next_attempt_at=clock_timestamp()
WHERE id=:id AND chat_id=:chat AND user_id=:user AND status='FAILED'
""")
            .setParameter("id", id)
            .setParameter("chat", chatId)
            .setParameter("user", userId)
            .executeUpdate()
        == 1;
  }

  public void telegramPath(UUID id, UUID token, String path) {
    requireActive(id, token);
    em.createNativeQuery("UPDATE transcription_job SET telegram_path=:path WHERE id=:id")
        .setParameter("path", path)
        .setParameter("id", id)
        .executeUpdate();
  }

  public Optional<ChunkResult> checkpoint(UUID id, int part) {
    List<?> found =
        em.createNativeQuery(
                "SELECT payload FROM transcription_checkpoint WHERE job_id=:id AND"
                    + " part_index=:part")
            .setParameter("id", id)
            .setParameter("part", part)
            .getResultList();
    if (found.isEmpty()) return Optional.empty();
    try {
      return Optional.of(json.readValue((String) found.get(0), ChunkResult.class));
    } catch (Exception e) {
      throw new ProcessingException("CHECKPOINT_INVALID", false);
    }
  }

  public void checkpoint(UUID id, UUID token, ChunkResult chunk) {
    requireActive(id, token);
    try {
      em.createNativeQuery(
              """
INSERT INTO transcription_checkpoint(job_id,part_index,payload) VALUES(:id,:part,:payload)
ON CONFLICT(job_id,part_index) DO NOTHING
""")
          .setParameter("id", id)
          .setParameter("part", chunk.index())
          .setParameter("payload", json.writeValueAsString(chunk))
          .executeUpdate();
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ProcessingException("CHECKPOINT_INVALID", false);
    }
  }

  public boolean delivered(UUID id, String item) {
    return !em.createNativeQuery("SELECT 1 FROM job_delivery WHERE job_id=:id AND item=:item")
        .setParameter("id", id)
        .setParameter("item", item)
        .getResultList()
        .isEmpty();
  }

  public void delivered(UUID id, UUID token, String item, long messageId) {
    requireActive(id, token);
    em.createNativeQuery(
            "INSERT INTO job_delivery(job_id,item,message_id) VALUES(:id,:item,:message) ON"
                + " CONFLICT(job_id,item) DO UPDATE SET"
                + " message_id=excluded.message_id,sent_at=clock_timestamp()")
        .setParameter("id", id)
        .setParameter("item", item)
        .setParameter("message", messageId)
        .executeUpdate();
  }

  public List<Job> pendingNotifications(int limit) {
    List<?> ids =
        em.createNativeQuery(
                """
SELECT j.id FROM transcription_job j JOIN job_notification n ON j.id=n.job_id
WHERE j.status<>'DELETED' AND n.next_attempt_at<=clock_timestamp() AND (n.sent_state IS DISTINCT FROM j.stage||':'||j.status)
ORDER BY j.created_at LIMIT :limit
""")
            .setParameter("limit", limit)
            .getResultList();
    return ids.stream().map(id -> find((UUID) id).orElseThrow()).toList();
  }

  public Notification notification(UUID id) {
    Object[] row =
        (Object[])
            em.createNativeQuery(
                    "SELECT message_id,sent_state FROM job_notification WHERE job_id=:id")
                .setParameter("id", id)
                .getSingleResult();
    return new Notification(
        id, row[0] == null ? null : ((Number) row[0]).longValue(), (String) row[1]);
  }

  public void notified(UUID id, Long messageId, String state) {
    em.createNativeQuery(
            "UPDATE job_notification SET"
                + " message_id=:message,sent_state=:state,attempt=0,next_attempt_at=clock_timestamp()"
                + " WHERE job_id=:id")
        .setParameter("message", messageId)
        .setParameter("state", state)
        .setParameter("id", id)
        .executeUpdate();
  }

  public void notificationFailed(UUID id, Duration baseDelay, Duration maxDelay) {
    em.createNativeQuery(
            "UPDATE job_notification SET"
                + " next_attempt_at=clock_timestamp()+least(:maxDelay,:baseDelay*power(2,least(attempt,20)))*interval"
                + " '1 second',attempt=attempt+1 WHERE job_id=:id")
        .setParameter("id", id)
        .setParameter("maxDelay", maxDelay.toSeconds())
        .setParameter("baseDelay", baseDelay.toSeconds())
        .executeUpdate();
  }

  public List<Job> expired(Duration retention, int limit) {
    List<?> ids =
        em.createNativeQuery(
                "SELECT id FROM transcription_job WHERE status='COMPLETED' AND"
                    + " updated_at<=clock_timestamp()-:seconds*interval '1 second' ORDER BY"
                    + " updated_at LIMIT :limit")
            .setParameter("seconds", retention.toSeconds())
            .setParameter("limit", limit)
            .getResultList();
    return ids.stream().map(id -> find((UUID) id).orElseThrow()).toList();
  }

  public boolean canDeleteTelegramFile(Job job) {
    return ((Number)
                em.createNativeQuery(
                        "SELECT count(*) FROM transcription_job WHERE id<>:id AND file_id=:file AND"
                            + " status<>'DELETED'")
                    .setParameter("id", job.id())
                    .setParameter("file", job.fileId())
                    .getSingleResult())
            .longValue()
        == 0;
  }

  public void deleted(UUID id, Duration retention) {
    int n =
        em.createNativeQuery(
                "UPDATE transcription_job SET status='DELETED' WHERE id=:id AND status='COMPLETED'"
                    + " AND updated_at<=clock_timestamp()-:seconds*interval '1 second'")
            .setParameter("id", id)
            .setParameter("seconds", retention.toSeconds())
            .executeUpdate();
    if (n == 1) {
      em.createNativeQuery("DELETE FROM transcription_checkpoint WHERE job_id=:id")
          .setParameter("id", id)
          .executeUpdate();
      em.createNativeQuery("DELETE FROM job_delivery WHERE job_id=:id")
          .setParameter("id", id)
          .executeUpdate();
    }
  }
}
