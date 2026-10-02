package ru.yanis.videoaudiobot.task.impl;

import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;
import ru.yanis.videoaudiobot.dto.*;
import ru.yanis.videoaudiobot.model.*;
import ru.yanis.videoaudiobot.service.*;
import ru.yanis.videoaudiobot.task.StageHandler;

@Component
class ConvertHandler implements StageHandler {
  private final StorageService storage;
  private final MediaService media;

  ConvertHandler(StorageService storage, MediaService media) {
    this.storage = storage;
    this.media = media;
  }

  public Stage stage() {
    return Stage.CONVERT;
  }

  public StageOutput execute(Job job, UUID token, Path dir, LeaseGuard guard) {
    guard.check();
    Path source = dir.resolve("source");
    storage.get(job.sourceKey(), source);
    var local = media.convert(source, dir);
    List<AudioPart> parts = new ArrayList<>();
    for (var p : local) {
      guard.check();
      String key = storage.key(job.id(), token, "audio-" + p.index() + ".mp3");
      storage.put(key, p.path(), "audio/mpeg");
      parts.add(
          new AudioPart(p.index(), p.offset(), p.duration(), p.keepFrom(), p.keepUntil(), key));
    }
    guard.check();
    String key = storage.key(job.id(), token, "audio.json");
    storage.putJson(key, new AudioManifest(parts.get(parts.size() - 1).keepUntil(), parts));
    return new StageOutput(key);
  }
}
