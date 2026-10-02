package ru.yanis.videoaudiobot.task.impl;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.model.*;
import ru.yanis.videoaudiobot.repo.JobRepository;
import ru.yanis.videoaudiobot.service.*;
import ru.yanis.videoaudiobot.task.*;

@Component
class DefaultPipelineRuntime implements PipelineRuntime {
  private static final Logger log = LoggerFactory.getLogger(DefaultPipelineRuntime.class);
  private final List<StageHandler> handlers;
  private final JobRepository jobs;
  private final LeaseService leases;
  private final WorkspaceService workspace;
  private final TelegramService telegram;
  private final BotUpdateService updates;
  private final MaintenanceTask maintenance;
  private final AppProperties config;
  private final List<ScheduledExecutorService> executors = new ArrayList<>();
  private volatile boolean running;

  DefaultPipelineRuntime(
      List<StageHandler> handlers,
      JobRepository jobs,
      LeaseService leases,
      WorkspaceService workspace,
      TelegramService telegram,
      BotUpdateService updates,
      MaintenanceTask maintenance,
      AppProperties config) {
    this.handlers = handlers;
    this.jobs = jobs;
    this.leases = leases;
    this.workspace = workspace;
    this.telegram = telegram;
    this.updates = updates;
    this.maintenance = maintenance;
    this.config = config;
  }

  public synchronized void start() {
    if (running || !config.pipeline().enabled()) return;
    if (handlers.stream().map(StageHandler::stage).distinct().count() != Stage.values().length
        || handlers.size() != Stage.values().length)
      throw new IllegalStateException("Exactly one handler is required per stage");
    running = true;
    for (var handler : handlers)
      schedule("stage-" + handler.stage(), config.pipeline().pollInterval(), () -> stage(handler));
    if (config.telegram().mode().equals("polling"))
      schedule(
          "telegram-poll",
          config.pipeline().pollInterval(),
          () ->
              leases.run(
                  "telegram-poll",
                  (token, guard) -> {
                    for (var update : telegram.updates(jobs.cursor())) {
                      guard.check();
                      updates.accept(update);
                    }
                  }));
    schedule(
        "notifications",
        config.pipeline().pollInterval(),
        () -> leases.run("notifications", (token, guard) -> maintenance.notifyUsers(guard)));
    if (config.cleanup().enabled())
      schedule(
          "cleanup",
          config.cleanup().interval(),
          () -> leases.run("cleanup", (token, guard) -> maintenance.cleanup(guard)));
  }

  private void schedule(String name, Duration interval, Runnable task) {
    var executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, name));
    executors.add(executor);
    executor.scheduleWithFixedDelay(
        () -> {
          if (!running) return;
          try {
            task.run();
          } catch (Exception e) {
            log.warn("Worker {} will retry ({})", name, e.getClass().getSimpleName());
          }
        },
        0,
        interval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  private void stage(StageHandler handler) {
    leases.run(
        "stage-" + handler.stage(),
        (token, guard) ->
            jobs.claim(handler.stage(), token, config.pipeline().leaseDuration())
                .ifPresent(
                    job -> {
                      Path dir = null;
                      try {
                        guard.attach(job.id());
                        if (job.attempt() > config.pipeline().maxAttempts())
                          throw new ProcessingException("ATTEMPTS_EXHAUSTED", false);
                        dir = workspace.attempt(job.id(), token);
                        var output = handler.execute(job, token, dir, guard);
                        guard.check();
                        jobs.complete(job, token, output);
                        log.info("Job {} completed stage {}", job.id(), job.stage());
                      } catch (Exception e) {
                        boolean retryable =
                            !(e instanceof ProcessingException pe) || pe.retryable();
                        String code =
                            e instanceof ProcessingException ? e.getMessage() : "PROCESSING_ERROR";
                        long multiplier = 1L << Math.min(20, Math.max(0, job.attempt() - 1));
                        long seconds =
                            Math.min(
                                config.pipeline().retryMax().toSeconds(),
                                config.pipeline().retryBase().toSeconds() * multiplier);
                        // Interrupted work retains its lease and is recovered after expiry,
                        // avoiding premature overlap.
                        if (!Thread.currentThread().isInterrupted())
                          jobs.fail(
                              job,
                              token,
                              code,
                              retryable,
                              config.pipeline().maxAttempts(),
                              Duration.ofSeconds(seconds));
                        log.warn("Job {} stage {} failed: {}", job.id(), job.stage(), code);
                      } finally {
                        if (dir != null)
                          try {
                            workspace.removeAttempt(dir);
                          } catch (Exception e) {
                            log.warn("Work files retained for job {}", job.id());
                          }
                      }
                    }));
  }

  public synchronized void stop() {
    running = false;
    executors.forEach(ExecutorService::shutdown);
    long deadline = System.nanoTime() + config.pipeline().shutdownTimeout().toNanos();
    for (var executor : executors) {
      try {
        if (!executor.awaitTermination(
            Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
          executor.shutdownNow();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        executor.shutdownNow();
      }
    }
    executors.clear();
  }

  public boolean isRunning() {
    return running;
  }

  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }
}
