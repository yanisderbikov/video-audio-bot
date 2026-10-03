package ru.yanis.videoaudiobot.service.impl;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.service.*;

@Service
class FileWorkspaceService implements WorkspaceService {
  private final Path root, serverRoot, localRoot;
  private final HttpTransport http;
  // When set, Local Bot API files are fetched over HTTP instead of a shared volume.
  private final String fileServer;
  private final Map<String, String> fileServerHeaders;
  private final Duration timeout;

  FileWorkspaceService(AppProperties p, HttpTransport http) {
    root = p.workDirectory().toAbsolutePath().normalize();
    serverRoot = p.telegram().serverDirectory().toAbsolutePath().normalize();
    localRoot = p.telegram().localDirectory().toAbsolutePath().normalize();
    this.http = http;
    String url = p.telegram().fileServerUrl();
    fileServer = url == null || url.isBlank() ? null : url.replaceAll("/+$", "");
    String token = p.telegram().fileServerToken();
    fileServerHeaders =
        token == null || token.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + token);
    timeout = p.telegram().requestTimeout();
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

  private Path relative(String path) {
    Path remote = Path.of(path).normalize();
    if (!remote.isAbsolute() || !remote.startsWith(serverRoot) || remote.equals(serverRoot))
      throw new ProcessingException("TELEGRAM_PATH_INVALID", false);
    return serverRoot.relativize(remote);
  }

  private Path mapped(String path) {
    return localRoot.resolve(relative(path)).normalize();
  }

  private String url(String path) {
    StringBuilder url = new StringBuilder(fileServer);
    for (Path part : relative(path))
      url.append('/')
          .append(URLEncoder.encode(part.toString(), StandardCharsets.UTF_8).replace("+", "%20"));
    return url.toString();
  }

  private Path telegramFile(String path) {
    try {
      Path file = mapped(path).toRealPath();
      if (!file.startsWith(localRoot.toRealPath()) || !Files.isRegularFile(file))
        throw new ProcessingException("TELEGRAM_PATH_INVALID", false);
      return file;
    } catch (IOException e) {
      throw new ProcessingException("TELEGRAM_SHARED_VOLUME_MISSING", true);
    }
  }

  public void copyTelegramFile(String path, Path destination, long maxBytes) {
    if (fileServer != null) {
      http.download(url(path), fileServerHeaders, destination, timeout, maxBytes);
      return;
    }
    Path local = telegramFile(path);
    try {
      if (Files.size(local) > maxBytes) throw new ProcessingException("FILE_TOO_LARGE", false);
      Files.copy(local, destination, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new ProcessingException("SOURCE_COPY_IO", true);
    }
  }

  public void removeTelegramFile(String path) {
    if (path == null) return;
    if (fileServer != null) {
      http.delete(url(path), fileServerHeaders, timeout);
      return;
    }
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
