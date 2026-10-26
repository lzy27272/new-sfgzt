package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;

@RestController @RequestMapping("/api/v1/finance/projects") @Transactional(readOnly=true)
public class FinanceProjectController {
 private final NamedParameterJdbcTemplate jdbc;private final AccessPolicy access;private final TenantDatabaseContext db;
 public FinanceProjectController(NamedParameterJdbcTemplate jdbc,AccessPolicy access,TenantDatabaseContext db){this.jdbc=jdbc;this.access=access;this.db=db;}
 @GetMapping("/choices") public Map<String,Object> choices(@RequestParam UUID hotelId){access.requireAnyPermission("finance.submit","finance.read","finance.review","finance.configure","finance.pay","finance.accounting.read","procurement.request","procurement.read","procurement.configure");var p=access.principal();db.apply(p.tenantId());access.requireOrgScope(hotelId);var config=jdbc.queryForList("select company_id from finance_store_config where tenant_id=:tenant and hotel_id=:hotel and enabled",base(p).addValue("hotel",hotelId));if(config.size()!=1)throw conflict("所属组织实际法人尚未配置，项目来源待配置，不能默认零值");return new FinanceProjectDimensions(jdbc,access).choices(p,hotelId,id(config.getFirst().get("company_id")));}
}
