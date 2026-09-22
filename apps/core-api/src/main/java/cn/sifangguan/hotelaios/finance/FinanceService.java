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
    private static final Set<String> EDITABLE=Set.of("DRAFT","RETURNED");
    private static final Set<String> REVIEWING=Set.of("MANAGER_REVIEW","FINANCE_REVIEW","LEADER_REVIEW");
    private static final UUID EMPTY_UUID=new UUID(0,0);
    private static final String SCOPE="(:tenantScope or c.hotel_id in (:scopeIds))";
    private static final String VISIBLE="((:own and c.applicant_id=:actorId) or ("+SCOPE+" and (:read or (:pay and c.status='APPROVED') or (:review and :actorId in (c.manager_account_id,c.finance_account_id,c.leader_account_id)))))";

    public FinanceService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext database,AccessPolicy access,ObjectMapper mapper,AuditWriter audit) {
        this.jdbc=jdbc;this.database=database;this.access=access;this.mapper=mapper;this.audit=audit;
    }
    public Map<String,Object> context() {
        var p=prepare();var b=base(p);
        var assignments=jdbc.queryForList("""
            select a.id as assignment_id,e.name as employee_name,h.id as hotel_id,h.name as hotel_name,
              f.enabled,f.version as config_version,co.name as company_name,co.tax_number as company_tax_number
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
        var categories=jdbc.queryForList("select id,hotel_id,name,treatment,keywords,debit_account,enabled,version from finance_category where tenant_id=:tenantId and hotel_id in (:hotels) order by name,id",b.addValue("hotels",nonempty(categoryHotels)));
        return Map.of("assignments",assignments,"hotels",hotels,"categories",categories,"today",today(p),
            "recognitionMode","PDF_TEXT_MANUAL_CONFIRMATION","approvalStages",List.of("门店负责人","财务","集团领导"));
    }
    public Map<String,Object> configuration() {
        var p=prepare("finance.configure");var b=base(p);
        return Map.of("stores",jdbc.queryForList("""
            select c.*,h.id as hotel_id,h.name as hotel_name,co.name as company_name,co.tax_number
            from org_unit h left join finance_store_config c on c.tenant_id=h.tenant_id and c.hotel_id=h.id
            left join finance_company co on co.tenant_id=c.tenant_id and co.id=c.company_id
            where h.tenant_id=:tenantId and h.status='ACTIVE' and h.unit_type in ('HOTEL','GROUP')
              and (:tenantScope or h.id in (:scopeIds)) order by h.name
            """,b),"accounts",jdbc.queryForList("""
            select distinct u.id,u.display_name from user_account u
            where u.tenant_id=:tenantId and u.status='ACTIVE' and (:tenantScope or exists (
             select 1 from employee e join employee_position_assignment a on a.tenant_id=e.tenant_id and a.employee_id=e.id
              where e.tenant_id=u.tenant_id and e.account_id=u.id and a.status='ACTIVE' and a.org_unit_id in (:scopeIds)))
            order by u.display_name,u.id
            """,b));
    }
    public Map<String,Object> configure(UUID hotel,FinanceModels.StoreConfig in) {
        var p=prepare("finance.configure");access.requireOrgScope(hotel);requireHotel(p,hotel);
        var accounts=new HashSet<>(List.of(in.managerAccountId(),in.financeAccountId(),in.leaderAccountId()));
        if(accounts.size()!=3) throw bad("三段审核必须配置不同账号");
        var b=base(p).addValue("hotel",hotel).addValue("accounts",accounts);
        if(jdbc.queryForObject("select count(*) from user_account where tenant_id=:tenantId and id in (:accounts) and status='ACTIVE'",b,Integer.class)!=3) throw bad("审核账号不存在或已停用");
        lock(p,"config:"+hotel);
        var existing=jdbc.queryForList("select version from finance_store_config where tenant_id=:tenantId and hotel_id=:hotel for update",b);
        if((existing.isEmpty()?-1:number(existing.getFirst(),"version"))!=in.expectedVersion()) throw conflict("门店财务配置已变化，请刷新再保存");
        b.addValue("name",in.companyName().trim()).addValue("tax",FinanceRules.taxNumber(in.taxNumber()));
        jdbc.update("insert into finance_company(tenant_id,name,tax_number) values(:tenantId,:name,:tax) on conflict(tenant_id,tax_number) do nothing",b);
        var company=jdbc.queryForMap("select id,name from finance_company where tenant_id=:tenantId and tax_number=:tax",b);
        if(!company.get("name").equals(in.companyName().trim())) throw bad("同一税号已登记为另一公司名称，请核对；不能静默改写既有法人档案");
        b.addValue("company",company.get("id")).addValue("manager",in.managerAccountId()).addValue("finance",in.financeAccountId())
            .addValue("leader",in.leaderAccountId()).addValue("payable",in.payableAccount().trim()).addValue("enabled",in.enabled());
        jdbc.update("""
            insert into finance_store_config(tenant_id,hotel_id,company_id,manager_account_id,finance_account_id,leader_account_id,payable_account,enabled)
            values(:tenantId,:hotel,:company,:manager,:finance,:leader,:payable,:enabled)
            on conflict(tenant_id,hotel_id) do update set company_id=excluded.company_id,manager_account_id=excluded.manager_account_id,
             finance_account_id=excluded.finance_account_id,leader_account_id=excluded.leader_account_id,payable_account=excluded.payable_account,
             enabled=excluded.enabled,version=finance_store_config.version+1,updated_at=now()
            """,b);
        audit.record("FINANCE_STORE_CONFIGURED","ORG_UNIT",hotel,"{}");
        return storeConfig(p,hotel);
    }
    public Map<String,Object> category(UUID hotel,UUID id,FinanceModels.Category in) {
        var p=prepare("finance.configure");access.requireOrgScope(hotel);requireHotel(p,hotel);lock(p,"category:"+hotel);
        var b=base(p).addValue("hotel",hotel).addValue("id",id);
        var old=jdbc.queryForList("select version from finance_category where tenant_id=:tenantId and hotel_id=:hotel and id=:id for update",b);
        if((old.isEmpty()?-1:number(old.getFirst(),"version"))!=in.expectedVersion()) throw conflict("费用分类已变化，请刷新");
        b.addValue("name",in.name().trim()).addValue("treatment",in.treatment()).addValue("keywords",in.keywords().trim()).addValue("debit",in.debitAccount().trim()).addValue("enabled",in.enabled());
        if(old.isEmpty()) jdbc.update("insert into finance_category(id,tenant_id,hotel_id,name,treatment,keywords,debit_account,enabled) values(:id,:tenantId,:hotel,:name,:treatment,:keywords,:debit,:enabled)",b);
        else jdbc.update("update finance_category set name=:name,treatment=:treatment,keywords=:keywords,debit_account=:debit,enabled=:enabled,version=version+1 where tenant_id=:tenantId and hotel_id=:hotel and id=:id",b);
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
            case "review" -> {access.requirePermission("finance.review");yield SCOPE+" and :actorId in (c.manager_account_id,c.finance_account_id,c.leader_account_id) and c.status in ('MANAGER_REVIEW','FINANCE_REVIEW','LEADER_REVIEW')";}
            default -> {access.requireAnyPermission("finance.read","finance.pay");yield VISIBLE;}
        };
        var rows=jdbc.queryForList("select c.* from finance_claim c where c.tenant_id=:tenantId and "+filter+" order by c.updated_at desc,c.id limit 51 offset :offset",base(p).addValue("offset",page*50));
        rows.forEach(FinanceService::moneyStrings);
        return Map.of("items",rows.size()>50?rows.subList(0,50):rows,"hasMore",rows.size()>50,"page",page);
    }
    public Map<String,Object> create(FinanceModels.Create in) {
        var p=prepare("finance.submit");access.requireActiveAssignment(in.assignmentId());
        var b=base(p).addValue("id",in.requestId());
        var prior=jdbc.queryForList("select applicant_id,assignment_id from finance_claim where tenant_id=:tenantId and id=:id",b);
        if(!prior.isEmpty()) {
            if(!p.actorId().equals(uuid(prior.getFirst(),"applicant_id"))||!in.assignmentId().equals(uuid(prior.getFirst(),"assignment_id"))) throw conflict("请求编号已使用");
            return detail(in.requestId());
        }
        var assignment=assignment(p,in.assignmentId());var config=storeConfig(p,uuid(assignment,"hotel_id"));requireEnabled(config);
        b.addValue("assignment",in.assignmentId()).addValue("hotel",assignment.get("hotel_id")).addValue("hotelName",assignment.get("hotel_name"))
            .addValue("company",config.get("company_id")).addValue("companyName",config.get("company_name")).addValue("tax",config.get("tax_number"))
            .addValue("name",assignment.get("employee_name")).addValue("date",today(p)).addValue("manager",config.get("manager_account_id"))
            .addValue("finance",config.get("finance_account_id")).addValue("leader",config.get("leader_account_id")).addValue("configVersion",config.get("version"));
        jdbc.update("""
            insert into finance_claim(id,tenant_id,applicant_id,assignment_id,hotel_id,company_id,applicant_name,hotel_name,company_name,company_tax_number,
                business_date,manager_account_id,finance_account_id,leader_account_id,config_version)
            values(:id,:tenantId,:actorId,:assignment,:hotel,:company,:name,:hotelName,:companyName,:tax,:date,:manager,:finance,:leader,:configVersion)
            on conflict(id) do nothing
            """,b);
        var created=claim(p,in.requestId(),false);
        if(!p.actorId().equals(uuid(created,"applicant_id"))||!in.assignmentId().equals(uuid(created,"assignment_id"))) throw conflict("请求编号已使用");
        audit.record("FINANCE_CLAIM_CREATED","FINANCE_CLAIM",in.requestId(),"{}");return detail(in.requestId());
    }
    public Map<String,Object> detail(UUID id) {
        var p=prepare();var row=claim(p,id,false);requireReadable(p,row);var b=base(p).addValue("id",id);
        var result=new LinkedHashMap<>(row);
        result.put("invoices",jdbc.queryForList("select * from finance_invoice where tenant_id=:tenantId and claim_id=:id order by id",b));
        result.put("attachments",attachments(p,id));
        var reviews=jdbc.queryForList("select r.stage,r.decision,r.note,r.created_at,r.review_snapshot,u.display_name as reviewer_name from finance_review r join user_account u on u.tenant_id=r.tenant_id and u.id=r.actor_id where r.tenant_id=:tenantId and r.claim_id=:id order by r.created_at,r.id",b);
        reviews.forEach(r->r.put("review_snapshot",parse(r.get("review_snapshot"))));result.put("reviews",reviews);
        result.put("payments",jdbc.queryForList("select amount,reference,paid_on,created_at from finance_payment where tenant_id=:tenantId and claim_id=:id",b));
        result.put("canEdit",has(p,"finance.submit")&&p.actorId().equals(uuid(row,"applicant_id"))&&p.assignmentIds().contains(uuid(row,"assignment_id"))&&EDITABLE.contains(row.get("status")));
        result.put("canReview",isReviewer(p,row));
        result.put("canPay",has(p,"finance.pay")&&inScope(p,row)&&!p.actorId().equals(uuid(row,"applicant_id"))&&"APPROVED".equals(row.get("status")));
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
        for(var invoice:in.invoices()) {
            String key=FinanceRules.invoiceKey(invoice.invoiceCode(),invoice.invoiceNumber());
            if(!keys.add(key)||!ids.add(invoice.id())) throw bad("本单含重复发票，请移除重复项");
            if(attachments.stream().noneMatch(a->invoice.attachmentId().equals(uuid(a,"id"))&&"INVOICE".equals(a.get("kind")))) throw bad("发票必须关联本单发票原件");
        }
        var category=in.categoryId()==null?Map.<String,Object>of():requireCategory(p,uuid(row,"hotel_id"),in.categoryId());
        jdbc.update("delete from finance_invoice where tenant_id=:tenantId and claim_id=:id",b);
        for(var invoice:in.invoices()) jdbc.update("""
            insert into finance_invoice(id,tenant_id,claim_id,attachment_id,invoice_code,invoice_number,invoice_key,amount,buyer_tax_number,confirmed)
            values(:invoiceId,:tenantId,:id,:attachment,:code,:number,:key,:amount,:tax,:confirmed)
            """,base(p).addValue("id",id).addValue("invoiceId",invoice.id()).addValue("attachment",invoice.attachmentId())
            .addValue("code",FinanceRules.digits(invoice.invoiceCode())).addValue("number",FinanceRules.digits(invoice.invoiceNumber()))
            .addValue("key",FinanceRules.invoiceKey(invoice.invoiceCode(),invoice.invoiceNumber())).addValue("amount",invoice.amount())
            .addValue("tax",FinanceRules.taxNumber(invoice.buyerTaxNumber())).addValue("confirmed",invoice.confirmed()));
        BigDecimal total=FinanceRules.invoiceTotal(in.invoices());
        boolean mismatch=FinanceRules.differs(total,in.transactionAmount(),in.paymentAmount(),in.requestedAmount())
            ||in.invoices().stream().anyMatch(x->!FinanceRules.taxNumber(x.buyerTaxNumber()).equals(row.get("company_tax_number")));
        b.addValue("title",in.title().trim()).addValue("date",in.businessDate()).addValue("category",in.categoryId())
            .addValue("categoryName",category.getOrDefault("name","")).addValue("treatment",category.get("treatment"))
            .addValue("total",total).addValue("transaction",in.transactionAmount()).addValue("payment",in.paymentAmount())
            .addValue("requested",in.requestedAmount()).addValue("note",in.differenceNote().trim()).addValue("mismatch",mismatch);
        jdbc.update("""
            update finance_claim set title=:title,business_date=:date,category_id=:category,category_name=:categoryName,treatment=:treatment,
              invoice_amount=:total,transaction_amount=:transaction,payment_amount=:payment,requested_amount=:requested,
              difference_note=:note,mismatch=:mismatch,version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id
            """,b);
        return detail(id);
    }
    public Map<String,Object> submit(UUID id,int expectedVersion) {
        var p=prepare("finance.submit");var row=claim(p,id,true);requireOwner(p,row);
        if(REVIEWING.contains(row.get("status"))&&number(row,"version")==expectedVersion+1) return detail(id);
        requireEditable(row);version(row,expectedVersion);var b=base(p).addValue("id",id);
        var config=storeConfig(p,uuid(row,"hotel_id"));requireEnabled(config);
        if(!config.get("company_id").equals(row.get("company_id"))) throw conflict("门店法人公司已调整，请新建报销并核对发票抬头；原草稿仍保留");
        var reviewers=List.of(uuid(config,"manager_account_id"),uuid(config,"finance_account_id"),uuid(config,"leader_account_id"));
        if(reviewers.contains(p.actorId())) throw conflict("审核链含申请人本人，请管理员先配置独立审核人");
        if(jdbc.queryForObject("select count(*) from user_account where tenant_id=:tenantId and id in (:reviewers) and status='ACTIVE'",base(p).addValue("reviewers",reviewers),Integer.class)!=3) throw conflict("审核人已停用，请更新门店审核配置");
        if(text(row,"title").isBlank()||decimal(row,"requested_amount").signum()<=0||row.get("category_id")==null) throw bad("请填写事由、费用类别和正数报销金额");
        requireCategory(p,uuid(row,"hotel_id"),uuid(row,"category_id"));
        var invoices=jdbc.queryForList("select * from finance_invoice where tenant_id=:tenantId and claim_id=:id order by invoice_key",b);
        if(invoices.isEmpty()||invoices.stream().anyMatch(x->!Boolean.TRUE.equals(x.get("confirmed")))) throw bad("请核对所有发票号码、金额和抬头后确认");
        var documents=attachments(p,id);Set<Object> kinds=new HashSet<>();documents.forEach(x->kinds.add(x.get("kind")));
        if(!kinds.containsAll(Set.of("INVOICE","TRANSACTION","PAYMENT"))) throw bad("请补齐发票、交易记录及付款凭证");
        if(documents.stream().filter(d->"INVOICE".equals(d.get("kind"))).anyMatch(d->invoices.stream().noneMatch(i->d.get("id").equals(i.get("attachment_id"))))) throw bad("请为每份发票原件填写对应的发票明细，或移除误上传的原件");
        if(Boolean.TRUE.equals(row.get("mismatch"))&&text(row,"difference_note").isBlank()) throw bad("金额或发票抬头存在差异，请填写原因；差异不会自动拒绝报销");
        lock(p,"invoice-registry");
        for(var invoice:invoices) {
            var q=base(p).addValue("key",invoice.get("invoice_key")).addValue("id",id);
            if(jdbc.queryForObject("select count(*) from finance_invoice_reservation where tenant_id=:tenantId and invoice_key=:key and (claim_id is null or claim_id<>:id)",q,Integer.class)>0) throw conflict("发票已被其他报销或历史已报销记录占用，已阻止重复提交");
        }
        for(var document:documents) if("INVOICE".equals(document.get("kind"))) {
            var q=base(p).addValue("hash",document.get("sha256")).addValue("id",id);
            if(jdbc.queryForObject("select count(*) from finance_evidence_reservation where tenant_id=:tenantId and sha256=:hash and claim_id<>:id",q,Integer.class)>0) throw conflict("同一发票文件已用于其他报销，已阻止重复提交");
        }
        release(p,id);
        for(var invoice:invoices) jdbc.update("insert into finance_invoice_reservation(tenant_id,invoice_key,claim_id) values(:tenantId,:key,:id)",base(p).addValue("key",invoice.get("invoice_key")).addValue("id",id));
        for(var document:documents) if("INVOICE".equals(document.get("kind"))) jdbc.update("insert into finance_evidence_reservation(tenant_id,sha256,claim_id) values(:tenantId,:hash,:id) on conflict do nothing",base(p).addValue("hash",document.get("sha256")).addValue("id",id));
        jdbc.update("""
            update finance_claim set status='MANAGER_REVIEW',manager_account_id=:manager,finance_account_id=:finance,leader_account_id=:leader,
              config_version=:configVersion,approved_amount=null,submitted_at=now(),version=version+1,updated_at=now()
            where tenant_id=:tenantId and id=:id
            """,b.addValue("manager",reviewers.get(0)).addValue("finance",reviewers.get(1)).addValue("leader",reviewers.get(2)).addValue("configVersion",config.get("version")));
        audit.record("FINANCE_CLAIM_SUBMITTED","FINANCE_CLAIM",id,"{}");return detail(id);
    }
    public Map<String,Object> withdraw(UUID id,int expectedVersion) {
        var p=prepare("finance.submit");var row=claim(p,id,true);requireOwner(p,row);version(row,expectedVersion);
        if(!EDITABLE.contains(row.get("status"))&&!REVIEWING.contains(row.get("status"))) throw conflict("当前单据不能撤回");
        lock(p,"invoice-registry");release(p,id);
        jdbc.update("update finance_claim set status='WITHDRAWN',version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",base(p).addValue("id",id));
        audit.record("FINANCE_CLAIM_WITHDRAWN","FINANCE_CLAIM",id,"{}");return detail(id);
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
            next=switch(stage) {case "MANAGER_REVIEW"->"FINANCE_REVIEW";case "FINANCE_REVIEW"->"LEADER_REVIEW";default->"APPROVED";};
            if("FINANCE_REVIEW".equals(stage)) {
                if(in.approvedAmount()==null||in.categoryId()==null) throw bad("财务复核须确认报销金额及费用类别");
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
            "evidence",jdbc.queryForList("select id,kind,original_name,sha256 from finance_attachment where tenant_id=:tenantId and claim_id=:id and retired_at is null order by id",b));
        jdbc.update("insert into finance_review(tenant_id,claim_id,actor_id,stage,decision,note,request_id,request_hash,review_snapshot) values(:tenantId,:id,:actorId,:stage,:decision,:note,:request,:hash,cast(:snapshot as jsonb))",
            b.addValue("stage",stage).addValue("decision",in.decision()).addValue("note",in.note().trim()).addValue("hash",hash).addValue("snapshot",json(snapshot)));
        jdbc.update("update finance_claim set status=:status,version=version+1,updated_at=now(),approved_at=case when :status='APPROVED' then now() else approved_at end where tenant_id=:tenantId and id=:id",b.addValue("status",next));
        if("REJECTED".equals(next)) {lock(p,"invoice-registry");release(p,id);}
        if("APPROVED".equals(next)) finalizeClaim(p,id);
        audit.record("FINANCE_CLAIM_REVIEWED","FINANCE_CLAIM",id,json(Map.of("stage",stage,"decision",in.decision())));return detail(id);
    }
    private void finalizeClaim(TenantPrincipal p,UUID id) {
        var row=claim(p,id,false);var b=base(p).addValue("id",id);
        if(row.get("approved_amount")==null) throw conflict("缺少财务确认金额，不能终审");
        jdbc.update("""
            insert into finance_expense_entry(tenant_id,claim_id,hotel_id,company_name,category_name,treatment,amount,business_date)
            select tenant_id,id,hotel_id,company_name,category_name,treatment,approved_amount,business_date from finance_claim where tenant_id=:tenantId and id=:id
            on conflict(tenant_id,claim_id) do nothing
            """,b);
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("status","DRAFT");snapshot.put("claimId",id);snapshot.put("companyName",row.get("company_name"));snapshot.put("companyTaxNumber",row.get("company_tax_number"));
        snapshot.put("hotelName",row.get("hotel_name"));snapshot.put("applicantName",row.get("applicant_name"));snapshot.put("businessDate",row.get("business_date").toString());
        snapshot.put("summary",row.get("title"));snapshot.put("amount",decimal(row,"approved_amount").toPlainString());snapshot.put("debitAccount",row.get("debit_account"));snapshot.put("creditAccount",row.get("payable_account"));
        snapshot.put("taxTreatment","待财务复核，不自动判断进项税抵扣");snapshot.put("treatment",row.get("treatment"));
        snapshot.put("evidence",jdbc.queryForList("select id,kind,original_name,sha256 from finance_attachment where tenant_id=:tenantId and claim_id=:id and retired_at is null order by id",b));
        snapshot.put("invoices",jdbc.queryForList("select invoice_code,invoice_number,amount::text,buyer_tax_number from finance_invoice where tenant_id=:tenantId and claim_id=:id order by invoice_key",b));
        snapshot.put("warning","凭证草稿，尚未记账；请核对科目、税额、期间和原始凭证。不替代税务发票。");
        jdbc.update("insert into finance_voucher_draft(tenant_id,claim_id,snapshot) values(:tenantId,:id,cast(:snapshot as jsonb)) on conflict(tenant_id,claim_id) do nothing",b.addValue("snapshot",json(snapshot)));
    }
    public Map<String,Object> payment(UUID id,FinanceModels.Payment in) {
        var p=prepare("finance.pay");var row=claim(p,id,true);access.requireOrgScope(uuid(row,"hotel_id"));var b=base(p).addValue("id",id);
        var existing=jdbc.queryForList("select * from finance_payment where tenant_id=:tenantId and claim_id=:id",b);
        if(!existing.isEmpty()) {
            var old=existing.getFirst();
            if(in.requestId().equals(uuid(old,"request_id"))&&p.actorId().equals(uuid(old,"actor_id"))&&in.amount().compareTo(decimal(old,"amount"))==0
                &&in.reference().trim().equals(old.get("reference"))&&in.paidOn().toString().equals(old.get("paid_on").toString())) return detail(id);
            throw conflict("本单已登记付款，不能重复登记");
        }
        version(row,in.expectedVersion());
        if(!"APPROVED".equals(row.get("status"))) throw conflict("三段审批全部通过后才能登记付款");
        if(p.actorId().equals(uuid(row,"applicant_id"))) throw forbidden();
        if(in.amount().compareTo(decimal(row,"approved_amount"))!=0) throw bad("首期仅支持足额付款登记，金额必须与审批金额一致");
        if(in.paidOn().isAfter(today(p))||in.paidOn().isBefore(LocalDate.parse(row.get("business_date").toString()))) throw bad("付款日期不能早于业务日期或晚于今天");
        jdbc.update("insert into finance_payment(tenant_id,claim_id,amount,reference,paid_on,actor_id,request_id) values(:tenantId,:id,:amount,:reference,:date,:actorId,:request)",
            b.addValue("amount",in.amount()).addValue("reference",in.reference().trim()).addValue("date",in.paidOn()).addValue("request",in.requestId()));
        jdbc.update("update finance_claim set version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",b);
        audit.record("FINANCE_PAYMENT_RECORDED","FINANCE_CLAIM",id,"{}");return detail(id);
    }
    public Map<String,Object> ledger(String month,UUID hotel) {
        var p=prepare("finance.read");var b=period(p,month,hotel);
        String from=" from finance_expense_entry e join finance_claim c on c.tenant_id=e.tenant_id and c.id=e.claim_id left join finance_payment py on py.tenant_id=e.tenant_id and py.claim_id=e.claim_id where e.tenant_id=:tenantId and e.business_date>=:start and e.business_date<:end and "+SCOPE+" and (cast(:hotel as uuid) is null or e.hotel_id=:hotel)";
        String totals="coalesce(sum(e.amount),0)::text as total,coalesce(sum(py.amount),0)::text as paid,coalesce(sum(e.amount-coalesce(py.amount,0)),0)::text as unpaid";
        var result=new LinkedHashMap<String,Object>();result.put("month",month);
        result.put("totals",jdbc.queryForMap("select "+totals+from+" and e.treatment='EXPENSE'",b));
        result.put("stores",jdbc.queryForList("select e.hotel_id,c.hotel_name,"+totals+from+" and e.treatment='EXPENSE' group by e.hotel_id,c.hotel_name order by c.hotel_name",b));
        result.put("categories",jdbc.queryForList("select e.category_name,coalesce(sum(e.amount),0)::text as amount"+from+" and e.treatment='EXPENSE' group by e.category_name order by sum(e.amount) desc,e.category_name",b));
        result.put("otherTreatments",jdbc.queryForList("select e.treatment,coalesce(sum(e.amount),0)::text as amount"+from+" and e.treatment<>'EXPENSE' group by e.treatment order by e.treatment",b));
        result.put("entries",jdbc.queryForList("select c.id,c.title,c.applicant_name,c.hotel_name,c.company_name,e.category_name,e.treatment,e.amount::text,e.business_date,py.paid_on"+from+" order by e.business_date desc,e.id limit 100",b));
        result.put("note","仅报销费用统计，未合并库存耗用、采购及收入；不是完整财务报表。库存、资产、预付及已记成本不重复计入费用。");
        return result;
    }
    public String export(String month,UUID hotel) {
        var p=prepare("finance.read");var b=period(p,month,hotel);
        var rows=jdbc.queryForList("""
            select c.id,c.business_date,c.hotel_name,c.company_name,c.applicant_name,c.title,c.category_name,c.treatment,c.approved_amount,
             coalesce(py.amount,0) as paid,c.approved_amount-coalesce(py.amount,0) as unpaid
            from finance_claim c left join finance_payment py on py.tenant_id=c.tenant_id and py.claim_id=c.id
            where c.tenant_id=:tenantId and c.status='APPROVED' and c.business_date>=:start and c.business_date<:end and
            """+SCOPE+" and (cast(:hotel as uuid) is null or c.hotel_id=:hotel) order by c.business_date,c.id limit 10001",b);
        if(rows.size()>10000) throw bad("导出超过一万笔，请按单店缩小范围");
        return csvRows(List.of("单据ID","业务日期","门店","法人公司","申请人","事由","费用类别","会计处理","批准金额","已付款","待付款"),rows,
            List.of("id","business_date","hotel_name","company_name","applicant_name","title","category_name","treatment","approved_amount","paid","unpaid"));
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
    public void authorizeUpload(UUID id) {var p=prepare("finance.submit");var row=claim(p,id,false);requireOwner(p,row);requireEditable(row);}
    public Map<String,Object> registerAttachment(UUID id,UUID attachmentId,String kind,String name,String type,long size,String hash,String objectKey,String scanStatus,Map<String,Object> extraction) {
        var p=prepare("finance.submit");var row=claim(p,id,true);requireOwner(p,row);requireEditable(row);var b=base(p).addValue("id",id).addValue("attachment",attachmentId);
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
        audit.record("FINANCE_EVIDENCE_UPLOADED","FINANCE_CLAIM",id,json(Map.of("attachmentId",attachmentId,"kind",kind,"sha256",hash)));
        return attachments(p,id).stream().filter(a->attachmentId.equals(uuid(a,"id"))).findFirst().orElseThrow();
    }
    public Map<String,Object> attachment(UUID id,UUID attachmentId) {
        var p=prepare();requireReadable(p,claim(p,id,false));
        var rows=jdbc.queryForList("select * from finance_attachment where tenant_id=:tenantId and claim_id=:id and id=:attachment",base(p).addValue("id",id).addValue("attachment",attachmentId));
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"凭证不存在");return rows.getFirst();
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
        rows.forEach(r->r.put("extraction",parse(r.get("extraction"))));return rows;
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
    private Map<String,Object> storeConfig(TenantPrincipal p,UUID hotel) {
        var rows=jdbc.queryForList("select f.*,c.name as company_name,c.tax_number from finance_store_config f join finance_company c on c.tenant_id=f.tenant_id and c.id=f.company_id where f.tenant_id=:tenantId and f.hotel_id=:hotel",base(p).addValue("hotel",hotel));
        if(rows.isEmpty()) throw conflict("所属门店尚未配置公司抬头和三段审核人，请联系管理员");return rows.getFirst();
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
        if(inScope(p,row)&&(has(p,"finance.read")||(has(p,"finance.pay")&&"APPROVED".equals(row.get("status")))
            ||(has(p,"finance.review")&&List.of(uuid(row,"manager_account_id"),uuid(row,"finance_account_id"),uuid(row,"leader_account_id")).contains(p.actorId())))) return;
        throw forbidden();
    }
    private boolean isReviewer(TenantPrincipal p,Map<String,Object> row) {
        if(!has(p,"finance.review")||!inScope(p,row)||p.actorId().equals(uuid(row,"applicant_id"))) return false;
        String column=switch(text(row,"status")) {case "MANAGER_REVIEW"->"manager_account_id";case "FINANCE_REVIEW"->"finance_account_id";case "LEADER_REVIEW"->"leader_account_id";default->null;};
        return column!=null&&p.actorId().equals(uuid(row,column));
    }
    private boolean inScope(TenantPrincipal p,Map<String,Object> row) {return p.hasTenantScope()||p.orgScopes().contains(uuid(row,"hotel_id"));}
    private void requireEditable(Map<String,Object> row) {if(!EDITABLE.contains(row.get("status"))) throw conflict("单据已提交或结束，不能覆盖原始内容");}
    private void requireEnabled(Map<String,Object> config) {if(!Boolean.TRUE.equals(config.get("enabled"))) throw conflict("所属门店报销尚未启用，请联系管理员完成配置");}
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
        else access.requireAnyPermission("finance.submit","finance.review","finance.read","finance.pay","finance.configure");
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
