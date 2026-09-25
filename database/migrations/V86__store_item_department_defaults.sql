-- User-approved classification of legacy store items/assets. Metadata only:
-- preserve explicit scopes, positions, employee eligibility, stock, cost and custody.
-- Do not disable FORCE RLS or rely on a superuser bypass.
-- tenant itself also has FORCE RLS. Temporarily allow only the migration owner
-- to enumerate tenant IDs; employee/item policies stay enforced throughout.
-- Flyway runs this file transactionally, so failures also roll back this policy.
DO $$ BEGIN
    EXECUTE format('CREATE POLICY v86_tenant_discovery ON tenant FOR SELECT TO %I USING (true)', current_user);
END $$;
DO $$
DECLARE
    tenant_row RECORD;
    kind TEXT;
    source_table TEXT;
    mapping_table TEXT;
    id_column TEXT;
    code_column TEXT;
    item_row RECORD;
    department UUID;
    prefix TEXT;
    expected_name TEXT;
    config JSONB;
    candidate_ids UUID[];
    existing_departments BOOLEAN;
    before_snapshot JSONB;
    target_mode TEXT;
    old_tenant TEXT := current_setting('app.tenant_id', true);
BEGIN
    FOR tenant_row IN SELECT id FROM tenant ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', tenant_row.id::text, true);
        FOREACH kind IN ARRAY ARRAY['ITEM', 'ASSET'] LOOP
            source_table := CASE kind WHEN 'ITEM' THEN 'store_resource_item' ELSE 'store_fixed_asset' END;
            mapping_table := CASE kind WHEN 'ITEM' THEN 'store_item_usage_department' ELSE 'store_asset_usage_department' END;
            id_column := CASE kind WHEN 'ITEM' THEN 'item_id' ELSE 'asset_id' END;
            code_column := CASE kind WHEN 'ITEM' THEN 'sku' ELSE 'asset_code' END;
            FOR item_row IN EXECUTE format(
                'select id, hotel_org_unit_id, %I as code, to_jsonb(t) as snapshot from %I t
                 where tenant_id=$1 and usage_scope_mode=''LEGACY'' order by id for update', code_column, source_table)
                USING tenant_row.id LOOP
                before_snapshot := item_row.snapshot;
                department := NULL;
                EXECUTE format('select exists(select 1 from %I where tenant_id=$1 and hotel_org_unit_id=$2 and %I=$3)', mapping_table, id_column)
                    INTO existing_departments USING tenant_row.id, item_row.hotel_org_unit_id, item_row.id;
                -- An existing manual department/position association is never replaced.
                IF NOT existing_departments THEN
                    prefix := substring(upper(btrim(item_row.code)) from '^(QT|KF|WX)-');
                    expected_name := CASE prefix WHEN 'QT' THEN '前厅部' WHEN 'KF' THEN '客房部' WHEN 'WX' THEN '维修部' END;
                    SELECT to_jsonb(c) INTO config FROM store_department_prefix_config c
                        WHERE c.tenant_id=tenant_row.id AND c.hotel_org_unit_id=item_row.hotel_org_unit_id;
                    IF prefix IS NOT NULL THEN
                        SELECT array_agg(d.id ORDER BY d.id) INTO candidate_ids FROM org_unit d
                        WHERE d.tenant_id=tenant_row.id AND d.unit_type='DEPARTMENT' AND d.status='ACTIVE'
                          AND EXISTS (SELECT 1 FROM org_unit_closure c WHERE c.tenant_id=d.tenant_id
                              AND c.ancestor_id=item_row.hotel_org_unit_id AND c.descendant_id=d.id)
                          AND NOT EXISTS (SELECT 1 FROM org_unit_closure c JOIN org_unit a
                              ON a.tenant_id=c.tenant_id AND a.id=c.ancestor_id
                              WHERE c.tenant_id=d.tenant_id AND c.descendant_id=d.id AND a.status<>'ACTIVE')
                          AND CASE WHEN config IS NULL THEN d.name=expected_name
                              ELSE d.id=(config->>(lower(prefix)||'_department_id'))::uuid END;
                        IF cardinality(candidate_ids)=1 THEN department := candidate_ids[1]; END IF;
                    END IF;
                    IF department IS NOT NULL THEN
                        EXECUTE format('insert into %I(tenant_id,hotel_org_unit_id,%I,department_id) values($1,$2,$3,$4)', mapping_table, id_column)
                            USING tenant_row.id, item_row.hotel_org_unit_id, item_row.id, department;
                    END IF;
                END IF;
                target_mode := CASE WHEN existing_departments OR department IS NOT NULL THEN 'DEPARTMENTS' ELSE 'ALL_DEPARTMENTS' END;
                EXECUTE format('update %I set usage_scope_mode=$1,row_version=row_version+1 where tenant_id=$2 and id=$3', source_table)
                    USING target_mode, tenant_row.id, item_row.id;
                INSERT INTO audit_log(tenant_id,action,resource_type,resource_id,correlation_id,before_data,after_data)
                VALUES(tenant_row.id,'STORE_LEGACY_SCOPE_DEFAULTED',CASE kind WHEN 'ITEM' THEN 'STORE_RESOURCE_ITEM' ELSE 'STORE_FIXED_ASSET' END,
                    item_row.id,gen_random_uuid(),before_snapshot,
                    jsonb_build_object('migration','V86','usageScopeMode',target_mode,'departmentId',department,
                        'preservedDepartmentMapping',existing_departments,'preservedPositionRestrictions',true));
            END LOOP;
        END LOOP;
    END LOOP;
    PERFORM set_config('app.tenant_id',coalesce(old_tenant,''),true);
END $$;
DROP POLICY v86_tenant_discovery ON tenant;
