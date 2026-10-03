package ru.yanis.videoaudiobot.service.impl;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.yanis.videoaudiobot.service.*;

@Service
class JdkHttpTransport implements HttpTransport {
  private final HttpClient client;
  private final ObjectMapper mapper;

  JdkHttpTransport(
      ObjectMapper mapper, @Value("${HTTP_CONNECT_TIMEOUT:15s}") Duration connectTimeout) {
    this.mapper = mapper;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  private JsonNode execute(HttpRequest request) {
    try {
      var response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int code = response.statusCode();
      if (code < 200 || code >= 300) {
        // Telegram uses meaningful JSON errors (including idempotent edits) with HTTP 400.
        try {
          JsonNode error = mapper.readTree(response.body());
          if (error.has("ok") && !error.path("ok").asBoolean() && error.has("error_code"))
            return error;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
        }
        throw new ProcessingException("HTTP_" + code, code == 429 || code >= 500 || code == 408);
      }
      return mapper.readTree(response.body());
    } catch (ProcessingException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("INTERRUPTED", true);
    } catch (Exception e) {
      throw new ProcessingException("HTTP_IO", true);
    }
  }

  public JsonNode json(String url, Map<String, ?> body, Duration timeout) {
    try {
      return execute(
          HttpRequest.newBuilder(URI.create(url))
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
              .build());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ProcessingException("JSON_WRITE", false);
    }
  }

  public JsonNode multipart(
      String url,
      Map<String, String> headers,
      Map<String, String> fields,
      String fileField,
      Path file,
      String mime,
      Duration timeout) {
    String boundary = "bot-" + UUID.randomUUID();
    List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
    fields.forEach(
        (key, value) ->
            parts.add(
                HttpRequest.BodyPublishers.ofString(
                    "--"
                        + boundary
                        + "\r\nContent-Disposition: form-data; name=\""
                        + key.replaceFirst("\\[\\d+\\]$", "[]")
                        + "\"\r\n\r\n"
                        + value
                        + "\r\n")));
    String filename = file.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
    parts.add(
        HttpRequest.BodyPublishers.ofString(
            "--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\""
                + fileField
                + "\"; filename=\""
                + filename
                + "\"\r\nContent-Type: "
                + mime
                + "\r\n\r\n"));
    try {
      parts.add(HttpRequest.BodyPublishers.ofFile(file));
    } catch (IOException e) {
      throw new ProcessingException("LOCAL_IO", true);
    }
    parts.add(HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
    var builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary);
    headers.forEach(builder::header);
    return execute(
        builder
            .POST(
                HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new)))
            .build());
  }

  public void download(String url, Path destination, Duration timeout, long maxBytes) {
    download(url, Map.of(), destination, timeout, maxBytes);
  }

  public void download(
      String url, Map<String, String> headers, Path destination, Duration timeout, long maxBytes) {
    // BodySubscriber writes to disk and enforces the limit while streaming; request timeout covers
    // the body.
    try {
      var builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
      headers.forEach(builder::header);
      var response =
          client.send(builder.build(), info -> new LimitedFileSubscriber(destination, maxBytes));
      if (response.statusCode() != 200) {
        Files.deleteIfExists(destination);
        throw new ProcessingException("TELEGRAM_DOWNLOAD_" + response.statusCode(), true);
      }
    } catch (ProcessingException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("INTERRUPTED", true);
    } catch (Exception e) {
      throw new ProcessingException("DOWNLOAD_IO", true);
    }
  }

  public boolean delete(String url, Map<String, String> headers, Duration timeout) {
    try {
      var builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).DELETE();
      headers.forEach(builder::header);
      int code = client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
      if (code == 404) return false;
      if (code < 200 || code >= 300)
        throw new ProcessingException("HTTP_" + code, code == 429 || code >= 500 || code == 408);
      return true;
    } catch (ProcessingException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("INTERRUPTED", true);
    } catch (Exception e) {
      throw new ProcessingException("HTTP_IO", true);
    }
  }

  private static class LimitedFileSubscriber implements HttpResponse.BodySubscriber<Path> {
    private final java.util.concurrent.CompletableFuture<Path> future =
        new java.util.concurrent.CompletableFuture<>();
    private final Path path;
    private final long limit;
    private long size;
    private OutputStream out;
    private java.util.concurrent.Flow.Subscription subscription;

    LimitedFileSubscriber(Path path, long limit) {
      this.path = path;
      this.limit = limit;
    }

    public java.util.concurrent.CompletionStage<Path> getBody() {
      return future;
    }

    public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
      subscription = s;
      try {
        out = Files.newOutputStream(path);
        s.request(1);
      } catch (Exception e) {
        s.cancel();
        onError(e);
      }
    }

    public void onNext(List<java.nio.ByteBuffer> buffers) {
      try {
        for (var b : buffers) {
          size += b.remaining();
          if (size > limit) throw new IOException("DOWNLOAD_TOO_LARGE");
          byte[] bytes = new byte[b.remaining()];
          b.get(bytes);
          out.write(bytes);
        }
        subscription.request(1);
      } catch (Exception e) {
        subscription.cancel();
        onError(e);
      }
    }

    public void onError(Throwable e) {
      try {
        if (out != null) out.close();
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
      }
      future.completeExceptionally(e);
    }

    public void onComplete() {
      try {
        out.close();
        future.complete(path);
      } catch (IOException e) {
        onError(e);
      }
    }
  }
}
