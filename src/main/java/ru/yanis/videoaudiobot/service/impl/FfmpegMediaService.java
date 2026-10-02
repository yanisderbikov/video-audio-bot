package ru.yanis.videoaudiobot.service.impl;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.dto.LocalAudioPart;
import ru.yanis.videoaudiobot.service.*;

@Service
class FfmpegMediaService implements MediaService {
  private final AppProperties config;

  FfmpegMediaService(AppProperties config) {
    this.config = config;
  }

  private String number(double n) {
    return String.format(Locale.ROOT, "%.6f", n);
  }

  private String run(List<String> args, Path directory) {
    Path log = directory.resolve("process-" + UUID.randomUUID() + ".log");
    Process process = null;
    try {
      process =
          new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
      if (!process.waitFor(config.media().timeout().toMillis(), TimeUnit.MILLISECONDS))
        throw new ProcessingException("MEDIA_TIMEOUT", true);
      if (process.exitValue() != 0)
        throw new ProcessingException("MEDIA_INVALID_OR_NO_AUDIO", false);
      return Files.readString(log).trim();
    } catch (ProcessingException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("INTERRUPTED", true);
    } catch (Exception e) {
      throw new ProcessingException("MEDIA_IO", true);
    } finally {
      if (process != null && process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
      try {
        Files.deleteIfExists(log);
      } catch (Exception ignored) {
      }
    }
  }

  public List<LocalAudioPart> convert(Path source, Path directory) {
    var m = config.media();
    String value =
        run(
            List.of(
                m.ffprobe(),
                "-v",
                "error",
                "-protocol_whitelist",
                "file,pipe",
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                source.toString()),
            directory);
    double duration;
    try {
      duration = Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new ProcessingException("MEDIA_DURATION_UNKNOWN", false);
    }
    if (!Double.isFinite(duration) || duration <= 0 || duration > m.maxDurationSeconds())
      throw new ProcessingException("MEDIA_DURATION_LIMIT", false);
    double chunk =
        Math.min(
            m.chunkSeconds(),
            config.openai().maxUploadBytes() * 8.0 / (m.bitrateKbps() * 1000) * 0.90
                - 2 * m.overlapSeconds());
    if (chunk < 10) throw new ProcessingException("AUDIO_CHUNK_CONFIG", false);
    List<LocalAudioPart> parts = new ArrayList<>();
    int index = 0;
    for (double start = 0; start < duration; start += chunk) {
      double end = Math.min(duration, start + chunk),
          offset = Math.max(0, start - m.overlapSeconds()),
          until = Math.min(duration, end + m.overlapSeconds());
      Path target = directory.resolve("audio-" + index + ".mp3");
      run(
          List.of(
              m.ffmpeg(),
              "-nostdin",
              "-hide_banner",
              "-loglevel",
              "error",
              "-y",
              "-protocol_whitelist",
              "file,pipe",
              "-ss",
              number(offset),
              "-i",
              source.toString(),
              "-t",
              number(until - offset),
              "-map",
              "0:a:0",
              "-vn",
              "-ac",
              "1",
              "-ar",
              Integer.toString(m.sampleRate()),
              "-c:a",
              "libmp3lame",
              "-b:a",
              m.bitrateKbps() + "k",
              target.toString()),
          directory);
      try {
        if (Files.size(target) > config.openai().maxUploadBytes())
          throw new ProcessingException("OPENAI_FILE_TOO_LARGE", false);
      } catch (java.io.IOException e) {
        throw new ProcessingException("LOCAL_IO", true);
      }
      parts.add(new LocalAudioPart(index++, offset, until - offset, start, end, target));
    }
    return parts;
  }

  public void reference(Path audio, double start, double duration, Path target) {
    if (duration < 2 || duration > 10)
      throw new IllegalArgumentException("Reference duration must be between 2 and 10 seconds");
    run(
        List.of(
            config.media().ffmpeg(),
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-protocol_whitelist",
            "file,pipe",
            "-ss",
            number(start),
            "-i",
            audio.toString(),
            "-t",
            number(duration),
            "-vn",
            "-ac",
            "1",
            "-ar",
            "16000",
            "-c:a",
            "pcm_s16le",
            target.toString()),
        target.getParent());
  }
}
