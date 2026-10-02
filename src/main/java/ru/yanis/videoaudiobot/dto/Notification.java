package ru.yanis.videoaudiobot.dto;

import java.util.UUID;

public record Notification(UUID jobId, Long messageId, String sentState) {}
