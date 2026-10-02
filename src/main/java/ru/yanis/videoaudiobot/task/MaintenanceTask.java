package ru.yanis.videoaudiobot.task;

import ru.yanis.videoaudiobot.service.LeaseGuard;

public interface MaintenanceTask {
  void notifyUsers(LeaseGuard guard);

  void cleanup(LeaseGuard guard);
}
