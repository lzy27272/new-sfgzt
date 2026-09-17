package cn.sifangguan.hotelaios.organization;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.PilotPasswordHasher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OrganizationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final PilotPasswordHasher passwordHasher;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;

    public OrganizationService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            PilotPasswordHasher passwordHasher,
            AuditWriter auditWriter,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.passwordHasher = passwordHasher;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOrgUnits(String unitType) {
        accessPolicy.requirePermission("org.read");
        TenantPrincipal principal = prepare();
        if (!principal.hasTenantScope() && principal.orgScopes().isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = base(principal).addValue("unitType", normalize(unitType));
        String visibility = visibility("o", principal, parameters);
        return jdbc.queryForList("""
                select o.id, o.parent_id, o.code, o.name, o.unit_type, o.status, o.sort_order,
                       h.property_code, h.city, h.room_count, h.opening_date,
                       h.breakfast_service_enabled, h.guest_room_floor_count,
                       h.assistant_work_template_mode,
                       coalesce((h.operational_feature_flags ->> 'kitchen')::boolean, false) as kitchen_enabled,
                       coalesce((h.operational_feature_flags ->> 'afternoonTea')::boolean, false) as afternoon_tea_enabled,
                       coalesce((h.operational_feature_flags ->> 'bar')::boolean, false) as bar_enabled,
                       coalesce((h.operational_feature_flags ->> 'projection')::boolean, false) as projection_enabled,
                       coalesce((h.operational_feature_flags ->> 'gym')::boolean, false) as gym_enabled,
                       coalesce((h.operational_feature_flags ->> 'terrace')::boolean, false) as terrace_enabled,
                       coalesce((h.operational_feature_flags ->> 'trendyPlayArea')::boolean, false) as trendy_play_area_enabled,
                       coalesce(h.evidence_instance_configuration,
                           '{"facilityInstances":{},"assetInstances":{}}'::jsonb) as evidence_instance_configuration
                from org_unit o
                left join hotel_profile h on h.tenant_id = o.tenant_id and h.org_unit_id = o.id
                where o.tenant_id = :tenantId
                  and o.status <> 'DELETED'
                  and (cast(:unitType as varchar) is null or o.unit_type = :unitType)
                """ + visibility + " order by o.sort_order, o.name", parameters);
    }

    @Transactional
    public Map<String, Object> createOrgUnit(OrganizationModels.CreateOrgUnit request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        UUID id = UUID.randomUUID();
        String unitType = normalize(request.unitType());
        if (!Set.of("GROUP", "REGION", "HOTEL", "DEPARTMENT").contains(unitType)) {
            throw new IllegalArgumentException("组织类型必须为集团、区域、门店或部门");
        }
        String parentType = null;
        if (request.parentId() != null) {
            parentType = requireOrgType(principal, request.parentId());
            accessPolicy.requireOrgScope(request.parentId());
        } else if (!principal.hasTenantScope()) {
            throw new AccessDeniedException("仅租户级管理员可以创建根组织");
        }
        validateHierarchy(unitType, request.parentId(), parentType);
        if ("HOTEL".equals(unitType) && isBlank(request.propertyCode())) {
            throw new IllegalArgumentException("创建门店时必须填写门店编码");
        }
        if (request.roomCount() != null && request.roomCount() < 0) {
            throw new IllegalArgumentException("房间数不能小于0");
        }
        String assistantMode = assistantWorkTemplateMode(request.assistantWorkTemplateMode());
        Map<String, List<String>> facilityEvidenceInstances = request.facilityEvidenceInstances() == null
                ? defaultFacilityEvidenceInstances(request.gymEnabled())
                : normalizeEvidenceInstances(request.facilityEvidenceInstances(), "设施");
        Map<String, List<String>> assetEvidenceInstances = request.assetEvidenceInstances() == null
                ? defaultAssetEvidenceInstances(request.breakfastServiceEnabled())
                : normalizeEvidenceInstances(request.assetEvidenceInstances(), "设备");
        requireUniqueCode("org_unit", principal, id, request.code(), "组织编码已存在");
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", id)
                .addValue("parentId", request.parentId())
                .addValue("code", request.code().trim())
                .addValue("name", request.name().trim())
                .addValue("unitType", unitType)
                .addValue("sortOrder", request.sortOrder() == null ? 0 : request.sortOrder())
                .addValue("propertyCode", trimToNull(request.propertyCode()))
                .addValue("city", trimToNull(request.city()))
                .addValue("roomCount", request.roomCount())
                .addValue("openingDate", request.openingDate())
                .addValue("breakfastServiceEnabled", Boolean.TRUE.equals(request.breakfastServiceEnabled()))
                .addValue("guestRoomFloorCount", request.guestRoomFloorCount() == null
                        ? 1 : request.guestRoomFloorCount())
                .addValue("assistantWorkTemplateMode", assistantMode)
                .addValue("kitchenEnabled", Boolean.TRUE.equals(request.kitchenEnabled()))
                .addValue("afternoonTeaEnabled", Boolean.TRUE.equals(request.afternoonTeaEnabled()))
                .addValue("barEnabled", Boolean.TRUE.equals(request.barEnabled()))
                .addValue("projectionEnabled", Boolean.TRUE.equals(request.projectionEnabled()))
                .addValue("gymEnabled", Boolean.TRUE.equals(request.gymEnabled()))
                .addValue("terraceEnabled", Boolean.TRUE.equals(request.terraceEnabled()))
                .addValue("trendyPlayAreaEnabled", Boolean.TRUE.equals(request.trendyPlayAreaEnabled()))
                .addValue("facilityEvidenceInstances", writeJson(facilityEvidenceInstances))
                .addValue("assetEvidenceInstances", writeJson(assetEvidenceInstances));
        jdbc.update("""
                insert into org_unit (id, tenant_id, parent_id, code, name, unit_type, sort_order)
                values (:id, :tenantId, :parentId, :code, :name, :unitType, :sortOrder)
                """, parameters);
        jdbc.update("""
                insert into org_unit_closure (tenant_id, ancestor_id, descendant_id, depth)
                values (:tenantId, :id, :id, 0)
                """, parameters);
        if (request.parentId() != null) {
            jdbc.update("""
                    insert into org_unit_closure (tenant_id, ancestor_id, descendant_id, depth)
                    select tenant_id, ancestor_id, :id, depth + 1
                    from org_unit_closure
                    where tenant_id = :tenantId and descendant_id = :parentId
                    """, parameters);
        }
        if ("HOTEL".equals(unitType)) {
            jdbc.update("""
                    insert into hotel_profile
                        (id, tenant_id, org_unit_id, property_code, city, room_count, opening_date,
                         breakfast_service_enabled, guest_room_floor_count,
                         assistant_work_template_mode, operational_feature_flags,
                         evidence_instance_configuration)
                    values
                        (:hotelId, :tenantId, :id, :propertyCode, :city, :roomCount, :openingDate,
                         :breakfastServiceEnabled, :guestRoomFloorCount, :assistantWorkTemplateMode,
                         jsonb_build_object(
                           'breakfastArea', :breakfastServiceEnabled,
                           'kitchen', :kitchenEnabled,
                           'afternoonTea', :afternoonTeaEnabled,
                           'bar', :barEnabled,
                           'projection', :projectionEnabled,
                           'gym', :gymEnabled,
                           'terrace', :terraceEnabled,
                           'trendyPlayArea', :trendyPlayAreaEnabled
                         ), jsonb_build_object(
                           'facilityInstances', cast(:facilityEvidenceInstances as jsonb),
                           'assetInstances', cast(:assetEvidenceInstances as jsonb)
                         ))
                    """, parameters.addValue("hotelId", UUID.randomUUID()));
            reconcileHotelOperationalTemplates(principal, id, assistantMode);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("code", request.code());
        response.put("name", request.name());
        response.put("unitType", unitType);
        response.put("propertyCode", trimToNull(request.propertyCode()));
        return response;
    }

    @Transactional
    public Map<String, Object> updateOrgUnit(UUID orgUnitId, OrganizationModels.UpdateOrgUnit request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        Map<String, Object> current = requireOrg(principal, orgUnitId);
        accessPolicy.requireOrgScope(orgUnitId);
        String unitType = String.valueOf(current.get("unit_type"));
        String status = lifecycleStatus(request.status());
        if ("GROUP".equals(unitType) && "INACTIVE".equals(status)) {
            throw new IllegalArgumentException("集团根组织不能停用");
        }
        if (request.roomCount() != null && request.roomCount() < 0) {
            throw new IllegalArgumentException("房间数不能小于0");
        }
        if (request.guestRoomFloorCount() != null
                && (request.guestRoomFloorCount() < 1 || request.guestRoomFloorCount() > 100)) {
            throw new IllegalArgumentException("客房楼层数必须在1到100之间");
        }
        if ("HOTEL".equals(unitType) && isBlank(request.propertyCode())) {
            throw new IllegalArgumentException("门店必须保留门店编码");
        }
        String assistantMode = request.assistantWorkTemplateMode() == null
                ? null : assistantWorkTemplateMode(request.assistantWorkTemplateMode());
        String facilityEvidenceInstances = request.facilityEvidenceInstances() == null
                ? null : writeJson(normalizeEvidenceInstances(request.facilityEvidenceInstances(), "设施"));
        String assetEvidenceInstances = request.assetEvidenceInstances() == null
                ? null : writeJson(normalizeEvidenceInstances(request.assetEvidenceInstances(), "设备"));
        String previousAssistantMode = "HOTEL".equals(unitType)
                ? jdbc.queryForObject("""
                        select assistant_work_template_mode
                        from hotel_profile
                        where tenant_id = :tenantId and org_unit_id = :id
                        """, base(principal).addValue("id", orgUnitId), String.class)
                : null;
        requireUniqueCode("org_unit", principal, orgUnitId, request.code(), "组织编码已存在");
        if ("ACTIVE".equals(status) && current.get("parent_id") != null) {
            Integer activeParent = jdbc.queryForObject("""
                    select count(*) from org_unit
                    where tenant_id = :tenantId and id = :parentId and status = 'ACTIVE'
                    """, base(principal).addValue("parentId", current.get("parent_id")), Integer.class);
            if (activeParent == null || activeParent != 1) {
                throw new IllegalArgumentException("上级组织未启用，不能启用当前组织");
            }
        }
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", orgUnitId)
                .addValue("code", request.code().trim())
                .addValue("name", request.name().trim())
                .addValue("sortOrder", request.sortOrder() == null ? 0 : request.sortOrder())
                .addValue("status", status)
                .addValue("propertyCode", trimToNull(request.propertyCode()))
                .addValue("city", trimToNull(request.city()))
                .addValue("roomCount", request.roomCount())
                .addValue("openingDate", request.openingDate())
                .addValue("breakfastServiceEnabled", Boolean.TRUE.equals(request.breakfastServiceEnabled()))
                .addValue("guestRoomFloorCount", request.guestRoomFloorCount() == null
                        ? 1 : request.guestRoomFloorCount())
                .addValue("assistantWorkTemplateMode", assistantMode)
                .addValue("kitchenEnabled", request.kitchenEnabled())
                .addValue("afternoonTeaEnabled", request.afternoonTeaEnabled())
                .addValue("barEnabled", request.barEnabled())
                .addValue("projectionEnabled", request.projectionEnabled())
                .addValue("gymEnabled", request.gymEnabled())
                .addValue("terraceEnabled", request.terraceEnabled())
                .addValue("trendyPlayAreaEnabled", request.trendyPlayAreaEnabled())
                .addValue("facilityEvidenceInstances", facilityEvidenceInstances)
                .addValue("assetEvidenceInstances", assetEvidenceInstances);
        jdbc.update("""
                update org_unit
                set code = :code, name = :name, sort_order = :sortOrder, status = :status, updated_at = now()
                where tenant_id = :tenantId and id = :id
                """, parameters);
        if ("HOTEL".equals(unitType)) {
            jdbc.update("""
                    update hotel_profile
                    set property_code = :propertyCode, city = :city, room_count = :roomCount,
                        opening_date = :openingDate,
                        breakfast_service_enabled = :breakfastServiceEnabled,
                        guest_room_floor_count = :guestRoomFloorCount,
                        assistant_work_template_mode = coalesce(:assistantWorkTemplateMode, assistant_work_template_mode),
                        operational_feature_flags = operational_feature_flags || jsonb_strip_nulls(jsonb_build_object(
                          'breakfastArea', :breakfastServiceEnabled,
                          'kitchen', :kitchenEnabled,
                          'afternoonTea', :afternoonTeaEnabled,
                          'bar', :barEnabled,
                          'projection', :projectionEnabled,
                          'gym', :gymEnabled,
                          'terrace', :terraceEnabled,
                          'trendyPlayArea', :trendyPlayAreaEnabled
                        )),
                        evidence_instance_configuration = jsonb_build_object(
                          'facilityInstances', coalesce(
                            cast(:facilityEvidenceInstances as jsonb),
                            evidence_instance_configuration -> 'facilityInstances', '{}'::jsonb),
                          'assetInstances', coalesce(
                            cast(:assetEvidenceInstances as jsonb),
                            evidence_instance_configuration -> 'assetInstances', '{}'::jsonb)
                        ),
                        updated_at = now()
                    where tenant_id = :tenantId and org_unit_id = :id
                    """, parameters);
            if (assistantMode != null && !assistantMode.equals(previousAssistantMode)) {
                reconcileHotelOperationalTemplates(principal, orgUnitId, assistantMode);
            }
        }
        if ("INACTIVE".equals(status)) {
            deactivateOrgTree(principal, orgUnitId);
        }
        auditWriter.record("ORG_UNIT_UPDATED", "ORG_UNIT", orgUnitId,
                "{\"status\":\"" + status + "\",\"code\":\"" + jsonEscape(request.code().trim()) + "\"}");
        return Map.of("id", orgUnitId, "code", request.code().trim(), "name", request.name().trim(),
                "unitType", unitType, "status", status);
    }

    private static String assistantWorkTemplateMode(String value) {
        String normalized = value == null || value.isBlank() ? null : value.trim().toUpperCase();
        if (normalized == null) return "WITH_FRONT_OFFICE_SUPERVISOR";
        if (!Set.of("WITH_FRONT_OFFICE_SUPERVISOR", "WITHOUT_FRONT_OFFICE_SUPERVISOR")
                .contains(normalized)) {
            throw new IllegalArgumentException("店助工作模板必须选择有前厅主管或无前厅主管");
        }
        return normalized;
    }

    private static Map<String, List<String>> defaultFacilityEvidenceInstances(Boolean gymEnabled) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        List<String> serviceFacilities = new java.util.ArrayList<>(List.of("百宝箱", "水吧台", "行李房"));
        List<String> publicAreas = new java.util.ArrayList<>(List.of("大堂", "百宝箱", "水吧台", "行李房", "洗衣房"));
        if (Boolean.TRUE.equals(gymEnabled)) {
            serviceFacilities.add("健身房");
            publicAreas.add("健身房");
        }
        result.put("serviceFacilityIds", List.copyOf(serviceFacilities));
        result.put("publicAreaFacilityIds", List.copyOf(publicAreas));
        result.put("eveningPublicAreaFacilityIds", List.of("门头灯光", "前厅大堂", "洗衣房", "楼层过道及电梯间"));
        return result;
    }

    private static Map<String, List<String>> defaultAssetEvidenceInstances(Boolean breakfastServiceEnabled) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("warmingEquipmentIds", Boolean.TRUE.equals(breakfastServiceEnabled)
                ? List.of("保温设备") : List.of());
        result.put("equipmentAssetIds", List.of("空气能机组"));
        return result;
    }

    private static Map<String, List<String>> normalizeEvidenceInstances(
            Map<String, List<String>> configured,
            String label
    ) {
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : configured.entrySet()) {
            String field = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!field.matches("[A-Za-z][A-Za-z0-9]{0,63}")) {
                throw new IllegalArgumentException(label + "证据清单字段格式不正确");
            }
            List<String> values = entry.getValue() == null ? List.of() : entry.getValue();
            if (values.size() > 100) {
                throw new IllegalArgumentException(label + "证据清单每组最多100项");
            }
            Set<String> distinct = new LinkedHashSet<>();
            List<String> items = new java.util.ArrayList<>(values.size());
            for (String value : values) {
                String item = value == null ? "" : value.trim();
                if (item.isBlank() || item.length() > 120) {
                    throw new IllegalArgumentException(label + "名称不能为空且最多120个字符");
                }
                if (!distinct.add(item.toUpperCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException(label + "名称不能重复：" + item);
                }
                items.add(item);
            }
            normalized.put(field, List.copyOf(items));
        }
        return Map.copyOf(normalized);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("门店证据清单无法序列化", exception);
        }
    }

    /**
     * Keeps the release-owned store-assistant/front-office work packages and daily-report
     * schedules aligned with the explicit hotel mode.  The switch becomes effective on
     * the next database business date; today's expectations and reports remain immutable.
     */
    private void reconcileHotelOperationalTemplates(
            TenantPrincipal principal,
            UUID hotelOrgUnitId,
            String assistantMode
    ) {
        LocalDate databaseToday = jdbc.queryForObject(
                "select current_date", base(principal), LocalDate.class);
        if (databaseToday == null) throw new IllegalStateException("无法读取数据库营业日期");
        LocalDate effectiveDate = databaseToday.plusDays(1);
        MapSqlParameterSource params = base(principal)
                .addValue("hotelOrgUnitId", hotelOrgUnitId)
                .addValue("assistantMode", assistantMode)
                .addValue("today", databaseToday)
                .addValue("effectiveDate", effectiveDate)
                .addValue("actorId", principal.actorId());

        jdbc.update("""
                update work_package_allocation allocation
                set status = case when allocation.valid_from > :today then 'REVOKED' else allocation.status end,
                    valid_to = case when allocation.valid_from <= :today then :today else allocation.valid_to end,
                    updated_at = now()
                from work_package_version version
                join work_package_definition definition
                  on definition.tenant_id = version.tenant_id
                 and definition.id = version.work_package_definition_id
                , employee_position_assignment employee_assignment
                where allocation.tenant_id = :tenantId
                  and allocation.work_package_version_id = version.id
                  and employee_assignment.tenant_id = allocation.tenant_id
                  and employee_assignment.id = allocation.position_assignment_id
                  and allocation.allocation_source = 'SYSTEM'
                  and allocation.status = 'ACTIVE'
                  and definition.code in ('WP-PILOT-AGM-DAILY','WP-PILOT-FO-SUPERVISOR-DAILY')
                  and exists (
                    select 1 from org_unit_closure scope
                    where scope.tenant_id = allocation.tenant_id
                      and scope.ancestor_id = :hotelOrgUnitId
                      and scope.descendant_id = employee_assignment.org_unit_id
                  )
                """, params);

        jdbc.update("""
                insert into work_package_allocation
                    (id,tenant_id,work_package_version_id,position_assignment_id,
                     target_org_unit_id,allocation_source,valid_from,status,allocated_by)
                select gen_random_uuid(), employee_assignment.tenant_id, selected_version.id,
                       employee_assignment.id, :hotelOrgUnitId,
                       'SYSTEM', :effectiveDate, 'ACTIVE', :actorId
                from employee_position_assignment employee_assignment
                join position_definition position
                  on position.tenant_id = employee_assignment.tenant_id
                 and position.id = employee_assignment.position_id
                join employee
                  on employee.tenant_id = employee_assignment.tenant_id
                 and employee.id = employee_assignment.employee_id
                join user_account account
                  on account.tenant_id = employee.tenant_id and account.id = employee.account_id
                join lateral (
                    select version.id
                    from work_package_definition definition
                    join work_package_version version
                      on version.tenant_id = definition.tenant_id
                     and version.work_package_definition_id = definition.id
                    where definition.tenant_id = employee_assignment.tenant_id
                      and definition.code = case position.code
                          when 'ASSISTANT_GENERAL_MANAGER' then 'WP-PILOT-AGM-DAILY'
                          else 'WP-PILOT-FO-SUPERVISOR-DAILY' end
                      and version.lifecycle_status = 'PUBLISHED'
                      and version.effective_from::date <= :effectiveDate
                      and (version.effective_to is null or version.effective_to::date >= :effectiveDate)
                      and (
                        position.code = 'FRONT_OFFICE_SUPERVISOR'
                        or exists (
                          select 1 from work_package_item item
                          where item.tenant_id = version.tenant_id
                            and item.work_package_version_id = version.id
                            and item.applicability_policy ->> 'assistantWorkTemplateMode' = :assistantMode
                        )
                      )
                    order by version.version_no desc
                    limit 1
                ) selected_version on true
                where employee_assignment.tenant_id = :tenantId
                  and position.code in ('ASSISTANT_GENERAL_MANAGER','FRONT_OFFICE_SUPERVISOR')
                  and (position.code <> 'FRONT_OFFICE_SUPERVISOR'
                       or :assistantMode = 'WITH_FRONT_OFFICE_SUPERVISOR')
                  and employee_assignment.status = 'ACTIVE'
                  and employee_assignment.valid_from <= :effectiveDate
                  and (employee_assignment.valid_to is null or employee_assignment.valid_to >= :effectiveDate)
                  and employee.employment_status = 'ACTIVE' and employee.deleted_at is null
                  and account.status = 'ACTIVE'
                  and exists (
                    select 1 from org_unit_closure scope
                    where scope.tenant_id = employee_assignment.tenant_id
                      and scope.ancestor_id = :hotelOrgUnitId
                      and scope.descendant_id = employee_assignment.org_unit_id
                  )
                on conflict (tenant_id,work_package_version_id,position_assignment_id,
                             target_org_unit_id,valid_from)
                do update set status = 'ACTIVE', valid_to = null,
                              allocated_by = excluded.allocated_by, updated_at = now()
                """, params);

        jdbc.update("""
                update daily_report_template_assignment template_assignment
                set status = case when template_assignment.valid_from > :today then 'REVOKED'
                                  else template_assignment.status end,
                    valid_to = case when template_assignment.valid_from <= :today then :today
                                    else template_assignment.valid_to end,
                    row_version = template_assignment.row_version + 1,
                    updated_at = now()
                from daily_report_template_version version
                join daily_report_template_definition definition
                  on definition.tenant_id = version.tenant_id and definition.id = version.template_id
                where template_assignment.tenant_id = :tenantId
                  and template_assignment.template_version_id = version.id
                  and template_assignment.assignment_kind = 'BASE'
                  and template_assignment.scope_type = 'ORG_TREE'
                  and template_assignment.org_unit_id = :hotelOrgUnitId
                  and template_assignment.status = 'ACTIVE'
                  and definition.code in (
                    'DRT-PILOT-AGM-WITH-SUPERVISOR',
                    'DRT-PILOT-AGM-WITHOUT-SUPERVISOR',
                    'DRT-PILOT-FO-SUPERVISOR-DAILY'
                  )
                """, params);

        jdbc.update("""
                update daily_report_template_assignment template_assignment
                set status = 'ACTIVE', valid_to = null, assigned_by = :actorId,
                    row_version = template_assignment.row_version + 1, updated_at = now()
                from daily_report_template_version version
                join daily_report_template_definition definition
                  on definition.tenant_id = version.tenant_id and definition.id = version.template_id
                where template_assignment.tenant_id = :tenantId
                  and template_assignment.template_version_id = version.id
                  and template_assignment.assignment_kind = 'BASE'
                  and template_assignment.scope_type = 'ORG_TREE'
                  and template_assignment.org_unit_id = :hotelOrgUnitId
                  and template_assignment.valid_from = :effectiveDate
                  and (
                    definition.code = case :assistantMode
                      when 'WITH_FRONT_OFFICE_SUPERVISOR' then 'DRT-PILOT-AGM-WITH-SUPERVISOR'
                      else 'DRT-PILOT-AGM-WITHOUT-SUPERVISOR' end
                    or (definition.code = 'DRT-PILOT-FO-SUPERVISOR-DAILY'
                        and :assistantMode = 'WITH_FRONT_OFFICE_SUPERVISOR')
                  )
                """, params);

        jdbc.update("""
                insert into daily_report_template_assignment
                    (id,tenant_id,template_version_id,assignment_kind,scope_type,
                     org_unit_id,priority,valid_from,status,assigned_by)
                select gen_random_uuid(), version.tenant_id, version.id, 'BASE', 'ORG_TREE',
                       :hotelOrgUnitId, 900, :effectiveDate, 'ACTIVE', :actorId
                from daily_report_template_version version
                join daily_report_template_definition definition
                  on definition.tenant_id = version.tenant_id and definition.id = version.template_id
                where version.tenant_id = :tenantId
                  and version.lifecycle_status = 'PUBLISHED'
                  and version.effective_from::date <= :effectiveDate
                  and (version.effective_to is null or version.effective_to::date >= :effectiveDate)
                  and (
                    definition.code = case :assistantMode
                      when 'WITH_FRONT_OFFICE_SUPERVISOR' then 'DRT-PILOT-AGM-WITH-SUPERVISOR'
                      else 'DRT-PILOT-AGM-WITHOUT-SUPERVISOR' end
                    or (definition.code = 'DRT-PILOT-FO-SUPERVISOR-DAILY'
                        and :assistantMode = 'WITH_FRONT_OFFICE_SUPERVISOR')
                  )
                  and not exists (
                    select 1 from daily_report_template_assignment existing
                    where existing.tenant_id = version.tenant_id
                      and existing.template_version_id = version.id
                      and existing.assignment_kind = 'BASE'
                      and existing.scope_type = 'ORG_TREE'
                      and existing.org_unit_id = :hotelOrgUnitId
                      and existing.valid_from = :effectiveDate
                  )
                  and version.version_no = (
                    select max(candidate.version_no)
                    from daily_report_template_version candidate
                    where candidate.tenant_id = version.tenant_id
                      and candidate.template_id = version.template_id
                      and candidate.lifecycle_status = 'PUBLISHED'
                      and candidate.effective_from::date <= :effectiveDate
                      and (candidate.effective_to is null or candidate.effective_to::date >= :effectiveDate)
                  )
                """, params);

        jdbc.update("""
                insert into daily_report_delivery_policy
                    (id,tenant_id,template_assignment_id,enabled,open_local_time,due_local_time,
                     grace_minutes,pre_due_reminder_minutes,overdue_reminder_minutes,backfill_days,
                     created_by,updated_by)
                select gen_random_uuid(), template_assignment.tenant_id, template_assignment.id, true,
                       case
                         when definition.code = 'DRT-PILOT-FO-SUPERVISOR-DAILY' then time '18:00'
                         else time '19:00' end,
                       case
                         when definition.code = 'DRT-PILOT-FO-SUPERVISOR-DAILY' then time '20:00'
                         else time '21:30' end,
                       0, array[15]::integer[], array[5,20]::integer[], 1,
                       :actorId, :actorId
                from daily_report_template_assignment template_assignment
                join daily_report_template_version version
                  on version.tenant_id = template_assignment.tenant_id
                 and version.id = template_assignment.template_version_id
                join daily_report_template_definition definition
                  on definition.tenant_id = version.tenant_id and definition.id = version.template_id
                where template_assignment.tenant_id = :tenantId
                  and template_assignment.scope_type = 'ORG_TREE'
                  and template_assignment.org_unit_id = :hotelOrgUnitId
                  and template_assignment.valid_from = :effectiveDate
                  and template_assignment.status = 'ACTIVE'
                  and definition.code in (
                    'DRT-PILOT-AGM-WITH-SUPERVISOR',
                    'DRT-PILOT-AGM-WITHOUT-SUPERVISOR',
                    'DRT-PILOT-FO-SUPERVISOR-DAILY'
                  )
                  and not exists (
                    select 1 from daily_report_delivery_policy existing
                    where existing.tenant_id = template_assignment.tenant_id
                      and existing.template_assignment_id = template_assignment.id
                  )
                """, params);

        auditWriter.record("HOTEL_OPERATIONAL_TEMPLATE_MODE_RECONCILED", "ORG_UNIT", hotelOrgUnitId,
                "{\"assistantWorkTemplateMode\":\"" + assistantMode
                        + "\",\"effectiveFrom\":\"" + effectiveDate + "\"}");
    }

    /**
     * Reconciles the release-owned operational daily package for one assignment.
     * Allocations start no earlier than the next business date, are always owned
     * by the closest HOTEL ancestor, and select the store's explicit assistant
     * mode/facility configuration.
     */
    private void reconcileOperationalDailyAllocation(TenantPrincipal principal, UUID assignmentId) {
        LocalDate databaseToday = jdbc.queryForObject(
                "select current_date", base(principal), LocalDate.class);
        if (databaseToday == null) throw new IllegalStateException("无法读取数据库营业日期");
        LocalDate effectiveDate = databaseToday.plusDays(1);
        MapSqlParameterSource params = base(principal)
                .addValue("assignmentId", assignmentId)
                .addValue("today", databaseToday)
                .addValue("effectiveDate", effectiveDate)
                .addValue("actorId", principal.actorId());

        jdbc.update("""
                update work_package_allocation allocation
                set status = case when allocation.valid_from > :today then 'REVOKED'
                                  else allocation.status end,
                    valid_to = case when allocation.valid_from <= :today then :today
                                    else allocation.valid_to end,
                    updated_at = now()
                from work_package_version version
                join work_package_definition definition
                  on definition.tenant_id = version.tenant_id
                 and definition.id = version.work_package_definition_id
                where allocation.tenant_id = :tenantId
                  and allocation.position_assignment_id = :assignmentId
                  and allocation.work_package_version_id = version.id
                  and allocation.allocation_source = 'SYSTEM'
                  and allocation.status = 'ACTIVE'
                  and definition.code in (
                    'WP-PILOT-GM-DAILY','WP-PILOT-AGM-DAILY',
                    'WP-PILOT-FO-SUPERVISOR-DAILY','WP-PILOT-SERVICE-STEWARD-DAILY',
                    'WP-PILOT-FRONT-DAILY','WP-PILOT-HK-SUPERVISOR-DAILY',
                    'WP-PILOT-HK-ATTENDANT-DAILY','WP-PILOT-NIGHT-ATTENDANT-DAILY',
                    'WP-PILOT-BREAKFAST-LEAD-DAILY'
                  )
                  and (allocation.valid_to is null or allocation.valid_to >= :today)
                """, params);

        jdbc.update("""
                insert into work_package_allocation
                    (id,tenant_id,work_package_version_id,position_assignment_id,
                     target_org_unit_id,allocation_source,valid_from,valid_to,status,allocated_by)
                select gen_random_uuid(), assignment.tenant_id, selected_version.id, assignment.id,
                       hotel_config.hotel_org_unit_id, 'SYSTEM',
                       greatest(:effectiveDate, assignment.valid_from), assignment.valid_to,
                       'ACTIVE', :actorId
                from employee_position_assignment assignment
                join position_definition position
                  on position.tenant_id = assignment.tenant_id
                 and position.id = assignment.position_id
                join employee employee_row
                  on employee_row.tenant_id = assignment.tenant_id
                 and employee_row.id = assignment.employee_id
                join user_account account
                  on account.tenant_id = employee_row.tenant_id
                 and account.id = employee_row.account_id
                join lateral (
                    select profile.org_unit_id as hotel_org_unit_id,
                           profile.assistant_work_template_mode,
                           coalesce(
                             (profile.operational_feature_flags ->> 'breakfastArea')::boolean,
                             profile.breakfast_service_enabled
                           ) as breakfast_area_enabled
                    from org_unit_closure scope
                    join hotel_profile profile
                      on profile.tenant_id = scope.tenant_id
                     and profile.org_unit_id = scope.ancestor_id
                    where scope.tenant_id = assignment.tenant_id
                      and scope.descendant_id = assignment.org_unit_id
                    order by scope.depth asc
                    limit 1
                ) hotel_config on true
                join lateral (
                    select version.id
                    from work_package_definition definition
                    join work_package_version version
                      on version.tenant_id = definition.tenant_id
                     and version.work_package_definition_id = definition.id
                    where definition.tenant_id = assignment.tenant_id
                      and definition.code = case position.code
                        when 'GENERAL_MANAGER' then 'WP-PILOT-GM-DAILY'
                        when 'ASSISTANT_GENERAL_MANAGER' then 'WP-PILOT-AGM-DAILY'
                        when 'FRONT_OFFICE_SUPERVISOR' then 'WP-PILOT-FO-SUPERVISOR-DAILY'
                        when 'SERVICE_STEWARD' then 'WP-PILOT-SERVICE-STEWARD-DAILY'
                        when 'FRONT_DESK' then 'WP-PILOT-FRONT-DAILY'
                        when 'HOUSEKEEPING_SUPERVISOR' then 'WP-PILOT-HK-SUPERVISOR-DAILY'
                        when 'HOUSEKEEPING_ATTENDANT' then 'WP-PILOT-HK-ATTENDANT-DAILY'
                        when 'NIGHT_SERVICE_ATTENDANT' then 'WP-PILOT-NIGHT-ATTENDANT-DAILY'
                        when 'BREAKFAST_AREA_LEAD' then 'WP-PILOT-BREAKFAST-LEAD-DAILY'
                        else null end
                      and version.lifecycle_status = 'PUBLISHED'
                      and version.effective_from is not null
                      and version.effective_from::date <= greatest(:effectiveDate, assignment.valid_from)
                      and (version.effective_to is null
                           or version.effective_to::date >= greatest(:effectiveDate, assignment.valid_from))
                      and (
                        position.code <> 'ASSISTANT_GENERAL_MANAGER'
                        or exists (
                          select 1 from work_package_item item
                          where item.tenant_id = version.tenant_id
                            and item.work_package_version_id = version.id
                            and item.applicability_policy ->> 'assistantWorkTemplateMode'
                                = hotel_config.assistant_work_template_mode
                        )
                      )
                    order by version.version_no desc
                    limit 1
                ) selected_version on true
                where assignment.tenant_id = :tenantId
                  and assignment.id = :assignmentId
                  and assignment.status = 'ACTIVE'
                  and position.status = 'ACTIVE'
                  and position.deleted_at is null and position.permanently_deleted_at is null
                  and employee_row.employment_status = 'ACTIVE' and employee_row.deleted_at is null
                  and account.status = 'ACTIVE'
                  and (assignment.valid_to is null
                       or assignment.valid_to >= greatest(:effectiveDate, assignment.valid_from))
                  and (position.code <> 'FRONT_OFFICE_SUPERVISOR'
                       or hotel_config.assistant_work_template_mode = 'WITH_FRONT_OFFICE_SUPERVISOR')
                  and (position.code <> 'BREAKFAST_AREA_LEAD'
                       or hotel_config.breakfast_area_enabled)
                on conflict (tenant_id,work_package_version_id,position_assignment_id,
                             target_org_unit_id,valid_from)
                do update set status = 'ACTIVE', valid_to = excluded.valid_to,
                              allocated_by = excluded.allocated_by, updated_at = now()
                """, params);
    }

    @Transactional
    public void deleteOrgUnit(UUID orgUnitId) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "组织删除只能由集团级管理员执行");
        Map<String, Object> current = requireOrg(principal, orgUnitId);
        if ("GROUP".equals(String.valueOf(current.get("unit_type")))) {
            throw new IllegalArgumentException("集团根组织不能删除");
        }
        if (!"INACTIVE".equals(String.valueOf(current.get("status")))) {
            throw new IllegalArgumentException("请先停用组织，再执行删除");
        }
        MapSqlParameterSource parameters = base(principal).addValue("id", orgUnitId);
        List<Map<String, Object>> subtree = jdbc.queryForList("""
                select child.id, child.status
                from org_unit_closure closure
                join org_unit child
                  on child.tenant_id = closure.tenant_id
                 and child.id = closure.descendant_id
                where closure.tenant_id = :tenantId and closure.ancestor_id = :id
                order by closure.depth desc
                """, parameters);
        if (subtree.stream().anyMatch(row -> !"INACTIVE".equals(String.valueOf(row.get("status"))))) {
            throw new IllegalArgumentException("删除前必须先停用该组织及全部下级组织");
        }
        List<UUID> subtreeIds = subtree.stream()
                .map(row -> (UUID) row.get("id"))
                .toList();
        int deleted = jdbc.update("""
                update org_unit
                set status = 'DELETED', updated_at = now()
                where tenant_id = :tenantId and id in (:subtreeIds) and status = 'INACTIVE'
                """, parameters.addValue("subtreeIds", subtreeIds));
        if (deleted != subtreeIds.size()) {
            throw new IllegalArgumentException("组织状态已变更，请刷新后重试");
        }
        auditWriter.record("ORG_UNIT_DELETED", "ORG_UNIT", orgUnitId,
                "{\"status\":\"DELETED\",\"subtreeCount\":" + deleted + "}");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPositions() {
        accessPolicy.requirePermission("org.read");
        TenantPrincipal principal = prepare();
        return jdbc.queryForList("""
                select position.id, position.code, position.name, position.job_family,
                       position.level_code, position.status,
                       position.applies_to_all_hotels,
                       published.authorization_scope_type,
                       applicable_scope.applicable_hotel_ids
                from position_definition position
                join position_function_profile profile
                  on profile.tenant_id = position.tenant_id
                 and profile.position_id = position.id
                 and profile.scope_type = 'GROUP'
                join position_function_profile_version published
                  on published.tenant_id = profile.tenant_id
                 and published.profile_id = profile.id
                 and published.lifecycle_status = 'PUBLISHED'
                left join lateral (
                    select string_agg(applicable.hotel_org_unit_id::text, ','
                                      order by applicable.hotel_org_unit_id) as applicable_hotel_ids
                    from position_applicable_hotel applicable
                    where applicable.tenant_id = position.tenant_id
                      and applicable.position_id = position.id
                ) applicable_scope on true
                where position.tenant_id = :tenantId
                  and position.deleted_at is null and position.permanently_deleted_at is null
                  and exists (
                    select 1
                    from position_function_profile visible_profile
                    join position_function_profile_version visible_published
                      on visible_published.tenant_id = visible_profile.tenant_id
                     and visible_published.profile_id = visible_profile.id
                     and visible_published.lifecycle_status = 'PUBLISHED'
                    where visible_profile.tenant_id = position.tenant_id
                      and visible_profile.position_id = position.id
                      and visible_profile.scope_type = 'GROUP'
                  )
                order by position.job_family, position.level_code, position.name
                """, base(principal));
    }

    @Transactional
    public Map<String, Object> createPosition(OrganizationModels.CreatePosition request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "岗位字典只能由集团级管理员维护");
        UUID id = UUID.randomUUID();
        requireUniqueCode("position_definition", principal, id, request.code(), "岗位编码已存在");
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", id)
                .addValue("code", request.code().trim())
                .addValue("name", request.name().trim())
                .addValue("jobFamily", request.jobFamily().trim())
                .addValue("levelCode", request.levelCode());
        jdbc.update("""
                insert into position_definition (id, tenant_id, code, name, job_family, level_code)
                values (:id, :tenantId, :code, :name, :jobFamily, :levelCode)
                """, parameters);
        return Map.of("id", id, "code", request.code(), "name", request.name());
    }

    @Transactional
    public Map<String, Object> updatePosition(UUID positionId, OrganizationModels.UpdatePosition request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "岗位字典只能由集团级管理员维护");
        requireEntity("position_definition", principal, positionId);
        requireUniqueCode("position_definition", principal, positionId, request.code(), "岗位编码已存在");
        String status = lifecycleStatus(request.status());
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", positionId)
                .addValue("code", request.code().trim())
                .addValue("name", request.name().trim())
                .addValue("jobFamily", request.jobFamily().trim())
                .addValue("levelCode", trimToNull(request.levelCode()))
                .addValue("status", status);
        jdbc.update("""
                update position_definition
                set code = :code, name = :name, job_family = :jobFamily,
                    level_code = :levelCode, status = :status, updated_at = now()
                where tenant_id = :tenantId and id = :id
                """, parameters);
        if ("INACTIVE".equals(status)) {
            jdbc.update("""
                    update employee_position_assignment
                    set status = 'INACTIVE', valid_to = case
                        when valid_to is null or valid_to > current_date then current_date else valid_to end,
                        updated_at = now()
                    where tenant_id = :tenantId and position_id = :id and status = 'ACTIVE'
                    """, parameters);
        }
        auditWriter.record("POSITION_UPDATED", "POSITION", positionId,
                "{\"status\":\"" + status + "\",\"code\":\"" + jsonEscape(request.code().trim()) + "\"}");
        return Map.of("id", positionId, "code", request.code().trim(), "name", request.name().trim(), "status", status);
    }

    @Transactional
    public void deletePosition(UUID positionId) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "岗位删除只能由集团级管理员执行");
        String status = jdbc.queryForObject("""
                select status from position_definition
                where tenant_id = :tenantId and id = :id
                """, base(principal).addValue("id", positionId), String.class);
        if (!"INACTIVE".equals(status)) {
            throw new IllegalArgumentException("请先停用岗位，再执行删除");
        }
        try {
            int deleted = jdbc.update("""
                    delete from position_definition where tenant_id = :tenantId and id = :id
                    """, base(principal).addValue("id", positionId));
            if (deleted != 1) {
                throw new IllegalArgumentException("岗位不存在或不属于当前租户");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("该岗位已有任职、标准、表单或工作包数据，只能停用，不能删除", exception);
        }
        auditWriter.record("POSITION_DELETED", "POSITION", positionId, "{\"status\":\"DELETED\"}");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listEmployees() {
        accessPolicy.requirePermission("org.read");
        TenantPrincipal principal = prepare();
        if (!principal.hasTenantScope() && principal.orgScopes().isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = base(principal);
        String visibility = visibility("o", principal, parameters);
        return jdbc.queryForList("""
                select distinct e.id, e.account_id, u.login_name, u.status as account_status,
                       e.employee_no, e.name, e.mobile, e.employment_status, e.hired_on,
                       a.id as assignment_id, a.manager_assignment_id, a.is_primary,
                       a.assignment_type, a.valid_from, a.valid_to,
                       o.id as org_unit_id, o.name as org_unit_name,
                       p.id as position_id, p.name as position_name,
                       published.authorization_scope_type,
                       assigned_scope.responsible_hotel_ids,
                       assigned_scope.responsible_hotel_names
                from employee e
                left join user_account u on u.tenant_id = e.tenant_id and u.id = e.account_id
                left join employee_position_assignment a
                  on a.tenant_id = e.tenant_id and a.employee_id = e.id and a.status = 'ACTIVE'
                left join org_unit o on o.tenant_id = a.tenant_id and o.id = a.org_unit_id
                left join position_definition p on p.tenant_id = a.tenant_id and p.id = a.position_id
                left join position_function_profile profile
                  on profile.tenant_id = p.tenant_id and profile.position_id = p.id
                 and profile.scope_type = 'GROUP'
                left join position_function_profile_version published
                  on published.tenant_id = profile.tenant_id and published.profile_id = profile.id
                 and published.lifecycle_status = 'PUBLISHED'
                left join lateral (
                    select string_agg(scope.hotel_org_unit_id::text, ',' order by hotel.name) as responsible_hotel_ids,
                           string_agg(hotel.name, '、' order by hotel.name) as responsible_hotel_names
                    from employee_assignment_hotel_scope scope
                    join org_unit hotel
                      on hotel.tenant_id = scope.tenant_id and hotel.id = scope.hotel_org_unit_id
                    where scope.tenant_id = a.tenant_id and scope.assignment_id = a.id
                ) assigned_scope on true
                where e.tenant_id = :tenantId and e.deleted_at is null
                """ + visibility + " order by e.name, a.is_primary desc", parameters);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDeletedEmployees() {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "员工回收站只能由集团级管理员查看");
        return jdbc.queryForList("""
                select e.id, e.account_id, u.login_name, e.employee_no, e.name, e.mobile,
                       e.employment_status, e.hired_on, e.deleted_at,
                       deleted_by.display_name as deleted_by_name,
                       latest.org_unit_id, latest.org_unit_name,
                       latest.position_id, latest.position_name
                from employee e
                left join user_account u
                  on u.tenant_id = e.tenant_id and u.id = e.account_id
                left join user_account deleted_by
                  on deleted_by.tenant_id = e.tenant_id and deleted_by.id = e.deleted_by
                left join lateral (
                    select a.org_unit_id, o.name as org_unit_name,
                           a.position_id, p.name as position_name
                    from employee_position_assignment a
                    join org_unit o on o.tenant_id = a.tenant_id and o.id = a.org_unit_id
                    join position_definition p on p.tenant_id = a.tenant_id and p.id = a.position_id
                    where a.tenant_id = e.tenant_id and a.employee_id = e.id
                    order by a.updated_at desc, a.created_at desc, a.id
                    limit 1
                ) latest on true
                where e.tenant_id = :tenantId
                  and e.deleted_at is not null and e.permanently_deleted_at is null
                order by e.deleted_at desc, e.id
                """, base(principal));
    }

    @Transactional
    public Map<String, Object> createEmployee(OrganizationModels.CreateEmployee request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "员工账号只能由集团级管理员创建");
        UUID id = UUID.randomUUID();
        requireUniqueCode("employee", principal, id, request.employeeNo(), "员工编号已存在");
        UUID accountId = null;
        String loginName = trimToNull(request.loginName());
        String temporaryPassword = request.temporaryPassword();
        if (loginName != null) {
            requireUniqueLogin(principal, null, loginName);
            passwordHasher.requirePassword(temporaryPassword);
            accountId = UUID.randomUUID();
        } else if (!isBlank(temporaryPassword)) {
            throw new IllegalArgumentException("设置初始密码时必须同时填写登录账号");
        }
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", id)
                .addValue("accountId", accountId)
                .addValue("employeeNo", request.employeeNo().trim())
                .addValue("name", request.name().trim())
                .addValue("mobile", request.mobile())
                .addValue("hiredOn", request.hiredOn())
                .addValue("loginName", loginName == null ? null : loginName.toLowerCase())
                .addValue("passwordHash", accountId == null ? null : passwordHasher.hash(temporaryPassword));
        if (accountId != null) {
            jdbc.update("""
                    insert into user_account
                        (id, tenant_id, login_name, display_name, mobile, password_hash, password_changed_at)
                    values
                        (:accountId, :tenantId, :loginName, :name, :mobile, :passwordHash, now())
                    """, parameters);
        }
        jdbc.update("""
                insert into employee (id, tenant_id, account_id, employee_no, name, mobile, hired_on)
                values (:id, :tenantId, :accountId, :employeeNo, :name, :mobile, :hiredOn)
                """, parameters);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("accountId", accountId);
        response.put("employeeNo", request.employeeNo());
        response.put("name", request.name());
        response.put("loginName", loginName);
        return response;
    }

    @Transactional
    public Map<String, Object> updateEmployee(UUID employeeId, OrganizationModels.UpdateEmployee request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "员工账号只能由集团级管理员维护");
        Map<String, Object> current = requireEmployee(principal, employeeId);
        requireUniqueCode("employee", principal, employeeId, request.employeeNo(), "员工编号已存在");
        String employmentStatus = lifecycleStatus(request.employmentStatus());
        UUID accountId = (UUID) current.get("account_id");
        String loginName = trimToNull(request.loginName());
        String temporaryPassword = request.temporaryPassword();
        if (loginName != null) {
            requireUniqueLogin(principal, accountId, loginName);
        }
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", employeeId)
                .addValue("employeeNo", request.employeeNo().trim())
                .addValue("name", request.name().trim())
                .addValue("mobile", trimToNull(request.mobile()))
                .addValue("hiredOn", request.hiredOn())
                .addValue("employmentStatus", employmentStatus);
        if (accountId == null && loginName != null) {
            passwordHasher.requirePassword(temporaryPassword);
            accountId = UUID.randomUUID();
            parameters.addValue("accountId", accountId)
                    .addValue("loginName", loginName.toLowerCase())
                    .addValue("passwordHash", passwordHasher.hash(temporaryPassword));
            jdbc.update("""
                    insert into user_account
                        (id, tenant_id, login_name, display_name, mobile, status, password_hash, password_changed_at)
                    values
                        (:accountId, :tenantId, :loginName, :name, :mobile, :employmentStatus,
                         :passwordHash, now())
                    """, parameters);
        } else if (accountId != null) {
            if (loginName == null) {
                throw new IllegalArgumentException("已开通账号的员工必须保留登录账号");
            }
            parameters.addValue("accountId", accountId).addValue("loginName", loginName.toLowerCase());
            if (!isBlank(temporaryPassword)) {
                passwordHasher.requirePassword(temporaryPassword);
                parameters.addValue("passwordHash", passwordHasher.hash(temporaryPassword));
                jdbc.update("""
                        update user_account
                        set login_name = :loginName, display_name = :name, mobile = :mobile,
                            status = :employmentStatus, password_hash = :passwordHash,
                            password_changed_at = now(), updated_at = now()
                        where tenant_id = :tenantId and id = :accountId
                        """, parameters);
            } else {
                jdbc.update("""
                        update user_account
                        set login_name = :loginName, display_name = :name, mobile = :mobile,
                            status = :employmentStatus, updated_at = now()
                        where tenant_id = :tenantId and id = :accountId
                        """, parameters);
            }
        } else if (!isBlank(temporaryPassword)) {
            throw new IllegalArgumentException("设置初始密码时必须同时填写登录账号");
        }
        parameters.addValue("accountId", accountId);
        jdbc.update("""
                update employee
                set account_id = :accountId, employee_no = :employeeNo, name = :name,
                    mobile = :mobile, hired_on = :hiredOn, employment_status = :employmentStatus,
                    updated_at = now()
                where tenant_id = :tenantId and id = :id
                """, parameters);
        if ("INACTIVE".equals(employmentStatus)) {
            jdbc.update("""
                    update employee_position_assignment
                    set status = 'INACTIVE', valid_to = case
                        when valid_to is null or valid_to > current_date then current_date else valid_to end,
                        updated_at = now()
                    where tenant_id = :tenantId and employee_id = :id and status = 'ACTIVE'
                    """, parameters);
            if (accountId != null) {
                jdbc.update("""
                        update role_assignment
                        set valid_to = now()
                        where tenant_id = :tenantId and account_id = :accountId
                          and (valid_to is null or valid_to > now())
                        """, parameters);
            }
        }
        auditWriter.record("EMPLOYEE_UPDATED", "EMPLOYEE", employeeId,
                "{\"status\":\"" + employmentStatus + "\",\"employeeNo\":\""
                        + jsonEscape(request.employeeNo().trim()) + "\"}");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", employeeId);
        response.put("accountId", accountId);
        response.put("employeeNo", request.employeeNo().trim());
        response.put("name", request.name().trim());
        response.put("loginName", loginName);
        response.put("employmentStatus", employmentStatus);
        return response;
    }

    @Transactional
    public void deleteEmployee(UUID employeeId) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "员工删除只能由集团级管理员执行");
        Map<String, Object> current = requireEmployee(principal, employeeId);
        if (!"INACTIVE".equals(String.valueOf(current.get("employment_status")))) {
            throw new IllegalArgumentException("请先停用员工，再执行删除");
        }
        softDeleteEmployee(principal, current);
    }

    @Transactional
    public Map<String, Object> deleteAllInactiveEmployees() {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "批量删除员工只能由集团级管理员执行");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, account_id, employee_no, name, employment_status
                from employee
                where tenant_id = :tenantId and employment_status = 'INACTIVE'
                  and deleted_at is null
                order by id
                for update
                """, base(principal));
        for (Map<String, Object> row : rows) {
            softDeleteEmployee(principal, row);
        }
        auditWriter.record("INACTIVE_EMPLOYEES_BULK_DELETED", "TENANT", principal.tenantId(),
                "{\"status\":\"RECYCLE_BIN\",\"count\":" + rows.size() + "}");
        return Map.of("deletedCount", rows.size());
    }

    @Transactional
    public Map<String, Object> restoreEmployee(UUID employeeId) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "员工恢复只能由集团级管理员执行");
        Map<String, Object> current = requireDeletedEmployee(principal, employeeId);
        if (current.get("permanently_deleted_at") != null) {
            throw new IllegalArgumentException("该员工已永久删除，无法恢复");
        }
        int restored = jdbc.update("""
                update employee
                set deleted_at = null, deleted_by = null, updated_at = now()
                where tenant_id = :tenantId and id = :id
                  and deleted_at is not null and permanently_deleted_at is null
                """, base(principal).addValue("id", employeeId));
        if (restored != 1) {
            throw new IllegalArgumentException("员工不在回收站或已被永久删除");
        }
        auditWriter.record("EMPLOYEE_RESTORED", "EMPLOYEE", employeeId,
                "{\"status\":\"INACTIVE\"}");
        return Map.of(
                "id", employeeId,
                "employeeNo", String.valueOf(current.get("employee_no")),
                "name", String.valueOf(current.get("name")),
                "employmentStatus", "INACTIVE"
        );
    }

    @Transactional
    public void permanentlyDeleteEmployee(UUID employeeId) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "员工永久删除只能由集团级管理员执行");
        Map<String, Object> current = requireDeletedEmployee(principal, employeeId);
        if (current.get("permanently_deleted_at") != null) {
            throw new IllegalArgumentException("该员工已永久删除");
        }
        UUID accountId = (UUID) current.get("account_id");
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", employeeId)
                .addValue("accountId", accountId)
                .addValue("actorId", principal.actorId())
                .addValue("employeeTombstone", "PURGED-" + employeeId)
                .addValue("accountTombstone", accountId == null ? null : "purged-" + accountId);
        jdbc.update("""
                update employee
                set employee_no = :employeeTombstone, name = '已永久删除员工', mobile = null,
                    hired_on = null, employment_status = 'INACTIVE',
                    permanently_deleted_at = now(), permanently_deleted_by = :actorId,
                    updated_at = now()
                where tenant_id = :tenantId and id = :id
                  and deleted_at is not null and permanently_deleted_at is null
                """, parameters);
        if (accountId != null) {
            jdbc.update("""
                    update user_account
                    set login_name = :accountTombstone, display_name = '已永久删除账号',
                        mobile = null, status = 'INACTIVE', password_hash = null,
                        password_changed_at = now(), failed_login_attempts = 0,
                        locked_until = null, last_login_at = null, updated_at = now()
                    where tenant_id = :tenantId and id = :accountId
                    """, parameters);
            jdbc.update("""
                    update wecom_user_binding
                    set wecom_user_id = 'purged-' || cast(id as text), status = 'REVOKED',
                        user_id_fingerprint = encode(digest(
                            corp_id || ':' || 'purged-' || cast(id as text), 'sha256'), 'hex'),
                        preferred_assignment_id = null, last_verified_at = null,
                        status_reason = 'ACCOUNT_PERMANENTLY_DELETED',
                        assignment_snapshot_hash = null, assignment_selection_required = false,
                        updated_by = :actorId, row_version = row_version + 1,
                        updated_at = now()
                    where tenant_id = :tenantId and account_id = :accountId
                    """, parameters);
            jdbc.update("""
                    update wecom_user_binding_request
                    set status = case
                            when status in ('WAITING_SCAN','AUTHORIZING','PENDING_APPROVAL','CONFLICT')
                            then 'CANCELLED' else status end,
                        oauth_state_hash = null, browser_verifier_hash = null,
                        provider_code_hash = null, candidate_wecom_user_id = null,
                        candidate_fingerprint = null, conflicting_account_id = null,
                        failure_code = 'ACCOUNT_PERMANENTLY_DELETED',
                        decision_reason = '员工账号已永久删除', row_version = row_version + 1
                    where tenant_id = :tenantId and account_id = :accountId
                    """, parameters);
        }
        auditWriter.record("EMPLOYEE_PERMANENTLY_DELETED", "EMPLOYEE", employeeId,
                "{\"status\":\"PURGED\",\"identityErased\":true}");
    }

    private void softDeleteEmployee(TenantPrincipal principal, Map<String, Object> current) {
        UUID employeeId = (UUID) current.get("id");
        UUID accountId = (UUID) current.get("account_id");
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", employeeId)
                .addValue("accountId", accountId)
                .addValue("actorId", principal.actorId());
        int deleted = jdbc.update("""
                update employee
                set employment_status = 'INACTIVE', deleted_at = now(), deleted_by = :actorId,
                    updated_at = now()
                where tenant_id = :tenantId and id = :id and deleted_at is null
                """, parameters);
        if (deleted != 1) {
            throw new IllegalArgumentException("员工不存在、已删除或不属于当前租户");
        }
        jdbc.update("""
                update employee_position_assignment
                set status = 'INACTIVE', valid_to = case
                    when valid_to is null or valid_to > greatest(valid_from, current_date)
                    then greatest(valid_from, current_date) else valid_to end,
                    updated_at = now()
                where tenant_id = :tenantId and employee_id = :id and status = 'ACTIVE'
                """, parameters);
        if (accountId != null) {
            jdbc.update("""
                    update user_account
                    set status = 'INACTIVE', updated_at = now()
                    where tenant_id = :tenantId and id = :accountId
                    """, parameters);
            jdbc.update("""
                    update role_assignment
                    set valid_to = now()
                    where tenant_id = :tenantId and account_id = :accountId
                      and (valid_to is null or valid_to > now())
                    """, parameters);
            jdbc.update("""
                    update wecom_user_binding
                    set status = 'SUSPENDED', status_reason = 'ACCOUNT_OR_ASSIGNMENT_INACTIVE',
                        assignment_selection_required = false, updated_by = :actorId,
                        row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenantId and account_id = :accountId
                      and status <> 'REVOKED'
                    """, parameters);
            jdbc.update("""
                    update wecom_user_binding_request
                    set status = 'CANCELLED', oauth_state_hash = null,
                        browser_verifier_hash = null, provider_code_hash = null,
                        candidate_wecom_user_id = null, candidate_fingerprint = null,
                        conflicting_account_id = null, failure_code = 'ACCOUNT_DELETED',
                        decision_reason = '员工账号已移入回收站', row_version = row_version + 1
                    where tenant_id = :tenantId and account_id = :accountId
                      and status in ('WAITING_SCAN','AUTHORIZING','PENDING_APPROVAL','CONFLICT')
                    """, parameters);
        }
        auditWriter.record("EMPLOYEE_SOFT_DELETED", "EMPLOYEE", employeeId,
                "{\"status\":\"RECYCLE_BIN\"}");
    }

    @Transactional
    public Map<String, Object> assignPosition(UUID employeeId, OrganizationModels.CreatePositionAssignment request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        validateAssignmentDates(request.validFrom(), request.validTo());
        String assignmentType = assignmentType(request.assignmentType());
        requireOrgType(principal, request.orgUnitId());
        PositionRoleGrant positionGrant = lockActivePublishedPosition(principal, request.positionId());
        requirePositionApplicableToOrganization(principal, request.positionId(), request.orgUnitId());
        accessPolicy.requireOrgScope(request.orgUnitId());
        Set<UUID> responsibleHotelIds = validateResponsibleHotels(
                principal, request.positionId(), positionGrant.scopeType(), request.responsibleHotelIds(),
                request.orgUnitId());
        UUID accountId = lockActiveEmployeeAccount(principal, employeeId);
        validateManagerAssignment(principal, employeeId, request.managerAssignmentId(), null);
        return createAssignment(principal, employeeId, accountId, request.orgUnitId(), request.positionId(),
                request.managerAssignmentId(), Boolean.TRUE.equals(request.primary()), assignmentType,
                request.validFrom(), request.validTo(), positionGrant, responsibleHotelIds);
    }

    @Transactional
    public Map<String, Object> updateAssignment(
            UUID assignmentId,
            OrganizationModels.UpdatePositionAssignment request
    ) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        AssignmentRecord current = lockActiveAssignment(principal, assignmentId);
        accessPolicy.requireOrgScope(current.orgUnitId());
        validateAssignmentDates(request.validFrom(), request.validTo());
        String assignmentType = assignmentType(request.assignmentType());
        requireOrgType(principal, request.orgUnitId());
        PositionRoleGrant positionGrant = lockActivePublishedPosition(principal, request.positionId());
        requirePositionApplicableToOrganization(principal, request.positionId(), request.orgUnitId());
        accessPolicy.requireOrgScope(request.orgUnitId());
        validateManagerAssignment(principal, current.employeeId(), request.managerAssignmentId(), assignmentId);
        Set<UUID> responsibleHotelIds = validateResponsibleHotels(
                principal, request.positionId(), positionGrant.scopeType(), request.responsibleHotelIds(),
                request.orgUnitId());

        boolean identityChanged = !current.orgUnitId().equals(request.orgUnitId())
                || !current.positionId().equals(request.positionId());
        if (identityChanged) {
            deactivateAssignmentRows(principal, current);
            Map<String, Object> replacement = createAssignment(
                    principal, current.employeeId(), current.accountId(), request.orgUnitId(), request.positionId(),
                    request.managerAssignmentId(), Boolean.TRUE.equals(request.primary()), assignmentType,
                    LocalDate.now(), request.validTo(), positionGrant, responsibleHotelIds);
            UUID replacementId = (UUID) replacement.get("id");
            int reassignedReports = jdbc.update("""
                    update employee_position_assignment
                    set manager_assignment_id = :replacementId, updated_at = now()
                    where tenant_id = :tenantId and manager_assignment_id = :assignmentId
                      and status = 'ACTIVE'
                    """, base(principal).addValue("assignmentId", assignmentId)
                    .addValue("replacementId", replacementId));
            replacePreferredWeComAssignment(principal, current.accountId(), assignmentId, replacementId);
            auditWriter.record("POSITION_ASSIGNMENT_REPLACED", "POSITION_ASSIGNMENT", assignmentId,
                    "{\"replacementAssignmentId\":\"" + replacementId
                            + "\",\"reassignedReportCount\":" + reassignedReports + "}");
            Map<String, Object> response = new LinkedHashMap<>(replacement);
            response.put("replacedAssignmentId", assignmentId);
            response.put("replacementAssignmentId", replacementId);
            response.put("changeMode", "REPLACED");
            return response;
        }

        if (Boolean.TRUE.equals(request.primary())) {
            demoteOtherPrimaryAssignments(principal, current.employeeId(), assignmentId);
        }
        boolean primary = Boolean.TRUE.equals(request.primary());
        MapSqlParameterSource parameters = base(principal)
                .addValue("assignmentId", assignmentId)
                .addValue("managerAssignmentId", request.managerAssignmentId())
                .addValue("primary", primary)
                .addValue("assignmentType", assignmentType)
                .addValue("validFrom", request.validFrom())
                .addValue("validTo", request.validTo());
        jdbc.update("""
                update employee_position_assignment
                set manager_assignment_id = :managerAssignmentId, is_primary = :primary,
                    assignment_type = :assignmentType, valid_from = :validFrom,
                    valid_to = :validTo, updated_at = now()
                where tenant_id = :tenantId and id = :assignmentId and status = 'ACTIVE'
                """, parameters);
        jdbc.update("""
                update role_assignment
                set valid_from = greatest(valid_from, cast(:validFrom as date)::timestamptz),
                    valid_to = case when cast(:validTo as date) is null then null
                                    else (cast(:validTo as date) + interval '1 day')::timestamptz end
                where tenant_id = :tenantId and source_type = 'POSITION_ASSIGNMENT'
                  and source_assignment_id = :assignmentId
                """, parameters);
        replaceAssignmentHotelScope(principal, assignmentId, responsibleHotelIds);
        reconcileOperationalDailyAllocation(principal, assignmentId);
        UUID effectivePrimaryId = ensurePrimaryAssignment(principal, current.employeeId(),
                primary ? null : assignmentId);
        auditWriter.record("POSITION_ASSIGNMENT_UPDATED", "POSITION_ASSIGNMENT", assignmentId,
                "{\"primaryAssignmentId\":" + nullableJsonUuid(effectivePrimaryId)
                        + ",\"assignmentType\":\"" + assignmentType + "\"}");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", assignmentId);
        response.put("employeeId", current.employeeId());
        response.put("orgUnitId", request.orgUnitId());
        response.put("positionId", request.positionId());
        response.put("primaryAssignmentId", effectivePrimaryId);
        response.put("responsibleHotelIds", List.copyOf(responsibleHotelIds));
        response.put("changeMode", "UPDATED");
        return response;
    }

    @Transactional
    public void endAssignment(UUID assignmentId) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        AssignmentRecord current = lockActiveAssignment(principal, assignmentId);
        accessPolicy.requireOrgScope(current.orgUnitId());
        deactivateAssignmentRows(principal, current);
        jdbc.update("""
                update employee_position_assignment
                set manager_assignment_id = null, updated_at = now()
                where tenant_id = :tenantId and manager_assignment_id = :assignmentId
                  and status = 'ACTIVE'
                """, base(principal).addValue("assignmentId", assignmentId));
        UUID primaryAssignmentId = ensurePrimaryAssignment(principal, current.employeeId(), assignmentId);
        reconcileWeComBindingAfterAssignmentEnd(principal, current.accountId(), assignmentId,
                primaryAssignmentId);
        auditWriter.record("POSITION_ASSIGNMENT_ENDED", "POSITION_ASSIGNMENT", assignmentId,
                "{\"primaryAssignmentId\":" + nullableJsonUuid(primaryAssignmentId) + "}");
    }

    private Map<String, Object> createAssignment(
            TenantPrincipal principal,
            UUID employeeId,
            UUID accountId,
            UUID orgUnitId,
            UUID positionId,
            UUID managerAssignmentId,
            boolean primaryRequested,
            String assignmentType,
            LocalDate validFrom,
            LocalDate validTo,
            PositionRoleGrant positionGrant,
            Set<UUID> responsibleHotelIds
    ) {
        boolean primary = primaryRequested || !hasActiveAssignment(principal, employeeId);
        int previousPrimaryCount = primary
                ? demoteOtherPrimaryAssignments(principal, employeeId, null)
                : 0;

        UUID id = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", id)
                .addValue("employeeId", employeeId)
                .addValue("orgUnitId", orgUnitId)
                .addValue("positionId", positionId)
                .addValue("accountId", accountId)
                .addValue("roleAssignmentId", roleAssignmentId)
                .addValue("roleId", positionGrant.roleId())
                .addValue("scopeType", positionGrant.scopeType())
                .addValue("scopeOrgUnitId", Set.of("ORG_UNIT", "ORG_TREE").contains(positionGrant.scopeType())
                        ? orgUnitId : null)
                .addValue("actorId", principal.actorId())
                .addValue("managerAssignmentId", managerAssignmentId)
                .addValue("primary", primary)
                .addValue("assignmentType", assignmentType)
                .addValue("validFrom", validFrom)
                .addValue("validTo", validTo);
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id, manager_assignment_id,
                     is_primary, assignment_type, valid_from, valid_to)
                values
                    (:id, :tenantId, :employeeId, :orgUnitId, :positionId, :managerAssignmentId,
                     :primary, :assignmentType, :validFrom, :validTo)
                """, parameters);
        jdbc.update("""
                insert into role_assignment
                    (id, tenant_id, account_id, role_id, scope_org_unit_id, scope_type,
                     valid_from, valid_to, granted_by, source_type, source_assignment_id)
                values
                    (:roleAssignmentId, :tenantId, :accountId, :roleId, :scopeOrgUnitId, :scopeType,
                     greatest(now(), cast(:validFrom as date)::timestamptz),
                     case when cast(:validTo as date) is null then null
                          else (cast(:validTo as date) + interval '1 day')::timestamptz end,
                     :actorId, 'POSITION_ASSIGNMENT', :id)
                """, parameters);
        replaceAssignmentHotelScope(principal, id, responsibleHotelIds);
        reconcileOperationalDailyAllocation(principal, id);
        if (!primary) {
            ensurePrimaryAssignment(principal, employeeId, id);
        }
        if (previousPrimaryCount > 0) {
            auditWriter.record("POSITION_PRIMARY_ASSIGNMENT_SWITCHED", "EMPLOYEE", employeeId,
                    "{\"previousPrimaryCount\":" + previousPrimaryCount
                            + ",\"newAssignmentId\":\"" + id + "\"}");
        }
        return Map.of("id", id, "employeeId", employeeId, "orgUnitId", orgUnitId,
                "positionId", positionId, "roleAssignmentId", roleAssignmentId,
                "responsibleHotelIds", List.copyOf(responsibleHotelIds));
    }

    @Transactional
    public Map<String, Object> updateAssignmentHotelScope(
            UUID assignmentId,
            OrganizationModels.UpdateAssignmentHotelScope request
    ) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        List<Map<String, Object>> assignments = jdbc.queryForList("""
                select assignment.position_id, assignment.org_unit_id,
                       published.authorization_scope_type
                from employee_position_assignment assignment
                join position_definition position
                  on position.tenant_id = assignment.tenant_id
                 and position.id = assignment.position_id
                 and position.status = 'ACTIVE'
                 and position.deleted_at is null and position.permanently_deleted_at is null
                join position_function_profile profile
                  on profile.tenant_id = position.tenant_id
                 and profile.position_id = position.id and profile.scope_type = 'GROUP'
                join position_function_profile_version published
                  on published.tenant_id = profile.tenant_id
                 and published.profile_id = profile.id
                 and published.lifecycle_status = 'PUBLISHED'
                where assignment.tenant_id = :tenantId and assignment.id = :assignmentId
                  and assignment.status = 'ACTIVE'
                for update of assignment
                """, base(principal).addValue("assignmentId", assignmentId));
        if (assignments.size() != 1) {
            throw new IllegalArgumentException("任职不存在、已停用或岗位方案尚未发布");
        }
        Map<String, Object> assignment = assignments.getFirst();
        UUID orgUnitId = (UUID) assignment.get("org_unit_id");
        UUID positionId = (UUID) assignment.get("position_id");
        accessPolicy.requireOrgScope(orgUnitId);
        String scopeType = String.valueOf(assignment.get("authorization_scope_type"));
        Set<UUID> responsibleHotelIds = validateResponsibleHotels(
                principal, positionId, scopeType, request.responsibleHotelIds(), null);
        replaceAssignmentHotelScope(principal, assignmentId, responsibleHotelIds);
        auditWriter.record("POSITION_ASSIGNMENT_HOTEL_SCOPE_UPDATED", "POSITION_ASSIGNMENT", assignmentId,
                "{\"responsibleHotelCount\":" + responsibleHotelIds.size() + "}");
        return Map.of("assignmentId", assignmentId,
                "responsibleHotelIds", List.copyOf(responsibleHotelIds));
    }

    private AssignmentRecord lockActiveAssignment(TenantPrincipal principal, UUID assignmentId) {
        List<AssignmentRecord> rows = jdbc.query("""
                select assignment.id, assignment.employee_id, employee.account_id,
                       assignment.org_unit_id, assignment.position_id,
                       assignment.is_primary, assignment.valid_from
                from employee_position_assignment assignment
                join employee
                  on employee.tenant_id = assignment.tenant_id
                 and employee.id = assignment.employee_id
                where assignment.tenant_id = :tenantId and assignment.id = :assignmentId
                  and assignment.status = 'ACTIVE'
                  and employee.employment_status = 'ACTIVE' and employee.deleted_at is null
                  and employee.account_id is not null
                for update of assignment, employee
                """, base(principal).addValue("assignmentId", assignmentId),
                (rs, rowNum) -> new AssignmentRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("employee_id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getObject("org_unit_id", UUID.class),
                        rs.getObject("position_id", UUID.class),
                        rs.getBoolean("is_primary"),
                        rs.getObject("valid_from", LocalDate.class)));
        if (rows.size() != 1) {
            throw new IllegalArgumentException("任职不存在、已结束或员工账号不可用");
        }
        return rows.getFirst();
    }

    private void validateAssignmentDates(LocalDate validFrom, LocalDate validTo) {
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("任职结束日期不能早于开始日期");
        }
        if (validTo != null && validTo.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("不能保存已经结束的有效任职；如需立即移除，请使用“结束任职”");
        }
    }

    private String assignmentType(String value) {
        String normalized = value == null || value.isBlank() ? "PERMANENT" : value.trim().toUpperCase();
        if (!Set.of("PERMANENT", "TEMPORARY", "ACTING").contains(normalized)) {
            throw new IllegalArgumentException("任职类型必须为正式、临时或代理");
        }
        return normalized;
    }

    private void validateManagerAssignment(
            TenantPrincipal principal,
            UUID employeeId,
            UUID managerAssignmentId,
            UUID editingAssignmentId
    ) {
        if (managerAssignmentId == null) return;
        Integer count = jdbc.queryForObject("""
                select count(*)
                from employee_position_assignment manager
                where manager.tenant_id = :tenantId and manager.id = :managerAssignmentId
                  and manager.employee_id <> :employeeId
                  and manager.status = 'ACTIVE' and manager.valid_from <= current_date
                  and (manager.valid_to is null or manager.valid_to >= current_date)
                  and (cast(:editingAssignmentId as uuid) is null or manager.id <> :editingAssignmentId)
                """, base(principal).addValue("managerAssignmentId", managerAssignmentId)
                .addValue("employeeId", employeeId)
                .addValue("editingAssignmentId", editingAssignmentId), Integer.class);
        if (count == null || count != 1) {
            throw new IllegalArgumentException("上级任职已失效，或不能选择该员工自己的任职");
        }
    }

    private void deactivateAssignmentRows(TenantPrincipal principal, AssignmentRecord assignment) {
        MapSqlParameterSource parameters = base(principal)
                .addValue("assignmentId", assignment.id());
        jdbc.update("""
                update employee_position_assignment
                set status = 'INACTIVE', valid_to = case
                    when valid_to is null or valid_to > greatest(valid_from, current_date)
                    then greatest(valid_from, current_date) else valid_to end,
                    updated_at = now()
                where tenant_id = :tenantId and id = :assignmentId and status = 'ACTIVE'
                """, parameters);
        jdbc.update("""
                update role_assignment
                set valid_to = greatest(valid_from, now())
                where tenant_id = :tenantId and source_type = 'POSITION_ASSIGNMENT'
                  and source_assignment_id = :assignmentId
                  and (valid_to is null or valid_to > now())
                """, parameters);
        jdbc.update("""
                update work_package_allocation
                set status = case when valid_from > current_date then 'REVOKED' else status end,
                    valid_to = case when valid_from <= current_date then current_date else valid_to end,
                    updated_at = now()
                where tenant_id = :tenantId
                  and position_assignment_id = :assignmentId
                  and status = 'ACTIVE'
                  and (valid_to is null or valid_to >= current_date)
                """, parameters);
    }

    private boolean hasActiveAssignment(TenantPrincipal principal, UUID employeeId) {
        Boolean exists = jdbc.queryForObject("""
                select exists (
                    select 1 from employee_position_assignment
                    where tenant_id = :tenantId and employee_id = :employeeId and status = 'ACTIVE'
                )
                """, base(principal).addValue("employeeId", employeeId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private int demoteOtherPrimaryAssignments(
            TenantPrincipal principal,
            UUID employeeId,
            UUID excludedAssignmentId
    ) {
        MapSqlParameterSource parameters = base(principal).addValue("employeeId", employeeId);
        String exclusion = "";
        if (excludedAssignmentId != null) {
            parameters.addValue("excludedAssignmentId", excludedAssignmentId);
            exclusion = " and id <> :excludedAssignmentId";
        }
        return jdbc.update("""
                update employee_position_assignment
                set is_primary = false, updated_at = now()
                where tenant_id = :tenantId and employee_id = :employeeId
                  and is_primary = true and status = 'ACTIVE'
                """ + exclusion, parameters);
    }

    private UUID ensurePrimaryAssignment(
            TenantPrincipal principal,
            UUID employeeId,
            UUID deprioritizedAssignmentId
    ) {
        List<UUID> current = jdbc.query("""
                select id from employee_position_assignment
                where tenant_id = :tenantId and employee_id = :employeeId
                  and status = 'ACTIVE' and is_primary = true
                  and valid_from <= current_date and (valid_to is null or valid_to >= current_date)
                order by valid_from, id limit 1
                """, base(principal).addValue("employeeId", employeeId),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (!current.isEmpty()) return current.getFirst();

        MapSqlParameterSource parameters = base(principal).addValue("employeeId", employeeId);
        String ordering = "valid_from, id";
        if (deprioritizedAssignmentId != null) {
            parameters.addValue("deprioritizedAssignmentId", deprioritizedAssignmentId);
            ordering = "case when id = :deprioritizedAssignmentId then 1 else 0 end, valid_from, id";
        }
        List<UUID> candidates = jdbc.query("""
                select id from employee_position_assignment
                where tenant_id = :tenantId and employee_id = :employeeId
                  and status = 'ACTIVE' and valid_from <= current_date
                  and (valid_to is null or valid_to >= current_date)
                order by """ + " " + ordering + " limit 1", parameters,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (candidates.isEmpty()) return null;
        UUID selected = candidates.getFirst();
        jdbc.update("""
                update employee_position_assignment
                set is_primary = true, updated_at = now()
                where tenant_id = :tenantId and id = :assignmentId
                """, base(principal).addValue("assignmentId", selected));
        return selected;
    }

    private void replacePreferredWeComAssignment(
            TenantPrincipal principal,
            UUID accountId,
            UUID previousAssignmentId,
            UUID replacementAssignmentId
    ) {
        jdbc.update("""
                update wecom_user_binding
                set preferred_assignment_id = case
                        when preferred_assignment_id = :previousAssignmentId then :replacementAssignmentId
                        else preferred_assignment_id end,
                    assignment_snapshot_hash = null,
                    assignment_selection_required = case
                        when preferred_assignment_id = :previousAssignmentId then false
                        else assignment_selection_required end,
                    row_version = row_version + 1, updated_by = :actorId, updated_at = now()
                where tenant_id = :tenantId and account_id = :accountId and status <> 'REVOKED'
                """, base(principal).addValue("accountId", accountId)
                .addValue("previousAssignmentId", previousAssignmentId)
                .addValue("replacementAssignmentId", replacementAssignmentId)
                .addValue("actorId", principal.actorId()));
    }

    private void reconcileWeComBindingAfterAssignmentEnd(
            TenantPrincipal principal,
            UUID accountId,
            UUID endedAssignmentId,
            UUID primaryAssignmentId
    ) {
        MapSqlParameterSource parameters = base(principal)
                .addValue("accountId", accountId)
                .addValue("endedAssignmentId", endedAssignmentId)
                .addValue("primaryAssignmentId", primaryAssignmentId)
                .addValue("actorId", principal.actorId());
        if (primaryAssignmentId == null) {
            jdbc.update("""
                    update wecom_user_binding
                    set status = 'SUSPENDED', status_reason = 'ACCOUNT_OR_ASSIGNMENT_INACTIVE',
                        preferred_assignment_id = null, assignment_snapshot_hash = null,
                        assignment_selection_required = false,
                        row_version = row_version + 1, updated_by = :actorId, updated_at = now()
                    where tenant_id = :tenantId and account_id = :accountId and status <> 'REVOKED'
                    """, parameters);
            return;
        }
        jdbc.update("""
                update wecom_user_binding
                set preferred_assignment_id = case
                        when preferred_assignment_id = :endedAssignmentId then :primaryAssignmentId
                        else preferred_assignment_id end,
                    assignment_snapshot_hash = null,
                    assignment_selection_required = case
                        when preferred_assignment_id = :endedAssignmentId then false
                        else assignment_selection_required end,
                    row_version = row_version + 1, updated_by = :actorId, updated_at = now()
                where tenant_id = :tenantId and account_id = :accountId and status <> 'REVOKED'
                """, parameters);
    }

    private String nullableJsonUuid(UUID value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private record AssignmentRecord(
            UUID id,
            UUID employeeId,
            UUID accountId,
            UUID orgUnitId,
            UUID positionId,
            boolean primary,
            LocalDate validFrom
    ) { }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private String visibility(String orgAlias, TenantPrincipal principal, MapSqlParameterSource parameters) {
        if (principal.hasTenantScope()) {
            return "";
        }
        parameters.addValue("scopeIds", principal.orgScopes());
        return " and exists (select 1 from org_unit_closure vis where vis.tenant_id = " + orgAlias
                + ".tenant_id and vis.descendant_id = " + orgAlias + ".id and vis.ancestor_id in (:scopeIds))";
    }

    private String requireOrgType(TenantPrincipal principal, UUID orgUnitId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select unit_type, status from org_unit where tenant_id = :tenantId and id = :id",
                base(principal).addValue("id", orgUnitId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("组织不存在或不属于当前租户");
        }
        if (!"ACTIVE".equals(String.valueOf(rows.getFirst().get("status")))) {
            throw new IllegalArgumentException("组织已停用，不能继续配置任职或下级组织");
        }
        return String.valueOf(rows.getFirst().get("unit_type"));
    }

    private Map<String, Object> requireOrg(TenantPrincipal principal, UUID orgUnitId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, parent_id, code, name, unit_type, status, sort_order
                from org_unit where tenant_id = :tenantId and id = :id and status <> 'DELETED'
                """, base(principal).addValue("id", orgUnitId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("组织不存在或不属于当前租户");
        }
        return rows.getFirst();
    }

    private Map<String, Object> requireEmployee(TenantPrincipal principal, UUID employeeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, account_id, employee_no, name, employment_status,
                       deleted_at, permanently_deleted_at
                from employee
                where tenant_id = :tenantId and id = :id and deleted_at is null
                """, base(principal).addValue("id", employeeId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("员工不存在或不属于当前租户");
        }
        return rows.getFirst();
    }

    private Map<String, Object> requireDeletedEmployee(TenantPrincipal principal, UUID employeeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, account_id, employee_no, name, employment_status,
                       deleted_at, permanently_deleted_at
                from employee
                where tenant_id = :tenantId and id = :id and deleted_at is not null
                for update
                """, base(principal).addValue("id", employeeId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("员工不在已删除列表或不属于当前租户");
        }
        return rows.getFirst();
    }

    private void deactivateOrgTree(TenantPrincipal principal, UUID orgUnitId) {
        MapSqlParameterSource parameters = base(principal).addValue("id", orgUnitId);
        jdbc.update("""
                update org_unit o
                set status = 'INACTIVE', updated_at = now()
                where o.tenant_id = :tenantId and exists (
                    select 1 from org_unit_closure c
                    where c.tenant_id = o.tenant_id and c.ancestor_id = :id and c.descendant_id = o.id
                )
                """, parameters);
        jdbc.update("""
                update employee_position_assignment a
                set status = 'INACTIVE', valid_to = case
                    when valid_to is null or valid_to > current_date then current_date else valid_to end,
                    updated_at = now()
                where a.tenant_id = :tenantId and a.status = 'ACTIVE' and exists (
                    select 1 from org_unit_closure c
                    where c.tenant_id = a.tenant_id and c.ancestor_id = :id and c.descendant_id = a.org_unit_id
                )
                """, parameters);
        jdbc.update("""
                update role_assignment ra
                set valid_to = now()
                where ra.tenant_id = :tenantId and (ra.valid_to is null or ra.valid_to > now())
                  and ra.scope_org_unit_id is not null and exists (
                    select 1 from org_unit_closure c
                    where c.tenant_id = ra.tenant_id and c.ancestor_id = :id
                      and c.descendant_id = ra.scope_org_unit_id
                  )
                """, parameters);
    }

    private void requireEntity(String table, TenantPrincipal principal, UUID id) {
        if (!Set.of("employee", "position_definition").contains(table)) {
            throw new IllegalArgumentException("不允许的实体类型");
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where tenant_id = :tenantId and id = :id",
                base(principal).addValue("id", id),
                Integer.class
        );
        if (count == null || count == 0) {
            throw new IllegalArgumentException("实体不存在或不属于当前租户");
        }
    }

    private void requireActiveEntity(
            String table,
            String statusColumn,
            TenantPrincipal principal,
            UUID id,
            String message
    ) {
        if (!("employee".equals(table) && "employment_status".equals(statusColumn))
                && !("position_definition".equals(table) && "status".equals(statusColumn))) {
            throw new IllegalArgumentException("不允许的实体状态检查");
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where tenant_id = :tenantId and id = :id and "
                        + statusColumn + " = 'ACTIVE'"
                        + ("employee".equals(table) ? " and deleted_at is null" : ""),
                base(principal).addValue("id", id), Integer.class);
        if (count == null || count != 1) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requirePositionApplicableToOrganization(
            TenantPrincipal principal,
            UUID positionId,
            UUID orgUnitId
    ) {
        Boolean applicable = jdbc.queryForObject("""
                select exists (
                    select 1
                    from position_definition position
                    left join lateral (
                        select ancestor.id
                        from org_unit_closure closure
                        join org_unit ancestor
                          on ancestor.tenant_id = closure.tenant_id
                         and ancestor.id = closure.ancestor_id
                        where closure.tenant_id = position.tenant_id
                          and closure.descendant_id = :orgUnitId
                          and ancestor.unit_type = 'HOTEL'
                          and ancestor.status = 'ACTIVE'
                        order by closure.depth
                        limit 1
                    ) hotel_context on true
                    where position.tenant_id = :tenantId
                      and position.id = :positionId
                      and position.status = 'ACTIVE'
                      and position.deleted_at is null
                      and position.permanently_deleted_at is null
                      and (
                        position.applies_to_all_hotels = true
                        or (
                          hotel_context.id is not null
                          and exists (
                            select 1
                            from position_applicable_hotel applicable_hotel
                            where applicable_hotel.tenant_id = position.tenant_id
                              and applicable_hotel.position_id = position.id
                              and applicable_hotel.hotel_org_unit_id = hotel_context.id
                          )
                        )
                      )
                )
                """, base(principal)
                .addValue("positionId", positionId)
                .addValue("orgUnitId", orgUnitId), Boolean.class);
        if (!Boolean.TRUE.equals(applicable)) {
            throw new IllegalArgumentException("该岗位不适用于任职所在门店，请先调整岗位适用门店");
        }
    }

    /**
     * Serializes assignment creation with applicability updates.  The caller
     * must validate applicability only after acquiring this row lock, so an
     * assignment cannot be inserted against a scope snapshot that has just
     * been narrowed by another transaction.
     */
    private PositionRoleGrant lockActivePublishedPosition(TenantPrincipal principal, UUID positionId) {
        List<PositionRoleGrant> rows = jdbc.query("""
                select position.id, profile.default_role_id, published.authorization_scope_type
                from position_definition position
                join position_function_profile profile
                  on profile.tenant_id = position.tenant_id
                 and profile.position_id = position.id and profile.scope_type = 'GROUP'
                join position_function_profile_version published
                  on published.tenant_id = profile.tenant_id
                 and published.profile_id = profile.id
                 and published.lifecycle_status = 'PUBLISHED'
                join app_role role
                  on role.tenant_id = profile.tenant_id and role.id = profile.default_role_id
                where position.tenant_id = :tenantId and position.id = :positionId
                  and position.status = 'ACTIVE'
                  and position.deleted_at is null and position.permanently_deleted_at is null
                for update of position
                """, base(principal).addValue("positionId", positionId),
                (rs, rowNum) -> new PositionRoleGrant(
                        rs.getObject("default_role_id", UUID.class),
                        rs.getString("authorization_scope_type")));
        if (rows.size() != 1) {
            throw new IllegalArgumentException("岗位已停用或功能方案尚未发布，不能分配任职");
        }
        return rows.getFirst();
    }

    private UUID lockActiveEmployeeAccount(TenantPrincipal principal, UUID employeeId) {
        List<UUID> rows = jdbc.query("""
                select account_id
                from employee
                where tenant_id = :tenantId and id = :employeeId
                  and employment_status = 'ACTIVE' and deleted_at is null
                  and account_id is not null
                for update
                """, base(principal).addValue("employeeId", employeeId),
                (rs, rowNum) -> rs.getObject("account_id", UUID.class));
        if (rows.size() != 1) {
            throw new IllegalArgumentException("员工已停用或尚未开通中台账号，不能分配任职");
        }
        return rows.getFirst();
    }

    private record PositionRoleGrant(UUID roleId, String scopeType) { }

    private Set<UUID> validateResponsibleHotels(
            TenantPrincipal principal,
            UUID positionId,
            String authorizationScopeType,
            List<UUID> requestedHotelIds,
            UUID defaultOrgUnitId
    ) {
        Set<UUID> hotelIds = requestedHotelIds == null
                ? new java.util.LinkedHashSet<>()
                : new java.util.LinkedHashSet<>(requestedHotelIds);
        if (!"ASSIGNED_HOTELS".equals(authorizationScopeType)) {
            if (!hotelIds.isEmpty()) {
                throw new IllegalArgumentException("仅“指定负责门店”数据范围可以配置负责门店");
            }
            return hotelIds;
        }
        if (requestedHotelIds == null && defaultOrgUnitId != null) {
            List<UUID> containingHotels = jdbc.queryForList("""
                    select ancestor.id
                    from org_unit_closure closure
                    join org_unit ancestor
                      on ancestor.tenant_id = closure.tenant_id
                     and ancestor.id = closure.ancestor_id
                     and ancestor.unit_type = 'HOTEL'
                     and ancestor.status = 'ACTIVE'
                    where closure.tenant_id = :tenantId
                      and closure.descendant_id = :orgUnitId
                    order by closure.depth
                    limit 1
                    """, base(principal).addValue("orgUnitId", defaultOrgUnitId), UUID.class);
            hotelIds.addAll(containingHotels);
        }
        if (hotelIds.isEmpty()) {
            throw new IllegalArgumentException("该岗位必须至少选择一家负责门店");
        }
        for (UUID hotelId : hotelIds) {
            if (!"HOTEL".equals(requireOrgType(principal, hotelId))) {
                throw new IllegalArgumentException("负责范围只能选择启用中的门店");
            }
            accessPolicy.requireOrgScope(hotelId);
            requirePositionApplicableToOrganization(principal, positionId, hotelId);
        }
        return hotelIds;
    }

    private void replaceAssignmentHotelScope(
            TenantPrincipal principal,
            UUID assignmentId,
            Set<UUID> hotelIds
    ) {
        MapSqlParameterSource parameters = base(principal)
                .addValue("assignmentId", assignmentId)
                .addValue("actorId", principal.actorId());
        jdbc.update("""
                delete from employee_assignment_hotel_scope
                where tenant_id = :tenantId and assignment_id = :assignmentId
                """, parameters);
        for (UUID hotelId : hotelIds) {
            jdbc.update("""
                    insert into employee_assignment_hotel_scope
                        (tenant_id, assignment_id, hotel_org_unit_id, created_by)
                    values (:tenantId, :assignmentId, :hotelId, :actorId)
                    """, parameters.addValue("hotelId", hotelId));
        }
    }

    private void requireUniqueCode(
            String table,
            TenantPrincipal principal,
            UUID id,
            String code,
            String message
    ) {
        String column = switch (table) {
            case "org_unit", "position_definition" -> "code";
            case "employee" -> "employee_no";
            default -> throw new IllegalArgumentException("不允许的唯一编码检查");
        };
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where tenant_id = :tenantId and id <> :id and lower("
                        + column + ") = lower(:code)",
                base(principal).addValue("id", id).addValue("code", code.trim()), Integer.class);
        if (count != null && count > 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireUniqueLogin(TenantPrincipal principal, UUID accountId, String loginName) {
        MapSqlParameterSource parameters = base(principal)
                .addValue("loginName", loginName.trim().toLowerCase());
        String exclusion = "";
        if (accountId != null) {
            parameters.addValue("accountId", accountId);
            exclusion = " and id <> :accountId";
        }
        Integer count = jdbc.queryForObject("""
                select count(*) from user_account
                where tenant_id = :tenantId and lower(login_name) = :loginName
                """ + exclusion, parameters, Integer.class);
        if (count != null && count > 0) {
            throw new IllegalArgumentException("登录账号已存在");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private String lifecycleStatus(String value) {
        String normalized = normalize(value);
        if (!Set.of("ACTIVE", "INACTIVE").contains(normalized)) {
            throw new IllegalArgumentException("状态必须为ACTIVE或INACTIVE");
        }
        return normalized;
    }

    private void validateHierarchy(String unitType, UUID parentId, String parentType) {
        if ("GROUP".equals(unitType)) {
            if (parentId != null) {
                throw new IllegalArgumentException("集团不能设置上级组织");
            }
            return;
        }
        if (parentId == null) {
            throw new IllegalArgumentException("区域、门店和部门必须选择上级组织");
        }
        boolean valid = switch (unitType) {
            case "REGION" -> "GROUP".equals(parentType);
            case "HOTEL" -> Set.of("GROUP", "REGION").contains(parentType);
            case "DEPARTMENT" -> "HOTEL".equals(parentType);
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("组织层级不符合集团→区域→门店→部门模型");
        }
    }

    private void requireTenantScope(TenantPrincipal principal, String message) {
        if (!principal.hasTenantScope()) {
            throw new AccessDeniedException(message);
        }
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
