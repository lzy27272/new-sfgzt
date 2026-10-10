-- Copy the approved tenant's current manager standard verbatim. This distributes
-- readable standards only: the runtime outbound AI hotel allowlist is unchanged.
-- New databases without an approved source are intentionally left unconfigured.
SELECT set_config('app.tenant_id', '10000000-0000-0000-0000-000000000001', true);

INSERT INTO ai_evidence_rule(id,tenant_id,position_id,version_no,state,configuration,created_by)
SELECT gen_random_uuid(), source.tenant_id, assistant.id,
       coalesce((SELECT max(version_no) FROM ai_evidence_rule old
                 WHERE old.tenant_id=source.tenant_id AND old.position_id=assistant.id),0)+1,
       'SHADOW', source.configuration || jsonb_build_object(
         'subjectScope','WORK_PARTICIPANTS', 'sourceRuleId',source.id,
         'sourceRuleVersion',source.version_no, 'hotelIds',hotels.ids,
         'itemCodes',jsonb_build_array('AGM_NS_APPEARANCE_MEETING','AGM_WS_APPEARANCE_MEETING')),
       source.created_by
FROM ai_evidence_rule source
JOIN position_definition manager ON manager.tenant_id=source.tenant_id AND manager.id=source.position_id
  AND manager.code='GENERAL_MANAGER' AND manager.status='ACTIVE'
JOIN position_definition assistant ON assistant.tenant_id=source.tenant_id
  AND assistant.code='ASSISTANT_GENERAL_MANAGER' AND assistant.status='ACTIVE'
JOIN LATERAL (
  SELECT jsonb_agg(o.id ORDER BY o.id) AS ids FROM org_unit o
  WHERE o.tenant_id=source.tenant_id AND o.unit_type='HOTEL' AND o.status='ACTIVE'
    AND o.name NOT IN ('集团总部','测试门店')
) hotels ON hotels.ids IS NOT NULL
WHERE source.tenant_id='10000000-0000-0000-0000-000000000001' AND source.state='SHADOW'
  -- Do not overwrite a rule/draft configured by an administrator meanwhile.
  AND NOT EXISTS(SELECT 1 FROM ai_evidence_rule existing
                 WHERE existing.tenant_id=source.tenant_id AND existing.position_id=assistant.id);

-- Photos are referenced, not moved/duplicated/deleted. Old rules and checks remain
-- immutable. Sample access is authorized through each applicable rule version.
