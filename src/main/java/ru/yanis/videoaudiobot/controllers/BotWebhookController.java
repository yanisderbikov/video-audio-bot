package ru.yanis.videoaudiobot.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

public interface BotWebhookController {
  @PostMapping("/telegram/webhook")
  ResponseEntity<Void> receive(
      @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret,
      @RequestBody JsonNode update);
}
