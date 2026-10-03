package ru.yanis.videoaudiobot.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.yanis.videoaudiobot.service.ProcessingException;

class HttpTransportTest {
  @TempDir Path dir;
  HttpServer server;

  @AfterEach
  void close() {
    if (server != null) server.stop(0);
  }

  @Test
  void streamsMultipartAndRepeatsSpeakerArrayFields() throws Exception {
    AtomicReference<String> request = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        e -> {
          request.set(
              new String(
                  e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
          byte[] body = "{\"segments\":[]}".getBytes();
          e.sendResponseHeaders(200, body.length);
          e.getResponseBody().write(body);
          e.close();
        });
    server.start();
    Path audio = dir.resolve("audio.mp3");
    Files.writeString(audio, "audio-content");
    var transport = new JdkHttpTransport(new ObjectMapper(), Duration.ofSeconds(15));
    var fields = new LinkedHashMap<String, String>();
    fields.put("response_format", "diarized_json");
    fields.put("known_speaker_names[0]", "speaker_a");
    fields.put("known_speaker_names[1]", "speaker_b");
    transport.multipart(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        Map.of(),
        fields,
        "file",
        audio,
        "audio/mpeg",
        Duration.ofSeconds(2));
    assertThat(request.get())
        .contains("name=\"known_speaker_names[]\"")
        .contains("speaker_a")
        .contains("speaker_b")
        .contains("audio-content")
        .contains("filename=\"audio.mp3\"");
  }

  @Test
  void providerErrorsAreSanitizedAndRetryableOn429() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        e -> {
          byte[] body = "secret-provider-response".getBytes();
          e.sendResponseHeaders(429, body.length);
          e.getResponseBody().write(body);
          e.close();
        });
    server.start();
    assertThatThrownBy(
            () ->
                new JdkHttpTransport(new ObjectMapper(), Duration.ofSeconds(15))
                    .json(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        Map.of(),
                        Duration.ofSeconds(2)))
        .isInstanceOfSatisfying(
            ProcessingException.class,
            e -> {
              assertThat(e.getMessage()).isEqualTo("HTTP_429");
              assertThat(e.retryable()).isTrue();
            });
  }

  @Test
  void downloadAndDeleteSendHeadersAndTreat404AsAbsent() throws Exception {
    Map<String, String> seen = new HashMap<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        e -> {
          seen.put(e.getRequestMethod(), e.getRequestHeaders().getFirst("Authorization"));
          byte[] body = "file-content".getBytes();
          if ("GET".equals(e.getRequestMethod())) {
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
          } else e.sendResponseHeaders(e.getRequestURI().getPath().contains("gone") ? 404 : 204, -1);
          e.close();
        });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    var transport = new JdkHttpTransport(new ObjectMapper(), Duration.ofSeconds(15));
    var headers = Map.of("Authorization", "Bearer secret");
    Path target = dir.resolve("source");
    transport.download(base + "/a/file", headers, target, Duration.ofSeconds(2), 100);
    assertThat(target).hasContent("file-content");
    assertThat(transport.delete(base + "/a/file", headers, Duration.ofSeconds(2))).isTrue();
    assertThat(transport.delete(base + "/a/gone", headers, Duration.ofSeconds(2))).isFalse();
    assertThat(seen).containsEntry("GET", "Bearer secret").containsEntry("DELETE", "Bearer secret");
  }
}
