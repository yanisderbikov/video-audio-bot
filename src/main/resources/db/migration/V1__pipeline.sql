CREATE TABLE transcription_job (
    id uuid PRIMARY KEY, update_id bigint NOT NULL UNIQUE,
    chat_id bigint NOT NULL, user_id bigint NOT NULL, message_id bigint NOT NULL,
    file_id varchar(1024) NOT NULL, file_name varchar(512) NOT NULL,
    mime_type varchar(255) NOT NULL, file_size bigint NOT NULL,
    stage varchar(32) NOT NULL CHECK (stage IN ('UPLOAD','CONVERT','TRANSCRIBE','FORMAT','DELIVER')),
    status varchar(32) NOT NULL CHECK (status IN ('READY','RUNNING','RETRY','COMPLETED','FAILED','DELETED')),
    attempt integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token uuid, lease_until timestamptz, last_error varchar(200),
    source_key text, audio_key text, transcript_key text, result_key text, telegram_path text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE FUNCTION touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at := clock_timestamp(); RETURN NEW; END;
$$;
CREATE TRIGGER transcription_job_updated_at BEFORE UPDATE ON transcription_job
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE INDEX job_queue ON transcription_job(stage, next_attempt_at, created_at)
    WHERE status IN ('READY','RETRY','RUNNING');
CREATE INDEX job_cleanup ON transcription_job(updated_at) WHERE status = 'COMPLETED';
CREATE INDEX job_file_id ON transcription_job(file_id) WHERE status <> 'DELETED';
-- Worker leases are separate: cleanup/notification bookkeeping MUST NOT shift updated_at.
CREATE TABLE worker_lease (name varchar(64) PRIMARY KEY, token uuid NOT NULL, lease_until timestamptz NOT NULL);
CREATE TABLE bot_cursor (id integer PRIMARY KEY CHECK(id=1), next_update_id bigint NOT NULL DEFAULT 0);
INSERT INTO bot_cursor(id) VALUES (1);
CREATE TABLE transcription_checkpoint (
    job_id uuid REFERENCES transcription_job(id), part_index integer NOT NULL,
    payload text NOT NULL, PRIMARY KEY(job_id, part_index)
);
CREATE TABLE job_notification (
    job_id uuid PRIMARY KEY REFERENCES transcription_job(id), message_id bigint, sent_state varchar(100),
    attempt integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE job_delivery (
    job_id uuid REFERENCES transcription_job(id), item varchar(32) NOT NULL,
    message_id bigint NOT NULL, sent_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(job_id,item)
);
