package ru.yanis.videoaudiobot.task.impl;

import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.dto.*;
import ru.yanis.videoaudiobot.model.*;
import ru.yanis.videoaudiobot.repo.JobRepository;
import ru.yanis.videoaudiobot.service.*;
import ru.yanis.videoaudiobot.task.StageHandler;

@Component
class DeliverHandler implements StageHandler {
  private final StorageService storage;
  private final TelegramService telegram;
  private final JobRepository jobs;
  private final AppProperties config;

  DeliverHandler(
      StorageService storage,
      TelegramService telegram,
      JobRepository jobs,
      AppProperties config) {
    this.storage = storage;
    this.telegram = telegram;
    this.jobs = jobs;
    this.config = config;
  }

  public Stage stage() {
    return Stage.DELIVER;
  }

  public StageOutput execute(Job job, UUID token, Path dir, LeaseGuard guard) {
    if (!jobs.delivered(job.id(), "document")) {
      Path path = dir.resolve("transcript.txt");
      storage.get(job.resultKey(), path);
      String name = StorageService.fileName(job.resultKey());
      guard.check();
      String url = storage.signedUrl(job.resultKey(), config.s3().linkTtl());
      String until =
          DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm 'UTC'")
              .withZone(ZoneOffset.UTC)
              .format(Instant.now().plus(config.s3().linkTtl()));
      String caption =
          "<a href=\""
              + html(url)
              + "\">Скачать TXT</a> — ссылка действует до "
              + until
              + ". Файлы удаляются через "
              + config.cleanup().retention().toHours()
              + " ч после завершения задачи.";
      long message = telegram.sendDocument(job.chatId(), job.messageId(), path, name, caption);
      jobs.delivered(job.id(), token, "document", message);
    }
    return new StageOutput(job.resultKey());
  }

  private static String html(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
}
