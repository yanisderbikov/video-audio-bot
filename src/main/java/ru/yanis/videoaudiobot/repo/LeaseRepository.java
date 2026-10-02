package ru.yanis.videoaudiobot.repo;

import java.time.Duration;
import java.util.UUID;

public interface LeaseRepository {
  boolean acquire(String name, UUID token, Duration duration);

  boolean renew(String name, UUID token, Duration duration);

  void release(String name, UUID token);
}
