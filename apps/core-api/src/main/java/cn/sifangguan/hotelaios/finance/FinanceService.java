package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
@Transactional
public class FinanceService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext database;
    private final AccessPolicy access;
    private final ObjectMapper mapper;
    private final AuditWriter audit;
    private final FinanceAccountingService accounting;
    private static final Set<String> EDITABLE=Set.of("DRAFT","RETURNED");
    private static final Set<String> REVIEWING=Set.of("MANAGER_REVIEW","FINANCE_REVIEW","DIRECTOR_REVIEW","LEADER_REVIEW");
    private static final UUID EMPTY_UUID=new UUID(0,0);
    private static final String SCOPE="(:tenantScope or c.hotel_id in (:scopeIds))";
    private static final String CASHIER="(c.cashier_account_id=:actorId or c.workflow_version=1 and exists(select 1 from finance_store_config cashier_config where cashier_config.tenant_id=c.tenant_id and cashier_config.hotel_id=c.hotel_id and cashier_config.enabled and cashier_config.cashier_account_id=:actorId))";
    private static final String VISIBLE="((:own and c.applicant_id=:actorId) or ("+SCOPE+" and (:read or (:pay and c.status='APPROVED' and "+CASHIER+") or (:review and :actorId in (c.manager_account_id,c.finance_account_id,c.director_account_id,c.leader_account_id)))))";

    public FinanceService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext database,AccessPolicy access,ObjectMapper mapper,AuditWriter audit,FinanceAccountingService accounting) {
        this.jdbc=jdbc;this.database=database;this.access=access;this.mapper=mapper;this.audit=audit;this.accounting=accounting;
    }
    public Map<String,Object> context() {
        var p=prepare();var b=base(p);
        var assignments=jdbc.queryForList("""
            select a.id as assignment_id,e.name as employee_name,h.id as hotel_id,h.name as hotel_name,
              (f.enabled and f.workflow_version=2) as enabled,f.version as config_version,co.name as company_name,co.tax_number as company_tax_number
            from employee_position_assignment a join employee e on e.tenant_id=a.tenant_id and e.id=a.employee_id
            join lateral (select o.id,o.name from org_unit_closure cl join org_unit o on o.tenant_id=cl.tenant_id and o.id=cl.ancestor_id
              where cl.tenant_id=a.tenant_id and cl.descendant_id=a.org_unit_id and o.unit_type in ('HOTEL','GROUP') and o.status='ACTIVE'
              order by cl.depth limit 1) h on true
            left join finance_store_config f on f.tenant_id=a.tenant_id and f.hotel_id=h.id
            left join finance_company co on co.tenant_id=f.tenant_id and co.id=f.company_id
            where a.tenant_id=:tenantId and e.account_id=:actorId and e.employment_status='ACTIVE' and a.status='ACTIVE'
             and a.valid_from<=current_date and (a.valid_to is null or a.valid_to>=current_date)
            order by a.is_primary desc,a.id
            """,b);
        assignments.removeIf(row->!p.assignmentIds().contains(uuid(row,"assignment_id")));
        var hotels=jdbc.queryForList("select id,name,unit_type from org_unit where tenant_id=:tenantId and status='ACTIVE' and unit_type in ('HOTEL','GROUP') and (:tenantScope or id in (:scopeIds)) order by unit_type,name",b);
        Set<UUID> categoryHotels=new HashSet<>();
        assignments.forEach(row->categoryHotels.add(uuid(row,"hotel_id")));
        if(has(p,"finance.read")||has(p,"finance.review")||has(p,"finance.configure")) hotels.forEach(row->categoryHotels.add(uuid(row,"id")));
        var categories=jdbc.queryForList("select id,hotel_id,name,treatment,keywords,debit_account,enabled,version"+(new FinanceProjectDimensions(jdbc,access).installed()?",budget_code":"")+" from finance_category where tenant_id=:tenantId and hotel_id in (:hotels) order by name,id",b.addValue("hotels",nonempty(categoryHotels)));
        return Map.of("assignments",assignments,"hotels",hotels,"categories",categories,"today",today(p),
            "recognitionMode","PDF_TEXT_MANUAL_CONFIRMATION","approvalStages",List.of("初审","门店财务","财务总监","CEO终审"));
    }
    public Map<String,Object> configuration() {
        var p=prepare("finance.configure");var b=base(p);
        var stores=jdbc.queryForList("""
            select c.*,h.id as hotel_id,h.name as hotel_name,h.code as hotel_code,h.unit_type,co.name as company_name,co.tax_number
            from org_unit h left join finance_store_config c on c.tenant_id=h.tenant_id and c.hotel_id=h.id
            left join finance_company co on co.tenant_id=c.tenant_id and co.id=c.company_id
            where h.tenant_id=:tenantId and h.status='ACTIVE' and h.unit_type in ('HOTEL','GROUP')
              and (:tenantScope or h.id in (:scopeIds)) order by h.name
            """,b);
        stores.forEach(s->{if(s.get("draft_configuration")!=null) s.put("draft",parse(s.get("draft_configuration")));s.remove("draft_configuration");});
        return Map.of("stores",stores,"accounts",jdbc.queryForList("""
            select distinct u.id,u.display_name,u.login_name from user_account u
            where u.tenant_id=:tenantId and u.status='ACTIVE' and (:tenantScope or exists (
             select 1 from employee e join employee_position_assignment a on a.tenant_id=e.tenant_id and a.employee_id=e.id
              where e.tenant_id=u.tenant_id and e.account_id=u.id and a.status='ACTIVE' and a.org_unit_id in (:scopeIds)))
            order by u.display_name,u.id
            """,b));
    }
    public Map<String,Object> configure(UUID hotel,FinanceModels.StoreConfig in) {
        return configureFor(prepare("finance.configure"),hotel,in,in.expectedVersion());
    }
    public Map<String,Object> configureBatch(FinanceModels.ConfigureBatch in) {
        var p=prepare("finance.configure");
        if(in.targets().stream().map(FinanceModels.ConfigTarget::hotelId).distinct().count()!=in.targets().size()) throw bad("适用组织不可重复");
        // A batch is atomic and locks organizations in one stable order to avoid cross-batch deadlocks.
        for(var target:in.targets().stream().sorted(Comparator.comparing(t->t.hotelId().toString())).toList())
            configureFor(p,target.hotelId(),in.configuration(),target.expectedVersion());
        return configuration();
    }
    private Map<String,Object> configureFor(TenantPrincipal p,UUID hotel,FinanceModels.StoreConfig in,int expectedVersion) {
        access.requireOrgScope(hotel);requireHotel(p,hotel);
        var reviewers=Arrays.asList(in.managerAccountId(),in.financeAccountId(),in.directorAccountId(),in.leaderAccountId());
        var selected=reviewers.stream().filter(Objects::nonNull).toList();
        if(new HashSet<>(selected).size()!=selected.size()) throw bad("四段审核必须配置不同账号");
        if(in.enabled()&&(selected.size()!=4||in.cashierAccountId()==null)) throw bad("审核与出纳账号未配置完整，暂不可启用；可先保存草稿");
        Set<UUID> accounts=new HashSet<>(selected);if(in.cashierAccountId()!=null) accounts.add(in.cashierAccountId());
        var b=base(p).addValue("hotel",hotel).addValue("accounts",accounts);
        if(!accounts.isEmpty()&&jdbc.queryForObject("select count(*) from user_account where tenant_id=:tenantId and id in (:accounts) and status='ACTIVE'",b,Integer.class)!=accounts.size()) throw bad("审核或出纳账号不存在或已停用");
        lock(p,"config:"+hotel);
        var existing=jdbc.queryForList("select version,enabled from finance_store_config where tenant_id=:tenantId and hotel_id=:hotel for update",b);
        if((existing.isEmpty()?-1:number(existing.getFirst(),"version"))!=expectedVersion) throw conflict("门店财务配置已变化，请刷新再保存；本次多组织配置全部未生效");
        if(!in.enabled()&&!existing.isEmpty()&&Boolean.TRUE.equals(existing.getFirst().get("enabled"))) {
            jdbc.update("update finance_store_config set draft_configuration=cast(:draft as jsonb),version=version+1,updated_at=now() where tenant_id=:tenantId and hotel_id=:hotel",b.addValue("draft",json(in)));
            audit.record("FINANCE_CONFIGURATION_DRAFT_SAVED","ORG_UNIT",hotel,json(Map.of("configuration",in)));
            return storeConfig(p,hotel);
        }
        b.addValue("name",in.companyName().trim()).addValue("tax",FinanceRules.taxNumber(in.taxNumber()));
        jdbc.update("insert into finance_company(tenant_id,name,tax_number) values(:tenantId,:name,:tax) on conflict(tenant_id,tax_number) do nothing",b);
        var company=jdbc.queryForMap("select id,name from finance_company where tenant_id=:tenantId and tax_number=:tax",b);
        if(!company.get("name").equals(in.companyName().trim())) throw bad("同一税号已登记为另一公司名称，请核对；不能静默改写既有法人档案");
        b.addValue("company",company.get("id")).addValue("manager",in.managerAccountId()).addValue("finance",in.financeAccountId())
            .addValue("director",in.directorAccountId()).addValue("cashier",in.cashierAccountId())
            .addValue("leaderName",Objects.toString(in.leaderName(),"").trim()).addValue("skipSelf",in.skipSelfReview())
            .addValue("leader",in.leaderAccountId()).addValue("payable",in.payableAccount().trim()).addValue("enabled",in.enabled());
        jdbc.update("""
            insert into finance_store_config(tenant_id,hotel_id,company_id,manager_account_id,finance_account_id,director_account_id,leader_account_id,cashier_account_id,leader_name,skip_self_review,workflow_version,payable_account,enabled)
            values(:tenantId,:hotel,:company,:manager,:finance,:director,:leader,:cashier,:leaderName,:skipSelf,2,:payable,:enabled)
            on conflict(tenant_id,hotel_id) do update set company_id=excluded.company_id,manager_account_id=excluded.manager_account_id,
             finance_account_id=excluded.finance_account_id,leader_account_id=excluded.leader_account_id,payable_account=excluded.payable_account,
             director_account_id=excluded.director_account_id,cashier_account_id=excluded.cashier_account_id,leader_name=excluded.leader_name,
             skip_self_review=excluded.skip_self_review,workflow_version=2,draft_configuration=null,
             enabled=excluded.enabled,version=finance_store_config.version+1,updated_at=now()
            """,b);
        audit.record("FINANCE_STORE_CONFIGURED","ORG_UNIT",hotel,json(Map.of("expectedVersion",expectedVersion,"configuration",in)));
        return storeConfig(p,hotel);
    }
    public Map<String,Object> category(UUID hotel,UUID id,FinanceModels.Category in) {
        var p=prepare("finance.configure");access.requireOrgScope(hotel);requireHotel(p,hotel);lock(p,"category:"+hotel);
        var b=base(p).addValue("hotel",hotel).addValue("id",id);
        var old=jdbc.queryForList("select * from finance_category where tenant_id=:tenantId and hotel_id=:hotel and id=:id for update",b);
        if((old.isEmpty()?-1:number(old.getFirst(),"version"))!=in.expectedVersion()) throw conflict("费用分类已变化，请刷新");
        b.addValue("name",in.name().trim()).addValue("treatment",in.treatment()).addValue("keywords",in.keywords().trim()).addValue("debit",in.debitAccount().trim()).addValue("enabled",in.enabled());
        if(old.isEmpty()) jdbc.update("insert into finance_category(id,tenant_id,hotel_id,name,treatment,keywords,debit_account,enabled) values(:id,:tenantId,:hotel,:name,:treatment,:keywords,:debit,:enabled)",b);
        else jdbc.update("update finance_category set name=:name,treatment=:treatment,keywords=:keywords,debit_account=:debit,enabled=:enabled,version=version+1 where tenant_id=:tenantId and hotel_id=:hotel and id=:id",b);
        if(new FinanceProjectDimensions(jdbc,access).installed()&&in.budgetCode()!=null){String code=in.budgetCode().trim();if(!code.isEmpty()&&!code.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}"))throw bad("稳定财务编码须为1至80位字母、数字、点、横线、冒号或下划线");String previous=old.isEmpty()?null:(String)old.getFirst().get("budget_code");if(previous!=null&&!Objects.equals(previous,code)&&jdbc.queryForObject("select exists(select 1 from fc_policy where tenant_id=:tenantId and hotel_id=:hotel and kind='BUDGET' and status='PUBLISHED' and (spec->>'categoryId'=cast(:id as text) or spec->>'categoryCode'=:previous)) or exists(select 1 from finance_claim_line where tenant_id=:tenantId and category_id=:id)",b.addValue("previous",previous),Boolean.class))throw conflict("该财务编码已有预算或原费用引用，不能直接更换使既有来源失联；请新增类别或先办理受控修订");if(!code.isEmpty()&&jdbc.queryForObject("select count(*) from finance_category where tenant_id=:tenantId and hotel_id=:hotel and budget_code=:budgetCode and id<>:id",b.addValue("budgetCode",code),Integer.class)>0)throw conflict("该组织稳定财务编码已绑定其他真实类别，不能重用");jdbc.update("update finance_category set budget_code=:budgetCode where tenant_id=:tenantId and id=:id",b.addValue("budgetCode",code.isEmpty()?null:code));audit.record("FINANCE_CATEGORY_BUDGET_CODE_CONFIGURED","FINANCE_CATEGORY",id,json(Map.of("previous",Objects.toString(previous,""),"current",code)));}
        audit.record("FINANCE_CATEGORY_CONFIGURED","FINANCE_CATEGORY",id,"{}");
        return jdbc.queryForMap("select * from finance_category where tenant_id=:tenantId and hotel_id=:hotel and id=:id",b);
    }
    public Map<String,Object> historical(FinanceModels.Historical in) {
        var p=prepare("finance.configure");
        // Historical registry spans the group, not just an administrator's one hotel.
        if(!p.hasTenantScope()) throw forbidden();
        lock(p,"invoice-registry");int count=0;
        for(var item:in.invoices()) {
            String key=FinanceRules.invoiceKey(item.invoiceCode(),item.invoiceNumber());
            var b=base(p).addValue("key",key).addValue("reference",item.reference().trim());
              if(new FinanceControlGuard(jdbc).table("fs_invoice_use")&&jdbc.queryForObject("select count(*) from fs_invoice_use where coalesce(to_jsonb(fs_invoice_use)->>'released_at','')='' and tenant_id=:tenantId and invoice_key=:key",b,Integer.class)>0)throw conflict("发票已经用于采购核算，不得再作为另一笔历史报销导入");
            var prior=jdbc.queryForList("select claim_id,historical_reference from finance_invoice_reservation where tenant_id=:tenantId and invoice_key=:key",b);
            if(!prior.isEmpty()) {
                if(!item.reference().trim().equals(prior.getFirst().get("historical_reference"))) throw conflict("历史发票与已有记录冲突，整批未导入，请核对");
                continue;
            }
            count+=jdbc.update("insert into finance_invoice_reservation(tenant_id,invoice_key,historical_reference) values(:tenantId,:key,:reference)",b);
        }
        audit.record("FINANCE_HISTORY_IMPORTED","FINANCE_REGISTRY",p.tenantId(),json(Map.of("count",count)));
        return Map.of("imported",count,"total",in.invoices().size());
    }
    public Map<String,Object> list(String scope,int page) {
        var p=prepare();if(!Set.of("mine","review","all").contains(scope)||page<0||page>10000) throw bad("查询条件无效");
        String filter=switch(scope) {
            case "mine" -> {access.requirePermission("finance.submit");yield "c.applicant_id=:actorId";}
            case "review" -> {access.requirePermission("finance.review");yield SCOPE+" and c.applicant_id<>:actorId and :actorId="+currentClaimReviewerSql();}
            default -> {access.requireAnyPermission("finance.read","finance.pay");yield VISIBLE;}
        };
        var rows=jdbc.queryForList("select c.* from finance_claim c where c.tenant_id=:tenantId and "+filter+" order by c.updated_at desc,c.id limit 51 offset :offset",base(p).addValue("offset",page*50));
        rows.forEach(FinanceService::moneyStrings);
        long total=jdbc.queryForObject("select count(*) from finance_claim c where c.tenant_id=:tenantId and "+filter,base(p),Long.class);
        return Map.of("items",rows.size()>50?rows.subList(0,50):rows,"hasMore",rows.size()>50,"page",page,"total",total);
    }
    public Map<String,Object> create(FinanceModels.Create in) {
        var p=prepare("finance.submit");access.requireActiveAssignment(in.assignmentId());
        var b=base(p).addValue("id",in.requestId());
        var prior=jdbc.queryForList("select applicant_id,assignment_id from finance_claim where tenant_id=:tenantId and id=:id",b);
        if(!prior.isEmpty()) {
            if(!p.actorId().equals(uuid(prior.getFirst(),"applicant_id"))||!in.assignmentId().equals(uuid(prior.getFirst(),"assignment_id"))) throw conflict("请求编号已使用");
            return detail(in.requestId());
        }
        var assignment=assignment(p,in.assignmentId());var config=optionalConfig(p,uuid(assignment,"hotel_id"));
        b.addValue("assignment",in.assignmentId()).addValue("hotel",assignment.get("hotel_id")).addValue("hotelName",assignment.get("hotel_name"))
            .addValue("company",config.get("company_id")).addValue("companyName",config.getOrDefault("company_name","")).addValue("tax",config.getOrDefault("tax_number",""))
            .addValue("name",assignment.get("employee_name")).addValue("date",today(p)).addValue("manager",config.get("manager_account_id"))
            .addValue("finance",config.get("finance_account_id")).addValue("leader",config.get("leader_account_id")).addValue("configVersion",config.getOrDefault("version",-1))
            .addValue("director",config.get("director_account_id")).addValue("cashier",config.get("cashier_account_id"));
        jdbc.update("""
            insert into finance_claim(id,tenant_id,applicant_id,assignment_id,hotel_id,company_id,applicant_name,hotel_name,company_name,company_tax_number,
                business_date,manager_account_id,finance_account_id,director_account_id,leader_account_id,cashier_account_id,workflow_version,config_version)
            values(:id,:tenantId,:actorId,:assignment,:hotel,:company,:name,:hotelName,:companyName,:tax,:date,:manager,:finance,:director,:leader,:cashier,2,:configVersion)
            on conflict(id) do nothing
            """,b);
        var created=claim(p,in.requestId(),false);
        if(!p.actorId().equals(uuid(created,"applicant_id"))||!in.assignmentId().equals(uuid(created,"assignment_id"))) throw conflict("请求编号已使用");
        audit.record("FINANCE_CLAIM_CREATED","FINANCE_CLAIM",in.requestId(),"{}");return detail(in.requestId());
    }
    public Map<String,Object> detail(UUID id) {
        var p=prepare();var row=claim(p,id,false);requireReadable(p,row);var b=base(p).addValue("id",id);
        var result=new LinkedHashMap<>(row);
        result.put("items",lineItems(p,id));
        if(EDITABLE.contains(row.get("status"))&&row.get("draft_line_candidates")!=null) {
            try {result.put("items",mapper.readValue(row.get("draft_line_candidates").toString(),List.class));}
            catch(Exception e){throw new IllegalStateException("费用候选草稿读取失败",e);}
        }
        result.remove("draft_line_candidates");
        result.put("configurationIssues",configurationIssues(optionalConfig(p,uuid(row,"hotel_id"))));
        result.put("invoices",jdbc.queryForList("select * from finance_invoice where tenant_id=:tenantId and claim_id=:id order by id",b));
        if(EDITABLE.contains(row.get("status"))&&row.get("draft_invoice_candidates")!=null) {
            try {result.put("invoices",mapper.readValue(row.get("draft_invoice_candidates").toString(),List.class));}
            catch(Exception e){throw new IllegalStateException("发票候选草稿读取失败",e);}
        }
        result.remove("draft_invoice_candidates");
        result.put("attachments",attachments(p,id));
        var reviews=jdbc.queryForList("select r.stage,r.decision,r.note,r.created_at,r.review_snapshot,u.display_name as reviewer_name from finance_review r join user_account u on u.tenant_id=r.tenant_id and u.id=r.actor_id where r.tenant_id=:tenantId and r.claim_id=:id order by r.created_at,r.id",b);
        reviews.forEach(r->r.put("review_snapshot",parse(r.get("review_snapshot"))));result.put("reviews",reviews);
        result.put("payments",jdbc.queryForList("select p.id,p.amount,p.reference,p.paid_on,p.created_at,exists(select 1 from finance_payment_receipt r where r.tenant_id=p.tenant_id and r.payment_id=p.id) as receipt_archived from finance_payment p where p.tenant_id=:tenantId and p.claim_id=:id order by p.created_at,p.id",b));
        if(new FinanceControlGuard(jdbc).installed()){
            var offsets=jdbc.queryForList("select payment_id from fc_claim_loan_clear where tenant_id=:tenantId and claim_id=:id",b).stream().map(x->x.get("payment_id")).collect(java.util.stream.Collectors.toSet());
            @SuppressWarnings("unchecked") var payments=(List<Map<String,Object>>)result.get("payments");for(var payment:payments){boolean offset=offsets.contains(payment.get("id"));payment.put("settlement_kind",offset?"LOAN_OFFSET":"CASH");if(offset){payment.put("receipt_archived",true);payment.put("receipt_status","NOT_APPLICABLE");}}
        }
        BigDecimal paid=new FinanceControlGuard(jdbc).claimPaid(p,id);
        result.put("paidAmount",paid.toPlainString());result.put("unpaidAmount",row.get("approved_amount")==null?"0":decimal(row,"approved_amount").subtract(paid).toPlainString());
        result.put("canEdit",has(p,"finance.submit")&&p.actorId().equals(uuid(row,"applicant_id"))&&p.assignmentIds().contains(uuid(row,"assignment_id"))&&EDITABLE.contains(row.get("status")));
        result.put("canReview",isReviewer(p,row));
        result.put("canPay",isCashier(p,row)&&!p.actorId().equals(uuid(row,"applicant_id"))&&"APPROVED".equals(row.get("status"))&&decimal(row,"approved_amount").compareTo(paid)>0);
        result.put("canArchiveReceipts",isCashier(p,row)&&!p.actorId().equals(uuid(row,"applicant_id"))&&"APPROVED".equals(row.get("status")));
        result.put("approvalRoute",readRoute(p,row));result.remove("approval_route");
        result.put("classificationSuggestion",suggestion(p,row));
        moneyStrings(result);
        for(String field:List.of("invoices","payments")) {
            @SuppressWarnings("unchecked") var records=(List<Map<String,Object>>)result.get(field);
            records.forEach(FinanceService::moneyStrings);
        }
        return result;
    }
    public Map<String,Object> save(UUID id,FinanceModels.Draft in) {
        var p=prepare("finance.submit");var row=claim(p,id,true);requireOwner(p,row);requireEditable(row);version(row,in.expectedVersion());
        if(in.businessDate().isAfter(today(p))||in.businessDate().getYear()<2000) throw bad("报销日期无效，不能填写未来日期");
        var b=base(p).addValue("id",id);var attachments=attachments(p,id);
        Set<String> keys=new HashSet<>();Set<UUID> ids=new HashSet<>();
        var completeInvoices=in.invoices().stream().filter(this::completeInvoice).toList();
        for(var invoice:in.invoices()) {
            if(!ids.add(invoice.id())) throw bad("本单含重复发票编号，请移除重复项");
            if(attachments.stream().noneMatch(a->invoice.attachmentId().equals(uuid(a,"id"))&&"INVOICE".equals(a.get("kind")))) throw bad("发票必须关联本单发票原件");
        }
        for(var invoice:completeInvoices) if(!keys.add(FinanceRules.invoiceKey(invoice.invoiceCode(),invoice.invoiceNumber()))) throw bad("本单含重复发票，请移除重复项");
        var category=in.categoryId()==null?Map.<String,Object>of():requireCategory(p,uuid(row,"hotel_id"),in.categoryId());
        List<FinanceModels.Line> candidates=in.items()==null?List.of():in.items();
        if(candidates.stream().map(FinanceModels.Line::id).distinct().count()!=candidates.size())throw bad("费用明细编号重复");
        List<FinanceModels.Line> lines=candidates.stream().filter(this::completeLine).toList();
        if(!lines.isEmpty()) {
            var sum=lines.stream().map(FinanceModels.Line::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(lines.size()==candidates.size()&&sum.compareTo(in.requestedAmount())!=0) throw bad("申请金额必须等于明细合计，请刷新金额后保存");
            Set<UUID> lineIds=new HashSet<>();int sequence=0;
            var previousProjects=new HashMap<UUID,Map<String,Object>>();if(new FinanceProjectDimensions(jdbc,access).installed())for(var previousLine:lineItems(p,id))previousProjects.put((UUID)previousLine.get("id"),previousLine);
            jdbc.update("delete from finance_claim_line where tenant_id=:tenantId and claim_id=:id",b);
            for(var line:lines) {
                if(!lineIds.add(line.id())) throw bad("费用明细编号重复");
                var cat=requireCategory(p,uuid(row,"hotel_id"),line.categoryId());
                if(line.quantity().multiply(line.unitPrice()).setScale(2,RoundingMode.HALF_UP).compareTo(line.amount())!=0) throw bad("明细数量×单价与金额不一致");
                if(!ids.containsAll(line.invoiceIds())) throw bad("费用明细只能引用本单发票");
                if(line.departmentId()!=null&&jdbc.queryForObject("select count(*) from org_unit_closure where tenant_id=:tenantId and ancestor_id=:hotel and descendant_id=:department",base(p).addValue("hotel",row.get("hotel_id")).addValue("department",line.departmentId()),Integer.class)==0) throw bad("部门不属于报销归属组织");
                LocalDate month=line.allocationMonth()==null?in.businessDate().withDayOfMonth(1):line.allocationMonth();
                if(month.getDayOfMonth()!=1||month.getYear()<2000||month.getYear()>2100) throw bad("分摊期间应填写月份首日");
                jdbc.update("insert into finance_claim_line(id,tenant_id,claim_id,sequence_no,category_id,category_name,treatment,debit_account,description,quantity,unit_price,amount,department_id,allocation_month,invoice_ids) values(:line,:tenantId,:id,:sequence,:category,:categoryName,:treatment,:debit,:description,:quantity,:unitPrice,:amount,:department,:month,cast(:invoices as jsonb))",
                    base(p).addValue("id",id).addValue("line",line.id()).addValue("sequence",++sequence).addValue("category",line.categoryId()).addValue("categoryName",cat.get("name")).addValue("treatment",cat.get("treatment")).addValue("debit",cat.get("debit_account")).addValue("description",line.description().trim()).addValue("quantity",line.quantity()).addValue("unitPrice",line.unitPrice()).addValue("amount",line.amount()).addValue("department",line.departmentId()).addValue("month",month).addValue("invoices",json(line.invoiceIds())));
                var projects=new FinanceProjectDimensions(jdbc,access);if(projects.installed()){var prior=previousProjects.get(line.id());var ref=line.projectKind()==null&&line.projectId()==null&&prior!=null?new FinanceProjectDimensions.ProjectRef(Objects.toString(prior.get("projectKind"),"NONE"),prior.get("projectId")==null?null:UUID.fromString(prior.get("projectId").toString())):new FinanceProjectDimensions.ProjectRef(line.projectKind(),line.projectId());var snapshot=projects.freeze(p,uuid(row,"hotel_id"),uuid(row,"company_id"),ref);jdbc.update("update finance_claim_line set project_kind=:kind,project_id=:project,project_key=:key,project_snapshot=cast(:snapshot as jsonb),financial_category_code=:code where tenant_id=:tenantId and claim_id=:id and id=:line",base(p).addValue("id",id).addValue("line",line.id()).addValue("kind",ref.kind()).addValue("project",ref.id()).addValue("key",ref.key()).addValue("snapshot",json(snapshot)).addValue("code",cat.get("budget_code")));}
            }
            category=requireCategory(p,uuid(row,"hotel_id"),lines.getFirst().categoryId());
        } else jdbc.update("delete from finance_claim_line where tenant_id=:tenantId and claim_id=:id",b);
        jdbc.update("delete from finance_invoice where tenant_id=:tenantId and claim_id=:id",b);
        for(var invoice:completeInvoices) jdbc.update("""
            insert into finance_invoice(id,tenant_id,claim_id,attachment_id,invoice_code,invoice_number,invoice_key,amount,buyer_tax_number,confirmed)
            values(:invoiceId,:tenantId,:id,:attachment,:code,:number,:key,:amount,:tax,:confirmed)
            """,base(p).addValue("id",id).addValue("invoiceId",invoice.id()).addValue("attachment",invoice.attachmentId())
            .addValue("code",FinanceRules.digits(invoice.invoiceCode())).addValue("number",FinanceRules.digits(invoice.invoiceNumber()))
            .addValue("key",FinanceRules.invoiceKey(invoice.invoiceCode(),invoice.invoiceNumber())).addValue("amount",invoice.amount())
            .addValue("tax",FinanceRules.taxNumber(invoice.buyerTaxNumber())).addValue("confirmed",invoice.confirmed()));
        BigDecimal total=FinanceRules.invoiceTotal(completeInvoices);
        boolean mismatch=FinanceRules.differs(total,in.transactionAmount(),in.paymentAmount(),in.requestedAmount())
            ||in.invoices().stream().anyMatch(x->!FinanceRules.taxNumber(x.buyerTaxNumber()).equals(row.get("company_tax_number")));
        b.addValue("title",in.title().trim()).addValue("date",in.businessDate()).addValue("category",lines.isEmpty()?in.categoryId():lines.getFirst().categoryId())
            .addValue("categoryName",category.getOrDefault("name","")).addValue("treatment",category.get("treatment"))
            .addValue("total",total).addValue("transaction",in.transactionAmount()).addValue("payment",in.paymentAmount())
            .addValue("requested",in.requestedAmount()).addValue("note",in.differenceNote().trim()).addValue("mismatch",mismatch)
            .addValue("candidates",json(in.invoices().stream().map(i->{var v=new LinkedHashMap<String,Object>();v.put("id",i.id());v.put("attachment_id",i.attachmentId());v.put("invoice_code",i.invoiceCode());v.put("invoice_number",i.invoiceNumber());v.put("amount",i.amount()==null?null:i.amount().toPlainString());v.put("buyer_tax_number",i.buyerTaxNumber());v.put("confirmed",i.confirmed());return v;}).toList()))
            .addValue("lineCandidates",json(candidates.stream().map(this::lineCandidate).toList()));
        jdbc.update("""
            update finance_claim set title=:title,business_date=:date,category_id=:category,category_name=:categoryName,treatment=:treatment,
              invoice_amount=:total,transaction_amount=:transaction,payment_amount=:payment,requested_amount=:requested,
              difference_note=:note,mismatch=:mismatch,draft_invoice_candidates=cast(:candidates as jsonb),draft_line_candidates=cast(:lineCandidates as jsonb),version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id
            """,b);
        return detail(id);
    }
    public Map<String,Object> submit(UUID id,int expectedVersion) {
        var p=prepare("finance.submit");var row=claim(p,id,true);requireOwner(p,row);
        if(REVIEWING.contains(row.get("status"))&&number(row,"version")==expectedVersion+1) return detail(id);
        requireEditable(row);version(row,expectedVersion);var b=base(p).addValue("id",id);
        var config=storeConfig(p,uuid(row,"hotel_id"));requireEnabled(config);
        if(row.get("company_id")==null) {
            jdbc.update("update finance_claim c set company_id=:company,company_name=:name,company_tax_number=:tax,mismatch=(c.invoice_amount<>c.transaction_amount or c.transaction_amount<>c.payment_amount or c.payment_amount<>c.requested_amount or exists(select 1 from finance_invoice fi where fi.tenant_id=c.tenant_id and fi.claim_id=c.id and fi.buyer_tax_number<>:tax)) where c.tenant_id=:tenantId and c.id=:id",b.addValue("company",config.get("company_id")).addValue("name",config.get("company_name")).addValue("tax",config.get("tax_number")));
            row=claim(p,id,false);
        } else if(!config.get("company_id").equals(row.get("company_id"))) throw conflict("门店法人公司已调整，请新建报销并核对发票抬头；原草稿仍保留");
        config=new FinanceControlGuard(jdbc).workflow(p,config,"CLAIM",decimal(row,"requested_amount"),LocalDate.parse(row.get("business_date").toString()),id);
        new FinanceControlGuard(jdbc).travel(p,row);
        var reviewers=List.of(uuid(config,"manager_account_id"),uuid(config,"finance_account_id"),uuid(config,"director_account_id"),uuid(config,"leader_account_id"));
        boolean skipSelf=reviewers.getFirst().equals(p.actorId())&&Boolean.TRUE.equals(config.get("skip_self_review"));
        if(reviewers.subList(1,4).contains(p.actorId())||(!skipSelf&&reviewers.getFirst().equals(p.actorId()))) throw conflict("审核链含申请人本人；仅允许已开启回避规则的初审跳转，其余节点请配置独立审核人");
        if(p.actorId().equals(uuid(config,"cashier_account_id"))) throw conflict("申请人不能登记本人的付款，请先配置其他出纳账号");
        var participants=new HashSet<>(reviewers);participants.add(uuid(config,"cashier_account_id"));
        new FinanceDelegationControl(jdbc).freeze(p,uuid(row,"hotel_id"),"CLAIM",id,expectedVersion,reviewers,p.actorId(),uuid(config,"cashier_account_id"));
        if(jdbc.queryForObject("select count(*) from user_account where tenant_id=:tenantId and id in (:reviewers) and status='ACTIVE'",base(p).addValue("reviewers",participants),Integer.class)!=participants.size()) throw conflict("审核人或出纳已停用，请更新门店审核配置");
        if(row.get("draft_line_candidates")!=null) {
            try {if(mapper.readTree(row.get("draft_line_candidates").toString()).size()!=lineItems(p,id).size())throw bad("请补齐费用明细类别、说明、数量、单价及金额后正式提交");}
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("费用草稿损坏",e);}
        }
        if(text(row,"title").isBlank()||decimal(row,"requested_amount").signum()<=0||row.get("category_id")==null) throw bad("请填写事由、费用类别和正数报销金额");
        requireCategory(p,uuid(row,"hotel_id"),uuid(row,"category_id"));
        for(var line:lineItems(p,id)) {
            var category=requireCategory(p,uuid(row,"hotel_id"),UUID.fromString(line.get("categoryId").toString()));
            if(!category.get("treatment").equals(line.get("treatment"))||!category.get("debit_account").equals(line.get("debitAccount")))throw conflict("费用明细对应处理方式或科目已调整，请重新保存草稿后提交");
            verifyClaimProject(p,row,line);
        }
        var invoices=jdbc.queryForList("select * from finance_invoice where tenant_id=:tenantId and claim_id=:id order by invoice_key",b);
        if(row.get("draft_invoice_candidates")!=null) {
            try {if(mapper.readTree(row.get("draft_invoice_candidates").toString()).size()!=invoices.size()) throw bad("请补齐发票候选的号码、金额及购买方税号后正式提交");}
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("发票草稿损坏",e);}
        }
        if(invoices.isEmpty()||invoices.stream().anyMatch(x->!Boolean.TRUE.equals(x.get("confirmed")))) throw bad("请核对所有发票号码、金额和抬头后确认");
        var documents=attachments(p,id);Set<Object> kinds=new HashSet<>();documents.forEach(x->kinds.add(x.get("kind")));
        if(!kinds.containsAll(Set.of("INVOICE","TRANSACTION","PAYMENT"))) throw bad("请补齐发票、交易记录及付款凭证");
        if(documents.stream().filter(d->"INVOICE".equals(d.get("kind"))).anyMatch(d->invoices.stream().noneMatch(i->d.get("id").equals(i.get("attachment_id"))))) throw bad("请为每份发票原件填写对应的发票明细，或移除误上传的原件");
        if(Boolean.TRUE.equals(row.get("mismatch"))&&text(row,"difference_note").isBlank()) throw bad("金额或发票抬头存在差异，请填写原因；差异不会自动拒绝报销");
        lock(p,"invoice-registry");
        for(var invoice:invoices) {
            var q=base(p).addValue("key",invoice.get("invoice_key")).addValue("id",id);
            if(new FinanceControlGuard(jdbc).table("fs_invoice_use")&&jdbc.queryForObject("select count(*) from fs_invoice_use where coalesce(to_jsonb(fs_invoice_use)->>'released_at','')='' and tenant_id=:tenantId and invoice_key=:key",q,Integer.class)>0)throw conflict("发票已用于采购核算，不可再次报销");
            if(jdbc.queryForObject("select count(*) from finance_invoice_reservation where tenant_id=:tenantId and invoice_key=:key and (claim_id is null or claim_id<>:id)",q,Integer.class)>0) throw conflict("发票已被其他报销或历史已报销记录占用，已阻止重复提交");
        }
        for(var document:documents) if("INVOICE".equals(document.get("kind"))) {
            var q=base(p).addValue("hash",document.get("sha256")).addValue("id",id);
            if(new FinanceControlGuard(jdbc).table("fs_invoice_use")&&jdbc.queryForObject("select count(*) from fs_invoice_use where coalesce(to_jsonb(fs_invoice_use)->>'released_at','')='' and tenant_id=:tenantId and sha256=:hash",q,Integer.class)>0)throw conflict("同一发票原件已用于采购核算，不可再次报销");
            if(jdbc.queryForObject("select count(*) from finance_evidence_reservation where tenant_id=:tenantId and sha256=:hash and claim_id<>:id",q,Integer.class)>0) throw conflict("同一发票文件已用于其他报销，已阻止重复提交");
        }
        new FinanceBudgetControl(jdbc).reserve(p,"CLAIM",row);
        release(p,id);
        for(var invoice:invoices) jdbc.update("insert into finance_invoice_reservation(tenant_id,invoice_key,claim_id) values(:tenantId,:key,:id)",base(p).addValue("key",invoice.get("invoice_key")).addValue("id",id));
        for(var document:documents) if("INVOICE".equals(document.get("kind"))) jdbc.update("insert into finance_evidence_reservation(tenant_id,sha256,claim_id) values(:tenantId,:hash,:id) on conflict do nothing",base(p).addValue("hash",document.get("sha256")).addValue("id",id));
        jdbc.update("""
            update finance_claim set status=:status,manager_account_id=:manager,finance_account_id=:finance,director_account_id=:director,leader_account_id=:leader,
              cashier_account_id=:cashier,workflow_version=2,approval_route=cast(:route as jsonb),
              config_version=:configVersion,approved_amount=null,submitted_at=now(),version=version+1,updated_at=now()
            where tenant_id=:tenantId and id=:id
            """,b.addValue("status",skipSelf?"FINANCE_REVIEW":"MANAGER_REVIEW").addValue("manager",reviewers.get(0)).addValue("finance",reviewers.get(1))
            .addValue("director",reviewers.get(2)).addValue("leader",reviewers.get(3)).addValue("cashier",config.get("cashier_account_id"))
            .addValue("route",json(buildRoute(p,config,skipSelf))).addValue("configVersion",config.get("version")));
        if(skipSelf) {
            String reason="初审人与申请人相同，已跳过本人初审并转交门店财务；跳过不代表审核通过";
            var submitted=claim(p,id,false);moneyStrings(submitted);
            var snapshot=Map.of("claim",submitted,"invoices",invoices,"evidence",documents,"items",lineItems(p,id));
            jdbc.update("insert into finance_review(tenant_id,claim_id,actor_id,stage,decision,note,request_id,request_hash,review_snapshot) values(:tenantId,:id,:actorId,'MANAGER_REVIEW','SKIP_SELF',:reason,:request,:hash,cast(:snapshot as jsonb))",
                base(p).addValue("id",id).addValue("reason",reason).addValue("request",UUID.randomUUID()).addValue("hash",hash(id+":"+expectedVersion+":SKIP_SELF")).addValue("snapshot",json(snapshot)));
            audit.record("FINANCE_INITIAL_SELF_REVIEW_SKIPPED","FINANCE_CLAIM",id,json(Map.of("reason",reason,"nextStage","FINANCE_REVIEW","configVersion",config.get("version"))));
        }
        notifyCurrent(p,id);
        audit.record("FINANCE_CLAIM_SUBMITTED","FINANCE_CLAIM",id,"{}");return detail(id);
    }
    public Map<String,Object> withdraw(UUID id,int expectedVersion) {
        var p=prepare("finance.submit");var row=claim(p,id,true);requireOwner(p,row);version(row,expectedVersion);
        if(!EDITABLE.contains(row.get("status"))&&!REVIEWING.contains(row.get("status"))) throw conflict("当前单据不能撤回");
        lock(p,"invoice-registry");release(p,id);
        jdbc.update("update finance_claim set status='WITHDRAWN',version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",base(p).addValue("id",id));
        new FinanceBudgetControl(jdbc).transition(p,"CLAIM",row,"WITHDRAWN");audit.record("FINANCE_CLAIM_WITHDRAWN","FINANCE_CLAIM",id,"{}");return detail(id);
    }
    public Map<String,Object> review(UUID id,FinanceModels.Review in) {
        var p=prepare("finance.review");var row=claim(p,id,true);requireReadable(p,row);
        var b=base(p).addValue("id",id).addValue("request",in.requestId());String hash=hash(json(in));
        var prior=jdbc.queryForList("select actor_id,request_hash from finance_review where tenant_id=:tenantId and claim_id=:id and request_id=:request",b);
        if(!prior.isEmpty()) {
            if(!p.actorId().equals(uuid(prior.getFirst(),"actor_id"))||!hash.equals(prior.getFirst().get("request_hash"))) throw conflict("审核请求编号不可复用于不同操作");
            return detail(id);
        }
        if(!isReviewer(p,row)) throw forbidden();version(row,in.expectedVersion());String stage=text(row,"status");String next;
        if("APPROVE".equals(in.decision())) {
            for(var line:lineItems(p,id))verifyClaimProject(p,row,line);
            next=FinanceWorkflow.next(stage,number(row,"workflow_version"));
            if("FINANCE_REVIEW".equals(stage)) {
                if(in.approvedAmount()==null||in.categoryId()==null) throw bad("财务复核须确认报销金额及费用类别");
                var detailLines=lineItems(p,id);
                if(!detailLines.isEmpty()&&(decimal(row,"requested_amount").compareTo(in.approvedAmount())!=0||!in.categoryId().equals(uuid(row,"category_id")))) throw bad("多行报销不能只改总金额或首行类别，请退回修改明细后重新审核");
                boolean changed=decimal(row,"requested_amount").compareTo(in.approvedAmount())!=0;
                if((Boolean.TRUE.equals(row.get("mismatch"))||changed)&&!in.acknowledgeDifference()) throw bad("请先核实差额并明确确认，不能忽略异常直接通过");
                var cat=requireCategory(p,uuid(row,"hotel_id"),in.categoryId());var config=storeConfig(p,uuid(row,"hotel_id"));
                if(!config.get("company_id").equals(row.get("company_id"))) throw conflict("法人公司配置已变化，请先退回核对，不能跨公司生成凭证");
                jdbc.update("update finance_claim set approved_amount=:amount,category_id=:category,category_name=:name,treatment=:treatment,debit_account=:debit,payable_account=:payable where tenant_id=:tenantId and id=:id",
                    base(p).addValue("id",id).addValue("amount",in.approvedAmount()).addValue("category",in.categoryId()).addValue("name",cat.get("name"))
                    .addValue("treatment",cat.get("treatment")).addValue("debit",cat.get("debit_account")).addValue("payable",config.get("payable_account")));
            }
        } else next="RETURN".equals(in.decision())?"RETURNED":"REJECTED";
        var reviewed=claim(p,id,false);moneyStrings(reviewed);
        var snapshot=Map.of("claim",reviewed,"invoices",jdbc.queryForList("select invoice_code,invoice_number,amount::text,buyer_tax_number,attachment_id from finance_invoice where tenant_id=:tenantId and claim_id=:id order by invoice_key",b),
            "evidence",jdbc.queryForList("select id,kind,original_name,sha256 from finance_attachment where tenant_id=:tenantId and claim_id=:id and retired_at is null order by id",b),"items",lineItems(p,id));
        jdbc.update("insert into finance_review(tenant_id,claim_id,actor_id,stage,decision,note,request_id,request_hash,review_snapshot) values(:tenantId,:id,:actorId,:stage,:decision,:note,:request,:hash,cast(:snapshot as jsonb))",
            b.addValue("stage",stage).addValue("decision",in.decision()).addValue("note",in.note().trim()).addValue("hash",hash).addValue("snapshot",json(snapshot)));
        jdbc.update("update finance_claim set status=:status,version=version+1,updated_at=now(),approved_at=case when :status='APPROVED' then now() else approved_at end where tenant_id=:tenantId and id=:id",b.addValue("status",next));
        if(Set.of("REJECTED","RETURNED").contains(next)) {lock(p,"invoice-registry");release(p,id);}
        if("APPROVED".equals(next)) finalizeClaim(p,id);
        new FinanceBudgetControl(jdbc).transition(p,"CLAIM",row,next);
        notifyCurrent(p,id);
        audit.record("FINANCE_CLAIM_REVIEWED","FINANCE_CLAIM",id,json(Map.of("stage",stage,"decision",in.decision())));return detail(id);
    }
    private void finalizeClaim(TenantPrincipal p,UUID id) {
        var row=claim(p,id,false);var b=base(p).addValue("id",id);
        if(row.get("approved_amount")==null) throw conflict("缺少财务确认金额，不能终审");
        var lines=lineItems(p,id);
        if(lines.isEmpty()) jdbc.update("""
            insert into finance_expense_entry(tenant_id,claim_id,hotel_id,company_name,category_name,treatment,amount,business_date)
            select tenant_id,id,hotel_id,company_name,category_name,treatment,approved_amount,business_date from finance_claim where tenant_id=:tenantId and id=:id
            on conflict(tenant_id,claim_id) do nothing
            """,b);
        else for(var line:lines) {
            jdbc.update("insert into finance_expense_line_entry(tenant_id,claim_id,line_id,hotel_id,category_name,treatment,amount,business_date,allocation_month,snapshot) values(:tenantId,:id,:line,:hotel,:category,:treatment,:amount,:date,:month,cast(:snapshot as jsonb)) on conflict(tenant_id,line_id) do nothing",
                base(p).addValue("id",id).addValue("line",line.get("id")).addValue("hotel",row.get("hotel_id")).addValue("category",line.get("categoryName")).addValue("treatment",line.get("treatment")).addValue("amount",new BigDecimal(line.get("amount").toString())).addValue("date",row.get("business_date")).addValue("month",LocalDate.parse(line.get("allocationMonth").toString())).addValue("snapshot",json(line)));
        }
        if(lines.isEmpty()) accounting.recordSourceEvent(uuid(row,"hotel_id"),"REIMBURSEMENT_"+text(row,"treatment"),id,"APPROVED:"+row.get("category_id"),LocalDate.parse(row.get("business_date").toString()),today(p),decimal(row,"approved_amount"),text(row,"title"),Map.of("applicantId",row.get("applicant_id").toString(),"claimId",id.toString(),"categoryId",row.get("category_id").toString(),"treatment",text(row,"treatment")));
        else {
            if(lines.stream().map(l->new BigDecimal(l.get("amount").toString())).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(decimal(row,"approved_amount"))!=0)throw conflict("费用明细合计与批准金额不一致，不能生成核算来源");
            for(var line:lines)accounting.recordSourceEvent(uuid(row,"hotel_id"),"REIMBURSEMENT_"+line.get("treatment"),UUID.fromString(line.get("id").toString()),"APPROVED:"+line.get("categoryId"),LocalDate.parse(row.get("business_date").toString()),today(p),new BigDecimal(line.get("amount").toString()),line.get("description").toString(),Map.of("applicantId",row.get("applicant_id").toString(),"claimId",id.toString(),"lineId",line.get("id").toString(),"categoryId",line.get("categoryId").toString(),"treatment",line.get("treatment").toString()));
        }
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("status","DRAFT");snapshot.put("claimId",id);snapshot.put("companyName",row.get("company_name"));snapshot.put("companyTaxNumber",row.get("company_tax_number"));
        snapshot.put("hotelName",row.get("hotel_name"));snapshot.put("applicantName",row.get("applicant_name"));snapshot.put("businessDate",row.get("business_date").toString());
        snapshot.put("summary",row.get("title"));snapshot.put("amount",decimal(row,"approved_amount").toPlainString());snapshot.put("debitAccount",row.get("debit_account"));snapshot.put("creditAccount",row.get("payable_account"));
        snapshot.put("taxTreatment","待财务复核，不自动判断进项税抵扣");snapshot.put("treatment",row.get("treatment"));
        snapshot.put("evidence",jdbc.queryForList("select id,kind,original_name,sha256 from finance_attachment where tenant_id=:tenantId and claim_id=:id and retired_at is null order by id",b));
        snapshot.put("invoices",jdbc.queryForList("select invoice_code,invoice_number,amount::text,buyer_tax_number from finance_invoice where tenant_id=:tenantId and claim_id=:id order by invoice_key",b));
        snapshot.put("warning","凭证草稿，尚未记账；请核对科目、税额、期间和原始凭证。不替代税务发票。");
        snapshot.put("items",lines);
        jdbc.update("insert into finance_voucher_draft(tenant_id,claim_id,snapshot) values(:tenantId,:id,cast(:snapshot as jsonb)) on conflict(tenant_id,claim_id) do nothing",b.addValue("snapshot",json(snapshot)));
    }
    public Map<String,Object> payment(UUID id,FinanceModels.Payment in) {
        var p=prepare("finance.pay");var row=claim(p,id,true);access.requireOrgScope(uuid(row,"hotel_id"));var b=base(p).addValue("id",id);
        var existing=jdbc.queryForList("select * from finance_payment where tenant_id=:tenantId and request_id=:request",b.addValue("request",in.requestId()));
        if(!existing.isEmpty()) {
            var old=existing.getFirst();
            if(id.equals(uuid(old,"claim_id"))&&p.actorId().equals(uuid(old,"actor_id"))&&in.amount().compareTo(decimal(old,"amount"))==0
                &&in.reference().trim().equals(old.get("reference"))&&in.paidOn().toString().equals(old.get("paid_on").toString())) return detail(id);
            throw conflict("付款请求编号已用于其他内容，不能重复登记");
        }
        version(row,in.expectedVersion());
        if(!"APPROVED".equals(row.get("status"))) throw conflict("全部审批通过后才能登记付款");
        if(!isCashier(p,row)||p.actorId().equals(uuid(row,"applicant_id"))) throw forbidden();
        new FinanceControlGuard(jdbc).cash(p,"CLAIM",row,"PAYMENT",in.amount(),in.paidOn(),null);
        BigDecimal paid=new FinanceControlGuard(jdbc).claimPaid(p,id);
        if(in.amount().signum()<=0||in.amount().compareTo(decimal(row,"approved_amount").subtract(paid))>0) throw bad("付款金额必须为正数且不能超过未付余额");
        if(in.paidOn().isAfter(today(p))||in.paidOn().isBefore(LocalDate.parse(row.get("business_date").toString()))) throw bad("付款日期不能早于业务日期或晚于今天");
        jdbc.update("insert into finance_payment(tenant_id,claim_id,amount,reference,paid_on,actor_id,request_id) values(:tenantId,:id,:amount,:reference,:date,:actorId,:request)",
            b.addValue("amount",in.amount()).addValue("reference",in.reference().trim()).addValue("date",in.paidOn()).addValue("request",in.requestId()));
        jdbc.update("update finance_claim set version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",b);
        accounting.recordSourceEvent(uuid(row,"hotel_id"),"REIMBURSEMENT_PAYMENT",in.requestId(),"PAID",in.paidOn(),in.paidOn(),in.amount(),in.reference(),Map.of("claimId",id.toString()));
        audit.record("FINANCE_PAYMENT_RECORDED","FINANCE_CLAIM",id,"{}");return detail(id);
    }
    public Map<String,Object> ledger(String month,UUID hotel) {
        var p=prepare("finance.read");var b=period(p,month,hotel);
        String from=ledgerFrom();
        String totals="coalesce(sum(e.amount),0)::text as total,coalesce(sum(py.amount),0)::text as paid,coalesce(sum(e.amount-coalesce(py.amount,0)),0)::text as unpaid";
        var result=new LinkedHashMap<String,Object>();result.put("month",month);
        result.put("totals",jdbc.queryForMap("select "+totals+from+" and e.treatment='EXPENSE'",b));
        result.put("stores",jdbc.queryForList("select e.hotel_id,c.hotel_name,"+totals+from+" and e.treatment='EXPENSE' group by e.hotel_id,c.hotel_name order by c.hotel_name",b));
        result.put("categories",jdbc.queryForList("select e.category_name,coalesce(sum(e.amount),0)::text as amount"+from+" and e.treatment='EXPENSE' group by e.category_name order by sum(e.amount) desc,e.category_name",b));
        result.put("otherTreatments",jdbc.queryForList("select e.treatment,coalesce(sum(e.amount),0)::text as amount"+from+" and e.treatment<>'EXPENSE' group by e.treatment order by e.treatment",b));
        result.put("entries",jdbc.queryForList("select c.id,e.id as entry_id,c.title,c.applicant_name,c.hotel_name,c.company_name,e.category_name,e.treatment,e.amount::text,e.business_date,coalesce(py.amount,0)::text as allocated_paid,(e.amount-coalesce(py.amount,0))::text as allocated_unpaid,py.paid_on"+from+" order by e.business_date desc,e.id limit 100",b));
        result.put("note","已付金额按单据明细累计比例分摊并守恒，不代表银行逐行付款；仅报销费用统计，未合并库存耗用、采购及收入；不是完整财务报表。库存、资产、预付及已记成本不重复计入费用。");
        return result;
    }
    /** The screen and export share the same immutable line facts and cent-conserving allocation. */
    private String ledgerFrom() {
        String sql=" from (select facts.*,sum(amount) over(partition by tenant_id,claim_id order by id rows unbounded preceding) as cumulative_amount,sum(amount) over(partition by tenant_id,claim_id) as claim_total from (select id,tenant_id,claim_id,hotel_id,category_name,treatment,amount,business_date from finance_expense_entry union all select id,tenant_id,claim_id,hotel_id,category_name,treatment,amount,business_date from finance_expense_line_entry) facts) e join finance_claim c on c.tenant_id=e.tenant_id and c.id=e.claim_id left join lateral(select round(sum(p.amount)*e.cumulative_amount/nullif(e.claim_total,0),2)-round(sum(p.amount)*(e.cumulative_amount-e.amount)/nullif(e.claim_total,0),2) as amount,max(p.paid_on) as paid_on from finance_payment p where p.tenant_id=e.tenant_id and p.claim_id=e.claim_id) py on true where e.tenant_id=:tenantId and e.business_date>=:start and e.business_date<:end and "+SCOPE+" and (cast(:hotel as uuid) is null or e.hotel_id=:hotel)";return new FinanceControlGuard(jdbc).table("fx_cash_correction")?sql.replace("sum(p.amount)","sum(fx_cash_effective_amount(p.tenant_id,'CLAIM',p.id,p.amount,'9999-12-31'::date))"):sql;
    }
    public String export(String month,UUID hotel) {
        var p=prepare("finance.read");var b=period(p,month,hotel);
        var rows=jdbc.queryForList("select c.id as claim_id,e.id as entry_id,e.business_date,c.hotel_name,c.company_name,c.applicant_name,c.title,e.category_name,e.treatment,e.amount::text as amount,coalesce(py.amount,0)::text as allocated_paid,(e.amount-coalesce(py.amount,0))::text as allocated_unpaid,py.paid_on,'按整单明细累计比例分摊并守恒；非逐行银行付款。费用仅筛选EXPENSE，其他性质单列。' as allocation_basis"+ledgerFrom()+" order by e.business_date desc,e.id limit 10001",b);
        if(rows.size()>10000) throw bad("导出超过一万条明细，请按单店缩小范围；本次不截断导出");
        // UUIDs, tax identifiers and any textual values are never converted to floating point.
        // FinanceRules.csv escapes formula-leading text; this ledger does not flatten invoice numbers into numeric columns.
        return csvRows(List.of("单据ID","明细事实ID","业务日期","门店","法人公司","申请人","事由","明细类别","会计处理","明细金额","已付分摊","待付分摊","最近付款日","付款分摊口径"),rows,
            List.of("claim_id","entry_id","business_date","hotel_name","company_name","applicant_name","title","category_name","treatment","amount","allocated_paid","allocated_unpaid","paid_on","allocation_basis"));
    }
    public Map<String,Object> voucher(UUID id) {
        var p=prepare();var c=claim(p,id,false);requireReadable(p,c);
        var rows=jdbc.queryForList("select snapshot from finance_voucher_draft where tenant_id=:tenantId and claim_id=:id",base(p).addValue("id",id));
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"审批尚未完成，暂无凭证草稿");return parse(rows.getFirst().get("snapshot"));
    }
    public String voucherExport(UUID id) {
        var v=voucher(id);var lines=new ArrayList<Map<String,Object>>();
        for(String direction:List.of("借","贷")) {
            var line=new LinkedHashMap<>(v);line.put("direction",direction);line.put("account",v.get(direction.equals("借")?"debitAccount":"creditAccount"));lines.add(line);
        }
        return csvRows(List.of("状态","报销单ID","法人公司","税号","归属门店","业务日期","摘要","方向","科目(待复核)","金额","税务处理","说明"),lines,
            List.of("status","claimId","companyName","companyTaxNumber","hotelName","businessDate","summary","direction","account","amount","taxTreatment","warning"));
    }
    public void authorizeUpload(UUID id) {var p=prepare("finance.submit","finance.pay");var row=claim(p,id,false);if(isCashier(p,row)&&"APPROVED".equals(row.get("status"))&&!p.actorId().equals(uuid(row,"applicant_id")))return;requireOwner(p,row);requireEditable(row);}
    public Map<String,Object> registerAttachment(UUID id,UUID attachmentId,String kind,String name,String type,long size,String hash,String objectKey,String scanStatus,Map<String,Object> extraction) {
        var p=prepare("finance.submit","finance.pay");var row=claim(p,id,true);
        if(isCashier(p,row)&&"APPROVED".equals(row.get("status"))&&!p.actorId().equals(uuid(row,"applicant_id"))) {if(!"PAYMENT".equals(kind)) throw bad("出纳只能补传付款回单，不能修改获批发票或交易证据");}
        else {requireOwner(p,row);requireEditable(row);}
        var b=base(p).addValue("id",id).addValue("attachment",attachmentId);
        var prior=jdbc.queryForList("select id,claim_id,sha256,kind,retired_at from finance_attachment where tenant_id=:tenantId and id=:attachment",b);
        if(!prior.isEmpty()) {
            var old=prior.getFirst();
            if(old.get("retired_at")!=null) throw conflict("该上传编号已从草稿移除，请重新选择文件，历史原件仍保留");
            if(id.equals(uuid(old,"claim_id"))&&hash.equals(old.get("sha256"))&&kind.equals(old.get("kind"))) return attachments(p,id).stream().filter(a->attachmentId.equals(uuid(a,"id"))).findFirst().orElseThrow();
            throw conflict("上传编号已存在且内容不同，请刷新附件列表");
        }
        if(jdbc.queryForObject("select count(*) from finance_attachment where tenant_id=:tenantId and claim_id=:id and retired_at is null and kind=:kind and sha256=:hash",b.addValue("kind",kind).addValue("hash",hash),Integer.class)>0) throw conflict("本单已上传相同类型的同一文件，请勿重复添加");
        if(jdbc.queryForObject("select count(*) from finance_attachment where tenant_id=:tenantId and claim_id=:id and retired_at is null",b,Integer.class)>=40) throw bad("每单最多40份凭证");
        b.addValue("kind",kind).addValue("name",name).addValue("type",type).addValue("size",size).addValue("hash",hash).addValue("object",objectKey).addValue("scan",scanStatus).addValue("extraction",json(extraction));
        jdbc.update("insert into finance_attachment(id,tenant_id,claim_id,kind,original_name,media_type,size_bytes,sha256,object_key,scan_status,extraction) values(:attachment,:tenantId,:id,:kind,:name,:type,:size,:hash,:object,:scan,cast(:extraction as jsonb))",b);
        if("CLEAN".equals(scanStatus)&&Set.of("image/jpeg","image/png").contains(type))appendLocalOcr(p,attachmentId,null,kind,hash,extraction);
        audit.record("FINANCE_EVIDENCE_UPLOADED","FINANCE_CLAIM",id,json(Map.of("attachmentId",attachmentId,"kind",kind,"sha256",hash)));
        return attachments(p,id).stream().filter(a->attachmentId.equals(uuid(a,"id"))).findFirst().orElseThrow();
    }
    public Map<String,Object> attachment(UUID id,UUID attachmentId) {
        var p=prepare();requireReadable(p,claim(p,id,false));
        var rows=jdbc.queryForList("select * from finance_attachment where tenant_id=:tenantId and claim_id=:id and id=:attachment",base(p).addValue("id",id).addValue("attachment",attachmentId));
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"凭证不存在");return rows.getFirst();
    }
    public Map<String,Object> recordAttachmentRecognition(UUID id,UUID attachmentId,Map<String,Object> extraction) {
        var p=prepare("finance.submit","finance.pay");var row=claim(p,id,true);
        if(!localOcrResultsInstalled())throw conflict("本地识别结果迁移尚未安装，请先完成受控服务升级；原件仍保留");
        if(!(isCashier(p,row)&&"APPROVED".equals(row.get("status"))&&!p.actorId().equals(uuid(row,"applicant_id")))){requireOwner(p,row);requireEditable(row);}
        var record=attachment(id,attachmentId);if(record.get("retired_at")!=null)throw conflict("已移除凭证不能重新识别");
        if(!text(record,"sha256").equals(extraction.get("sourceSha256")))throw conflict("识别结果与已存原件指纹不一致，未采纳候选");
        if(!"DRAFT".equals(row.get("status"))&&!"RETURNED".equals(row.get("status"))&&!"PAYMENT".equals(record.get("kind")))throw forbidden();
        appendLocalOcr(p,attachmentId,null,text(record,"kind"),text(record,"sha256"),extraction);
        return attachments(p,id).stream().filter(a->attachmentId.equals(uuid(a,"id"))).findFirst().orElseThrow();
    }
    private boolean localOcrResultsInstalled(){return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('finance_local_ocr_result') is not null",Map.of(),Boolean.class));}
    private Map<String,Object> localOcrOverlay(TenantPrincipal p,UUID attachment,UUID intake,String hash,Map<String,Object> fallback){
        if(!localOcrResultsInstalled())return fallback;
        var rows=jdbc.queryForList("select extraction from finance_local_ocr_result where tenant_id=:tenantId and source_sha256=:hash and (attachment_id=:attachment or intake_id=:intake) order by created_at desc,id desc limit 1",base(p).addValue("hash",hash).addValue("attachment",attachment).addValue("intake",intake));
        return rows.isEmpty()?fallback:parse(rows.getFirst().get("extraction"));
    }
    private void appendLocalOcr(TenantPrincipal p,UUID attachment,UUID intake,String kind,String hash,Map<String,Object> extraction){
        if(!localOcrResultsInstalled()||!hash.equals(extraction.get("sourceSha256")))return;
        var observation=new LinkedHashMap<String,Object>(extraction);var image=extraction.get("imageHash") instanceof Map<?,?> m?m:Map.of();
        String phash=Objects.toString(image.get("phash"),""),dhash=Objects.toString(image.get("dhash"),"");
        var b=base(p).addValue("attachment",attachment).addValue("intake",intake).addValue("hash",hash).addValue("kind",kind);
        if("phash-dhash-v1".equals(image.get("algorithm"))&&phash.matches("[a-f0-9]{16}")&&dhash.matches("[a-f0-9]{16}")) {
            boolean similar=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from finance_local_ocr_result where tenant_id=:tenantId and phash is not null and not(attachment_id is not distinct from :attachment and intake_id is not distinct from :intake) and bit_count(('x'||phash)::bit(64)#('x'||:phash)::bit(64))<=6 and bit_count(('x'||dhash)::bit(64)#('x'||:dhash)::bit(64))<=8)",b.addValue("phash",phash).addValue("dhash",dhash),Boolean.class));
            if(similar)observation.put("similarityWarning","同一租户存在图像相近的凭证，请核对是否重复；这只是提醒，不作为拒绝依据，也不展示其他员工资料。");
        } else {phash=null;dhash=null;}
        jdbc.update("insert into finance_local_ocr_result(id,tenant_id,attachment_id,intake_id,source_sha256,kind,extraction,phash,dhash,created_by) values(:observation,:tenantId,:attachment,:intake,:hash,:kind,cast(:extraction as jsonb),:phash,:dhash,:actorId)",b.addValue("observation",UUID.randomUUID()).addValue("extraction",json(observation)).addValue("phash",phash).addValue("dhash",dhash));
        audit.record("FINANCE_LOCAL_RECOGNITION_RECORDED",attachment==null?"FINANCE_INVOICE_INTAKE":"FINANCE_CLAIM_ATTACHMENT",attachment==null?intake:attachment,json(Map.of("sourceSha256",hash,"status",Objects.toString(extraction.get("status"),"UNKNOWN"))));
    }
    public void recordEvidenceRead(UUID id,UUID attachmentId) {
        var p=prepare();requireReadable(p,claim(p,id,false));audit.record("FINANCE_ORIGINAL_DOWNLOADED","FINANCE_CLAIM",id,json(Map.of("attachmentId",attachmentId)));
    }
    public Map<String,Object> removeAttachment(UUID id,UUID attachmentId,int expectedVersion) {
        var p=prepare("finance.submit");var row=claim(p,id,true);requireOwner(p,row);requireEditable(row);version(row,expectedVersion);
        var b=base(p).addValue("id",id).addValue("attachment",attachmentId);
        if(jdbc.queryForObject("select count(*) from finance_invoice where tenant_id=:tenantId and claim_id=:id and attachment_id=:attachment",b,Integer.class)>0) throw conflict("请先从发票明细中移除该原件对应的发票并保存");
        if(jdbc.update("update finance_attachment set retired_at=now() where tenant_id=:tenantId and claim_id=:id and id=:attachment and retired_at is null",b)!=1) throw conflict("凭证已移除或不存在，请刷新草稿");
        // Never delete financial evidence bytes here: archive retention is a separate controlled process.
        jdbc.update("update finance_claim set version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",b);
        audit.record("FINANCE_DRAFT_ATTACHMENT_REMOVED","FINANCE_CLAIM",id,json(Map.of("attachmentId",attachmentId)));return detail(id);
    }
    private List<Map<String,Object>> attachments(TenantPrincipal p,UUID id) {
        var rows=jdbc.queryForList("select id,kind,original_name,media_type,size_bytes,sha256,scan_status,extraction,created_at from finance_attachment where tenant_id=:tenantId and claim_id=:id and retired_at is null order by created_at,id",base(p).addValue("id",id));
        rows.forEach(r->r.put("extraction",localOcrOverlay(p,uuid(r,"id"),null,text(r,"sha256"),parse(r.get("extraction")))));return rows;
    }
    private Map<String,Object> suggestion(TenantPrincipal p,Map<String,Object> row) {
        var categories=jdbc.queryForList("select id,name,keywords from finance_category where tenant_id=:tenantId and hotel_id=:hotel and enabled",base(p).addValue("hotel",row.get("hotel_id")));
        String title=text(row,"title");
        var matches=categories.stream().filter(c->Arrays.stream(text(c,"keywords").split("[,，;；\\s]+")).anyMatch(k->!k.isBlank()&&title.contains(k))).toList();
        if(matches.size()==1) return Map.of("source","STORE_RULE","categoryId",matches.getFirst().get("id"),"categoryName",matches.getFirst().get("name"),"humanConfirmationRequired",true);
        if(!title.isBlank()) {
            var history=jdbc.queryForList("select distinct c.category_id,c.category_name from finance_claim c join finance_category ca on ca.tenant_id=c.tenant_id and ca.id=c.category_id and ca.enabled where c.tenant_id=:tenantId and c.hotel_id=:hotel and c.status='APPROVED' and c.title=:title limit 2",base(p).addValue("hotel",row.get("hotel_id")).addValue("title",title));
            if(history.size()==1) return Map.of("source","REVIEWED_HISTORY","categoryId",history.getFirst().get("category_id"),"categoryName",history.getFirst().get("category_name"),"humanConfirmationRequired",true);
        }
        return Map.of("source","MANUAL_REQUIRED","humanConfirmationRequired",true);
    }
    private Map<String,Object> assignment(TenantPrincipal p,UUID assignment) {
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)context().get("assignments");
        return rows.stream().filter(x->assignment.equals(uuid(x,"assignment_id"))).findFirst().orElseThrow(()->bad("当前任职无有效归属门店或总部"));
    }
    private Map<String,Object> optionalConfig(TenantPrincipal p,UUID hotel) {
        var rows=jdbc.queryForList("select f.*,c.name as company_name,c.tax_number from finance_store_config f join finance_company c on c.tenant_id=f.tenant_id and c.id=f.company_id where f.tenant_id=:tenantId and f.hotel_id=:hotel",base(p).addValue("hotel",hotel));
        return rows.isEmpty()?Map.of():rows.getFirst();
    }
    private boolean completeInvoice(FinanceModels.Invoice invoice) {
        if(invoice.amount()==null||invoice.amount().signum()<=0||invoice.buyerTaxNumber().isBlank()) return false;
        try {FinanceRules.invoiceKey(invoice.invoiceCode(),invoice.invoiceNumber());FinanceRules.taxNumber(invoice.buyerTaxNumber());return true;}
        catch(IllegalArgumentException e){return false;}
    }
    private boolean completeLine(FinanceModels.Line line) {
        return line.categoryId()!=null&&!line.description().isBlank()&&line.quantity()!=null&&line.quantity().signum()>0&&line.unitPrice()!=null&&line.amount()!=null&&line.amount().signum()>0;
    }
    private Map<String,Object> lineCandidate(FinanceModels.Line line) {
        var v=new LinkedHashMap<String,Object>();v.put("id",line.id());v.put("categoryId",line.categoryId());v.put("description",line.description());
        v.put("quantity",line.quantity()==null?null:line.quantity().toPlainString());v.put("unitPrice",line.unitPrice()==null?null:line.unitPrice().toPlainString());v.put("amount",line.amount()==null?null:line.amount().toPlainString());v.put("departmentId",line.departmentId());v.put("allocationMonth",line.allocationMonth());v.put("invoiceIds",line.invoiceIds());v.put("projectKind",line.projectKind());v.put("projectId",line.projectId());return v;
    }
    private List<String> configurationIssues(Map<String,Object> config) {
        if(config.isEmpty()) return List.of("所属组织尚未配置法人、税号与审批；仍可保存草稿，提交前请联系财务配置");
        var issues=new ArrayList<String>();
        if(!Boolean.TRUE.equals(config.get("enabled"))) issues.add("本组织报销尚未启用");
        for(String k:List.of("manager_account_id","finance_account_id","director_account_id","leader_account_id","cashier_account_id")) if(config.get(k)==null) issues.add("缺少配置："+k);
        return issues;
    }
    private List<Map<String,Object>> lineItems(TenantPrincipal p,UUID id) {
        String projectColumns=new FinanceProjectDimensions(jdbc,access).installed()?",project_kind as \"projectKind\",project_id as \"projectId\",project_key as \"projectKey\",project_snapshot as \"projectSnapshot\",financial_category_code as \"categoryCode\"":"";
        return jdbc.queryForList("select id,category_id as \"categoryId\",category_name as \"categoryName\",treatment,debit_account as \"debitAccount\",description,quantity::text,unit_price::text as \"unitPrice\",amount::text,department_id as \"departmentId\",allocation_month as \"allocationMonth\",invoice_ids as \"invoiceIds\""+projectColumns+" from finance_claim_line where tenant_id=:tenantId and claim_id=:id order by sequence_no",base(p).addValue("id",id)).stream().map(line->{
            try {line.put("invoiceIds",mapper.readValue(line.get("invoiceIds").toString(),List.class));if(line.containsKey("projectSnapshot"))line.put("projectSnapshot",mapper.readValue(line.get("projectSnapshot").toString(),Map.class));return line;} catch(Exception e){throw new IllegalStateException("费用明细读取失败",e);}
        }).toList();
    }
    private void verifyClaimProject(TenantPrincipal p,Map<String,Object> claim,Map<String,Object> line){var projects=new FinanceProjectDimensions(jdbc,access);if(!projects.installed()||!Set.of("CONTRACT","CIP").contains(Objects.toString(line.get("projectKind"))))return;var current=projects.freeze(p,uuid(claim,"hotel_id"),uuid(claim,"company_id"),new FinanceProjectDimensions.ProjectRef(line.get("projectKind").toString(),UUID.fromString(line.get("projectId").toString())));var frozen=(Map<?,?>)line.get("projectSnapshot");if(frozen==null||!Objects.equals(current.get("sourceVersion"),frozen.get("sourceVersion")))throw conflict("项目锚点真实版本已变化，请退回核对原项目并重新保存，不覆盖原审核快照");}
    public Map<String,Object> todos(int page) {
        return todos(page,null);
    }
    public Map<String,Object> todos(int page,String kind) {
        var p=prepare("finance.submit","finance.review","finance.read","finance.pay","finance.configure","finance.tax","finance.accounting.read","finance.accounting.write","finance.accounting.review","finance.accounting.post","finance.accounting.close","finance.archive.read","finance.archive.grant");if(page<0||page>10000) throw bad("页码无效");
        if(kind!=null&&!Set.of("review","payment","returned","receipt","work").contains(kind))throw bad("待办类别无效");
        String current=currentClaimReviewerSql();
        String review="(:review and "+SCOPE+" and c.applicant_id<>:actorId and :actorId="+current+")";
        String payment="(:pay and "+SCOPE+" and c.applicant_id<>:actorId and "+CASHIER+" and c.status='APPROVED' and coalesce((select sum(fp.amount) from finance_payment fp where fp.tenant_id=c.tenant_id and fp.claim_id=c.id),0)<c.approved_amount)";
        String returned="(:own and c.applicant_id=:actorId and c.assignment_id in (:assignments) and c.status='RETURNED')";
        String receipt="(:pay and "+SCOPE+" and c.applicant_id<>:actorId and "+CASHIER+" and c.status='APPROVED' and exists(select 1 from finance_payment fp where fp.tenant_id=c.tenant_id and fp.claim_id=c.id and not exists(select 1 from finance_payment_receipt r where r.tenant_id=fp.tenant_id and r.payment_id=fp.id)))";
        String scope="(:tenantScope or d.hotel_id in (:scopeIds)) and (d.type<>'PAYROLL' or :payroll) and (d.type<>'TAX_TASK' or :tax)";
        String opCurrent=new FinanceDelegationControl(jdbc).currentSql("OPERATION","d","d.review_index","cast(d.approval_route->>d.review_index as uuid)");
        String opReview="(:review and "+scope+" and d.applicant_id<>:actorId and d.status='REVIEW' and "+opCurrent+"=:actorId)";
        String opPayment="(:pay and "+scope+" and d.applicant_id<>:actorId and d.status='APPROVED' and d.cashier_account_id=:actorId and d.type in ('CONTRACT','PAYABLE','LOAN','PREPAYMENT','ASSET','PAYROLL','TAX_TASK','INTERCOMPANY') and d.recognized_source_document_id is null and (d.type<>'INTERCOMPANY' or exists(select 1 from fo_action cs where cs.tenant_id=d.tenant_id and cs.document_id=d.id and cs.action='COUNTERSIGN')) and d.amount>(select coalesce(sum(case when a.action='PAYMENT' then a.amount when a.action='REFUND' then -a.amount else 0 end),0) from fo_action a where a.tenant_id=d.tenant_id and a.document_id=d.id)+(select coalesce(sum(w.amount),0) from fo_action w where w.tenant_id=d.tenant_id and w.related_document_id=d.id and w.action='WRITEOFF'))";
        String opReceipt="(:pay and "+scope+" and d.applicant_id<>:actorId and d.status='APPROVED' and d.cashier_account_id=:actorId and exists(select 1 from fo_action cash where cash.tenant_id=d.tenant_id and cash.document_id=d.id and cash.action in ('PAYMENT','RECEIPT','REFUND','REPAYMENT') and not exists(select 1 from fo_attachment fa where fa.tenant_id=cash.tenant_id and fa.document_id=d.id and fa.action_id=cash.id and fa.kind='PAYMENT')))";
        String opReturned="(:own and "+scope+" and d.applicant_id=:actorId and d.status='RETURNED')";
        boolean controlled=new FinanceControlGuard(jdbc).installed();
        if(new FinanceControlGuard(jdbc).table("fx_cash_correction")){payment=payment.replace("coalesce((select sum(fp.amount) from finance_payment fp where fp.tenant_id=c.tenant_id and fp.claim_id=c.id),0)","fx_claim_paid_amount(c.tenant_id,c.id,'9999-12-31'::date)");receipt=receipt.replace("and not exists(select 1 from finance_payment_receipt","and fx_cash_effective_amount(fp.tenant_id,'CLAIM',fp.id,fp.amount,'9999-12-31'::date)>0 and not exists(select 1 from finance_payment_receipt");opPayment=opPayment.replace("(select coalesce(sum(case when a.action='PAYMENT' then a.amount when a.action='REFUND' then -a.amount else 0 end),0) from fo_action a where a.tenant_id=d.tenant_id and a.document_id=d.id)","(fx_operation_action_amount(d.tenant_id,d.id,'PAYMENT','9999-12-31'::date)-fx_operation_action_amount(d.tenant_id,d.id,'REFUND','9999-12-31'::date))");opReceipt=opReceipt.replace("and cash.action in","and fx_cash_effective_amount(cash.tenant_id,'OPERATION',cash.id,cash.amount,'9999-12-31'::date)>0 and cash.action in");}
        if(controlled)receipt=receipt.replace("and not exists(select 1 from finance_payment_receipt", "and not exists(select 1 from fc_claim_loan_clear lc where lc.tenant_id=fp.tenant_id and lc.payment_id=fp.id) and not exists(select 1 from finance_payment_receipt");
        if(controlled){payment+=" and not exists(select 1 from fc_cash_instruction ci where ci.tenant_id=c.tenant_id and ci.source_kind='CLAIM' and ci.source_id=c.id and ci.status in ('DRAFT','APPROVED') and "+FinanceCashNodes.sourceCurrentSql("ci")+")";opPayment+=" and not exists(select 1 from fc_cash_instruction ci where ci.tenant_id=d.tenant_id and ci.source_kind='OPERATION' and ci.source_id=d.id and ci.status in ('DRAFT','APPROVED') and "+FinanceCashNodes.sourceCurrentSql("ci")+")";}
        String cte="with todos as (select c.id,case when "+review+" then 'review' when "+payment+" then 'payment' else 'returned' end as kind,c.title,c.hotel_name,c.applicant_name,coalesce(c.approved_amount,c.requested_amount) as amount,c.status,null::date as due_at,coalesce(c.submitted_at,c.created_at) as waiting_since,c.mismatch,c.version,'CLAIM' as domain,'' as document_type from finance_claim c where c.tenant_id=:tenantId and ("+review+" or "+payment+" or "+returned+")"
            +" union all select c.id,'receipt',c.title,c.hotel_name,c.applicant_name,c.approved_amount,c.status,null::date,c.updated_at,false,c.version,'CLAIM','' from finance_claim c where c.tenant_id=:tenantId and "+receipt
            +" union all select d.id,case when "+opReview+" then 'review' when "+opPayment+" then 'payment' else 'returned' end,d.title,h.name,u.display_name,d.amount,d.status,d.due_date,d.created_at,false,d.version,'OPERATION',d.type from fo_document d join org_unit h on h.tenant_id=d.tenant_id and h.id=d.hotel_id join user_account u on u.tenant_id=d.tenant_id and u.id=d.applicant_id where d.tenant_id=:tenantId and ("+opReview+" or "+opPayment+" or "+opReturned+")";
        cte+=" union all select d.id,'receipt',d.title,h.name,u.display_name,d.amount,d.status,d.due_date,d.created_at,false,d.version,'OPERATION',d.type from fo_document d join org_unit h on h.tenant_id=d.tenant_id and h.id=d.hotel_id join user_account u on u.tenant_id=d.tenant_id and u.id=d.applicant_id where d.tenant_id=:tenantId and "+opReceipt+") ";
        if(controlled)cte=cte.substring(0,cte.length()-2)+" union all select ci.id,case when not "+FinanceCashNodes.sourceCurrentSql("ci")+" then 'work' when ci.status='DRAFT' then 'review' else 'payment' end,'受控资金指令',h.name,u.display_name,ci.amount,ci.status,null::date,ci.created_at,false,ci.version,'CASH_INSTRUCTION',case when "+FinanceCashNodes.sourceCurrentSql("ci")+" then ci.action else 'STALE' end from fc_cash_instruction ci join org_unit h on h.tenant_id=ci.tenant_id and h.id=ci.hotel_id join user_account u on u.tenant_id=ci.tenant_id and u.id=ci.applicant_id where ci.tenant_id=:tenantId and (:tenantScope or ci.hotel_id in (:scopeIds)) and ci.status in ('DRAFT','APPROVED') and (("+FinanceCashNodes.sourceCurrentSql("ci")+" and ((:review and ci.status='DRAFT' and ci.reviewer_id=:actorId) or (:pay and ci.status='APPROVED' and ci.cashier_id=:actorId))) or (not "+FinanceCashNodes.sourceCurrentSql("ci")+" and (:review or :pay) and :actorId in (ci.created_by,ci.reviewer_id,ci.cashier_id))) and (ci.source_kind<>'OPERATION' or exists(select 1 from fo_document d where d.tenant_id=ci.tenant_id and d.id=ci.source_id and (d.type<>'PAYROLL' or :payroll) and (d.type<>'TAX_TASK' or :tax)))) ";
        cte=cte.substring(0,cte.length()-2)+FinancePendingV2.unionSql(jdbc,p)+") ";
        var b=base(p).addValue("assignments",nonempty(p.assignmentIds())).addValue("offset",page*50).addValue("payroll",has(p,"finance.payroll")).addValue("tax",has(p,"finance.tax")).addValue("kind",kind);
        var counts=jdbc.queryForMap(cte+"select count(*) filter(where kind='review') as review,count(*) filter(where kind='payment') as payment,count(*) filter(where kind='returned') as returned,count(*) filter(where kind='receipt') as receipt,count(*) filter(where kind='work') as work,count(*) as total from todos",b);
        var items=jdbc.queryForList(cte+"select id,kind,title,hotel_name as \"hotelName\",applicant_name as \"applicantName\",amount::text,status,due_at as \"dueAt\",waiting_since as \"waitingSince\",mismatch,version,domain,document_type as \"documentType\" from todos where (cast(:kind as text) is null or kind=:kind) order by waiting_since,id,kind limit 50 offset :offset",b);
        for(var item:items)item.put("target",todoTarget(p,item));
        return Map.of("items",items,"total",counts.get(kind==null?"total":kind),"counts",Map.of("review",counts.get("review"),"payment",counts.get("payment"),"returned",counts.get("returned"),"receipt",counts.get("receipt"),"work",counts.get("work")),"page",page);
    }
    private Map<String,Object> todoTarget(TenantPrincipal p,Map<String,Object> item){
        Object id=item.get("id");String type=Objects.toString(item.get("documentType"),"");
        return switch(item.get("domain").toString()){
            case "CASH_INSTRUCTION"->Map.of("tab","control","screen","cash","instructionId",id);
            case "ACCRUAL"->Map.of("tab","accrual","screen","plans","planId",id);
            case "ACCRUAL_POLICY"->{var book=jdbc.queryForObject("select book_id from far_policy where tenant_id=:tenantId and id=:id",base(p).addValue("id",id),UUID.class);yield Map.of("tab","accrual","screen","policies","bookId",book);}
            case "CASH_ERROR"->Map.of("tab","cash-errors","correctionId",id);
            case "ASSET_GAIN"->Map.of("tab","supply","screen","asset-gains","gainId",id);
            case "INPUT_BASIS"->Map.of("tab","tax-control","section","inputs","basisId",id);
            case "TAX_ACCRUAL"->Map.of("tab","tax-control","section","accruals","accrualId",id);
            case "LOAN_OVERDUE"->Map.of("tab","operations","screen","loans","documentId",id);
            case "INSIGHT_ACTION"->Map.of("tab","insight-actions","actionId",id);
            case "DELEGATION"->Map.of("tab","authorizations","delegationId",id);
            case "SUPPLY_INVOICE_RELEASE"->{var receipt=jdbc.queryForObject("select r.receipt_id from fs_invoice_release c join fs_recognition r on r.tenant_id=c.tenant_id and r.id=c.recognition_id where c.tenant_id=:tenantId and c.id=:id",base(p).addValue("id",id),UUID.class);yield Map.of("tab","supply","screen","procurement","receiptId",receipt);}
            case "AR_ADJUSTMENT","AR_RECOVERY","TAX_CALENDAR"->Map.of("tab","business-exceptions","section",item.get("domain").equals("AR_ADJUSTMENT")?"adjustments":item.get("domain").equals("AR_RECOVERY")?"recoveries":"tax-calendar","exceptionId",id);
            case "BUDGET_EXCEPTION"->Map.of("tab","authorizations","exceptionId",id);
            case "CLOSE"->{var table=type.equals("REGISTER")?"fcl_register":"fcl_bank_explanation";var row=jdbc.queryForMap("select book_id,"+(type.equals("REGISTER")?"month":"as_of")+" as day from "+table+" where tenant_id=:tenantId and id=:id",base(p).addValue("id",id));yield Map.of("tab","accounting","screen","periods","bookId",row.get("book_id"),"month",row.get("day").toString().substring(0,7));}
            case "ARCHIVE_REQUEST"->Map.of("tab","archive","screen","requests","requestId",id);
            case "INSIGHT"->Map.of("tab","insights","screen","reports","reportId",id);
            case "FINANCE_BUSINESS"->Map.of("tab","business","screen",switch(type){case "REVENUE"->"revenue";case "PAYROLL"->"payroll";default->"intercompany";},"section",switch(type){case "REVENUE"->"imports";case "PAYROLL"->"batches";default->"transactions";},"businessId",id);
            case "ACCOUNTING"->{var row=jdbc.queryForMap("select book_id,month from fa_voucher where tenant_id=:tenantId and id=:id",base(p).addValue("id",id));yield Map.of("tab","accounting","screen","vouchers","voucherId",id,"bookId",row.get("book_id"),"month",row.get("month").toString().substring(0,7));}
            case "LEDGER_CHANGE"->{var book=jdbc.queryForObject("select book_id from fc_ledger_change where tenant_id=:tenantId and id=:id",base(p).addValue("id",id),UUID.class);yield Map.of("tab","accounting","screen",type.equals("ACCOUNT")?"accounts":"rules","bookId",book);}
            case "OPERATION"->Map.of("tab","operations","screen","documents","type",type,"documentId",id);
            default->Map.of("tab",switch(item.get("kind").toString()){case "review"->"review";case "payment","receipt"->"all";default->"mine";},"id",id);
        };
    }
    private void notifyCurrent(TenantPrincipal p,UUID id) {
        var row=claim(p,id,false);String state=text(row,"status");UUID recipient;String type;
        if(REVIEWING.contains(state)) {recipient=effectiveClaimReviewer(p,row);type="FINANCE_REVIEW_PENDING";}
        else if("APPROVED".equals(state)) {recipient=uuid(row,"cashier_account_id");type="FINANCE_PAYMENT_PENDING";}
        else if("RETURNED".equals(state)) {recipient=uuid(row,"applicant_id");type="FINANCE_RETURNED";}
        else return;
        if(recipient==null) return;
        jdbc.update("insert into notification(tenant_id,recipient_account_id,notification_type,title,content,source_type,source_id,idempotency_key) values(:tenantId,:recipient,:type,:title,:content,'FINANCE',:id,:key) on conflict(tenant_id,recipient_account_id,idempotency_key) do nothing",
            base(p).addValue("recipient",recipient).addValue("type",type).addValue("title","财务待办："+("APPROVED".equals(state)?"待付款":"RETURNED".equals(state)?"退回待补":FinanceWorkflow.label(state,number(row,"workflow_version")))).addValue("content","有一笔报销等待您处理。点击进入中台核对单据及凭证；处理权限以实时状态为准。").addValue("id",id).addValue("key","finance:"+id+":"+state+":"+row.get("version")));
    }
    public Map<String,Object> invoicePool(int page) {
        var p=prepare();if(page<0||page>10000) throw bad("页码无效");var b=base(p).addValue("offset",page*50);
        String filter="i.tenant_id=:tenantId and ((:own and i.owner_id=:actorId) or (:read and (:tenantScope or i.hotel_id in (:scopeIds))))";
        var rows=jdbc.queryForList("select i.*,h.name as hotel_name from finance_invoice_intake i join org_unit h on h.tenant_id=i.tenant_id and h.id=i.hotel_id where "+filter+" order by i.created_at desc,i.id limit 50 offset :offset",b);
        return Map.of("items",rows.stream().map(this::intakeExternal).toList(),"total",jdbc.queryForObject("select count(*) from finance_invoice_intake i where "+filter,b,Long.class),"page",page,"notice","独立收票不占用报销票号；关联后由正式提交事务进行排重。人工确认或文字提取不等于外部验真。");
    }
    public Map<String,Object> createIntake(FinanceModels.Create in) {
        var p=prepare("finance.submit");access.requireActiveAssignment(in.assignmentId());var a=assignment(p,in.assignmentId());
        jdbc.update("insert into finance_invoice_intake(id,tenant_id,owner_id,assignment_id,hotel_id) values(:id,:tenantId,:actorId,:assignment,:hotel) on conflict(id) do nothing",base(p).addValue("id",in.requestId()).addValue("assignment",in.assignmentId()).addValue("hotel",a.get("hotel_id")));
        var intake=loadIntake(p,in.requestId(),false);if(!p.actorId().equals(uuid(intake,"owner_id"))||!in.assignmentId().equals(uuid(intake,"assignment_id"))) throw conflict("收票请求编号已使用");
        return intakeDetail(in.requestId());
    }
    public Map<String,Object> intakeDetail(UUID id) {var p=prepare();return intakeExternal(loadIntake(p,id,false));}
    private Map<String,Object> loadIntake(TenantPrincipal p,UUID id,boolean lock) {
        var rows=jdbc.queryForList("select i.*,h.name as hotel_name from finance_invoice_intake i join org_unit h on h.tenant_id=i.tenant_id and h.id=i.hotel_id where i.tenant_id=:tenantId and i.id=:id"+(lock?" for update of i":""),base(p).addValue("id",id));
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"收票资料不存在");var row=rows.getFirst();
        if((has(p,"finance.submit")&&p.actorId().equals(uuid(row,"owner_id")))||(has(p,"finance.read")&&(p.hasTenantScope()||p.orgScopes().contains(uuid(row,"hotel_id")))))return row;
        throw forbidden();
    }
    private Map<String,Object> intakeExternal(Map<String,Object> row) {
        var out=new LinkedHashMap<String,Object>();out.put("id",row.get("id"));out.put("assignmentId",row.get("assignment_id"));out.put("hotelId",row.get("hotel_id"));out.put("hotelName",row.get("hotel_name"));out.put("invoiceCode",row.get("invoice_code"));out.put("invoiceNumber",row.get("invoice_number"));out.put("amount",row.get("amount")==null?null:decimal(row,"amount").toPlainString());out.put("buyerTaxNumber",row.get("buyer_tax_number"));out.put("confirmed",row.get("confirmed"));out.put("claimId",row.get("linked_claim_id"));out.put("status",row.get("status"));out.put("version",row.get("version"));out.put("verificationStatus","NOT_VERIFIED");out.put("createdAt",row.get("created_at"));
        var original=row.get("original")==null?Map.<String,Object>of():parse(row.get("original"));
        for(String field:List.of("originalName","mediaType","sizeBytes","sha256","extraction","scanStatus"))out.put(field,original.get(field));
        var p=access.principal();
        if(!original.isEmpty())out.put("extraction",localOcrOverlay(p,null,uuid(row,"id"),Objects.toString(original.get("sha256"),""),original.get("extraction") instanceof Map<?,?> ? parse(json(original.get("extraction"))) : Map.of()));
        if(row.get("amount")!=null&&!text(row,"buyer_tax_number").isBlank()) {
            try {String key=FinanceRules.invoiceKey(text(row,"invoice_code"),text(row,"invoice_number"));
                var statuses=jdbc.queryForList("select status from finance_invoice_verification where tenant_id=:tenantId and target_kind='INTAKE' and invoice_id=:id and invoice_snapshot=cast(:snapshot as jsonb) order by created_at desc,id desc limit 1",base(p).addValue("id",row.get("id")).addValue("snapshot",json(Map.of("invoice_key",key,"amount",decimal(row,"amount").toPlainString(),"buyer_tax_number",text(row,"buyer_tax_number")))));
                if(!statuses.isEmpty())out.put("verificationStatus",statuses.getFirst().get("status"));
            }catch(IllegalArgumentException ignored){/* Partial capture is deliberately not a verified invoice. */}
        }
        out.put("canEdit",has(p,"finance.submit")&&p.actorId().equals(uuid(row,"owner_id"))&&p.assignmentIds().contains(uuid(row,"assignment_id"))&&"DRAFT".equals(row.get("status")));return out;
    }
    public Map<String,Object> saveIntake(UUID id,FinanceModels.IntakeDraft in) {
        var p=prepare("finance.submit");var row=loadIntake(p,id,true);requireIntakeOwner(p,row);version(row,in.expectedVersion());
        if(in.confirmed()) {if(in.amount()==null) throw bad("确认发票前请填写金额");FinanceRules.invoiceKey(in.invoiceCode(),in.invoiceNumber());if(in.buyerTaxNumber().isBlank())throw bad("请填写购买方税号");}
        jdbc.update("update finance_invoice_intake set invoice_code=:code,invoice_number=:number,amount=:amount,buyer_tax_number=:tax,confirmed=:confirmed,version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",base(p).addValue("id",id).addValue("code",in.invoiceCode().trim()).addValue("number",in.invoiceNumber().trim()).addValue("amount",in.amount()).addValue("tax",in.buyerTaxNumber().trim()).addValue("confirmed",in.confirmed()));return intakeDetail(id);
    }
    private void requireIntakeOwner(TenantPrincipal p,Map<String,Object> row) {
        if(!p.actorId().equals(uuid(row,"owner_id"))||!"DRAFT".equals(row.get("status")))throw conflict("只有本人未关联的收票资料可修改");access.requireActiveAssignment(uuid(row,"assignment_id"));
    }
    public void authorizeIntakeUpload(UUID id) {var p=prepare("finance.submit");requireIntakeOwner(p,loadIntake(p,id,false));}
    public Map<String,Object> registerIntakeOriginal(UUID id,Map<String,Object> original) {
        var p=prepare("finance.submit");var row=loadIntake(p,id,true);requireIntakeOwner(p,row);
        if(row.get("original")!=null) {var old=parse(row.get("original"));if(old.get("sha256").equals(original.get("sha256")))return old;throw conflict("原件已归档不可覆盖；新票请新建收票资料");}
        jdbc.update("update finance_invoice_intake set original=cast(:original as jsonb),version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",base(p).addValue("id",id).addValue("original",json(original)));
        if("CLEAN".equals(original.get("scanStatus"))&&Set.of("image/jpeg","image/png").contains(original.get("mediaType"))&&original.get("extraction") instanceof Map<?,?>)appendLocalOcr(p,null,id,"INVOICE",Objects.toString(original.get("sha256")),parse(json(original.get("extraction"))));
        audit.record("FINANCE_INVOICE_INTAKE_ORIGINAL_STORED","FINANCE_INVOICE_INTAKE",id,json(Map.of("sha256",original.get("sha256"))));return original;
    }
    public Map<String,Object> intakeOriginal(UUID id) {
        var p=prepare();var row=loadIntake(p,id,false);if(row.get("original")==null)throw conflict("收票资料尚未上传原件");
        audit.record("FINANCE_ORIGINAL_ACCESSED","FINANCE_INVOICE_INTAKE",id,"{}");return parse(row.get("original"));
    }
    public Map<String,Object> recordIntakeRecognition(UUID id,Map<String,Object> extraction){
        var p=prepare("finance.submit");var row=loadIntake(p,id,true);requireIntakeOwner(p,row);
        if(!localOcrResultsInstalled())throw conflict("本地识别结果迁移尚未安装，请先完成受控服务升级；原件仍保留");
        if(row.get("original")==null)throw conflict("收票资料尚未上传原件");var original=parse(row.get("original"));
        if(!original.get("sha256").equals(extraction.get("sourceSha256")))throw conflict("识别结果与已存原件指纹不一致，未采纳候选");
        appendLocalOcr(p,null,id,"INVOICE",Objects.toString(original.get("sha256")),extraction);
        return intakeDetail(id);
    }
    public Map<String,Object> associateIntake(UUID intakeId,FinanceModels.Associate in) {
        var p=prepare("finance.submit");lock(p,"invoice-registry");var intake=loadIntake(p,intakeId,true);
        FinanceInputTaxGuard.assertIntakeReusable(jdbc,p,intakeId);
        var control=new FinanceControlGuard(jdbc);
        if(control.table("fs_invoice_use")&&jdbc.queryForObject("select count(*) from fs_invoice_use where coalesce(to_jsonb(fs_invoice_use)->>'released_at','')='' and tenant_id=:tenantId and invoice_id=:intake",base(p).addValue("intake",intakeId),Integer.class)>0)throw conflict("该发票原件已用于采购入账，不可重复申请报销");
        if(!p.actorId().equals(uuid(intake,"owner_id")))throw forbidden();
        if(in.claimId().equals(uuid(intake,"linked_claim_id")))return detail(in.claimId());
        requireIntakeOwner(p,intake);var target=claim(p,in.claimId(),true);requireOwner(p,target);requireEditable(target);version(target,in.expectedVersion());
        if(!uuid(intake,"hotel_id").equals(uuid(target,"hotel_id")))throw bad("收票资料与报销归属组织不同，不能静默跨公司关联");
        if(!Boolean.TRUE.equals(intake.get("confirmed"))||intake.get("original")==null||intake.get("amount")==null)throw bad("请先上传原件并确认发票号码、金额及税号");
        String key=FinanceRules.invoiceKey(text(intake,"invoice_code"),text(intake,"invoice_number"));
        var b=base(p).addValue("id",in.claimId()).addValue("key",key);
        if(jdbc.queryForObject("select count(*) from finance_invoice where tenant_id=:tenantId and claim_id=:id and invoice_key=:key",b,Integer.class)>0)throw conflict("目标报销已有关联发票，不能重复添加");
        var original=parse(intake.get("original"));UUID attachment=UUID.nameUUIDFromBytes((p.tenantId()+":"+intakeId+":"+in.claimId()+":original").getBytes(StandardCharsets.UTF_8));UUID invoice=UUID.nameUUIDFromBytes((p.tenantId()+":"+intakeId+":"+in.claimId()+":invoice").getBytes(StandardCharsets.UTF_8));
        registerAttachment(in.claimId(),attachment,"INVOICE",original.get("originalName").toString(),original.get("mediaType").toString(),((Number)original.get("sizeBytes")).longValue(),original.get("sha256").toString(),original.get("objectKey").toString(),original.get("scanStatus").toString(),parse(json(original.get("extraction"))));
        jdbc.update("insert into finance_invoice(id,tenant_id,claim_id,attachment_id,invoice_code,invoice_number,invoice_key,amount,buyer_tax_number,confirmed) values(:invoice,:tenantId,:id,:attachment,:code,:number,:key,:amount,:tax,true)",b.addValue("invoice",invoice).addValue("attachment",attachment).addValue("code",FinanceRules.digits(text(intake,"invoice_code"))).addValue("number",FinanceRules.digits(text(intake,"invoice_number"))).addValue("amount",intake.get("amount")).addValue("tax",FinanceRules.taxNumber(text(intake,"buyer_tax_number"))));
        List<Map<String,Object>> candidates;
        try {candidates=target.get("draft_invoice_candidates")==null?new ArrayList<>(jdbc.queryForList("select id,attachment_id,invoice_code,invoice_number,amount,buyer_tax_number,confirmed from finance_invoice where tenant_id=:tenantId and claim_id=:id and id<>:invoice",b)):mapper.readValue(target.get("draft_invoice_candidates").toString(),new TypeReference<ArrayList<Map<String,Object>>>(){});}
        catch(Exception e){throw new IllegalStateException("报销草稿读取失败",e);}
        candidates.add(new LinkedHashMap<>(Map.of("id",invoice,"attachment_id",attachment,"invoice_code",FinanceRules.digits(text(intake,"invoice_code")),"invoice_number",FinanceRules.digits(text(intake,"invoice_number")),"amount",decimal(intake,"amount").toPlainString(),"buyer_tax_number",FinanceRules.taxNumber(text(intake,"buyer_tax_number")),"confirmed",true)));
        candidates.forEach(FinanceService::moneyStrings);
        jdbc.update("update finance_claim set draft_invoice_candidates=cast(:candidates as jsonb),invoice_amount=(select coalesce(sum(amount),0) from finance_invoice where tenant_id=:tenantId and claim_id=:id),mismatch=true,version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",b.addValue("candidates",json(candidates)));
        jdbc.update("update finance_invoice_intake set linked_claim_id=:id,status='LINKED',version=version+1,updated_at=now() where tenant_id=:tenantId and id=:intake",b.addValue("intake",intakeId));
        audit.record("FINANCE_INVOICE_INTAKE_ASSOCIATED","FINANCE_CLAIM",in.claimId(),json(Map.of("intakeId",intakeId,"invoiceId",invoice,"originalSha256",original.get("sha256"))));return detail(in.claimId());
    }
    public Map<String,Object> verifyInvoice(UUID invoiceId,FinanceModels.Verification in) {
        var p=prepare("finance.review");var b=base(p).addValue("invoice",invoiceId);
        var intakes=jdbc.queryForList("select * from finance_invoice_intake where tenant_id=:tenantId and id=:invoice",b);
        if(!intakes.isEmpty()) {
            var intake=intakes.getFirst();if(p.actorId().equals(uuid(intake,"owner_id"))||!(p.hasTenantScope()||p.orgScopes().contains(uuid(intake,"hotel_id"))))throw forbidden();
            if(intake.get("amount")==null||text(intake,"buyer_tax_number").isBlank())throw bad("资料尚不完整，不能人工确认");
            String key=FinanceRules.invoiceKey(text(intake,"invoice_code"),text(intake,"invoice_number"));
            var snapshot=Map.of("invoice_key",key,"amount",decimal(intake,"amount").toPlainString(),"buyer_tax_number",text(intake,"buyer_tax_number"));
            jdbc.update("insert into finance_invoice_verification(tenant_id,invoice_id,target_kind,status,provider,note,actor_id,invoice_snapshot) values(:tenantId,:invoice,'INTAKE','MANUAL_CONFIRMED','MANUAL',:note,:actorId,cast(:snapshot as jsonb))",b.addValue("note",in.note().trim()).addValue("snapshot",json(snapshot)));
            audit.record("FINANCE_INTAKE_MANUALLY_CONFIRMED","FINANCE_INVOICE_INTAKE",invoiceId,json(snapshot));return Map.of("status","MANUAL_CONFIRMED","notice","人工核对不等于税务外部验真");
        }
        var rows=jdbc.queryForList("select claim_id from finance_invoice where tenant_id=:tenantId and id=:invoice",b);
        if(rows.isEmpty()) throw bad("发票不存在");UUID id=uuid(rows.getFirst(),"claim_id");var row=claim(p,id,true);requireReadable(p,row);
        if(!inScope(p,row)||p.actorId().equals(uuid(row,"applicant_id"))) throw forbidden();
        var invoice=jdbc.queryForMap("select invoice_key,amount::text,buyer_tax_number from finance_invoice where tenant_id=:tenantId and id=:invoice",b);
        jdbc.update("insert into finance_invoice_verification(tenant_id,invoice_id,status,provider,note,actor_id,invoice_snapshot) values(:tenantId,:invoice,'MANUAL_CONFIRMED','MANUAL',:note,:actorId,cast(:snapshot as jsonb))",b.addValue("note",in.note().trim()).addValue("snapshot",json(invoice)));
        audit.record("FINANCE_INVOICE_MANUALLY_CONFIRMED","FINANCE_CLAIM",id,json(Map.of("invoiceId",invoiceId)));
        return Map.of("status","MANUAL_CONFIRMED","notice","已记录人工核对，不代表税务外部验真");
    }
    public Map<String,Object> linkPaymentReceipt(UUID id,UUID paymentId,FinanceModels.Receipt in) {
        var p=prepare("finance.pay");var row=claim(p,id,true);
        if(!isCashier(p,row)||!"APPROVED".equals(row.get("status"))||p.actorId().equals(uuid(row,"applicant_id"))) throw forbidden();
        var b=base(p).addValue("id",id).addValue("payment",paymentId).addValue("attachment",in.attachmentId());
        if(jdbc.queryForObject("select count(*) from finance_payment where tenant_id=:tenantId and claim_id=:id and id=:payment",b,Integer.class)!=1) throw bad("付款记录不存在或不属于本单");
        if(new FinanceControlGuard(jdbc).installed()&&jdbc.queryForObject("select count(*) from fc_claim_loan_clear where tenant_id=:tenantId and payment_id=:payment",b,Integer.class)>0)throw bad("借款核销是非资金清偿，不适用银行付款回单归档");
        if(jdbc.queryForObject("select count(*) from finance_attachment where tenant_id=:tenantId and claim_id=:id and id=:attachment and kind='PAYMENT' and retired_at is null",b,Integer.class)!=1) throw bad("请选择本单已上传的付款回单");
        jdbc.update("insert into finance_payment_receipt(tenant_id,payment_id,attachment_id) values(:tenantId,:payment,:attachment) on conflict do nothing",b);
        audit.record("FINANCE_PAYMENT_RECEIPT_ARCHIVED","FINANCE_CLAIM",id,json(Map.of("paymentId",paymentId,"attachmentId",in.attachmentId())));return detail(id);
    }
    private Map<String,Object> storeConfig(TenantPrincipal p,UUID hotel) {
        var rows=jdbc.queryForList("select f.*,c.name as company_name,c.tax_number from finance_store_config f join finance_company c on c.tenant_id=f.tenant_id and c.id=f.company_id where f.tenant_id=:tenantId and f.hotel_id=:hotel",base(p).addValue("hotel",hotel));
        if(rows.isEmpty()) throw conflict("所属组织尚未配置公司抬头和审批流程，请联系管理员");return rows.getFirst();
    }
    private Map<String,Object> requireCategory(TenantPrincipal p,UUID hotel,UUID category) {
        var rows=jdbc.queryForList("select * from finance_category where tenant_id=:tenantId and hotel_id=:hotel and id=:id and enabled",base(p).addValue("hotel",hotel).addValue("id",category));
        if(rows.isEmpty()) throw bad("费用类别不属于本店或已停用");return rows.getFirst();
    }
    private void requireHotel(TenantPrincipal p,UUID hotel) {
        if(jdbc.queryForObject("select count(*) from org_unit where tenant_id=:tenantId and id=:hotel and unit_type in ('HOTEL','GROUP') and status='ACTIVE'",base(p).addValue("hotel",hotel),Integer.class)!=1) throw bad("门店或总部无效");
    }
    private Map<String,Object> claim(TenantPrincipal p,UUID id,boolean lock) {
        var rows=jdbc.queryForList("select * from finance_claim where tenant_id=:tenantId and id=:id"+(lock?" for update":""),base(p).addValue("id",id));
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"报销单不存在");return rows.getFirst();
    }
    private void requireOwner(TenantPrincipal p,Map<String,Object> row) {
        if(!p.actorId().equals(uuid(row,"applicant_id"))) throw forbidden();access.requireActiveAssignment(uuid(row,"assignment_id"));
    }
    private void requireReadable(TenantPrincipal p,Map<String,Object> row) {
        if(has(p,"finance.submit")&&p.actorId().equals(uuid(row,"applicant_id"))) return;
        if(isReviewer(p,row))return;
        if(inScope(p,row)&&has(p,"finance.review")&&new FinanceDelegationControl(jdbc).installed()&&jdbc.queryForObject("select count(*) from fd_route r where r.tenant_id=:tenantId and r.source_kind='CLAIM' and r.source_id=:id and fd_current_reviewer(r.tenant_id,r.source_kind,r.source_id,r.node,r.original_id)=:actorId",base(p).addValue("id",row.get("id")),Integer.class)>0)return;
        if(inScope(p,row)&&(has(p,"finance.read")||(isCashier(p,row)&&"APPROVED".equals(row.get("status")))
            ||(has(p,"finance.review")&&Arrays.asList(uuid(row,"manager_account_id"),uuid(row,"finance_account_id"),uuid(row,"director_account_id"),uuid(row,"leader_account_id")).contains(p.actorId())))) return;
        throw forbidden();
    }
    private boolean isReviewer(TenantPrincipal p,Map<String,Object> row) {
        if(!has(p,"finance.review")||!inScope(p,row)||p.actorId().equals(uuid(row,"applicant_id"))) return false;
        String column=REVIEWING.contains(row.get("status"))?FinanceWorkflow.accountColumn(text(row,"status")):null;
        return column!=null&&p.actorId().equals(effectiveClaimReviewer(p,row));
    }
    private UUID effectiveClaimReviewer(TenantPrincipal p,Map<String,Object> row){String state=text(row,"status");int index=List.of("MANAGER_REVIEW","FINANCE_REVIEW","DIRECTOR_REVIEW","LEADER_REVIEW").indexOf(state);if(index<0)return null;return new FinanceDelegationControl(jdbc).current(p,"CLAIM",uuid(row,"id"),index,uuid(row,FinanceWorkflow.accountColumn(state)));}
    private String currentClaimReviewerSql(){String original="case c.status when 'MANAGER_REVIEW' then c.manager_account_id when 'FINANCE_REVIEW' then c.finance_account_id when 'DIRECTOR_REVIEW' then c.director_account_id when 'LEADER_REVIEW' then c.leader_account_id end";return new FinanceDelegationControl(jdbc).currentSql("CLAIM","c","case c.status when 'MANAGER_REVIEW' then 0 when 'FINANCE_REVIEW' then 1 when 'DIRECTOR_REVIEW' then 2 when 'LEADER_REVIEW' then 3 else -1 end",original);}
    private boolean isCashier(TenantPrincipal p,Map<String,Object> row) {
        if(!has(p,"finance.pay")||!inScope(p,row))return false;
        if(p.actorId().equals(uuid(row,"cashier_account_id")))return true;
        return number(row,"workflow_version")==1&&jdbc.queryForObject("select count(*) from finance_store_config where tenant_id=:tenantId and hotel_id=:hotel and enabled and cashier_account_id=:actorId",base(p).addValue("hotel",row.get("hotel_id")),Integer.class)==1;
    }
    private boolean inScope(TenantPrincipal p,Map<String,Object> row) {return p.hasTenantScope()||p.orgScopes().contains(uuid(row,"hotel_id"));}
    private void requireEditable(Map<String,Object> row) {if(!EDITABLE.contains(row.get("status"))) throw conflict("单据已提交或结束，不能覆盖原始内容");}
    private void requireEnabled(Map<String,Object> config) {
        if(!Boolean.TRUE.equals(config.get("enabled"))) throw conflict("所属门店报销尚未启用，请联系管理员完成配置");
        if(number(config,"workflow_version")!=2||Arrays.asList("manager_account_id","finance_account_id","director_account_id","leader_account_id","cashier_account_id").stream().anyMatch(k->config.get(k)==null))
            throw conflict("审核与出纳账号未配置完整，请补全四段审批流程后启用；不能自动跳过缺失节点");
    }
    private List<Map<String,Object>> buildRoute(TenantPrincipal p,Map<String,Object> config,boolean skipSelf) {
        int version=number(config,"workflow_version");var stages=new ArrayList<>(FinanceWorkflow.stages(version));stages.add("PAYMENT");
        var ids=stages.stream().map(s->uuid(config,FinanceWorkflow.accountColumn(s))).filter(Objects::nonNull).toList();
        var names=new HashMap<UUID,String>();
        for(var account:jdbc.queryForList("select id,display_name from user_account where tenant_id=:tenantId and id in (:ids)",base(p).addValue("ids",nonempty(ids)))) names.put(uuid(account,"id"),text(account,"display_name"));
        var route=new ArrayList<Map<String,Object>>();
        for(String stage:stages) {
            UUID id=uuid(config,FinanceWorkflow.accountColumn(stage));var item=new LinkedHashMap<String,Object>();
            item.put("stage",stage);item.put("label",FinanceWorkflow.label(stage,version));item.put("accountId",id);
            item.put("accountName",id==null?"未指定":names.getOrDefault(id,"账号不可用"));item.put("skipped",skipSelf&&"MANAGER_REVIEW".equals(stage));route.add(item);
        }
        return route;
    }
    private List<Map<String,Object>> readRoute(TenantPrincipal p,Map<String,Object> row) {
        try {
            var route=mapper.readValue(row.get("approval_route").toString(),new TypeReference<List<Map<String,Object>>>(){});
            return route.isEmpty()?buildRoute(p,row,false):route;
        } catch(Exception e) {throw new IllegalStateException("审批流程快照读取失败",e);}
    }
    private void version(Map<String,Object> row,int expected) {if(number(row,"version")!=expected) throw conflict("单据已在其他页面更新，请重新打开；本机草稿不会自动覆盖新版本");}
    private void release(TenantPrincipal p,UUID id) {
        var b=base(p).addValue("id",id);jdbc.update("delete from finance_invoice_reservation where tenant_id=:tenantId and claim_id=:id",b);jdbc.update("delete from finance_evidence_reservation where tenant_id=:tenantId and claim_id=:id",b);
    }
    private void lock(TenantPrincipal p,String key) {jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(:key,69))",Map.of("key",p.tenantId()+":"+key));}
    private MapSqlParameterSource period(TenantPrincipal p,String value,UUID hotel) {
        YearMonth month;try {month=YearMonth.parse(value);} catch(Exception e) {throw bad("月份格式应为 YYYY-MM");}
        if(month.getYear()<2000||month.isAfter(YearMonth.from(today(p)))) throw bad("月份超出可查询范围");
        if(hotel!=null) access.requireOrgScope(hotel);
        return base(p).addValue("hotel",hotel).addValue("start",month.atDay(1)).addValue("end",month.plusMonths(1).atDay(1));
    }
    private TenantPrincipal prepare(String... permissions) {
        if(permissions.length>0) access.requireAnyPermission(permissions);
        else access.requireAnyPermission("finance.submit","finance.review","finance.read","finance.pay","finance.configure","finance.accounting.read","finance.accounting.review","finance.accounting.post","finance.archive.grant");
        var p=access.principal();database.apply(p.tenantId());return p;
    }
    private MapSqlParameterSource base(TenantPrincipal p) {
        return new MapSqlParameterSource("tenantId",p.tenantId()).addValue("actorId",p.actorId()).addValue("tenantScope",p.hasTenantScope()).addValue("scopeIds",nonempty(p.orgScopes()))
            .addValue("own",has(p,"finance.submit")).addValue("review",has(p,"finance.review")).addValue("read",has(p,"finance.read")).addValue("pay",has(p,"finance.pay"));
    }
    private static Collection<UUID> nonempty(Collection<UUID> values) {return values.isEmpty()?List.of(EMPTY_UUID):values;}
    private boolean has(TenantPrincipal p,String permission) {return p.hasPermission(permission)||p.hasPermission("*");}
    private LocalDate today(TenantPrincipal p) {return jdbc.queryForObject("select (clock_timestamp() at time zone timezone)::date from tenant where id=:tenantId",base(p),LocalDate.class);}
    private static UUID uuid(Map<String,Object> row,String key) {var value=row.get(key);return value==null?null:UUID.fromString(value.toString());}
    private static String text(Map<String,Object> row,String key) {return Objects.toString(row.get(key),"");}
    private static int number(Map<String,Object> row,String key) {return ((Number)row.get(key)).intValue();}
    private static BigDecimal decimal(Map<String,Object> row,String key) {return new BigDecimal(row.get(key).toString());}
    private static void moneyStrings(Map<String,Object> row) {row.replaceAll((key,value)->value instanceof BigDecimal amount?amount.toPlainString():value);}
    private Map<String,Object> parse(Object value) {try{return mapper.readValue(value.toString(),new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("财务记录读取失败",e);}}
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    public static String hash(String value) {return hash(value.getBytes(StandardCharsets.UTF_8));}
    public static String hash(byte[] value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String csvRows(List<String> headers,List<Map<String,Object>> rows,List<String> keys) {
        var out=new StringBuilder(String.join(",",headers.stream().map(FinanceRules::csv).toList())).append("\r\n");
        for(var row:rows) out.append(String.join(",",keys.stream().map(k->FinanceRules.csv(row.get(k))).toList())).append("\r\n");return out.toString();
    }
    private static ResponseStatusException bad(String message) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private static ResponseStatusException forbidden() {return new ResponseStatusException(HttpStatus.FORBIDDEN,"无权操作此报销，请核对当前岗位、门店范围和审核阶段");}
}
