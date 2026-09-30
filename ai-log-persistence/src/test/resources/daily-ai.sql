-- 隔离 H2 测试数据；表结构来自正式 schema.sql，不连接业务库。
INSERT INTO tb_ai_log_analysis_task
    (id, task_no, environment, system_code, module_code, log_date, status, fingerprint_version)
VALUES
    (1, 'analysis-1', 'test', 'demo', 'sample-service', '2026-09-23', 'SUCCESS', 'v2'),
    (2, 'analysis-2', 'test', 'demo', 'sample-service', '2026-09-23', 'SUCCESS', 'v2'),
    (3, 'analysis-3', 'test', 'demo', 'sample-service', '2026-09-24', 'SUCCESS', 'v2'),
    (4, 'analysis-4', 'test', 'demo', 'sample-service', '2026-09-22', 'SUCCESS', 'v2');

INSERT INTO tb_ai_log_issue_group
    (id, environment, system_code, module_code, stable_fingerprint, fingerprint_version,
     root_cause_category, ai_status, current_ai_item_id)
VALUES (100, 'test', 'demo', 'sample-service', 'stable', 'v2', 'CODE', 'COMPLETED', 501);

INSERT INTO tb_ai_log_issue_group_governance
    (issue_group_id, review_status, reviewer_user_id, reviewed_ai_item_id, review_remark,
     human_conclusion, problem_type, problem_level, resolution_days_override,
     owner_user_id, claim_user_id, process_status, version)
VALUES (100, 'PENDING', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'PENDING', 0);

INSERT INTO tb_ai_log_ai_task
    (id, task_no, analysis_task_id, environment, system_code, module_code,
     provider_code, model_code, selection_limit, status)
VALUES
    (10, 'ai-10', 1, 'test', 'demo', 'sample-service', 'openai', 'model', 200, 'WAITING'),
    (11, 'ai-11', 2, 'test', 'demo', 'sample-service', 'openai', 'model', 200, 'WAITING'),
    (12, 'ai-12', 3, 'test', 'demo', 'sample-service', 'openai', 'model', 200, 'WAITING'),
    (13, 'ai-13', 4, 'test', 'demo', 'sample-service', 'openai', 'model', 200, 'SUCCESS');

INSERT INTO tb_ai_log_error_event
    (id, analysis_task_id, issue_group_id, environment, system_code, module_code, log_date,
     aggregate_type, aggregate_key, root_cause_category, start_line, end_line, start_byte,
     end_byte, location_mode, match_type, stable_fingerprint, fingerprint_version, sample_quality_score, trigger_channel, strict_fingerprint)
VALUES
    (101, 1, 100, 'test', 'demo', 'sample-service', '2026-09-23', 'ISSUE', 'a', 'CODE', 1, 2, 0, 20,
     'PLAIN_BYTE_OFFSET', 'STRICT_ERROR', 'stable', 'v2', 3, 'HTTP', 'strict'),
    (102, 1, 100, 'test', 'demo', 'sample-service', '2026-09-23', 'ISSUE', 'b', 'CODE', 3, 4, 20, 40,
     'PLAIN_BYTE_OFFSET', 'STRICT_ERROR', 'stable', 'v2', 3, 'HTTP', 'strict'),
    (201, 2, 100, 'test', 'demo', 'sample-service', '2026-09-23', 'ISSUE', 'a', 'CODE', 1, 2, 0, 20,
     'PLAIN_BYTE_OFFSET', 'STRICT_ERROR', 'stable', 'v2', 3, 'HTTP', 'strict'),
    (301, 3, 100, 'test', 'demo', 'sample-service', '2026-09-24', 'ISSUE', 'a', 'CODE', 1, 2, 0, 20,
     'PLAIN_BYTE_OFFSET', 'STRICT_ERROR', 'stable', 'v2', 3, 'HTTP', 'strict'),
    (401, 4, 100, 'test', 'demo', 'sample-service', '2026-09-22', 'ISSUE', 'a', 'CODE', 1, 2, 0, 20,
     'PLAIN_BYTE_OFFSET', 'STRICT_ERROR', 'stable', 'v2', 7, 'HTTP', 'strict');

INSERT INTO tb_ai_log_ai_task_item
    (id, ai_task_id, analysis_task_id, issue_group_id, sample_event_id, selection_order,
     occurrence_count_snapshot, status, result_summary)
VALUES (501, 13, 4, 100, 401, 1, 1, 'SUCCESS', 'old valid result');

-- 日期边界回归：相邻日期具有更大的次数和不同时间窗。
UPDATE tb_ai_log_error_event
SET occurrence_count = id, first_seen_time = CAST(log_date AS TIMESTAMP),
    last_seen_time = DATEADD('HOUR', 23, CAST(log_date AS TIMESTAMP));
