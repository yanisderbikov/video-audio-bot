package ru.yanis.videoaudiobot;

import static org.assertj.core.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.yanis.videoaudiobot.dto.*;
import ru.yanis.videoaudiobot.model.*;
import ru.yanis.videoaudiobot.repo.*;
import ru.yanis.videoaudiobot.service.ProcessingException;

@SpringBootTest(
    properties = {
      "app.pipeline.enabled=false",
      "app.telegram.token=test",
      "app.openai.api-key=test",
      "app.s3.bucket=test",
      "app.s3.access-key=test",
      "app.s3.secret-key=test",
      "spring.jpa.hibernate.ddl-auto=validate"
    })
class PipelineDatabaseTest {
  static final EmbeddedPostgres pg = startPostgres();

  static EmbeddedPostgres startPostgres() {
    try {
      return EmbeddedPostgres.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
    r.add("spring.datasource.username", () -> "postgres");
    r.add("spring.datasource.password", () -> "postgres");
  }

  @Autowired JobRepository jobs;
  @Autowired LeaseRepository leases;
  @Autowired JdbcTemplate sql;

  @BeforeEach
  void clear() {
    sql.execute(
        "TRUNCATE"
            + " transcription_job,transcription_checkpoint,job_delivery,job_notification,worker_lease"
            + " CASCADE");
  }

  UUID enqueue(long update) {
    return jobs.ingest(
            update, new IncomingFile(update, 1, 2, 3, "f", "recording.mp4", "video/mp4", 123))
        .orElseThrow();
  }

  Job claim(Stage stage, UUID token) {
    return jobs.claim(stage, token, Duration.ofMinutes(2)).orElseThrow();
  }

  @Test
  void duplicateUpdateIsAcceptedOnce() {
    UUID id = enqueue(100);
    assertThat(
            jobs.ingest(
                100, new IncomingFile(100, 1, 2, 3, "f", "recording.mp4", "video/mp4", 123)))
        .isEmpty();
    assertThat(sql.queryForObject("select count(*) from transcription_job", Long.class))
        .isEqualTo(1);
    assertThat(jobs.cursor()).isGreaterThanOrEqualTo(101);
    assertThat(jobs.find(id).orElseThrow().updatedAt()).isNotNull();
  }

  @Test
  void stagesAdvanceOnlyAfterCommitAndDatabaseGeneratesUpdatedAt() {
    UUID id = enqueue(101);
    Instant before = jobs.find(id).orElseThrow().updatedAt();
    UUID token = UUID.randomUUID();
    Job job = claim(Stage.UPLOAD, token);
    jobs.complete(job, token, new StageOutput("source"));
    Job next = jobs.find(id).orElseThrow();
    assertThat(next.stage()).isEqualTo(Stage.CONVERT);
    assertThat(next.status()).isEqualTo(JobStatus.READY);
    assertThat(next.sourceKey()).isEqualTo("source");
    assertThat(next.updatedAt()).isAfterOrEqualTo(before);
    assertThat(next.leaseToken()).isNull();
    assertThat(next.attempt()).isZero();
  }

  @Test
  void expiredWorkerCannotOverwriteNewOwnerOrCheckpoint() {
    UUID id = enqueue(102), old = UUID.randomUUID(), fresh = UUID.randomUUID();
    Job previous = claim(Stage.UPLOAD, old);
    sql.update(
        "update transcription_job set lease_until=clock_timestamp()-interval '1 second' where id=?",
        id);
    Job current = claim(Stage.UPLOAD, fresh);
    assertThat(current.attempt()).isEqualTo(2);
    assertThat(jobs.heartbeat(id, old, Duration.ofMinutes(1))).isFalse();
    assertThatThrownBy(() -> jobs.complete(previous, old, new StageOutput("wrong")))
        .isInstanceOf(ProcessingException.class);
    assertThatThrownBy(
            () -> jobs.checkpoint(id, old, new ChunkResult(0, "raw", List.of(), List.of())))
        .isInstanceOf(ProcessingException.class);
    jobs.complete(current, fresh, new StageOutput("right"));
    assertThat(jobs.find(id).orElseThrow().sourceKey()).isEqualTo("right");
  }

  @Test
  void independentStagesUseFifoAndSkipDelayedRetries() {
    UUID first = enqueue(103), second = enqueue(104);
    UUID token = UUID.randomUUID();
    Job failed = claim(Stage.UPLOAD, token);
    assertThat(failed.id()).isEqualTo(first);
    jobs.fail(failed, token, "HTTP_429", true, 5, Duration.ofHours(1));
    Job next = claim(Stage.UPLOAD, UUID.randomUUID());
    assertThat(next.id()).isEqualTo(second);
  }

  @Test
  void simultaneousClaimsNeverShareTheSameJob() throws Exception {
    for (int i = 0; i < 12; i++) enqueue(200 + i);
    var pool = Executors.newFixedThreadPool(6);
    try {
      List<Callable<UUID>> calls = new ArrayList<>();
      for (int i = 0; i < 12; i++) calls.add(() -> claim(Stage.UPLOAD, UUID.randomUUID()).id());
      List<UUID> ids = new ArrayList<>();
      for (var future : pool.invokeAll(calls)) ids.add(future.get());
      assertThat(new HashSet<>(ids)).hasSize(12);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void onlyOneInstanceOwnsEachStageLeaseAndExpiredTokenIsFenced() {
    UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    assertThat(leases.acquire("stage-TRANSCRIBE", first, Duration.ofMinutes(1))).isTrue();
    assertThat(leases.acquire("stage-TRANSCRIBE", second, Duration.ofMinutes(1))).isFalse();
    sql.update("update worker_lease set lease_until=clock_timestamp()-interval '1 second'");
    assertThat(leases.acquire("stage-TRANSCRIBE", second, Duration.ofMinutes(1))).isTrue();
    assertThat(leases.renew("stage-TRANSCRIBE", first, Duration.ofMinutes(1))).isFalse();
    leases.release("stage-TRANSCRIBE", first);
    assertThat(leases.renew("stage-TRANSCRIBE", second, Duration.ofMinutes(1))).isTrue();
  }

  @Test
  void cleanupSelectsOnlyOldCompletedAndNotificationDoesNotExtendRetention() {
    UUID done = enqueue(300), active = enqueue(301), failed = enqueue(302), recent = enqueue(303);
    sql.update("update transcription_job set status='COMPLETED' where id in (?,?)", done, recent);
    sql.update("update transcription_job set status='FAILED' where id=?", failed);
    sql.execute("alter table transcription_job disable trigger transcription_job_updated_at");
    try {
      sql.update(
          "update transcription_job set updated_at=clock_timestamp()-interval '25 hours' where id"
              + " in (?,?,?)",
          done,
          active,
          failed);
    } finally {
      sql.execute("alter table transcription_job enable trigger transcription_job_updated_at");
    }
    Instant timestamp = jobs.find(done).orElseThrow().updatedAt();
    jobs.notified(done, 42L, "DELIVER:COMPLETED");
    assertThat(jobs.find(done).orElseThrow().updatedAt()).isEqualTo(timestamp);
    assertThat(jobs.expired(Duration.ofHours(24), 50)).extracting(Job::id).containsExactly(done);
    jobs.deleted(done, Duration.ofHours(24));
    assertThat(jobs.find(done).orElseThrow().status()).isEqualTo(JobStatus.DELETED);
    jobs.deleted(recent, Duration.ofHours(24));
    assertThat(jobs.find(recent).orElseThrow().status()).isEqualTo(JobStatus.COMPLETED);
  }

  @Test
  void failuresCanOnlyBeRetriedByTheirOwnerAndCompletedJobsAreImmutable() {
    UUID id = enqueue(400);
    UUID token = UUID.randomUUID();
    Job job = claim(Stage.UPLOAD, token);
    jobs.fail(job, token, "BAD_FILE", false, 5, Duration.ZERO);
    assertThat(jobs.retry(id, 1, 999)).isFalse();
    assertThat(jobs.retry(id, 1, 2)).isTrue();
    sql.update("update transcription_job set status='COMPLETED' where id=?", id);
    assertThat(jobs.retry(id, 1, 2)).isFalse();
  }

  @Test
  void checkpointAndDeliveryAreDurableAndDoNotRepeatCompletedParts() {
    UUID id = enqueue(500), token = UUID.randomUUID();
    Job job = claim(Stage.UPLOAD, token);
    var result =
        new ChunkResult(
            0, "raw", List.of(new Segment(0, 3, "part1_speaker1", "Привет")), List.of());
    jobs.checkpoint(id, token, result);
    jobs.delivered(id, token, "text", 123);
    assertThat(jobs.checkpoint(id, 0)).contains(result);
    assertThat(jobs.delivered(id, "text")).isTrue();
    jobs.checkpoint(id, token, new ChunkResult(0, "other", List.of(), List.of()));
    assertThat(jobs.checkpoint(id, 0)).contains(result);
  }
}
