-- Time-limited deputies never change the underlying four-stage approval responsibility.
INSERT INTO permission(code,resource,action,description,delegable_to_position,function_category) VALUES
 ('finance.archive.read','finance','archive.read','按原始来源权限读取财务档案图谱及冻结版本',true,'FINANCE'),
 ('finance.archive.grant','finance','archive.grant','独立审核有范围和时效的财务档案调阅申请',true,'FINANCE')
 ON CONFLICT(code) DO NOTHING;
CREATE TABLE fd_delegation (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, hotel_id UUID NOT NULL,
 source_kind VARCHAR(12) NOT NULL CHECK(source_kind IN ('CLAIM','OPERATION')),
 original_id UUID NOT NULL, deputy_id UUID NOT NULL, valid_from TIMESTAMPTZ NOT NULL, valid_until TIMESTAMPTZ NOT NULL,
 reason VARCHAR(2000) NOT NULL, status VARCHAR(12) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','REVOKED','REJECTED')),
 created_by UUID NOT NULL, published_by UUID, version INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,original_id) REFERENCES user_account(tenant_id,id), FOREIGN KEY(tenant_id,deputy_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES user_account(tenant_id,id), FOREIGN KEY(tenant_id,published_by) REFERENCES user_account(tenant_id,id),
 CHECK(original_id<>deputy_id AND valid_until>valid_from AND valid_until<=valid_from+interval '90 days'),
 CHECK(published_by IS NULL OR published_by<>created_by)
);
CREATE INDEX ix_fd_delegation_lookup ON fd_delegation(tenant_id,hotel_id,source_kind,original_id,valid_from,valid_until);
CREATE TABLE fd_route (
 tenant_id UUID NOT NULL, source_kind VARCHAR(12) NOT NULL, source_id UUID NOT NULL, source_version INT NOT NULL, node INT NOT NULL CHECK(node BETWEEN 0 AND 3),
 delegation_id UUID NOT NULL, original_id UUID NOT NULL, deputy_id UUID NOT NULL, valid_from TIMESTAMPTZ NOT NULL, valid_until TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,source_kind,source_id,source_version,node,delegation_id),
 FOREIGN KEY(tenant_id,delegation_id) REFERENCES fd_delegation(tenant_id,id)
);
CREATE INDEX ix_fd_route_deputy ON fd_route(tenant_id,deputy_id,valid_until);
CREATE FUNCTION fd_current_reviewer(t UUID,k TEXT,s UUID,n INT,o UUID) RETURNS UUID
 LANGUAGE sql STABLE SECURITY INVOKER SET search_path=public AS $$
 SELECT coalesce((SELECT r.deputy_id
   FROM fd_route r JOIN fd_delegation d ON d.tenant_id=r.tenant_id AND d.id=r.delegation_id
   WHERE r.tenant_id=t AND r.source_kind=k AND r.source_id=s AND r.node=n
     AND r.source_version=(SELECT max(v.source_version) FROM fd_route v
       WHERE v.tenant_id=t AND v.source_kind=k AND v.source_id=s AND v.node=n)
     AND r.original_id=o AND d.status='PUBLISHED'
     AND statement_timestamp()>=r.valid_from AND statement_timestamp()<r.valid_until
     AND EXISTS(SELECT 1 FROM user_account u WHERE u.tenant_id=t AND u.id=r.deputy_id AND u.status='ACTIVE')
   ORDER BY r.valid_from DESC,r.delegation_id LIMIT 1),o)
 $$;
CREATE TABLE fc_budget_exception (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, hotel_id UUID NOT NULL, policy_id UUID NOT NULL,
 source_kind VARCHAR(12) NOT NULL CHECK(source_kind IN ('CLAIM','OPERATION')), source_id UUID NOT NULL, source_version INT NOT NULL,
 ceiling NUMERIC(18,2) NOT NULL CHECK(ceiling>0), valid_until TIMESTAMPTZ NOT NULL, reason VARCHAR(2000) NOT NULL,
 status VARCHAR(12) NOT NULL DEFAULT 'REVIEW' CHECK(status IN ('REVIEW','APPROVED','REJECTED','VOID')),
 applicant_id UUID NOT NULL, reviewer_id UUID NOT NULL, reviewed_by UUID, review_note VARCHAR(2000),
 version INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,policy_id) REFERENCES fc_policy(tenant_id,id),
 FOREIGN KEY(tenant_id,applicant_id) REFERENCES user_account(tenant_id,id), FOREIGN KEY(tenant_id,reviewer_id) REFERENCES user_account(tenant_id,id),
 CHECK(applicant_id<>reviewer_id), CHECK(reviewed_by IS NULL OR reviewed_by=reviewer_id)
);
CREATE INDEX ix_fc_exception_source ON fc_budget_exception(tenant_id,source_kind,source_id,policy_id,status);
DO $$ DECLARE tab TEXT; BEGIN
 FOREACH tab IN ARRAY ARRAY['fd_delegation','fd_route','fc_budget_exception'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
 END LOOP;
 CREATE TRIGGER fd_route_immutable BEFORE UPDATE OR DELETE ON fd_route FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
  GRANT SELECT,INSERT,UPDATE ON fd_delegation,fc_budget_exception TO hotel_ai_os_app;
  GRANT SELECT,INSERT ON fd_route TO hotel_ai_os_app;
  REVOKE UPDATE,DELETE ON fd_route FROM hotel_ai_os_app;
  REVOKE DELETE ON fd_delegation,fc_budget_exception FROM hotel_ai_os_app;
 END IF;
END $$;
CREATE FUNCTION fd_protect_reviewed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (TG_TABLE_NAME='fd_delegation' AND OLD.status='PUBLISHED' AND
      NOT(NEW.status='REVOKED' AND (to_jsonb(OLD)-'status'-'version')=(to_jsonb(NEW)-'status'-'version')))
    OR (TG_TABLE_NAME='fd_delegation' AND OLD.status IN('REVOKED','REJECTED'))
    OR (TG_TABLE_NAME='fc_budget_exception' AND OLD.status IN('APPROVED','REJECTED','VOID')) THEN
  RAISE EXCEPTION 'reviewed financial authorization is immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fd_published_immutable BEFORE UPDATE ON fd_delegation FOR EACH ROW EXECUTE FUNCTION fd_protect_reviewed();
CREATE TRIGGER fc_exception_immutable BEFORE UPDATE ON fc_budget_exception FOR EACH ROW EXECUTE FUNCTION fd_protect_reviewed();
