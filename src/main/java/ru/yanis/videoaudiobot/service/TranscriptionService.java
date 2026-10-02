package ru.yanis.videoaudiobot.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;

public interface TranscriptionService {
  JsonNode transcribe(Path audio, Map<String, Path> references);
}
