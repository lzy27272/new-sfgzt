-- Approved normal-only routine work; publishing schedules and switching allocations stay separate.
CREATE FUNCTION work_review_routine_exemption_eligible(item work_package_item)
RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
  SELECT item.required AND item.item_code IN (
    'HKA_CART_PREPARATION',
    'AGM_NS_AFTERNOON_TEA','AGM_NS_APPEARANCE_MEETING','AGM_NS_BREAKFAST_FEEDBACK',
    'AGM_NS_EVENING_LIGHT_BAR','AGM_NS_OTA_CHECK','AGM_NS_PUBLIC_AREA',
    'AGM_NS_REVIEW_TRAINING','AGM_NS_THREE_FOLLOWUPS',
    'FD_WORKSTATION_READY','FD_PMS_OTA_PRECHECK',
    'FO_AFTERNOON_TEA','FO_CTRIP_TRAINING','FO_OTA_CHECK','FO_PUBLIC_AREA','FO_THREE_FOLLOWUPS',
    'GM_APPEARANCE_MEETING_V56','GM_CCTV_REVIEW_V56','GM_FRONT_DESK_COACHING_V56',
    'GM_PUBLIC_AREA_AM_V56','GM_PUBLIC_AREA_PM_V56',
    'GM_M_CHANNEL','GM_W_MEETING','GM_W_TRAINING','GM_W_HYGIENE',
    'HKS_MORNING_ASSIGNMENT','HKS_READINESS_CHECK',
    'NSA_CART_PREPARATION','NSA_FINAL_PUBLIC_RECHECK','NSA_NIGHT_PUBLIC_PATROL',
    'SS_AFTERNOON_TEA','SS_INHOUSE_FOLLOWUP','SS_PREARRIVAL','SS_PUBLIC_AREA','SS_RECEPTION_FOLLOWUP'
  )
    AND item.item_type NOT IN ('REVIEW_APPROVAL','METRIC_REVIEW')
    AND coalesce(item.execution_policy->>'handoverConfirmationRequired','false')<>'true'
    AND coalesce(item.execution_policy->>'highRiskImmediateEscalation','false')<>'true'
    AND coalesce(item.execution_policy->>'reworkReinspectionRequired','false')<>'true'
    AND coalesce(item.execution_policy->>'inventoryUsage','false')<>'true'
    AND coalesce(item.submission_policy->>'inventoryUsage','false')<>'true'
    AND coalesce(item.execution_policy->>'repeatableDailyRecords','false')<>'true'
    AND nullif(btrim(item.execution_policy->>'inventoryMovement'),'') IS NULL
$$;

-- Use the same explicit exception evidence for historic capture and live completion.
-- Plans, ordinary service counts and special-arrival requests alone are not failures.
CREATE FUNCTION work_review_record_has_exception(record work_record)
RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
  SELECT btrim(coalesce(record.exception_statement,'')) NOT IN ('','无','无异常','正常','未发现问题','无问题')
    OR btrim(coalesce(record.payload->>'issueSummary','')) NOT IN ('','无','无异常','正常','未发现问题','无问题')
    OR EXISTS (
      SELECT 1 FROM (VALUES ('issueFound'),('hasException'),('lostPropertyOccurred')) flag(key)
      WHERE lower(btrim(coalesce(record.payload->>flag.key,''))) NOT IN ('','false','0','无','否')
    )
    OR btrim(coalesce(record.payload->>'serviceAwareness','')) NOT IN ('','达标')
    OR btrim(coalesce(record.payload->>'receptionProcess','')) NOT IN ('','正确')
    OR btrim(coalesce(record.payload->>'pendingHandover','')) NOT IN
      ('','无','无异常','正常','未发现问题','无问题','无待交接事项','无未完成事项')
    OR EXISTS (
      SELECT 1 FROM (VALUES ('complaintCount'),('reworkCount'),('cashDifference')) metric(key)
      WHERE nullif(btrim(record.payload->>metric.key),'') IS NOT NULL
        AND CASE WHEN btrim(record.payload->>metric.key) ~ '^[+-]?[0-9]+([.][0-9]+)?$'
          THEN (record.payload->>metric.key)::numeric<>0 ELSE true END
    )
$$;

-- Exact item/version, tenant and hotel bindings remain required; no shared-key or role-wide override.
CREATE OR REPLACE FUNCTION work_review_configured_mode(item work_package_item, expectation work_expectation)
RETURNS text LANGUAGE sql STABLE AS $$
  SELECT CASE WHEN work_review_routine_exemption_eligible(item)
    AND item.tenant_id=expectation.tenant_id AND item.id=expectation.work_package_item_id
    AND EXISTS (
      SELECT 1 FROM work_review_policy_activation a
      WHERE a.tenant_id=item.tenant_id AND a.work_package_item_id=item.id
        AND a.target_org_unit_id=expectation.target_org_unit_id
        AND ((expectation.business_date>=a.new_work_from AND NOT EXISTS (
          SELECT 1 FROM work_record prior
          WHERE prior.tenant_id=expectation.tenant_id AND prior.work_expectation_id=expectation.id
            AND prior.submitted_at<a.created_at)) OR EXISTS (
          SELECT 1 FROM work_review_policy_correction c
          WHERE c.tenant_id=a.tenant_id AND c.activation_id=a.id
            AND c.work_expectation_id=expectation.id))
    ) THEN 'NONE' ELSE item.review_mode END
$$;
