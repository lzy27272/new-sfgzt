package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

final class FinanceV2Support {
 private FinanceV2Support(){}
 static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
 static MapSqlParameterSource base(TenantPrincipal p){return new MapSqlParameterSource("tenant",p.tenantId()).addValue("actor",p.actorId()).addValue("tenantScope",p.hasTenantScope()).addValue("scopes",p.orgScopes().isEmpty()?List.of(new UUID(0,0)):p.orgScopes());}
 static String json(Object o){try{return JSON.writeValueAsString(o);}catch(Exception e){throw new IllegalArgumentException("无法序列化财务数据",e);}}
 @SuppressWarnings("unchecked") static Map<String,Object> map(Object o){if(o instanceof Map<?,?> m)return (Map<String,Object>)m;try{return o==null?new LinkedHashMap<>():JSON.readValue(o.toString(),Map.class);}catch(Exception e){throw new IllegalArgumentException("财务快照格式无效",e);}}
 @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object o){if(o instanceof List<?> l)return (List<Map<String,Object>>)l;try{return JSON.readValue(o.toString(),List.class);}catch(Exception e){throw new IllegalArgumentException("财务行格式无效",e);}}
 static UUID id(Object o){return o==null?null:UUID.fromString(o.toString());}
 static BigDecimal amount(Object o){return o==null?BigDecimal.ZERO:new BigDecimal(o.toString());}
 static LocalDate month(String s){try{return YearMonth.parse(s).atDay(1);}catch(Exception e){throw bad("月份必须为YYYY-MM");}}
 static void version(Map<String,Object> r,int expected){if(((Number)r.get("version")).intValue()!=expected)throw conflict("版本已变化，请刷新后重试");}
 static Object value(Object o){if(o==null)return null;if(o instanceof BigDecimal d)return d.toPlainString();if(o instanceof java.sql.Date d)return d.toLocalDate().toString();if(o instanceof java.sql.Timestamp d)return d.toInstant().toString();if(o.getClass().getName().equals("org.postgresql.util.PGobject")){try{return value(JSON.readValue(o.toString(),Object.class));}catch(Exception e){throw new IllegalStateException(e);}}if(o instanceof Map<?,?> m){var r=new LinkedHashMap<String,Object>();m.forEach((k,v)->r.put(camel(k.toString()),value(v)));return r;}if(o instanceof Collection<?> c)return c.stream().map(FinanceV2Support::value).toList();return o;}
 @SuppressWarnings("unchecked") static Map<String,Object> output(Map<String,?> m){return (Map<String,Object>)value(m);}
 static String camel(String s){var b=new StringBuilder();boolean u=false;for(char c:s.toCharArray()){if(c=='_')u=true;else{b.append(u?Character.toUpperCase(c):c);u=false;}}return b.toString();}
 static ResponseStatusException bad(String s){return new ResponseStatusException(HttpStatus.BAD_REQUEST,s);}
 static ResponseStatusException conflict(String s){return new ResponseStatusException(HttpStatus.CONFLICT,s);}
 static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"财务记录不存在于当前授权范围");}
}
