package ru.yanis.videoaudiobot.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public interface HttpTransport {
  JsonNode json(String url, Map<String, ?> body, Duration timeout);

  JsonNode multipart(
      String url,
      Map<String, String> headers,
      Map<String, String> fields,
      String fileField,
      Path file,
      String mime,
      Duration timeout);

  void download(String url, Path destination, Duration timeout, long maxBytes);

  void download(
      String url, Map<String, String> headers, Path destination, Duration timeout, long maxBytes);

  /** Returns false when the resource is already absent (HTTP 404). */
  boolean delete(String url, Map<String, String> headers, Duration timeout);
}
