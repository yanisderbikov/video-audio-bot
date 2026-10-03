package ru.yanis.videoaudiobot.service;

import ru.yanis.videoaudiobot.dto.Transcript;

public interface TranscriptFormatter {
  String format(Transcript transcript);

  /** TXT file name (with extension) built from the first two utterances. */
  String fileName(Transcript transcript);
}
