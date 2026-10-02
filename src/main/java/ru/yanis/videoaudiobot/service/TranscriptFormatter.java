package ru.yanis.videoaudiobot.service;

import ru.yanis.videoaudiobot.dto.Transcript;

public interface TranscriptFormatter {
  String format(Transcript transcript);
}
