package ru.yanis.videoaudiobot.service;

import java.util.UUID;
import java.util.function.BiConsumer;

public interface LeaseService {
  void run(String name, BiConsumer<UUID, LeaseGuard> action);
}
