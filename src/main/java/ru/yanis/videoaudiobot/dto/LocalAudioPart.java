package ru.yanis.videoaudiobot.dto;

import java.nio.file.Path;

public record LocalAudioPart(
    int index, double offset, double duration, double keepFrom, double keepUntil, Path path) {}
