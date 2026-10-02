package ru.yanis.videoaudiobot.controllers.impl;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.controllers.BotWebhookController;
import ru.yanis.videoaudiobot.service.BotUpdateService;

@RestController
class TelegramWebhookController implements BotWebhookController {
  private final BotUpdateService updates;
  private final AppProperties.Telegram config;

  TelegramWebhookController(BotUpdateService updates, AppProperties p) {
    this.updates = updates;
    this.config = p.telegram();
  }

  public ResponseEntity<Void> receive(String secret, JsonNode update) {
    if (!"webhook".equals(config.mode())) return ResponseEntity.notFound().build();
    if (secret == null
        || !MessageDigest.isEqual(
            config.webhookSecret().getBytes(StandardCharsets.UTF_8),
            secret.getBytes(StandardCharsets.UTF_8))) return ResponseEntity.status(403).build();
    updates.accept(update);
    return ResponseEntity.ok().build();
  }
}
