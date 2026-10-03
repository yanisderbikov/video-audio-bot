package ru.yanis.videoaudiobot.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

public interface StorageService {
  String prefix(UUID jobId);

  String key(UUID jobId, UUID attempt, String name);

  void put(String key, Path path, String contentType);

  void get(String key, Path target);

  void putJson(String key, Object value);

  <T> T getJson(String key, Class<T> type);

  /** Download link; the browser saves the object under {@link #fileName(String)}. */
  String signedUrl(String key, Duration ttl);

  void deleteJob(UUID jobId);

  static String fileName(String key) {
    return key.substring(key.lastIndexOf('/') + 1);
  }
}
