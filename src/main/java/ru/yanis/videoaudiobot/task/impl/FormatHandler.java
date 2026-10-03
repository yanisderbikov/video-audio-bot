package ru.yanis.videoaudiobot.task.impl;

import java.nio.file.*;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.yanis.videoaudiobot.dto.*;
import ru.yanis.videoaudiobot.model.*;
import ru.yanis.videoaudiobot.service.*;
import ru.yanis.videoaudiobot.task.StageHandler;

@Component
class FormatHandler implements StageHandler {
  private final StorageService storage;
  private final TranscriptFormatter formatter;

  FormatHandler(StorageService storage, TranscriptFormatter formatter) {
    this.storage = storage;
    this.formatter = formatter;
  }

  public Stage stage() {
    return Stage.FORMAT;
  }

  public StageOutput execute(Job job, UUID token, Path dir, LeaseGuard guard) {
    guard.check();
    Transcript transcript = storage.getJson(job.transcriptKey(), Transcript.class);
    String text = formatter.format(transcript);
    Path path = dir.resolve("transcript.txt");
    try {
      Files.writeString(path, text);
    } catch (java.io.IOException e) {
      throw new ProcessingException("LOCAL_IO", true);
    }
    // The object name is the user-facing file name; delivery and the S3 link reuse it.
    String key = storage.key(job.id(), token, formatter.fileName(transcript));
    guard.check();
    storage.put(key, path, "text/plain; charset=utf-8");
    return new StageOutput(key);
  }
}
