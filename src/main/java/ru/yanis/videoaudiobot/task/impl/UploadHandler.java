package ru.yanis.videoaudiobot.task.impl;

import java.nio.file.*;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.dto.*;
import ru.yanis.videoaudiobot.model.*;
import ru.yanis.videoaudiobot.repo.JobRepository;
import ru.yanis.videoaudiobot.service.*;
import ru.yanis.videoaudiobot.task.StageHandler;

@Component
class UploadHandler implements StageHandler {
  private final TelegramService telegram;
  private final StorageService storage;
  private final WorkspaceService work;
  private final JobRepository jobs;
  private final AppProperties config;

  UploadHandler(
      TelegramService telegram,
      StorageService storage,
      WorkspaceService work,
      JobRepository jobs,
      AppProperties config) {
    this.telegram = telegram;
    this.storage = storage;
    this.work = work;
    this.jobs = jobs;
    this.config = config;
  }

  public Stage stage() {
    return Stage.UPLOAD;
  }

  public StageOutput execute(Job job, UUID token, Path dir, LeaseGuard guard) {
    guard.check();
    RemoteFile remote = telegram.file(job.fileId());
    if (remote.size() > config.telegram().maxFileBytes())
      throw new ProcessingException("FILE_TOO_LARGE", false);
    Path source = dir.resolve("source");
    try {
      if (remote.path().startsWith("/")) {
        jobs.telegramPath(job.id(), token, remote.path());
        Path local = work.telegramFile(remote.path());
        if (Files.size(local) > config.telegram().maxFileBytes())
          throw new ProcessingException("FILE_TOO_LARGE", false);
        Files.copy(local, source, StandardCopyOption.REPLACE_EXISTING);
      } else telegram.download(remote.path(), source);
      if (Files.size(source) == 0 || Files.size(source) > config.telegram().maxFileBytes())
        throw new ProcessingException("FILE_SIZE_INVALID", false);
    } catch (java.io.IOException e) {
      throw new ProcessingException("SOURCE_COPY_IO", true);
    }
    guard.check();
    String key = storage.key(job.id(), token, "source");
    storage.put(key, source, job.mimeType());
    return new StageOutput(key);
  }
}
