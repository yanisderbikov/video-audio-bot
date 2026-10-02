package ru.yanis.videoaudiobot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Service;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.dto.IncomingFile;
import ru.yanis.videoaudiobot.repo.JobRepository;
import ru.yanis.videoaudiobot.service.*;

@Service
class TelegramUpdateService implements BotUpdateService {
  private final JobRepository jobs;
  private final TelegramService telegram;
  private final AppProperties.Telegram config;

  TelegramUpdateService(JobRepository jobs, TelegramService telegram, AppProperties p) {
    this.jobs = jobs;
    this.telegram = telegram;
    this.config = p.telegram();
  }

  public void accept(JsonNode update) {
    if (!update.has("update_id")) throw new IllegalArgumentException("update_id required");
    long id = update.path("update_id").asLong();
    JsonNode message = update.path("message");
    if (message.isMissingNode() || message.path("from").path("is_bot").asBoolean()) {
      jobs.ingest(id, null);
      return;
    }
    long user = message.path("from").path("id").asLong(),
        chat = message.path("chat").path("id").asLong(),
        reply = message.path("message_id").asLong();
    if (config.allowedUserIds() != null
        && !config.allowedUserIds().isEmpty()
        && !config.allowedUserIds().contains(user)) {
      jobs.ingest(id, null);
      return;
    }
    JsonNode file = null;
    for (String type : List.of("video", "audio", "voice", "video_note", "document"))
      if (message.has(type)) {
        file = message.path(type);
        break;
      }
    if (file != null && file.hasNonNull("file_id")) {
      long size = file.path("file_size").asLong();
      if (size > config.maxFileBytes()) {
        jobs.ingest(id, null);
        telegram.sendText(
            chat, reply, "Файл превышает настроенный лимит: " + config.maxFileBytes() + " байт.");
        return;
      }
      String name = file.path("file_name").asText("recording");
      if (name.length() > 512) name = name.substring(0, 512);
      String mime = file.path("mime_type").asText("application/octet-stream");
      jobs.ingest(
          id,
          new IncomingFile(id, chat, user, reply, file.path("file_id").asText(), name, mime, size));
      return;
    }
    jobs.ingest(id, null);
    String text = message.path("text").asText("").strip();
    String[] words = text.split("\\s+", 2);
    String command = words[0].split("@", 2)[0];
    switch (command) {
      case "/start", "/help" ->
          telegram.sendText(
              chat,
              reply,
              "Отправь видео, аудио, голосовое сообщение или файл. Я верну транскрипцию с"
                  + " говорящими, TXT и временную ссылку.\n"
                  + "/status — последняя задача\n"
                  + "/retry <id> — повторить свою задачу с ошибкой.");
      case "/status" ->
          telegram.sendText(
              chat,
              reply,
              jobs.latest(chat, user)
                  .map(j -> j.id() + "\n" + j.stage() + " / " + j.status())
                  .orElse("Задач пока нет."));
      case "/retry" -> {
        boolean retried = false;
        try {
          if (words.length == 2)
            retried = jobs.retry(UUID.fromString(words[1].strip()), chat, user);
        } catch (IllegalArgumentException ignored) {
        }
        telegram.sendText(
            chat,
            reply,
            retried
                ? "Задача снова в очереди."
                : "Укажи /retry <id> своей задачи в статусе FAILED.");
      }
      default -> {
        if (!text.isEmpty())
          telegram.sendText(chat, reply, "Пришли видео или аудио. Справка: /help");
      }
    }
  }
}
