package ru.yanis.videoaudiobot.dto;

public record AudioPart(
    int index, double offset, double duration, double keepFrom, double keepUntil, String key) {}
