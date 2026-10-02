package ru.yanis.videoaudiobot.dto;

import java.util.List;

public record ChunkResult(
    int index, String rawKey, List<Segment> segments, List<SpeakerReference> references) {}
