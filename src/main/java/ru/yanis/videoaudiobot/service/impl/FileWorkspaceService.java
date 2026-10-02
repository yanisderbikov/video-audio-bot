package ru.yanis.videoaudiobot.service.impl;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.service.*;

@Service
class FileWorkspaceService implements WorkspaceService {
  private final Path root, serverRoot, localRoot;

  FileWorkspaceService(AppProperties p) {
    root = p.workDirectory().toAbsolutePath().normalize();
    serverRoot = p.telegram().serverDirectory().toAbsolutePath().normalize();
    localRoot = p.telegram().localDirectory().toAbsolutePath().normalize();
  }

  public Path attempt(UUID job, UUID token) {
    Path path = root.resolve(job.toString()).resolve(token.toString());
    try {
      Files.createDirectories(path);
      return path;
    } catch (IOException e) {
      throw new ProcessingException("WORK_DIRECTORY_IO", true);
    }
  }

  private Path mapped(String path) {
    Path remote = Path.of(path).normalize();
    if (!remote.isAbsolute() || !remote.startsWith(serverRoot) || remote.equals(serverRoot))
      throw new ProcessingException("TELEGRAM_PATH_INVALID", false);
    return localRoot.resolve(serverRoot.relativize(remote)).normalize();
  }

  public Path telegramFile(String path) {
    try {
      Path file = mapped(path).toRealPath();
      if (!file.startsWith(localRoot.toRealPath()) || !Files.isRegularFile(file))
        throw new ProcessingException("TELEGRAM_PATH_INVALID", false);
      return file;
    } catch (IOException e) {
      throw new ProcessingException("TELEGRAM_SHARED_VOLUME_MISSING", true);
    }
  }

  public void removeTelegramFile(String path) {
    if (path == null) return;
    Path file = mapped(path);
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
    try {
      Files.deleteIfExists(telegramFile(path));
    } catch (IOException e) {
      throw new ProcessingException("TELEGRAM_CACHE_DELETE", true);
    }
  }

  public void removeAttempt(Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    if (!normalized.startsWith(root) || normalized.equals(root))
      throw new IllegalArgumentException("Invalid work path");
    deleteTree(normalized);
  }

  public void removeJob(UUID job) {
    removeAttempt(root.resolve(job.toString()));
  }

  private void deleteTree(Path path) {
    if (!Files.exists(path)) return;
    try (var files = Files.walk(path)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
    } catch (IOException e) {
      throw new ProcessingException("LOCAL_DELETE", true);
    }
  }
}
