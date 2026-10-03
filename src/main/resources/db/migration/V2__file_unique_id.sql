-- Telegram's file_unique_id is stable across re-sends and forwards, unlike file_id.
ALTER TABLE transcription_job ADD COLUMN file_unique_id varchar(255);
CREATE INDEX job_duplicate ON transcription_job(chat_id, user_id, file_unique_id)
    WHERE file_unique_id IS NOT NULL AND status <> 'FAILED';
