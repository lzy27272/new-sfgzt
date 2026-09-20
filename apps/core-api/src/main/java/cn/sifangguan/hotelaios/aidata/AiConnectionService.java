package cn.sifangguan.hotelaios.aidata;

import cn.sifangguan.hotelaios.shared.context.*;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.EffectiveIdentityService;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
public class AiConnectionService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext db;
    private final EffectiveIdentityService identities;
    private final AuditWriter audit;
    public AiConnectionService(NamedParameterJdbcTemplate jdbc, TenantDatabaseContext db,
                               EffectiveIdentityService identities, AuditWriter audit) {
        this.jdbc=jdbc; this.db=db; this.identities=identities; this.audit=audit;
    }
    public record Grant(TenantPrincipal principal, boolean reportWrite) {}
    @Transactional
    public Map<String,Object> create(AiDataModels.Connection input) {
        var p=TenantContext.require(); db.apply(p.tenantId());
        if (input.days()<1 || input.days()>30 || input.name()==null || input.name().isBlank() || input.name().length()>80)
            throw new IllegalArgumentException("连接名称或期限无效");
        var params=base(p);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key,68))",Map.of("key",p.actorId().toString()),Object.class);
        Integer count=jdbc.queryForObject("select count(*) from ai_data_connection where tenant_id=:tenantId and account_id=:actorId and revoked_at is null and expires_at>now()",params,Integer.class);
        if(count!=null && count>=10) throw new ResponseStatusException(HttpStatus.CONFLICT,"最多保留10个有效连接，请先撤销旧连接");
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        String token="sfgai_"+p.tenantId()+"_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id=UUID.randomUUID(); var expiry=OffsetDateTime.now(ZoneOffset.UTC).plusDays(input.days());
        jdbc.update("""
            insert into ai_data_connection(id,tenant_id,account_id,assignment_id,name,token_hash,allow_report_write,expires_at)
            values(:id,:tenantId,:actorId,:assignment,:name,:hash,:write,:expires)
            """,params.addValue("id",id).addValue("assignment",p.businessActorAssignmentId()).addValue("name",input.name().trim())
            .addValue("hash",AiDataSupport.hash(token)).addValue("write",input.allowReportWrite()).addValue("expires",expiry));
        audit.record("AI_CONNECTION_CREATED","AI_DATA_CONNECTION",id,"{\"secretStored\":false}");
        return Map.of("id",id,"token",token,"expiresAt",expiry,"allowReportWrite",input.allowReportWrite());
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list() {
        var p=TenantContext.require(); db.apply(p.tenantId());
        return jdbc.queryForList("select id,name,allow_report_write,created_at,expires_at,revoked_at from ai_data_connection where tenant_id=:tenantId and account_id=:actorId order by created_at desc limit 50",base(p));
    }
    @Transactional
    public void revoke(UUID id) {
        var p=TenantContext.require(); db.apply(p.tenantId());
        int n=jdbc.update("update ai_data_connection set revoked_at=coalesce(revoked_at,now()) where tenant_id=:tenantId and account_id=:actorId and id=:id",base(p).addValue("id",id));
        if(n!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"连接不存在");
        audit.record("AI_CONNECTION_REVOKED","AI_DATA_CONNECTION",id,"{}");
    }
    @Transactional(readOnly=true)
    public Grant authenticate(String authorization) {
        if(authorization==null || !authorization.matches("Bearer sfgai_[0-9a-fA-F-]{36}_[A-Za-z0-9_-]{43}")) throw unauthorized();
        String token=authorization.substring(7); UUID tenant;
        try { tenant=UUID.fromString(token.substring(6,42)); } catch(IllegalArgumentException e) { throw unauthorized(); }
        db.apply(tenant);
        var rows=jdbc.queryForList("""
            select account_id,assignment_id,allow_report_write from ai_data_connection
            where tenant_id=:tenant and token_hash=:hash and revoked_at is null and expires_at>now()
            """,Map.of("tenant",tenant,"hash",AiDataSupport.hash(token)));
        if(rows.size()!=1) throw unauthorized();
        var r=rows.getFirst();
        // IAM is resolved on every request: a disabled account/assignment is not a valid grant.
        var principal=identities.resolve(tenant,(UUID)r.get("account_id"),UUID.randomUUID(),(UUID)r.get("assignment_id"));
        return new Grant(principal,Boolean.TRUE.equals(r.get("allow_report_write")));
    }
    private ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"需要有效的中台AI接入密钥"); }
    private MapSqlParameterSource base(TenantPrincipal p) { return new MapSqlParameterSource("tenantId",p.tenantId()).addValue("actorId",p.actorId()); }
}
