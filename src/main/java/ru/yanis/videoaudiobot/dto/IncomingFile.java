package ru.yanis.videoaudiobot.dto;

public record IncomingFile(
    long updateId,
    long chatId,
    long userId,
    long messageId,
    String fileId,
    String fileName,
    String mimeType,
    long fileSize) {}
