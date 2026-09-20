package cn.sifangguan.hotelaios.dailyreporttemplates;

import cn.sifangguan.hotelaios.shared.context.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Reads the exact version's children when a legacy seed omitted the JSON section cache. */
@Component
public class DailyReportTemplateSnapshotReader {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DailyReportTemplateSnapshotReader(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public ObjectNode complete(UUID versionId, JsonNode configuration) {
        ObjectNode result = configuration != null && configuration.isObject()
                ? ((ObjectNode) configuration).deepCopy() : mapper.createObjectNode();
        if (result.path("sections").isArray() && !result.path("sections").isEmpty()) return result;
        var params = new MapSqlParameterSource("tenantId", TenantContext.require().tenantId())
                .addValue("versionId", versionId);
        var sections = new LinkedHashMap<UUID, ObjectNode>();
        jdbc.query("""
                select relation.section_version_id, relation.section_role, relation.required,
                       relation.sort_order, definition.id, definition.code, definition.name,
                       definition.section_origin, version.configuration::text,
                       version.condition_expression::text
                from daily_report_template_section relation
                join daily_report_section_version version
                  on version.tenant_id = relation.tenant_id and version.id = relation.section_version_id
                join daily_report_section_definition definition
                  on definition.tenant_id = version.tenant_id and definition.id = version.section_definition_id
                where relation.tenant_id = :tenantId and relation.template_version_id = :versionId
                order by relation.sort_order, relation.id
                """, params, rs -> {
            ObjectNode section = (ObjectNode) parse(rs.getString("configuration"));
            section.put("id", rs.getString("id"));
            section.put("sectionVersionId", rs.getString("section_version_id"));
            if (!section.hasNonNull("sectionCode")) section.put("sectionCode", rs.getString("code"));
            if (!section.hasNonNull("title")) section.put("title", rs.getString("name"));
            section.put("sectionOrigin", rs.getString("section_origin"));
            section.put("sectionRole", rs.getString("section_role"));
            section.put("required", rs.getBoolean("required"));
            section.put("sortOrder", rs.getInt("sort_order"));
            section.set("applicabilityCondition", parse(rs.getString("condition_expression")));
            section.putArray("items");
            sections.put(rs.getObject("section_version_id", UUID.class), section);
        });
        jdbc.query("""
                select item.* from daily_report_template_item item
                join daily_report_template_section relation
                  on relation.tenant_id = item.tenant_id and relation.section_version_id = item.section_version_id
                where relation.tenant_id = :tenantId and relation.template_version_id = :versionId
                order by item.sort_order, item.item_code, item.id
                """, params, rs -> {
            ObjectNode item = mapper.createObjectNode();
            item.put("id", rs.getString("id"));
            item.put("itemCode", rs.getString("item_code"));
            item.put("label", rs.getString("label"));
            item.put("description", rs.getString("help_text"));
            item.put("valueType", rs.getString("input_type"));
            item.put("required", rs.getBoolean("required"));
            item.put("workPackageItemId", rs.getString("work_package_item_id"));
            item.put("standardVersionId", rs.getString("standard_version_id"));
            item.put("metricId", rs.getString("metric_id"));
            item.put("sortOrder", rs.getInt("sort_order"));
            JsonNode policy = parse(rs.getString("source_policy"));
            item.put("dataSourceType", policy.path("sourceType").asText("MANUAL"));
            item.set("dataSourceConfig", policy.path("configuration").isMissingNode() ? policy : policy.path("configuration"));
            item.set("evidencePolicy", parse(rs.getString("evidence_policy")));
            item.set("validationRules", parse(rs.getString("validation_rules")));
            item.set("optionValues", parse(rs.getString("option_values")));
            ((ArrayNode) sections.get(rs.getObject("section_version_id", UUID.class)).path("items")).add(item);
        });
        ArrayNode resolved = result.putArray("sections");
        sections.values().forEach(resolved::add);
        return result;
    }

    private JsonNode parse(String value) {
        try { return mapper.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException("日报模板字段不是有效JSON", exception); }
    }
}
