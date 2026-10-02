package ru.yanis.videoaudiobot.task.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.model.*;
import ru.yanis.videoaudiobot.repo.JobRepository;
import ru.yanis.videoaudiobot.service.*;
import ru.yanis.videoaudiobot.task.MaintenanceTask;

@Component
class PipelineMaintenanceTask implements MaintenanceTask {
  private static final Logger log = LoggerFactory.getLogger(PipelineMaintenanceTask.class);
  private final JobRepository jobs;
  private final StorageService storage;
  private final TelegramService telegram;
  private final WorkspaceService workspace;
  private final AppProperties config;

  PipelineMaintenanceTask(
      JobRepository jobs,
      StorageService storage,
      TelegramService telegram,
      WorkspaceService workspace,
      AppProperties config) {
    this.jobs = jobs;
    this.storage = storage;
    this.telegram = telegram;
    this.workspace = workspace;
    this.config = config;
  }

  public void notifyUsers(LeaseGuard guard) {
    for (Job job : jobs.pendingNotifications(50)) {
      guard.check();
      try {
        var n = jobs.notification(job.id());
        String state = job.stage() + ":" + job.status();
        String text = status(job);
        Long message = n.messageId();
        if (message == null) message = telegram.sendText(job.chatId(), job.messageId(), text);
        else {
          try {
            telegram.editText(job.chatId(), message, text);
          } catch (ProcessingException e) {
            if (!e.retryable()
                && (e.getMessage().equals("HTTP_400") || e.getMessage().equals("TELEGRAM_400")))
              message = telegram.sendText(job.chatId(), job.messageId(), text);
            else throw e;
          }
        }
        guard.check();
        jobs.notified(job.id(), message, state);
      } catch (Exception e) {
        jobs.notificationFailed(
            job.id(), config.pipeline().retryBase(), config.pipeline().retryMax());
        log.warn("Notification failed for job {} ({})", job.id(), e.getClass().getSimpleName());
      }
    }
  }

  private String status(Job job) {
    String text =
        switch (job.status()) {
          case COMPLETED -> "Готово. TXT и ссылка отправлены.";
          case FAILED ->
              "Обработка остановлена: " + job.lastError() + ". Повтор: /retry " + job.id();
          case RETRY -> "Временная ошибка. Повторим обработку автоматически.";
          case DELETED -> "Файлы удалены.";
          default ->
              switch (job.stage()) {
                case UPLOAD -> "Скачиваю файл и сохраняю в S3";
                case CONVERT -> "Извлекаю и подготавливаю звук";
                case TRANSCRIBE -> "Распознаю речь и разделяю говорящих";
                case FORMAT -> "Формирую TXT";
                case DELIVER -> "Отправляю результат";
              };
        };
    return text + "\nЗадача: " + job.id();
  }

  public void cleanup(LeaseGuard guard) {
    for (Job job : jobs.expired(config.cleanup().retention(), config.cleanup().batchSize())) {
      guard.check();
      try {
        storage.deleteJob(job.id());
        guard.check();
        workspace.removeJob(job.id());
        if (jobs.canDeleteTelegramFile(job)) workspace.removeTelegramFile(job.telegramPath());
        guard.check();
        jobs.deleted(job.id(), config.cleanup().retention());
      } catch (Exception e) {
        log.warn("Cleanup will retry job {} ({})", job.id(), e.getClass().getSimpleName());
      }
    }
  }
}
