package ru.yanis.videoaudiobot.service.impl;

import java.util.*;
import org.springframework.stereotype.Service;
import ru.yanis.videoaudiobot.dto.Transcript;
import ru.yanis.videoaudiobot.service.TranscriptFormatter;

@Service
class TextTranscriptFormatter implements TranscriptFormatter {
  public String format(Transcript transcript) {
    StringBuilder text = new StringBuilder("Транскрипция\n\n");
    if (transcript.multipleParts())
      text.append(
          "Запись обработана частями. Голоса сопоставлены по образцам, когда это удалось.\n"
              + "Несопоставленные голоса в разных частях имеют отдельные номера; это может быть"
              + " один человек.\n\n");
    Map<String, Integer> names = new LinkedHashMap<>();
    for (var s : transcript.segments()) {
      int number = names.computeIfAbsent(s.speaker(), ignored -> names.size() + 1);
      text.append('[')
          .append(time(s.start()))
          .append(" — ")
          .append(time(s.end()))
          .append("] Спикер ")
          .append(number)
          .append(": ")
          .append(s.text().strip())
          .append('\n');
    }
    if (transcript.segments().isEmpty()) text.append("Речь не обнаружена.\n");
    return text.toString();
  }

  private String time(double value) {
    long n = Math.max(0, (long) value);
    return String.format(Locale.ROOT, "%02d:%02d:%02d", n / 3600, (n % 3600) / 60, n % 60);
  }
}
