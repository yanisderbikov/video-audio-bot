package ru.yanis.videoaudiobot.service;

import java.util.UUID;

public interface LeaseGuard {
  void check();

  void attach(UUID jobId);
}
