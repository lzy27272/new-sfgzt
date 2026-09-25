package cn.sifangguan.hotelaios.dataretention;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/image-retention")
public class ImageRetentionController {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext context;
    private final AccessPolicy access;
    private final AuditWriter audit;
    private final ObjectMapper json;
    private final ImageRetentionWorker worker;
    private final Path root;

    public ImageRetentionController(NamedParameterJdbcTemplate jdbc, TenantDatabaseContext context,
            AccessPolicy access, AuditWriter audit, ObjectMapper json, ImageRetentionWorker worker,
            @Value("${app.attachments.root:var/attachments}") String root) {
        this.jdbc=jdbc; this.context=context; this.access=access; this.audit=audit; this.json=json;
        this.worker=worker; this.root=Path.of(root).toAbsolutePath().normalize();
    }

    private TenantPrincipal prepare() {
        var principal=access.principal();
        if (!principal.hasTenantScope() || principal.roleCodes().stream()
                .noneMatch(Set.of("PLATFORM_ADMIN","GROUP_ADMIN")::contains)) {
            throw new AccessDeniedException("仅全租户范围的平台管理员或集团管理员可管理图片保留");
        }
        context.apply(principal.tenantId());
        return principal;
    }

    @GetMapping
    @Transactional(readOnly=true)
    public Map<String,Object> list(@RequestParam(defaultValue="0") int page,
                                   @RequestParam(defaultValue="") String search) {
        var principal=prepare();
        if (page<0 || page>100000 || search.length()>240) throw new IllegalArgumentException("分页或搜索条件无效");
        var params=new MapSqlParameterSource("tenant",principal.tenantId())
                .addValue("offset",page*20).addValue("search",search.trim());
        String filter="o.tenant_id=:tenant and (:search='' or strpos(o.original_name,:search)>0 or o.id::text=:search)";
        var items=jdbc.queryForList("""
                select o.id,o.source_type,o.source_id,o.original_name,o.size_bytes,o.uploaded_at,
                       case when o.source_type <> 'ai_evidence_sample'
                            then o.uploaded_at + p.image_retention_days * interval '24 hours' end as expires_at,
                       (o.source_type = 'ai_evidence_sample') as permanent_retention,
                       o.legal_hold,o.hold_reason,o.state,o.last_error,o.row_version,
                       (select org.name from attachment a join work_record w on w.tenant_id=a.tenant_id and w.id=a.work_record_id
                        join org_unit org on org.tenant_id=w.tenant_id and org.id=w.target_org_unit_id
                        where a.tenant_id=o.tenant_id and a.object_key=o.object_key limit 1) as store_name
                from image_retention_object o join data_retention_policy p using(tenant_id)
                where
                """ + filter + " order by o.uploaded_at desc,o.id limit 20 offset :offset", params);
        long total=jdbc.queryForObject("select count(*) from image_retention_object o where "+filter,params,Long.class);
        Boolean policy=jdbc.queryForObject("select image_cleanup_enabled from data_retention_policy where tenant_id=:tenant",params,Boolean.class);
        return Map.of("items",items,"total",total,"page",page,"workerEnabled",worker.enabledFor(principal.tenantId()),
                "active",worker.enabledFor(principal.tenantId()) && Boolean.TRUE.equals(policy));
    }

    public record Hold(@NotNull Boolean enabled, @NotBlank @Size(max=500) String reason,
                       @NotNull @PositiveOrZero Long expectedVersion) {}

    @PutMapping("/{id}/hold")
    @Transactional
    public Map<String,Object> hold(@PathVariable UUID id,@Valid @RequestBody Hold request) {
        var principal=prepare();
        var params=new MapSqlParameterSource("tenant",principal.tenantId()).addValue("id",id);
        var rows=jdbc.queryForList("select * from image_retention_object where tenant_id=:tenant and id=:id for update",params);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"图片不存在");
        var row=rows.getFirst();
        if ("DELETED".equals(row.get("state")) || ((Number)row.get("row_version")).longValue()!=request.expectedVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"图片已清理或状态已变化，请刷新");
        }
        if ("ai_evidence_sample".equals(row.get("source_type"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"规范样例照片长期保留，不参与自动清理，无需设置或解除保留");
        }
        Path file=root.resolve((String)row.get("object_key")).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"图片文件缺失，不能确认保留状态");
        }
        jdbc.update("""
                update image_retention_object set legal_hold=:held,hold_reason=:reason,
                  hold_updated_by=:actor,hold_updated_at=now(),row_version=row_version+1
                where tenant_id=:tenant and id=:id
                """,params.addValue("held",request.enabled()).addValue("reason",request.reason().trim())
                .addValue("actor",principal.actorId()));
        audit.record(request.enabled()?"IMAGE_IMPORTANT_EVIDENCE_HELD":"IMAGE_IMPORTANT_EVIDENCE_RELEASED",
                "IMAGE_RETENTION_OBJECT",id,json.valueToTree(Map.of("held",request.enabled(),"reason",request.reason().trim())).toString());
        return Map.of("id",id,"legalHold",request.enabled(),"version",request.expectedVersion()+1);
    }
}
