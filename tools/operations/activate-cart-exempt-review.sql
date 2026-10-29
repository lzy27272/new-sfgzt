-- psql -X -v ON_ERROR_STOP=1 -v tenant_id=... -v hotel_one=... -v hotel_two=... -f this-file
-- Run only after V117 is deployed and the paired production recovery gate passes.
-- Authorizes cart preparation only; does not publish schedule drafts or change submitted facts.
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
SELECT pg_advisory_xact_lock(hashtextextended('activate-cart-exempt-review',0));
CREATE TEMP TABLE cart_activation_scope ON COMMIT DROP AS
SELECT o.tenant_id,o.id AS hotel_id,(now() AT TIME ZONE t.timezone)::date AS new_work_from
FROM org_unit o JOIN tenant t ON t.id=o.tenant_id
WHERE o.tenant_id=:'tenant_id'::uuid AND o.id IN (:'hotel_one'::uuid,:'hotel_two'::uuid)
  AND o.unit_type='HOTEL' AND o.status='ACTIVE';
DO $$ BEGIN
  IF (SELECT count(*) FROM cart_activation_scope)<>2 THEN
    RAISE EXCEPTION 'Exactly the two authorized active hotels are required';
  END IF;
END $$;

INSERT INTO work_review_policy_activation
  (tenant_id,target_org_unit_id,work_package_item_id,new_work_from,authorization_reference,reason)
SELECT DISTINCT scope.tenant_id,scope.hotel_id,i.id,scope.new_work_from,
  'USER_CONFIRMED_20261029_CART_PENDING',
  '已确认：工作车整理后续提交免审，已有待审记录一并修正；保留原始提交和抽查整改。物品领用及下达任务继续人工审核。'
FROM cart_activation_scope scope
JOIN work_package_item i ON i.tenant_id=scope.tenant_id
WHERE i.item_code='HKA_CART_PREPARATION' AND i.required AND i.review_mode<>'NONE'
  AND (EXISTS (
    SELECT 1 FROM work_package_allocation a JOIN work_package_version v
      ON v.tenant_id=a.tenant_id AND v.id=a.work_package_version_id
    WHERE a.tenant_id=scope.tenant_id AND a.target_org_unit_id=scope.hotel_id
      AND a.work_package_version_id=i.work_package_version_id
      AND a.status='ACTIVE' AND a.valid_from<=scope.new_work_from
      AND (a.valid_to IS NULL OR a.valid_to>=scope.new_work_from) AND v.lifecycle_status='PUBLISHED'
  ) OR EXISTS (
    SELECT 1 FROM work_expectation x JOIN work_record r ON r.tenant_id=x.tenant_id AND r.work_expectation_id=x.id
    WHERE x.tenant_id=scope.tenant_id AND x.target_org_unit_id=scope.hotel_id
      AND x.work_package_item_id=i.id AND x.status='SUBMITTED' AND r.status='SUBMITTED'
  ))
  AND i.item_type NOT IN ('REVIEW_APPROVAL','METRIC_REVIEW')
  AND coalesce(i.execution_policy->>'handoverConfirmationRequired','false')<>'true'
  AND coalesce(i.execution_policy->>'highRiskImmediateEscalation','false')<>'true'
  AND coalesce(i.execution_policy->>'inventoryUsage','false')<>'true'
  AND coalesce(i.submission_policy->>'inventoryUsage','false')<>'true'
  AND nullif(btrim(i.execution_policy->>'inventoryMovement'),'') IS NULL
ON CONFLICT (tenant_id,target_org_unit_id,work_package_item_id) DO NOTHING;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM cart_activation_scope scope WHERE NOT EXISTS (
    SELECT 1 FROM work_review_policy_activation a
    WHERE a.tenant_id=scope.tenant_id AND a.target_org_unit_id=scope.hotel_id
      AND a.authorization_reference='USER_CONFIRMED_20261029_CART_PENDING')) THEN
    RAISE EXCEPTION 'An authorized hotel has no eligible active cart item';
  END IF;
END $$;

INSERT INTO work_review_policy_correction
  (tenant_id,activation_id,work_expectation_id,work_record_id,before_record,before_expectation)
SELECT a.tenant_id,a.id,x.id,r.id,to_jsonb(r),to_jsonb(x)
FROM work_review_policy_activation a
JOIN cart_activation_scope scope ON scope.tenant_id=a.tenant_id AND scope.hotel_id=a.target_org_unit_id
JOIN work_expectation x ON x.tenant_id=a.tenant_id AND x.work_package_item_id=a.work_package_item_id
  AND x.target_org_unit_id=a.target_org_unit_id
JOIN LATERAL (
  SELECT record.* FROM work_record record
  WHERE record.tenant_id=x.tenant_id AND record.work_expectation_id=x.id
  ORDER BY record.series_entry_no DESC,record.attempt_no DESC,record.id DESC LIMIT 1
) r ON true
WHERE a.authorization_reference='USER_CONFIRMED_20261029_CART_PENDING'
  AND r.status='SUBMITTED' AND NOT r.repeatable_entry AND x.status='SUBMITTED'
  AND r.submitted_at<=a.created_at
  AND btrim(coalesce(r.exception_statement,'')) IN ('','无','无异常','正常','未发现问题')
  AND coalesce(r.payload->>'issueFound','false')<>'true'
  AND btrim(coalesce(r.payload->>'issueSummary','')) IN ('','无','无异常','正常','未发现问题')
  AND NOT EXISTS (SELECT 1 FROM work_record failed
    WHERE failed.tenant_id=x.tenant_id AND failed.work_expectation_id=x.id AND failed.status='REJECTED')
ON CONFLICT (tenant_id,activation_id,work_expectation_id) DO NOTHING;

SELECT scope.hotel_id,count(DISTINCT a.id) AS activated_items,count(c.id) AS captured_pending_records
FROM cart_activation_scope scope
LEFT JOIN work_review_policy_activation a ON a.tenant_id=scope.tenant_id AND a.target_org_unit_id=scope.hotel_id
LEFT JOIN work_review_policy_correction c ON c.tenant_id=a.tenant_id AND c.activation_id=a.id
GROUP BY scope.hotel_id ORDER BY scope.hotel_id;
COMMIT;
