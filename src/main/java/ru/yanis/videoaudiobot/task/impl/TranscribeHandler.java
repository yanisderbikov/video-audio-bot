package ru.yanis.videoaudiobot.task.impl;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.dto.*;
import ru.yanis.videoaudiobot.model.*;
import ru.yanis.videoaudiobot.repo.JobRepository;
import ru.yanis.videoaudiobot.service.*;
import ru.yanis.videoaudiobot.task.StageHandler;

@Component
class TranscribeHandler implements StageHandler {
  private final StorageService storage;
  private final TranscriptionService openai;
  private final MediaService media;
  private final JobRepository jobs;
  private final AppProperties config;

  TranscribeHandler(
      StorageService storage,
      TranscriptionService openai,
      MediaService media,
      JobRepository jobs,
      AppProperties config) {
    this.storage = storage;
    this.openai = openai;
    this.media = media;
    this.jobs = jobs;
    this.config = config;
  }

  public Stage stage() {
    return Stage.TRANSCRIBE;
  }

  public StageOutput execute(Job job, UUID token, Path dir, LeaseGuard guard) {
    AudioManifest audio = storage.getJson(job.audioKey(), AudioManifest.class);
    List<Segment> all = new ArrayList<>();
    List<SpeakerReference> references = new ArrayList<>();
    for (AudioPart part : audio.parts()) {
      guard.check();
      var cached = jobs.checkpoint(job.id(), part.index());
      ChunkResult result;
      if (cached.isPresent()) result = cached.get();
      else {
        Path path = dir.resolve("audio-" + part.index() + ".mp3");
        storage.get(part.key(), path);
        Map<String, Path> known = new LinkedHashMap<>();
        for (var ref : references) {
          Path target = dir.resolve(ref.name() + ".wav");
          storage.get(ref.key(), target);
          known.put(ref.name(), target);
        }
        guard.check();
        JsonNode raw = openai.transcribe(path, known);
        String rawKey = storage.key(job.id(), token, "raw-" + part.index() + ".json");
        storage.putJson(rawKey, raw);
        Map<String, String> names = new LinkedHashMap<>();
        List<Segment> local = new ArrayList<>();
        for (JsonNode s : raw.path("segments")) {
          String label = s.path("speaker").asText();
          String mapped =
              known.containsKey(label)
                  ? label
                  : names.computeIfAbsent(
                      label,
                      ignored -> "part" + (part.index() + 1) + "_speaker" + (names.size() + 1));
          local.add(
              new Segment(
                  s.path("start").asDouble(),
                  s.path("end").asDouble(),
                  mapped,
                  s.path("text").asText()));
        }
        // Only clear, non-overlapping segments become references. Unknown labels never get merged
        // by ordinal.
        List<SpeakerReference> nextReferences = new ArrayList<>(references);
        for (String name : new LinkedHashSet<>(names.values())) {
          if (nextReferences.size() >= config.openai().maxSpeakerReferences()) break;
          Optional<Segment> sample =
              local.stream()
                  .filter(s -> s.speaker().equals(name) && s.end() - s.start() >= 2.2)
                  .filter(
                      s ->
                          local.stream()
                              .noneMatch(
                                  other ->
                                      !other.speaker().equals(name)
                                          && other.start() < s.end()
                                          && other.end() > s.start()))
                  .max(Comparator.comparingDouble(s -> s.end() - s.start()));
          if (sample.isPresent()) {
            Segment s = sample.get();
            Path reference = dir.resolve(name + ".wav");
            double length = Math.min(8, s.end() - s.start() - 0.1);
            media.reference(path, s.start(), length, reference);
            String referenceKey = storage.key(job.id(), token, name + ".wav");
            guard.check();
            storage.put(referenceKey, reference, "audio/wav");
            nextReferences.add(new SpeakerReference(name, referenceKey));
          }
        }
        List<Segment> normalized =
            local.stream()
                .filter(
                    s -> {
                      double mid = part.offset() + (s.start() + s.end()) / 2;
                      return mid >= part.keepFrom() && mid < part.keepUntil();
                    })
                .map(
                    s ->
                        new Segment(
                            Math.max(0, part.offset() + s.start()),
                            Math.min(audio.duration(), part.offset() + s.end()),
                            s.speaker(),
                            s.text()))
                .toList();
        result = new ChunkResult(part.index(), rawKey, normalized, List.copyOf(nextReferences));
        guard.check();
        jobs.checkpoint(job.id(), token, result);
        try {
          Files.deleteIfExists(path);
        } catch (java.io.IOException e) {
          throw new ProcessingException("LOCAL_IO", true);
        }
      }
      all.addAll(result.segments());
      references = new ArrayList<>(result.references());
    }
    all.sort(Comparator.comparingDouble(Segment::start));
    guard.check();
    String key = storage.key(job.id(), token, "transcript.json");
    storage.putJson(key, new Transcript(all, audio.parts().size() > 1));
    return new StageOutput(key);
  }
}
