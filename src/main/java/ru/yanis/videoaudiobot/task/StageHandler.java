package ru.yanis.videoaudiobot.task;

import java.nio.file.Path;
import java.util.UUID;
import ru.yanis.videoaudiobot.dto.StageOutput;
import ru.yanis.videoaudiobot.model.*;
import ru.yanis.videoaudiobot.service.LeaseGuard;

public interface StageHandler {
  Stage stage();

  StageOutput execute(Job job, UUID token, Path workspace, LeaseGuard guard);
}
