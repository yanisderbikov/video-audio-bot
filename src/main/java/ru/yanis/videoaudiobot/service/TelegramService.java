package ru.yanis.videoaudiobot.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import ru.yanis.videoaudiobot.dto.RemoteFile;

public interface TelegramService {
  List<JsonNode> updates(long offset);

  RemoteFile file(String id);

  void download(String path, Path destination);

  long sendText(long chatId, long replyTo, String text);

  void editText(long chatId, long messageId, String text);

  long sendDocument(long chatId, long replyTo, Path path, String caption);
}
