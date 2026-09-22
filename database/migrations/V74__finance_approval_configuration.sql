-- Version existing chains; never silently add or remove a reviewer on an in-flight claim.
ALTER TABLE finance_store_config
 ALTER COLUMN manager_account_id DROP NOT NULL,
 ALTER COLUMN finance_account_id DROP NOT NULL,
 ALTER COLUMN leader_account_id DROP NOT NULL,
 ADD COLUMN director_account_id UUID,
 ADD COLUMN cashier_account_id UUID,
 ADD COLUMN leader_name VARCHAR(120) NOT NULL DEFAULT '',
 ADD COLUMN draft_configuration JSONB,
 ADD COLUMN skip_self_review BOOLEAN NOT NULL DEFAULT false,
 ADD COLUMN workflow_version SMALLINT NOT NULL DEFAULT 1 CHECK(workflow_version IN (1,2)),
 ADD FOREIGN KEY(tenant_id,director_account_id) REFERENCES user_account(tenant_id,id),
 ADD FOREIGN KEY(tenant_id,cashier_account_id) REFERENCES user_account(tenant_id,id),
 ADD CONSTRAINT finance_config_distinct_director CHECK(director_account_id<>manager_account_id AND director_account_id<>finance_account_id AND director_account_id<>leader_account_id),
 ADD CONSTRAINT finance_config_enabled_complete CHECK(NOT enabled OR
   (manager_account_id IS NOT NULL AND finance_account_id IS NOT NULL AND leader_account_id IS NOT NULL AND
    (workflow_version=1 OR (director_account_id IS NOT NULL AND cashier_account_id IS NOT NULL))));

ALTER TABLE finance_claim DROP CONSTRAINT finance_claim_status_check;
ALTER TABLE finance_claim
 ADD CONSTRAINT finance_claim_status_check CHECK(status IN ('DRAFT','RETURNED','MANAGER_REVIEW','FINANCE_REVIEW','DIRECTOR_REVIEW','LEADER_REVIEW','APPROVED','REJECTED','WITHDRAWN')),
 ADD COLUMN director_account_id UUID,
 ADD COLUMN cashier_account_id UUID,
 ADD COLUMN workflow_version SMALLINT NOT NULL DEFAULT 1 CHECK(workflow_version IN (1,2)),
 ADD COLUMN approval_route JSONB NOT NULL DEFAULT '[]',
 ADD FOREIGN KEY(tenant_id,director_account_id) REFERENCES user_account(tenant_id,id),
 ADD FOREIGN KEY(tenant_id,cashier_account_id) REFERENCES user_account(tenant_id,id),
 ADD CONSTRAINT finance_claim_complete_chain CHECK(workflow_version=1 OR (director_account_id IS NOT NULL AND cashier_account_id IS NOT NULL));

CREATE INDEX ix_finance_config_director ON finance_store_config(tenant_id,director_account_id) WHERE director_account_id IS NOT NULL;
CREATE INDEX ix_finance_config_cashier ON finance_store_config(tenant_id,cashier_account_id) WHERE cashier_account_id IS NOT NULL;
CREATE INDEX ix_finance_claim_director ON finance_claim(tenant_id,director_account_id,status) WHERE director_account_id IS NOT NULL;
CREATE INDEX ix_finance_claim_cashier ON finance_claim(tenant_id,cashier_account_id,status) WHERE cashier_account_id IS NOT NULL;

-- Tenant RLS and immutable finance_review audit facts from V72 remain in force.
