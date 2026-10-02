package ru.yanis.videoaudiobot.dto;

import java.util.List;

public record Transcript(List<Segment> segments, boolean multipleParts) {}
