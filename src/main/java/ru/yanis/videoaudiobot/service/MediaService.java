package ru.yanis.videoaudiobot.service;

import java.nio.file.Path;
import java.util.List;
import ru.yanis.videoaudiobot.dto.LocalAudioPart;

public interface MediaService {
  List<LocalAudioPart> convert(Path source, Path directory);

  void reference(Path audio, double start, double duration, Path target);
}
