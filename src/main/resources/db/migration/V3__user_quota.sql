CREATE INDEX job_user_recent ON transcription_job(user_id, created_at) WHERE status <> 'FAILED';
