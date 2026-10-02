package ru.yanis.videoaudiobot.service;

import java.nio.file.Path;
import java.util.UUID;

public interface WorkspaceService {
  Path attempt(UUID job, UUID token);

  Path telegramFile(String serverPath);

  void removeAttempt(Path path);

  void removeJob(UUID job);

  void removeTelegramFile(String serverPath);
}
