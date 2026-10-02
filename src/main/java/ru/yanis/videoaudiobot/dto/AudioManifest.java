package ru.yanis.videoaudiobot.dto;

import java.util.List;

public record AudioManifest(double duration, List<AudioPart> parts) {}
