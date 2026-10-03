package ru.yanis.videoaudiobot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Service;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.dto.RemoteFile;
import ru.yanis.videoaudiobot.service.*;

@Service
class TelegramHttpService implements TelegramService {
  private final HttpTransport http;
  private final AppProperties.Telegram config;

  TelegramHttpService(HttpTransport http, AppProperties p) {
    this.http = http;
    config = p.telegram();
  }

  private String base() {
    return config.baseUrl().replaceAll("/+$", "");
  }

  private String url(String method) {
    return base() + "/bot" + config.token() + "/" + method;
  }

  private JsonNode result(JsonNode value) {
    if (!value.path("ok").asBoolean()) {
      int code = value.path("error_code").asInt();
      String description = value.path("description").asText("");
      if (description.contains("message is not modified")) return value.path("result");
      throw new ProcessingException("TELEGRAM_" + code, code == 429 || code >= 500);
    }
    return value.path("result");
  }

  public List<JsonNode> updates(long offset) {
    JsonNode r =
        result(
            http.json(
                url("getUpdates"),
                Map.of(
                    "offset",
                    offset,
                    "timeout",
                    config.pollTimeoutSeconds(),
                    "allowed_updates",
                    List.of("message")),
                config.requestTimeout()));
    List<JsonNode> updates = new ArrayList<>();
    r.forEach(updates::add);
    return updates;
  }

  public RemoteFile file(String id) {
    JsonNode r = result(http.json(url("getFile"), Map.of("file_id", id), config.requestTimeout()));
    if (!r.hasNonNull("file_path")) throw new ProcessingException("TELEGRAM_FILE_MISSING", true);
    return new RemoteFile(r.path("file_path").asText(), r.path("file_size").asLong());
  }

  public void download(String path, Path destination) {
    if (path.startsWith("/") || path.contains("..") || !path.matches("[A-Za-z0-9_./-]+"))
      throw new ProcessingException("TELEGRAM_PATH_INVALID", false);
    http.download(
        base() + "/file/bot" + config.token() + "/" + path,
        destination,
        config.requestTimeout(),
        config.maxFileBytes());
  }

  public long sendText(long chat, long reply, String text) {
    return result(
            http.json(
                url("sendMessage"),
                Map.of(
                    "chat_id",
                    chat,
                    "text",
                    text,
                    "reply_parameters",
                    Map.of("message_id", reply, "allow_sending_without_reply", true),
                    "link_preview_options",
                    Map.of("is_disabled", true)),
                config.requestTimeout()))
        .path("message_id")
        .asLong();
  }

  public void editText(long chat, long message, String text) {
    result(
        http.json(
            url("editMessageText"),
            Map.of("chat_id", chat, "message_id", message, "text", text),
            config.requestTimeout()));
  }

  public long sendDocument(long chat, long reply, Path path, String fileName, String caption) {
    return result(
            http.multipart(
                url("sendDocument"),
                Map.of(),
                Map.of(
                    "chat_id",
                    Long.toString(chat),
                    "caption",
                    caption,
                    "parse_mode",
                    "HTML",
                    "reply_parameters",
                    "{\"message_id\":" + reply + ",\"allow_sending_without_reply\":true}"),
                "document",
                path,
                fileName,
                "text/plain; charset=utf-8",
                config.requestTimeout()))
        .path("message_id")
        .asLong();
  }
}
