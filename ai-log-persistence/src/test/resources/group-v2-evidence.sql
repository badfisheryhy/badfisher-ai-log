CREATE TABLE tb_ai_log_analysis_task (id BIGINT PRIMARY KEY, status VARCHAR);
CREATE TABLE tb_ai_log_issue_group (
    id BIGINT PRIMARY KEY, current_ai_item_id BIGINT, active_ai_item_id BIGINT, ai_status VARCHAR,
    lock_version INT DEFAULT 0
);
CREATE TABLE tb_ai_log_issue_group_governance (
    issue_group_id BIGINT PRIMARY KEY, process_status VARCHAR, review_status VARCHAR,
    owner_user_id INT, claim_user_id INT, version INT DEFAULT 0
);
CREATE TABLE tb_ai_log_error_event (
    id BIGINT PRIMARY KEY, analysis_task_id BIGINT, issue_group_id BIGINT,
    expected BOOLEAN, ai_required BOOLEAN, occurrence_count BIGINT,
    log_time TIMESTAMP, first_seen_time TIMESTAMP, last_seen_time TIMESTAMP, sample_content VARCHAR,
    sample_content_truncated BOOLEAN, truncated BOOLEAN, root_cause_exception VARCHAR,
    simplified_stack VARCHAR, tid VARCHAR, trace_id VARCHAR,
    sample_quality_score SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_event_group_evidence ON tb_ai_log_error_event
    (issue_group_id, expected, ai_required, sample_quality_score, id);
INSERT INTO tb_ai_log_analysis_task VALUES (1,'SUCCESS'),(2,'SUCCESS'),(3,'FAILED');
INSERT INTO tb_ai_log_issue_group VALUES
    (100,NULL,NULL,'WAITING',0),
    (200,50,NULL,'COMPLETED',0);
INSERT INTO tb_ai_log_issue_group_governance VALUES
    (100,'PENDING','PENDING',NULL,NULL,0),
    (200,'RESOLVED','APPROVED',7,7,3);
INSERT INTO tb_ai_log_error_event VALUES
    (1,1,100,FALSE,TRUE,3,'2026-09-01','2026-09-01','2026-09-01','complete message',FALSE,FALSE,'RootException','stack','tid-1',NULL,6),
    (2,2,100,FALSE,TRUE,5,'2026-09-22','2026-09-22','2026-09-22','new but truncated',TRUE,TRUE,NULL,NULL,NULL,NULL,2),
    (3,3,100,FALSE,TRUE,1,'2026-09-22','2026-09-22','2026-09-22','failed analysis sample',FALSE,FALSE,'Root','stack','tid-3',NULL,7);

ALTER TABLE tb_ai_log_analysis_task ADD log_date DATE;
UPDATE tb_ai_log_analysis_task SET log_date = '2026-09-01' WHERE id = 1;
UPDATE tb_ai_log_analysis_task SET log_date = '2026-09-22' WHERE id IN (2, 3);
ALTER TABLE tb_ai_log_error_event ADD log_date DATE;
UPDATE tb_ai_log_error_event SET log_date = CAST(log_time AS DATE);
CREATE TABLE tb_ai_log_ai_task_item (
    id BIGINT PRIMARY KEY, issue_group_id BIGINT, analysis_task_id BIGINT, rerun_operation_id BIGINT
);
