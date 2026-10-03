package ru.yanis.videoaudiobot.service.impl;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class S3StorageServiceTest {
  @Test
  void downloadUsesObjectNameWithUtf8Encoding() {
    assertThat(S3StorageService.disposition("bot/job/attempt/Привет коллеги.txt"))
        .isEqualTo(
            "attachment; filename=\"transcript.txt\"; filename*=UTF-8''"
                + "%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82%20"
                + "%D0%BA%D0%BE%D0%BB%D0%BB%D0%B5%D0%B3%D0%B8.txt");
  }
}
