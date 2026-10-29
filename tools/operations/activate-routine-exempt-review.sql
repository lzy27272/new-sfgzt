-- psql -X -v ON_ERROR_STOP=1 -v tenant_id=... -v hotel_one=... -v hotel_two=... -f this-file
-- Run only after V118 and the paired database/originals recovery gate pass.
-- User confirmed the 34 listed routine items AND all matching normal pending submissions.
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
SELECT pg_advisory_xact_lock(hashtextextended('activate-routine-exempt-review',0));
CREATE TEMP TABLE routine_activation_scope ON COMMIT DROP AS
SELECT o.tenant_id,o.id AS hotel_id,t.timezone,(now() AT TIME ZONE t.timezone)::date AS new_work_from
FROM org_unit o JOIN tenant t ON t.id=o.tenant_id
WHERE o.tenant_id=:'tenant_id'::uuid AND o.id IN (:'hotel_one'::uuid,:'hotel_two'::uuid)
  AND o.unit_type='HOTEL' AND o.status='ACTIVE';
DO $$ BEGIN
  IF (SELECT count(*) FROM routine_activation_scope)<>2 THEN
    RAISE EXCEPTION 'Exactly the two authorized active hotels are required';
  END IF;
END $$;

-- A retired, previously published version may still have legitimate pending work.
-- Lock original rows while capturing; no submission, approval or expectation is updated.
CREATE TEMP TABLE routine_pending_capture ON COMMIT DROP AS
SELECT x.tenant_id,x.target_org_unit_id,x.work_package_item_id,x.id AS expectation_id,
  r.id AS record_id,r.submitted_at,to_jsonb(r) AS before_record,to_jsonb(x) AS before_expectation
FROM routine_activation_scope scope
JOIN work_expectation x ON x.tenant_id=scope.tenant_id AND x.target_org_unit_id=scope.hotel_id
JOIN work_package_item i ON i.tenant_id=x.tenant_id AND i.id=x.work_package_item_id
JOIN work_package_version v ON v.tenant_id=i.tenant_id AND v.id=i.work_package_version_id
JOIN LATERAL (
  SELECT record.* FROM work_record record
  WHERE record.tenant_id=x.tenant_id AND record.work_expectation_id=x.id
  ORDER BY record.series_entry_no DESC,record.attempt_no DESC,record.id DESC LIMIT 1
  FOR UPDATE OF record
) r ON true
WHERE i.item_code<>'HKA_CART_PREPARATION' AND work_review_routine_exemption_eligible(i)
  AND v.lifecycle_status IN ('PUBLISHED','RETIRED') AND v.published_at IS NOT NULL
  AND r.status='SUBMITTED' AND NOT r.repeatable_entry AND x.status='SUBMITTED'
  AND r.submitted_at<=now() AND NOT work_review_record_has_exception(r)
  AND NOT EXISTS (SELECT 1 FROM work_record failed
    WHERE failed.tenant_id=x.tenant_id AND failed.work_expectation_id=x.id AND failed.status='REJECTED')
  AND NOT EXISTS (SELECT 1 FROM store_work_usage usage JOIN work_record usage_record
    ON usage_record.tenant_id=usage.tenant_id AND usage_record.id=usage.work_record_id
    WHERE usage_record.tenant_id=x.tenant_id AND usage_record.work_expectation_id=x.id)
FOR UPDATE OF x;

INSERT INTO work_review_policy_activation
  (tenant_id,target_org_unit_id,work_package_item_id,new_work_from,authorization_reference,reason)
SELECT DISTINCT scope.tenant_id,scope.hotel_id,i.id,scope.new_work_from,
  'USER_CONFIRMED_20261029_ROUTINE34_PENDING',
  '用户确认：34项普通例行工作正常免审、异常必审；已有待审且符合免审的记录一并修正，保留原始提交和处理记录。关键业务及下达任务仍人工审核。'
FROM routine_activation_scope scope
JOIN work_package_item i ON i.tenant_id=scope.tenant_id
JOIN work_package_version v ON v.tenant_id=i.tenant_id AND v.id=i.work_package_version_id
WHERE i.item_code<>'HKA_CART_PREPARATION' AND work_review_routine_exemption_eligible(i)
  AND v.published_at IS NOT NULL AND (
    (v.lifecycle_status='PUBLISHED' AND (v.effective_from AT TIME ZONE scope.timezone)::date<=scope.new_work_from
      AND (v.effective_to IS NULL OR (v.effective_to AT TIME ZONE scope.timezone)::date>=scope.new_work_from) AND EXISTS (
        SELECT 1 FROM work_package_allocation a
        WHERE a.tenant_id=scope.tenant_id AND a.target_org_unit_id=scope.hotel_id
          AND a.work_package_version_id=v.id AND a.status='ACTIVE'
          AND a.valid_from<=scope.new_work_from AND (a.valid_to IS NULL OR a.valid_to>=scope.new_work_from)
      ))
    OR EXISTS (SELECT 1 FROM routine_pending_capture c
      WHERE c.tenant_id=i.tenant_id AND c.target_org_unit_id=scope.hotel_id AND c.work_package_item_id=i.id)
  )
ON CONFLICT (tenant_id,target_org_unit_id,work_package_item_id) DO NOTHING;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM routine_activation_scope scope WHERE NOT EXISTS (
    SELECT 1 FROM work_review_policy_activation a
    WHERE a.tenant_id=scope.tenant_id AND a.target_org_unit_id=scope.hotel_id
      AND a.authorization_reference='USER_CONFIRMED_20261029_ROUTINE34_PENDING')) THEN
    RAISE EXCEPTION 'An authorized hotel has no eligible routine work';
  END IF;
END $$;

INSERT INTO work_review_policy_correction
  (tenant_id,activation_id,work_expectation_id,work_record_id,before_record,before_expectation)
SELECT c.tenant_id,a.id,c.expectation_id,c.record_id,c.before_record,c.before_expectation
FROM routine_pending_capture c JOIN work_review_policy_activation a
  ON a.tenant_id=c.tenant_id AND a.target_org_unit_id=c.target_org_unit_id
  AND a.work_package_item_id=c.work_package_item_id
WHERE a.authorization_reference='USER_CONFIRMED_20261029_ROUTINE34_PENDING' AND c.submitted_at<=a.created_at
ON CONFLICT (tenant_id,activation_id,work_expectation_id) DO NOTHING;

SELECT scope.hotel_id,count(DISTINCT a.id) AS activated_version_items,count(c.id) AS captured_pending_records
FROM routine_activation_scope scope
LEFT JOIN work_review_policy_activation a ON a.tenant_id=scope.tenant_id AND a.target_org_unit_id=scope.hotel_id
  AND a.authorization_reference='USER_CONFIRMED_20261029_ROUTINE34_PENDING'
LEFT JOIN work_review_policy_correction c ON c.tenant_id=a.tenant_id AND c.activation_id=a.id
GROUP BY scope.hotel_id ORDER BY scope.hotel_id;
COMMIT;
