package ru.yanis.videoaudiobot.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface BotUpdateService {
  void accept(JsonNode update);
}
