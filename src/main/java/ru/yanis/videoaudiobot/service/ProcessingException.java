package ru.yanis.videoaudiobot.service;

/** A safe error code, never a provider response or URL containing credentials. */
public class ProcessingException extends RuntimeException {
  private final boolean retryable;

  public ProcessingException(String code, boolean retryable) {
    super(code);
    this.retryable = retryable;
  }

  public boolean retryable() {
    return retryable;
  }
}
