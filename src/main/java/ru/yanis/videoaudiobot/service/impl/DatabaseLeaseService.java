package ru.yanis.videoaudiobot.service.impl;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Service;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.repo.*;
import ru.yanis.videoaudiobot.service.*;

@Service
class DatabaseLeaseService implements LeaseService {
  private final LeaseRepository leases;
  private final JobRepository jobs;
  private final AppProperties.Pipeline config;
  private final ScheduledExecutorService heartbeat =
      Executors.newScheduledThreadPool(
          2,
          r -> {
            Thread t = new Thread(r, "lease-heartbeat");
            t.setDaemon(true);
            return t;
          });

  DatabaseLeaseService(LeaseRepository leases, JobRepository jobs, AppProperties p) {
    this.leases = leases;
    this.jobs = jobs;
    this.config = p.pipeline();
  }

  public void run(String name, BiConsumer<UUID, LeaseGuard> action) {
    UUID token = UUID.randomUUID();
    if (!leases.acquire(name, token, config.leaseDuration())) return;
    Guard guard = new Guard(name, token, Thread.currentThread());
    ScheduledFuture<?> future =
        heartbeat.scheduleWithFixedDelay(
            guard::renew,
            config.heartbeatInterval().toMillis(),
            config.heartbeatInterval().toMillis(),
            TimeUnit.MILLISECONDS);
    try {
      action.accept(token, guard);
    } finally {
      guard.finish();
      future.cancel(false);
      try {
        leases.release(name, token);
      } finally {
        Thread.interrupted();
      }
    }
  }

  @PreDestroy
  void close() {
    heartbeat.shutdownNow();
  }

  private class Guard implements LeaseGuard {
    private final String name;
    private final UUID token;
    private final Thread worker;
    private UUID job;
    private boolean valid = true, finished = false;

    Guard(String name, UUID token, Thread worker) {
      this.name = name;
      this.token = token;
      this.worker = worker;
    }

    public synchronized void attach(UUID jobId) {
      job = jobId;
      check();
    }

    public synchronized void check() {
      if (!valid
          || finished
          || Thread.currentThread().isInterrupted()
          || !leases.renew(name, token, config.leaseDuration())
          || (job != null && !jobs.active(job, token)))
        throw new ProcessingException("LEASE_LOST", true);
    }

    synchronized void renew() {
      if (finished) return;
      try {
        if (!leases.renew(name, token, config.leaseDuration())
            || (job != null && !jobs.heartbeat(job, token, config.leaseDuration()))) lose();
      } catch (Exception e) {
        lose();
      }
    }

    private void lose() {
      valid = false;
      worker.interrupt();
    }

    synchronized void finish() {
      finished = true;
    }
  }
}
