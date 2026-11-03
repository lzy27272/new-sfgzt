-- Deliberate opt-in only. Existing position profiles and role grants are unchanged.
INSERT INTO permission(code,resource,action,description,delegable_to_position,function_category)
VALUES('ai-evidence.backfill','ai-evidence','backfill','授权管理人员发起仪容仪表AI补查（试点历史批次）',true,'WORK_PACKAGE')
ON CONFLICT(code) DO NOTHING;
