package ru.yanis.videoaudiobot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.yanis.videoaudiobot.dto.*;
import ru.yanis.videoaudiobot.model.*;
import ru.yanis.videoaudiobot.repo.*;
import ru.yanis.videoaudiobot.service.*;
import ru.yanis.videoaudiobot.task.*;

@SpringBootTest(
    properties = {
      "app.pipeline.enabled=false",
      "app.telegram.token=test",
      "app.openai.api-key=test",
      "app.s3.bucket=test",
      "app.s3.access-key=test",
      "app.s3.secret-key=test",
      "app.media.chunk-seconds=30",
      "app.telegram.server-directory=/telegram"
    })
class FullPipelineTest {
  static final Path root = tempDirectory();

  static Path tempDirectory() {
    try {
      return Files.createTempDirectory("bot-e2e-");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    PipelineDatabaseTest.database(r);
    r.add("app.telegram.local-directory", () -> root.toString());
    r.add("app.work-directory", () -> root.resolve("work").toString());
  }

  @Autowired JobRepository jobs;
  @Autowired JdbcTemplate sql;
  @Autowired ObjectMapper json;
  @Autowired List<StageHandler> handlers;
  @Autowired BotUpdateService updates;
  @Autowired WorkspaceService workspace;
  @Autowired MaintenanceTask maintenance;
  @MockitoBean StorageService storage;
  @MockitoBean TelegramService telegram;
  @MockitoBean TranscriptionService transcription;
  final Map<String, byte[]> objects = new HashMap<>();
  final LeaseGuard guard =
      new LeaseGuard() {
        public void check() {}

        public void attach(UUID id) {}
      };

  @Test
  void resentFileIsNotProcessedAgainUnlessPreviousJobFailed() throws Exception {
    sql.execute(
        "TRUNCATE"
            + " transcription_job,transcription_checkpoint,job_delivery,job_notification,worker_lease"
            + " CASCADE");
    String template =
        "{\"update_id\":%d,\"message\":{\"message_id\":%d,\"chat\":{\"id\":5},\"from\":{\"id\":6},"
            + "\"voice\":{\"file_id\":\"id-%d\",\"file_unique_id\":\"same\",\"file_size\":100}}}";
    JsonNode first = json.readTree(template.formatted(9100, 20, 1));
    updates.accept(first);
    updates.accept(first);
    updates.accept(json.readTree(template.formatted(9101, 21, 2)));
    assertThat(jobCount(5)).isEqualTo(1);
    verify(telegram, times(1)).sendText(eq(5L), eq(20L), contains("уже отправлялся"));

    sql.update("update transcription_job set status='FAILED' where chat_id=5");
    updates.accept(json.readTree(template.formatted(9102, 22, 3)));
    assertThat(jobCount(5)).isEqualTo(2);
  }

  long jobCount(long chat) {
    return sql.queryForObject(
        "select count(*) from transcription_job where chat_id=?", Long.class, chat);
  }

  @Test
  void largeVideoResumesTranscriptionAndDeliveryThenCleansOnlyCompletedJob() throws Exception {
    sql.execute(
        "TRUNCATE"
            + " transcription_job,transcription_checkpoint,job_delivery,job_notification,worker_lease"
            + " CASCADE");
    Path source = root.resolve("source.mp4");
    Process generated =
        new ProcessBuilder(
                "ffmpeg",
                "-nostdin",
                "-v",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:duration=65",
                "-c:a",
                "aac",
                source.toString())
            .inheritIO()
            .start();
    assertThat(generated.waitFor()).isZero();
    try (var append = Files.newOutputStream(source, StandardOpenOption.APPEND)) {
      append.write(new byte[21 * 1024 * 1024]);
    }
    assertThat(Files.size(source)).isGreaterThan(20 * 1024 * 1024);
    when(telegram.file("large-file"))
        .thenReturn(new RemoteFile("/telegram/source.mp4", Files.size(source)));
    when(telegram.sendDocument(anyLong(), anyLong(), any(), anyString(), anyString()))
        .thenThrow(new ProcessingException("HTTP_429", true))
        .thenReturn(900L);
    when(telegram.sendText(anyLong(), anyLong(), anyString())).thenReturn(901L);
    when(storage.key(any(), any(), anyString()))
        .thenAnswer(i -> i.getArgument(0) + "/" + i.getArgument(1) + "/" + i.getArgument(2));
    doAnswer(
            i -> {
              objects.put(i.getArgument(0), Files.readAllBytes(i.getArgument(1, Path.class)));
              return null;
            })
        .when(storage)
        .put(anyString(), any(), anyString());
    doAnswer(
            i -> {
              Files.write(i.getArgument(1, Path.class), objects.get(i.getArgument(0)));
              return null;
            })
        .when(storage)
        .get(anyString(), any());
    doAnswer(
            i -> {
              objects.put(i.getArgument(0), json.writeValueAsBytes(i.getArgument(1)));
              return null;
            })
        .when(storage)
        .putJson(anyString(), any());
    when(storage.getJson(anyString(), any()))
        .thenAnswer(
            i -> json.readValue(objects.get(i.getArgument(0)), i.getArgument(1, Class.class)));
    when(storage.signedUrl(anyString(), any()))
        .thenReturn("https://s3.example/transcript?signature=test");
    AtomicInteger calls = new AtomicInteger();
    when(transcription.transcribe(any(), anyMap()))
        .thenAnswer(
            i -> {
              if (calls.incrementAndGet() == 2) throw new ProcessingException("HTTP_429", true);
              Map<String, Path> references = i.getArgument(1);
              String speaker = references.isEmpty() ? "A" : references.keySet().iterator().next();
              return json.readTree(
                  "{\"segments\":[{\"start\":2.5,\"end\":6,\"speaker\":\""
                      + speaker
                      + "\",\"text\":\"Русская речь\"}]}");
            });
    JsonNode update =
        json.readTree(
            "{\"update_id\":9000,\"message\":{\"message_id\":10,\"chat\":{\"id\":1},\"from\":{\"id\":2},\"video\":{\"file_id\":\"large-file\",\"file_size\":22000000}}}");
    updates.accept(update);
    updates.accept(update);
    Job accepted = jobs.latest(1, 2).orElseThrow();
    UUID id = accepted.id();
    runStage(Stage.UPLOAD, false);
    assertThat(jobs.find(id).orElseThrow().stage()).isEqualTo(Stage.CONVERT);
    runStage(Stage.CONVERT, false);
    runStage(Stage.TRANSCRIBE, true);
    assertThat(jobs.checkpoint(id, 0)).isPresent();
    assertThat(jobs.checkpoint(id, 1)).isEmpty();
    assertThat(jobs.find(id).orElseThrow().status()).isEqualTo(JobStatus.RETRY);
    runStage(Stage.TRANSCRIBE, false);
    assertThat(calls.get()).isEqualTo(4);
    runStage(Stage.FORMAT, false);
    runStage(Stage.DELIVER, true);
    runStage(Stage.DELIVER, false);
    verify(telegram, times(2))
        .sendDocument(
            eq(1L),
            eq(10L),
            any(),
            eq("Русская речь Русская речь.txt"),
            contains("<a href=\"https://s3.example/transcript?signature=test\">Скачать TXT</a>"));
    verify(telegram, never()).sendText(anyLong(), anyLong(), contains("Спикер"));
    assertThat(jobs.find(id).orElseThrow().status()).isEqualTo(JobStatus.COMPLETED);
    assertThat(calls.get()).isEqualTo(4);
    String resultKey = jobs.find(id).orElseThrow().resultKey();
    assertThat(new String(objects.get(resultKey), java.nio.charset.StandardCharsets.UTF_8))
        .contains("Русская речь")
        .contains("Спикер 1");
    maintenance.cleanup(guard);
    verify(storage, never()).deleteJob(any());
    sql.execute("alter table transcription_job disable trigger transcription_job_updated_at");
    try {
      sql.update(
          "update transcription_job set updated_at=clock_timestamp()-interval '25 hours' where"
              + " id=?",
          id);
    } finally {
      sql.execute("alter table transcription_job enable trigger transcription_job_updated_at");
    }
    Instant aged = jobs.find(id).orElseThrow().updatedAt();
    doThrow(new ProcessingException("S3_DELETE_INCOMPLETE", true))
        .doNothing()
        .when(storage)
        .deleteJob(id);
    maintenance.cleanup(guard);
    assertThat(jobs.find(id).orElseThrow().status()).isEqualTo(JobStatus.COMPLETED);
    assertThat(jobs.find(id).orElseThrow().updatedAt()).isEqualTo(aged);
    maintenance.cleanup(guard);
    assertThat(jobs.find(id).orElseThrow().status()).isEqualTo(JobStatus.DELETED);
    assertThat(Files.exists(source)).isFalse();
    assertThat(jobs.checkpoint(id, 0)).isEmpty();
  }

  private void runStage(Stage stage, boolean expectFailure) {
    UUID token = UUID.randomUUID();
    Job job = jobs.claim(stage, token, Duration.ofMinutes(2)).orElseThrow();
    Path dir = workspace.attempt(job.id(), token);
    StageHandler handler =
        handlers.stream().filter(h -> h.stage() == stage).findFirst().orElseThrow();
    try {
      StageOutput result = handler.execute(job, token, dir, guard);
      if (expectFailure) fail("Expected a transient failure");
      jobs.complete(job, token, result);
    } catch (ProcessingException e) {
      if (!expectFailure) throw e;
      jobs.fail(job, token, e.getMessage(), e.retryable(), 5, Duration.ZERO);
    } finally {
      workspace.removeAttempt(dir);
    }
  }
}
