package ru.yanis.videoaudiobot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.service.*;

@Service
class OpenAiTranscriptionService implements TranscriptionService {
  private final HttpTransport http;
  private final AppProperties.Openai config;

  OpenAiTranscriptionService(HttpTransport http, AppProperties p) {
    this.http = http;
    config = p.openai();
  }

  public JsonNode transcribe(Path audio, Map<String, Path> references) {
    try {
      if (Files.size(audio) > config.maxUploadBytes())
        throw new ProcessingException("OPENAI_FILE_TOO_LARGE", false);
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put("model", config.model());
      fields.put("language", config.language());
      fields.put("response_format", "diarized_json");
      fields.put("chunking_strategy", "auto");
      int i = 0;
      for (var entry : references.entrySet()) {
        if (i >= config.maxSpeakerReferences()) break;
        fields.put("known_speaker_names[" + i + "]", entry.getKey());
        fields.put(
            "known_speaker_references[" + i + "]",
            "data:audio/wav;base64,"
                + Base64.getEncoder().encodeToString(Files.readAllBytes(entry.getValue())));
        i++;
      }
      JsonNode response =
          http.multipart(
              config.baseUrl().replaceAll("/+$", "") + "/v1/audio/transcriptions",
              Map.of("Authorization", "Bearer " + config.apiKey()),
              fields,
              "file",
              audio,
              "audio/mpeg",
              config.timeout());
      if (!response.path("segments").isArray())
        throw new ProcessingException("OPENAI_DIARIZATION_MISSING", false);
      for (JsonNode s : response.path("segments")) {
        double start = s.path("start").asDouble(-1), end = s.path("end").asDouble(-1);
        if (!s.hasNonNull("speaker")
            || !s.hasNonNull("text")
            || !Double.isFinite(start)
            || !Double.isFinite(end)
            || start < 0
            || end < start) throw new ProcessingException("OPENAI_SEGMENT_INVALID", false);
      }
      return response;
    } catch (java.io.IOException e) {
      throw new ProcessingException("LOCAL_IO", true);
    }
  }
}
