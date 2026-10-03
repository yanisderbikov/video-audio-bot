package ru.yanis.videoaudiobot.service.impl;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.yanis.videoaudiobot.dto.*;

class TranscriptFormatterTest {
  @Test
  void unknownSpeakersAcrossPartsAreNotMerged() {
    String result =
        new TextTranscriptFormatter()
            .format(
                new Transcript(
                    List.of(
                        new Segment(1, 4, "part1_speaker1", "Один"),
                        new Segment(1201, 1204, "part2_speaker1", "Два"),
                        new Segment(1205, 1208, "part1_speaker1", "Три")),
                    true));
    assertThat(result)
        .contains("00:20:01")
        .contains("Спикер 1: Один")
        .contains("Спикер 2: Два")
        .contains("Спикер 1: Три")
        .contains("может быть один человек");
  }

  @Test
  void fileNameUsesFirstTwoUtterancesAndIsSafe() {
    var formatter = new TextTranscriptFormatter();
    String name =
        formatter.fileName(
            new Transcript(
                List.of(
                    new Segment(0, 1, "a", "  Привет, коллеги!  "),
                    new Segment(1, 2, "b", "Обсудим: бюджет/сроки?"),
                    new Segment(2, 3, "a", "Третья реплика")),
                false));
    assertThat(name).isEqualTo("Привет коллеги Обсудим бюджет сроки.txt");
    String longName =
        formatter.fileName(
            new Transcript(List.of(new Segment(0, 1, "a", "слово ".repeat(40))), false));
    assertThat(longName).endsWith("слово.txt").hasSizeLessThanOrEqualTo(64);
    assertThat(formatter.fileName(new Transcript(List.of(), false))).isEqualTo("Транскрипция.txt");
  }
}
