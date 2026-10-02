package ru.yanis.videoaudiobot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VideoAudioBotApplication {
  public static void main(String[] args) {
    SpringApplication.run(VideoAudioBotApplication.class, args);
  }
}
