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
      StorageService storage, TelegramService telegram, JobRepository jobs, AppProperties config) {
    this.storage = storage;
    this.telegram = telegram;
    this.jobs = jobs;
    this.config = config;
  }

  public Stage stage() {
    return Stage.DELIVER;
  }

  public StageOutput execute(Job job, UUID token, Path dir, LeaseGuard guard) {
    Path path = dir.resolve("transcript.txt");
    storage.get(job.resultKey(), path);
    if (!jobs.delivered(job.id(), "document")) {
      guard.check();
      long message =
          telegram.sendDocument(
              job.chatId(),
              job.messageId(),
              path,
              "Транскрипция с таймкодами и говорящими. Задача: " + job.id());
      jobs.delivered(job.id(), token, "document", message);
    }
    if (!jobs.delivered(job.id(), "text")) {
      String text;
      try {
        text = Files.readString(path);
      } catch (java.io.IOException e) {
        throw new ProcessingException("LOCAL_IO", true);
      }
      int limit = config.telegram().textLimit();
      if (text.length() > limit) {
        int end = limit - 100;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        text = text.substring(0, end) + "\n…\nПолный текст — в TXT-файле.";
      }
      guard.check();
      long message = telegram.sendText(job.chatId(), job.messageId(), text);
      jobs.delivered(job.id(), token, "text", message);
    }
    if (!jobs.recentlyDelivered(job.id(), "link", config.s3().linkTtl().dividedBy(2))) {
      guard.check();
      String url = storage.signedUrl(job.resultKey(), config.s3().linkTtl());
      String until =
          DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss 'UTC'")
              .withZone(ZoneOffset.UTC)
              .format(Instant.now().plus(config.s3().linkTtl()));
      long message =
          telegram.sendText(
              job.chatId(),
              job.messageId(),
              "Скачать TXT из S3:\n"
                  + url
                  + "\nСсылка действует до "
                  + until
                  + ". Файлы удаляются через "
                  + config.cleanup().retention().toHours()
                  + " ч после завершения задачи.");
      jobs.delivered(job.id(), token, "link", message);
    }
    return new StageOutput(job.resultKey());
  }
}
