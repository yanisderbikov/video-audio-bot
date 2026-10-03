package ru.yanis.videoaudiobot.service;

import java.nio.file.Path;
import java.util.UUID;

public interface WorkspaceService {
  Path attempt(UUID job, UUID token);

  /** Copies a Local Bot API file to destination, enforcing maxBytes. */
  void copyTelegramFile(String serverPath, Path destination, long maxBytes);

  void removeAttempt(Path path);

  void removeJob(UUID job);

  void removeTelegramFile(String serverPath);
}
