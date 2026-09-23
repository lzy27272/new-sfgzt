package cn.sifangguan.hotelaios.academy;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

@Component
public class AcademySupport {
    final NamedParameterJdbcTemplate db;
    final ObjectMapper json;
    final AccessPolicy access;
    final TenantDatabaseContext tenants;
    public AcademySupport(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access,TenantDatabaseContext tenants) {
        this.db=db;this.json=json;this.access=access;this.tenants=tenants;
    }
    TenantPrincipal prepare(String... permissions) {
        access.requireAnyPermission(permissions);
        var p=access.principal();tenants.apply(p.tenantId());return p;
    }
    static MapSqlParameterSource params(UUID tenant) {return new MapSqlParameterSource("tid",tenant);}
    MapSqlParameterSource params() {return params(access.principal().tenantId());}
    Map<String,Object> one(String sql,MapSqlParameterSource p) {
        var rows=db.queryForList(sql,p);if(rows.isEmpty()) throw missing();return rows.getFirst();
    }
    String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("无法保存学院数据",e);}}
    Object wire(Object value) {
        if(value instanceof Map<?,?> map){var result=new LinkedHashMap<String,Object>();map.forEach((k,v)->result.put(k.toString(),wire(v)));return result;}
        if(value instanceof List<?> list)return list.stream().map(this::wire).toList();
        if(value!=null && value.getClass().getSimpleName().equals("PGobject"))return decode(value,new TypeReference<Object>(){});
        return value;
    }
    <T> T decode(Object value,TypeReference<T> type) {
        try {return json.readValue(String.valueOf(value),type);}catch(Exception e){throw new IllegalStateException("学院数据格式异常",e);}
    }
    List<Map<String,Object>> objects(Object value) {return decode(value,new TypeReference<>(){});}
    List<Integer> integers(Object value) {return decode(value,new TypeReference<>(){});}
    static UUID id(Map<String,Object> m,String key) {return (UUID)m.get(key);}
    static int integer(Map<String,Object> m,String key) {return ((Number)m.get(key)).intValue();}
    static double number(Map<String,Object> m,String key) {return ((Number)m.get(key)).doubleValue();}
    static String str(Map<String,Object> m,String key) {return m.get(key)==null?"":m.get(key).toString();}
    static Instant instant(Object value) {
        if(value instanceof java.sql.Timestamp t)return t.toInstant();
        if(value instanceof OffsetDateTime t)return t.toInstant();
        return Instant.parse(String.valueOf(value));
    }
    Instant now() {return db.getJdbcTemplate().queryForObject("select clock_timestamp()",OffsetDateTime.class).toInstant();}
    static ResponseStatusException bad(String message) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    static ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    static ResponseStatusException missing() {return new ResponseStatusException(HttpStatus.NOT_FOUND,"学院记录不存在或无权访问");}
    static String text(String v) {return v==null?"":v.trim();}
    void scope(UUID orgId) {
        if(orgId==null) {if(!access.principal().hasTenantScope())throw missing();}
        else access.requireOrgScope(orgId);
    }
    boolean canScope(UUID orgId) {return access.principal().hasTenantScope() || access.principal().orgScopes().contains(orgId);}
    void audit(UUID tenant,UUID actor,String action,UUID resource,Object payload) {
        var correlation=UUID.randomUUID();
        db.update("""
            insert into audit_log(tenant_id,actor_id,action,resource_type,resource_id,correlation_id,trace_id,outcome,sensitivity_level,after_data)
            values(:tid,:actor,:action,'ACADEMY',:id,:trace,:trace,'SUCCESS','INTERNAL',cast(:data as jsonb))
            """,params(tenant).addValue("actor",actor).addValue("action",action).addValue("id",resource)
                .addValue("trace",correlation).addValue("data",encode(payload)));
    }
    void audit(String action,UUID id,Object payload) {
        var p=access.principal();audit(p.tenantId(),p.actorId(),action,id,payload);
    }
    void notify(UUID tenant,UUID account,UUID assignment,UUID plan,String type,String title,String content,String key) {
        db.update("""
            insert into notification(id,tenant_id,recipient_account_id,recipient_assignment_id,notification_type,title,content,source_type,source_id,idempotency_key)
            values(:id,:tid,:account,:assignment,:type,:title,:content,'ACADEMY_PLAN',:plan,:key)
            on conflict(tenant_id,recipient_account_id,idempotency_key) do nothing
            """,params(tenant).addValue("id",UUID.randomUUID()).addValue("account",account).addValue("assignment",assignment)
            .addValue("type",type).addValue("title",title).addValue("content",content).addValue("plan",plan).addValue("key",key));
    }
    void lockEmployee(UUID tenant,UUID employee) {
        one("select id from employee where tenant_id=:tid and id=:id for update",params(tenant).addValue("id",employee));
    }
    List<Map<String,Object>> publicQuestions(Object paper,boolean reveal) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(var q:objects(paper)){var copy=new LinkedHashMap<>(q);if(!reveal){copy.remove("answers");copy.remove("explanation");}result.add(copy);}
        return result;
    }
}
