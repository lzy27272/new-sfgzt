package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.*;
import java.time.*;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceOperationsModels.*;

/** Business subledgers only. Every economic event is separately mapped/reviewed by the accounting domain. */
@Service @Transactional
public class FinanceOperationsService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext database;
    private final AccessPolicy access;
    private final ObjectMapper mapper;
    private final AuditWriter audit;
    private final FinanceAccountingService accounting;
    private static final String SCOPE="(:tenantScope or d.hotel_id in (:scopeIds))";
    private static final String VISIBLE="("+SCOPE+" and ((:owner and d.applicant_id=:actorId) or :reader or (:reviewer and d.approval_route @> cast(:actorRoute as jsonb)) or (:payer and d.cashier_account_id=:actorId))) and (d.type<>'PAYROLL' or :payroll) and (d.type<>'TAX_TASK' or :tax)";
    private static final List<String> TYPES=List.of("CONTRACT","PAYABLE","LOAN","PREPAYMENT","ASSET","PAYROLL","REVENUE","INTERCOMPANY","TAX_TASK");
    private static final Set<String> CASH=Set.of("PAYMENT","RECEIPT","REFUND","REPAYMENT");
    private static final Set<String> EDITABLE=Set.of("DRAFT","RETURNED");
    private static final String SUMS=" left join lateral(select coalesce(sum(amount) filter(where action='PAYMENT'),0) paid_total,coalesce(sum(amount) filter(where action='REFUND'),0) refund_total,coalesce(sum(amount) filter(where action='RECEIPT'),0) receipt_total,coalesce(sum(amount) filter(where action='REPAYMENT'),0) repaid_total,coalesce(sum(amount) filter(where action='WRITEOFF'),0) written_off_total,coalesce(sum(amount) filter(where action='DEPRECIATE'),0) depreciation_total,coalesce(sum(amount) filter(where action='AMORTIZE'),0) amortization_total,(select coalesce(sum(w.amount),0) from fo_action w where w.tenant_id=d.tenant_id and w.related_document_id=d.id and w.action='WRITEOFF') writeoff_received_total from fo_action a where a.tenant_id=d.tenant_id and a.document_id=d.id) sums on true ";
    public FinanceOperationsService(NamedParameterJdbcTemplate jdbc,TenantDatabaseContext database,AccessPolicy access,ObjectMapper mapper,AuditWriter audit,FinanceAccountingService accounting) {
        this.jdbc=jdbc;this.database=database;this.access=access;this.mapper=mapper;this.audit=audit;this.accounting=accounting;
    }
    public Map<String,Object> context() {
        var p=prepare();var b=base(p);
        var out=new LinkedHashMap<String,Object>();out.put("types",TYPES.stream().filter(t->!t.equals("PAYROLL")||has(p,"finance.payroll")).filter(t->!t.equals("TAX_TASK")||has(p,"finance.tax")).toList());
        out.put("parties",parties().get("items"));out.put("bankAccounts",has(p,"finance.read")||has(p,"finance.pay")||has(p,"finance.configure")?bankAccounts().get("items"):List.of());
        out.put("hotels",rows("select id,name,unit_type from org_unit d where d.tenant_id=:tenantId and d.status='ACTIVE' and d.unit_type in ('HOTEL','GROUP') and (:tenantScope or d.id in (:scopeIds)) order by d.name",b));
        out.put("capabilities",Map.of("canCreate",has(p,"finance.submit"),"canReview",has(p,"finance.review"),"canPay",has(p,"finance.pay"),"canConfigure",has(p,"finance.configure"),"canReadPayroll",has(p,"finance.payroll")));
        out.put("limitations",List.of("手工导入/确认资料不等于已接通PMS、银行、税务或开票服务","采购退换货库存逆向尚未接入；本域不修改库存","外部回单引用与已上传原件分别显示","业务台账不等于已过账总账或正式合并报表"));return out;
    }
    public Map<String,Object> documents(String type,UUID hotelId,int page) {
        var p=prepare();if(page<0||page>10000||type!=null&&!TYPES.contains(type)) throw bad("单据查询条件无效");if(hotelId!=null) access.requireOrgScope(hotelId);
        var b=base(p).addValue("type",type).addValue("hotel",hotelId).addValue("offset",page*50);
        String filter="d.tenant_id=:tenantId and "+visibleSql()+" and (cast(:type as text) is null or d.type=:type) and (cast(:hotel as uuid) is null or d.hotel_id=:hotel)";
        long total=jdbc.queryForObject("select count(*) from fo_document d where "+filter,b,Long.class);
        var items=rows("select d.*,h.name as hotel_name,sums.* from fo_document d join org_unit h on h.tenant_id=d.tenant_id and h.id=d.hotel_id "+SUMS.replace("sum(amount)","sum("+cashAmountSql("a","'9999-12-31'::date")+")")+" where "+filter+" order by d.updated_at desc,d.id limit 50 offset :offset",b);items.forEach(this::balances);
        return Map.of("items",items,"total",total,"page",page,"pageSize",50);
    }
    public Map<String,Object> detail(UUID id) {
        var p=prepare();var d=load(p,id,false);var b=base(p).addValue("id",id);
        var acts=rows("select a.*,coalesce((select sum(m.amount) from fo_bank_match m where m.tenant_id=a.tenant_id and m.action_id=a.id),0) as matched_amount,case when a.action not in ('PAYMENT','RECEIPT','REFUND','REPAYMENT') then 'NOT_APPLICABLE' when exists(select 1 from fo_attachment f where f.tenant_id=a.tenant_id and f.document_id=a.document_id and f.action_id=a.id and f.kind='PAYMENT') then 'ARCHIVED' when a.receipt_reference<>'' then 'EXTERNAL_REFERENCE' else 'MISSING' end as receipt_status from fo_action a where a.tenant_id=:tenantId and a.document_id=:id order by a.created_at,a.id",b);
        if(new FinanceControlGuard(jdbc).table("fx_cash_correction")){var values=jdbc.queryForList("select a.id,"+cashAmountSql("a","'9999-12-31'::date")+" effective_amount,fx_bank_action_matched_amount(a.tenant_id,a.id,'9999-12-31'::date) matched_amount from fo_action a where a.tenant_id=:tenantId and a.document_id=:id",b);var index=new HashMap<String,Map<String,Object>>();for(var value:values)index.put(value.get("id").toString(),value);for(var act:acts){var value=index.get(act.get("id").toString());act.put("effectiveAmount",value.get("effective_amount").toString());act.put("matchedAmount",value.get("matched_amount").toString());act.put("correctedAmount",new BigDecimal(act.get("amount").toString()).subtract(new BigDecimal(value.get("effective_amount").toString())).toPlainString());}}
        var result=external(d);result.put("settlements",acts.stream().filter(a->!Set.of("SUBMIT","APPROVE","RETURN","REJECT","CANCEL","SKIP_SELF").contains(a.get("action"))).toList());
        result.put("reviews",acts.stream().filter(a->Set.of("SUBMIT","APPROVE","RETURN","REJECT","CANCEL","SKIP_SELF").contains(a.get("action"))).toList());
        result.put("attachments",rows("select id,document_id,action_id,kind,original_name,media_type,size_bytes,sha256,scan_status,extraction,created_at from fo_attachment where tenant_id=:tenantId and document_id=:id order by created_at,id",b));
        result.put("canEdit",p.actorId().equals(uid(d,"applicant_id"))&&EDITABLE.contains(str(d,"status"))&&has(p,"finance.submit"));
        result.put("canSubmit",result.get("canEdit"));result.put("canReview",canReview(p,d));
        var allowed=allowedActions(p,d);result.put("allowedActions",allowed);result.put("canSettle",!allowed.isEmpty());
        result.put("paidAmount",sum(p,id,"PAYMENT").subtract(sum(p,id,"REFUND")).toPlainString());
        result.put("receivedAmount",sum(p,id,"RECEIPT").toPlainString());result.put("writtenOffAmount",sum(p,id,"WRITEOFF").toPlainString());
        for(var field:Map.of("paidTotal","PAYMENT","refundTotal","REFUND","receiptTotal","RECEIPT","repaidTotal","REPAYMENT","writtenOffTotal","WRITEOFF","depreciationTotal","DEPRECIATE","amortizationTotal","AMORTIZE").entrySet())result.put(field.getKey(),acts.stream().filter(a->field.getValue().equals(a.get("action"))).map(a->new BigDecimal(a.getOrDefault("effectiveAmount",a.get("amount")).toString())).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString());balances(result);
        result.put("writeoffReceivedTotal",writeoffReceived(p,id).toPlainString());balances(result);
        result.put("events",rows("select source_type,source_id,event_key,status,amount,voucher_id from fa_source_event where tenant_id=:tenantId and metadata->>'documentId'=cast(:id as text) order by created_at,id",b));
        audit.record("FINANCE_OPERATION_VIEWED","FINANCE_OPERATION",id,"{}");return result;
    }
    public Map<String,Object> save(UUID id,Document in) {
        var p=prepare("finance.submit");access.requireOrgScope(in.hotelId());requireHotel(p,in.hotelId());if(in.type().equals("PAYROLL"))access.requirePermission("finance.payroll");if(in.type().equals("TAX_TASK"))access.requirePermission("finance.tax");
        lock(p,"command:"+in.requestId());if(replayed(p,in.requestId(),id==null?in.requestId():id,id==null?"CREATE":"UPDATE",in)) return detail(id==null?in.requestId():id);
        UUID target=id==null?in.requestId():id;var b=base(p).addValue("id",target);Map<String,Object> previous=null;
        if(id!=null) {previous=load(p,id,true);version(previous,in.expectedVersion());if(!p.actorId().equals(uid(previous,"applicant_id"))||!EDITABLE.contains(str(previous,"status")))throw conflict("只有本人未提交/退回单据可以编辑");
            if(!uid(previous,"hotel_id").equals(in.hotelId())||!str(previous,"type").equals(in.type()))throw bad("不能更换原单组织或业务类型");}
        else if(in.expectedVersion()!=0)throw conflict("新建版本应为0");
        BigDecimal amount=validate(p,in,target);if(in.partyId()!=null) party(p,in.partyId(),in.hotelId());
        var frozenDetails=new LinkedHashMap<String,Object>(in.details());var projectDimensions=new FinanceProjectDimensions(jdbc,access);if(projectDimensions.installed()){if(previous!=null&&!frozenDetails.containsKey("projectKind")&&!frozenDetails.containsKey("projectId")){var oldProject=obj(previous,"details");frozenDetails.put("projectKind",oldProject.get("projectKind"));frozenDetails.put("projectId",oldProject.get("projectId"));}var project=FinanceProjectDimensions.from(frozenDetails);var projectConfigs=jdbc.queryForList("select company_id from finance_store_config where tenant_id=:tenantId and hotel_id=:hotel and enabled",base(p).addValue("hotel",in.hotelId()));UUID projectCompany=projectConfigs.size()==1?UUID.fromString(projectConfigs.getFirst().get("company_id").toString()):null;var snapshot=projectDimensions.freeze(p,in.hotelId(),projectCompany,project);frozenDetails.put("projectKind",project.kind());frozenDetails.put("projectId",project.id());frozenDetails.put("projectKey",project.key());frozenDetails.put("projectSnapshot",snapshot);}
        if(in.budgetId()!=null)budget(p,in.budgetId(),in.hotelId(),in.businessDate());
        b.addValue("hotel",in.hotelId()).addValue("type",in.type()).addValue("title",in.title().trim()).addValue("date",in.businessDate()).addValue("due",in.dueDate())
            .addValue("party",in.partyId()).addValue("budget",in.budgetId()).addValue("sourceType",blank(in.sourceType()).isEmpty()?"MANUAL":in.sourceType().trim())
            .addValue("sourceId",in.sourceId()).addValue("sourceReference",blank(in.sourceReference())).addValue("amount",amount).addValue("details",json(frozenDetails)).addValue("lines",json(in.lines()))
            .addValue("recognizedSource",str(in.details(),"recognizedSourceDocumentId").isBlank()?null:uuidField(in.details(),"recognizedSourceDocumentId"));
        if(previous==null)jdbc.update("insert into fo_document(id,tenant_id,hotel_id,applicant_id,type,title,business_date,due_date,party_id,budget_id,source_type,source_id,source_reference,amount,details,lines,recognized_source_document_id) values(:id,:tenantId,:hotel,:actorId,:type,:title,:date,:due,:party,:budget,:sourceType,:sourceId,:sourceReference,:amount,cast(:details as jsonb),cast(:lines as jsonb),:recognizedSource)",b);
        else jdbc.update("update fo_document set title=:title,business_date=:date,due_date=:due,party_id=:party,budget_id=:budget,source_type=:sourceType,source_id=:sourceId,source_reference=:sourceReference,amount=:amount,details=cast(:details as jsonb),lines=cast(:lines as jsonb),recognized_source_document_id=:recognizedSource,version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",b);
        recordCommand(p,in.requestId(),target,previous==null?"CREATE":"UPDATE",in);audit.record("FINANCE_OPERATION_SAVED","FINANCE_OPERATION",target,json(Map.of("type",in.type())));return detail(target);
    }
    public Map<String,Object> submit(UUID id,Version in) {
        var p=prepare("finance.submit");lock(p,"command:"+in.requestId());if(replayed(p,in.requestId(),id,"SUBMIT",in))return detail(id);
        var d=load(p,id,true);version(d,in.expectedVersion());if(!EDITABLE.contains(str(d,"status"))||!p.actorId().equals(uid(d,"applicant_id")))throw conflict("只有申请人可以提交未完成审批的单据");
        var b=base(p).addValue("id",id).addValue("hotel",uid(d,"hotel_id"));
        var configs=jdbc.queryForList("select * from finance_store_config where tenant_id=:tenantId and hotel_id=:hotel and enabled and workflow_version=2",b);
        if(configs.isEmpty())throw conflict("四段审批与出纳配置尚未完整启用，草稿已保留");var config=new FinanceControlGuard(jdbc).workflow(p,configs.getFirst(),str(d,"type"),dec(d,"amount"),date(d,"business_date"),id);
        var route=new ArrayList<String>();for(String col:List.of("manager_account_id","finance_account_id","director_account_id","leader_account_id")){UUID user=uid(config,col);if(user==null)throw conflict("审核人缺失，草稿保留");requireAccount(p,user);route.add(user.toString());}
        UUID cashier=uid(config,"cashier_account_id");if(cashier==null)throw conflict("出纳缺失");requireAccount(p,cashier);if(cashier.equals(p.actorId()))throw conflict("申请人不能登记本人付款，请配置独立出纳");int index=0;
        if(uid(d,"company_id")!=null&&!uid(d,"company_id").equals(uid(config,"company_id")))throw conflict("法人主体已经调整，原单据主体不得静默替换，请重新建立并核对来源");
        if(uid(d,"recognized_source_document_id")!=null&&!Objects.equals(uid(load(p,uid(d,"recognized_source_document_id"),false),"company_id"),uid(config,"company_id")))throw conflict("承接来源与当前发布法人主体不一致，请核对后重新建单");
        if(route.getFirst().equals(p.actorId().toString())&&Boolean.TRUE.equals(config.get("skip_self_review"))){index=1;action(p,d,"SKIP_SELF",BigDecimal.ZERO,in.requestId(),"按已发布配置回避首段自审","",null,null,null,LocalDate.now(),Map.of());}
        if(route.subList(index,route.size()).contains(p.actorId().toString()))throw conflict("后续节点不可由申请人本人审核，请调整审批配置");
        if(new HashSet<>(route).size()!=4)throw conflict("四段审核人必须不同");
        new FinanceDelegationControl(jdbc).freeze(p,uid(d,"hotel_id"),"OPERATION",id,in.expectedVersion(),route.stream().map(UUID::fromString).toList(),p.actorId(),cashier);
        b.addValue("route",json(route)).addValue("index",index).addValue("cashier",cashier).addValue("company",config.get("company_id")).addValue("partySnapshot",json(uid(d,"party_id")==null?Map.of():party(p,uid(d,"party_id"),uid(d,"hotel_id"))));
        jdbc.update("update fo_document set status='REVIEW',approval_route=cast(:route as jsonb),review_index=:index,cashier_account_id=:cashier,company_id=:company,party_snapshot=cast(:partySnapshot as jsonb),version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",b);
        new FinanceBudgetControl(jdbc).reserve(p,"OPERATION",d);
        usage(p,d,BigDecimal.ZERO,dec(d,"amount"),BigDecimal.ZERO);recordCommand(p,in.requestId(),id,"SUBMIT",in);action(p,d,"SUBMIT",BigDecimal.ZERO,UUID.randomUUID(),"提交审批","",null,null,null,LocalDate.now(),Map.of());notifyCurrent(p,id);return detail(id);
    }
    public Map<String,Object> review(UUID id,Review in) {
        var p=prepare("finance.review");lock(p,"command:"+in.requestId());if(replayed(p,in.requestId(),id,"REVIEW",in))return detail(id);
        var d=load(p,id,true);version(d,in.expectedVersion());if(!canReview(p,d))throw forbidden();var b=base(p).addValue("id",id);String status;int index=((Number)d.get("review_index")).intValue();
        if(in.decision().equals("APPROVE")){index++;status=index==4?"APPROVED":"REVIEW";}else status=in.decision().equals("RETURN")?"RETURNED":"REJECTED";
        b.addValue("status",status).addValue("index",index);jdbc.update("update fo_document set status=:status,review_index=:index,version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",b);
        action(p,d,in.decision(),BigDecimal.ZERO,in.requestId(),in.note(),"",null,null,null,LocalDate.now(),Map.of("stage",index));recordCommand(p,in.requestId(),id,"REVIEW",in);
        if(status.equals("APPROVED")){
            boolean actual=str(d,"type").equals("PAYROLL")||str(d,"type").equals("PAYABLE")&&str(obj(d,"details"),"treatment").equals("EXPENSE");usage(p,d,actual?BigDecimal.ZERO:dec(d,"amount"),BigDecimal.ZERO,actual?dec(d,"amount"):BigDecimal.ZERO);
            // Authorization of a loan, prepayment or deposit is not an actual advance or cash receipt.
            boolean recognize=Set.of("PAYABLE","PAYROLL","ASSET").contains(str(d,"type"))||str(d,"type").equals("REVENUE")&&Set.of("ROOM","OTHER").contains(str(obj(d,"details"),"revenueKind"));
            // A financial asset card linked to an already-recognized purchase is a dimension, not another acquisition.
            if(recognize&&str(obj(d,"details"),"recognizedSourceDocumentId").isBlank()&&!"FS_PROCUREMENT".equals(str(d,"source_type")))event(d,"RECOGNITION",dec(d,"amount"),date(d,"business_date"),in.requestId(),Map.of("action","APPROVED"));
        }
        else if(!status.equals("REVIEW"))usage(p,d,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO);new FinanceBudgetControl(jdbc).transition(p,"OPERATION",d,status);notifyCurrent(p,id);return detail(id);
    }
    public Map<String,Object> cancel(UUID id,Version in) {
        var p=prepare("finance.submit");lock(p,"command:"+in.requestId());if(replayed(p,in.requestId(),id,"CANCEL",in))return detail(id);var d=load(p,id,true);version(d,in.expectedVersion());
        if(!p.actorId().equals(uid(d,"applicant_id"))||!Set.of("DRAFT","RETURNED","REVIEW").contains(str(d,"status")))throw conflict("只能取消本人的未批准单据；已批准事项必须受控调整，不能删除历史");
        jdbc.update("update fo_document set status='CANCELLED',version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",base(p).addValue("id",id));usage(p,d,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO);new FinanceBudgetControl(jdbc).transition(p,"OPERATION",d,"CANCELLED");recordCommand(p,in.requestId(),id,"CANCEL",in);action(p,d,"CANCEL",BigDecimal.ZERO,in.requestId(),"申请人取消","",null,null,null,LocalDate.now(),Map.of());return detail(id);
    }
    public Map<String,Object> settle(UUID id,Settlement in) {
        var p=prepare();lock(p,"command:"+in.requestId());if(replayed(p,in.requestId(),id,"SETTLE",in))return detail(id);
        var preliminary=load(p,id,false);var lockedIds=new HashSet<UUID>();lockedIds.add(id);if(in.relatedDocumentId()!=null)lockedIds.add(in.relatedDocumentId());if(uid(preliminary,"recognized_source_document_id")!=null)lockedIds.add(uid(preliminary,"recognized_source_document_id"));
        // Cash settlement, loan write-off and source-card allocation share the payable row lock.
        // Sort before taking multiple rows so concurrent cross-document operations cannot invert locks.
        jdbc.queryForList("select id from fo_document where tenant_id=:tenantId and id in (:ids) order by id for update",base(p).addValue("ids",lockedIds));
        var d=load(p,id,false);version(d,in.expectedVersion());
        if(!str(d,"status").equals("APPROVED"))throw conflict("单据未完成全部审批，不能办理后续财务动作");
        String type=str(d,"type"),act=in.action();BigDecimal amount=in.amount();
        if(CASH.contains(act)){access.requirePermission("finance.pay");if(!p.actorId().equals(uid(d,"cashier_account_id"))||p.actorId().equals(uid(d,"applicant_id")))throw forbidden();if(in.bankAccountId()==null)throw bad("资金动作必须选择真实出入款账户");bank(p,in.bankAccountId(),uid(d,"hotel_id"));}
        else {access.requirePermission("finance.review");if(p.actorId().equals(uid(d,"applicant_id")))throw forbidden();}
        if(type.equals("PAYROLL"))access.requirePermission("finance.payroll");
        if(type.equals("TAX_TASK")||Set.of("ISSUE_INVOICE","RED_INVOICE","TAX_FILE").contains(act))access.requirePermission("finance.tax");
        validateSettlement(p,d,in);
        if(CASH.contains(act))new FinanceControlGuard(jdbc).cash(p,"OPERATION",d,act,amount,in.occurredOn(),in.bankAccountId());
        if(Set.of("DEPRECIATE","AMORTIZE").contains(act))accounting.lockBudgetOwner(p,d);
        if(CASH.contains(act)&&amount.signum()<=0)throw bad("收付款金额必须大于0");
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("party",obj(d,"party_snapshot"));snapshot.put("documentVersion",d.get("version"));snapshot.put("sourceType",d.get("source_type"));snapshot.put("sourceId",Objects.toString(d.get("source_id"),""));
        UUID actionId=action(p,d,act,amount,in.requestId(),blank(in.note()),in.reference(),blank(in.receiptReference()),in.bankAccountId(),in.relatedDocumentId(),in.occurredOn(),snapshot);
        jdbc.update("update fo_document set version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",base(p).addValue("id",id));recordCommand(p,in.requestId(),id,"SETTLE",in);
        if(act.equals("WRITEOFF"))jdbc.update("update fo_document set version=version+1,updated_at=now() where tenant_id=:tenantId and id=:related",base(p).addValue("related",in.relatedDocumentId()));
        if(!Set.of("COUNTERSIGN","ISSUE_INVOICE","RED_INVOICE","TAX_FILE").contains(act)&&amount.signum()>0)event(d,act,amount,in.occurredOn(),in.requestId(),Map.of("action",act,"reference",in.reference()));
        if(in.bankTransactionId()!=null){if(!CASH.contains(act))throw bad("只有资金动作可关联银行流水");match(in.bankTransactionId(),new Match(UUID.nameUUIDFromBytes((in.requestId()+":bank").getBytes(java.nio.charset.StandardCharsets.UTF_8)),List.of(new Allocation(actionId,amount)),"结算登记时核对"));}
        if(Set.of("DEPRECIATE","AMORTIZE").contains(act))convertUsage(p,d,amount);
        // A loan write-off settles an already-recognized expense, it is not a second budget actual.
        if(Set.of("WRITEOFF","REPAYMENT").contains(act))releaseCommitment(p,d,amount);
        audit.record("FINANCE_OPERATION_"+act,"FINANCE_OPERATION",id,json(in));return detail(id);
    }
    private void validateSettlement(TenantPrincipal p,Map<String,Object> d,Settlement in) {
        String type=str(d,"type"),a=in.action();UUID id=uid(d,"id");BigDecimal total=new FinanceControlGuard(jdbc).contractAmount(p,d),paid=sum(p,id,"PAYMENT").subtract(sum(p,id,"REFUND"));Map<String,Object> x=obj(d,"details");
        if(a.equals("DISPOSE"))throw conflict("请在财务中心“采购与资产”的真实财务卡片中核对净值、累计折旧与处置损益，办理完整处置及独立复核；旧零金额动作不能标记资产已处置");
        if(a.equals("PAYMENT"))new FinanceControlGuard(jdbc).requireActiveSourceForPayment(p,id);
        Set<String> allowed=typeActions(type);
        if(!allowed.contains(a))throw bad("此业务类型不支持该动作");
        if(CASH.contains(a)&&uid(d,"recognized_source_document_id")!=null)throw conflict("承接财务卡片不能重复办理资金；请到原应付单据登记收付款");
        if(a.equals("PAYMENT")&&paid.add(writeoffReceived(p,id)).add(in.amount()).compareTo(total)>0)throw conflict("累计付款及借款核销不能超过批准金额/剩余清偿余额");
        if(a.equals("REFUND")){BigDecimal basis=type.equals("REVENUE")?sum(p,id,"RECEIPT"):sum(p,id,"PAYMENT");if(sum(p,id,"REFUND").add(in.amount()).compareTo(basis)>0)throw conflict("退款不能超过已收/已付金额");if(type.equals("PREPAYMENT")&&paid.subtract(in.amount()).compareTo(sum(p,id,"AMORTIZE").add(recurringAllocated(p,id,null)))<0)throw conflict("退款后预付余额不足以覆盖已摊销金额，请先办理受控摊销冲回");}
        if(a.equals("RECEIPT")&&sum(p,id,"RECEIPT").add(in.amount()).compareTo(total)>0)throw conflict("累计收款不能超过确认应收金额");
        if(a.equals("REPAYMENT")||a.equals("WRITEOFF")){BigDecimal cleared=sum(p,id,"REPAYMENT").add(sum(p,id,"WRITEOFF"));if(cleared.add(in.amount()).compareTo(paid)>0)throw conflict("还款/核销累计不能超过借款已发放余额");if(a.equals("WRITEOFF")){
            if(in.amount().signum()<=0)throw bad("核销金额必须大于0");if(in.relatedDocumentId()==null)throw bad("核销必须关联已批准的实际费用单据");var expense=load(p,in.relatedDocumentId(),false);
            if(!str(expense,"status").equals("APPROVED")||!str(expense,"type").equals("PAYABLE")||!str(obj(expense,"details"),"treatment").equals("EXPENSE")||!uid(expense,"hotel_id").equals(uid(d,"hotel_id"))||!Objects.equals(uid(expense,"company_id"),uid(d,"company_id")))throw bad("只能用同组织同主体已批准的实际费用应付核销");
            if(!uid(expense,"applicant_id").equals(uuidField(x,"borrowerAccountId")))throw bad("核销费用申请人必须为本笔借款的借款人");
            BigDecimal settled=writeoffReceived(p,in.relatedDocumentId()).add(sum(p,in.relatedDocumentId(),"PAYMENT")).subtract(sum(p,in.relatedDocumentId(),"REFUND"));
            if(settled.add(in.amount()).compareTo(dec(expense,"amount"))>0)throw conflict("借款核销与已付款合计超过关联费用的剩余清偿余额");
        }}
        if(Set.of("DEPRECIATE","AMORTIZE").contains(a)){
            new FinanceControlGuard(jdbc).requireLegacyAccrualAvailable(p,id);
            if(jdbc.queryForObject("select count(*) from fa_recurring_plan where tenant_id=:tenantId and source_id=:source and source_type in ('FO_ASSET','FO_PREPAYMENT')",base(p).addValue("source",id),Integer.class)>0)throw conflict("本来源已有受控周期计划，请通过该计划执行，不能双轨重复计提");
            BigDecimal basis=recognizedBasis(p,d,in.occurredOn());BigDecimal ceiling=a.equals("DEPRECIATE")?basis.subtract(decimalField(x,"residualAmount")).max(BigDecimal.ZERO):basis;
            BigDecimal prior=sum(p,id,a).add(recurringAllocated(p,id,null));
            if(prior.add(in.amount()).compareTo(ceiling)>0)throw conflict("累计折旧/摊销超出真实已付或已核算来源的可计提价值");
            if(in.amount().signum()<=0)throw bad("计提金额必须大于0");YearMonth month=YearMonth.from(in.occurredOn());
            var b=base(p).addValue("id",id).addValue("action",a).addValue("start",month.atDay(1)).addValue("end",month.plusMonths(1).atDay(1));
            if(jdbc.queryForObject("select count(*) from fo_action where tenant_id=:tenantId and document_id=:id and action=:action and occurred_on>=:start and occurred_on<:end",b,Integer.class)>0||recurringAllocated(p,id,month).signum()>0)throw conflict("该月份已经计提（包括核算周期计划），不能重复执行");
            if(a.equals("DEPRECIATE")){
                LocalDate start=LocalDate.parse(x.get("inServiceDate").toString());int months=intField(x,"usefulMonths");YearMonth last=YearMonth.from(start).plusMonths(months-1);
                if(in.occurredOn().isBefore(start)||month.isAfter(last))throw bad("折旧日期必须在已批准投用日期及使用月数范围内，不能以第13月等方式绕过尾差");
                BigDecimal original=total.subtract(decimalField(x,"residualAmount")),standard=original.divide(BigDecimal.valueOf(months),2,RoundingMode.HALF_UP);
                BigDecimal expected=(month.equals(last)?original.subtract(standard.multiply(BigDecimal.valueOf(months-1))):standard).min(ceiling.subtract(prior));
                if(in.amount().compareTo(expected)!=0)throw bad("直线折旧金额应为 "+expected.toPlainString());
            } else {
                LocalDate start=LocalDate.parse(x.get("benefitStart").toString()),end=LocalDate.parse(x.get("benefitEnd").toString());if(in.occurredOn().isBefore(start)||in.occurredOn().isAfter(end))throw bad("计提日期不在受益期间内");
                if(str(x,"recognitionMethod").equals("STRAIGHT_LINE")){
                    long months=java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(start),YearMonth.from(end))+1;
                    // The approved schedule is stable; later funding changes availability, not the policy.
                    BigDecimal standard=total.divide(BigDecimal.valueOf(months),2,RoundingMode.HALF_UP);
                    BigDecimal expected=(month.equals(YearMonth.from(end))?total.subtract(standard.multiply(BigDecimal.valueOf(months-1))):standard).min(ceiling.subtract(prior));
                    if(in.amount().compareTo(expected)!=0)throw bad("直线摊销金额应为 "+expected.toPlainString());
                }
            }
        }
        if(a.equals("DISPOSE")&&sumCount(p,id,"DISPOSE")>0)throw conflict("资产已经处置");
        if(a.equals("DEPRECIATE")&&(sumCount(p,id,"DISPOSE")>0||new FinanceControlGuard(jdbc).disposed(p,id)))throw conflict("已处置资产不能继续折旧");
        if(a.equals("COUNTERSIGN")){if(!p.actorId().toString().equals(str(x,"counterpartyReviewerId")))throw forbidden();access.requireOrgScope(UUID.fromString(str(x,"counterpartyHotelId")));if(sumCount(p,id,a)>0)throw conflict("对方已确认");}
        if(type.equals("INTERCOMPANY")&&!a.equals("COUNTERSIGN")&&sumCount(p,id,"COUNTERSIGN")==0)throw conflict("内部往来尚未完成对方确认");
        if(a.equals("ISSUE_INVOICE")){if(in.receiptReference()==null||in.receiptReference().isBlank())throw bad("必须提供真实发票归档引用，开票服务尚未自动连接");BigDecimal issued=sum(p,id,a).subtract(sum(p,id,"RED_INVOICE"));if(issued.add(in.amount()).compareTo(total)>0)throw conflict("开票累计不能超过确认收入");}
        if(a.equals("RED_INVOICE")&&sum(p,id,a).add(in.amount()).compareTo(sum(p,id,"ISSUE_INVOICE"))>0)throw conflict("红冲不能超过已开票额");
        if(a.equals("TAX_FILE")){if(sumCount(p,id,a)>0)throw conflict("此税务任务已经归档申报结果");if(blank(in.receiptReference()).isEmpty())throw bad("必须提供真实申报回执归档引用，不能将取数视为已申报");}
    }
    public Map<String,Object> parties() {
        var p=prepare();var list=rows("select d.* from fo_party d where d.tenant_id=:tenantId and "+SCOPE+" order by d.name,d.id",base(p));
        if(!has(p,"finance.pay")&&!has(p,"finance.configure"))list.forEach(r->r.put("bankAccount",mask(Objects.toString(r.get("bankAccount"),""))));return Map.of("items",list,"total",list.size());
    }
    public Map<String,Object> saveParty(Party in) {
        var p=prepare("finance.configure");access.requireOrgScope(in.hotelId());requireHotel(p,in.hotelId());lock(p,"party:"+in.requestId());
        var b=base(p).addValue("id",in.requestId()).addValue("hotel",in.hotelId());var existing=jdbc.queryForList("select * from fo_party where tenant_id=:tenantId and id=:id for update",b);
        if(!existing.isEmpty()){if(!uid(existing.getFirst(),"hotel_id").equals(in.hotelId()))throw forbidden();version(existing.getFirst(),in.expectedVersion());}else if(in.expectedVersion()!=0)throw conflict("新建版本应为0");
        b.addValue("name",in.name().trim()).addValue("kind",in.kind()).addValue("tax",blank(in.taxNumber())).addValue("bank",blank(in.bankName())).addValue("account",blank(in.bankAccount()));
        if(existing.isEmpty())jdbc.update("insert into fo_party(id,tenant_id,hotel_id,name,kind,tax_number,bank_name,bank_account,created_by) values(:id,:tenantId,:hotel,:name,:kind,:tax,:bank,:account,:actorId)",b);
        else jdbc.update("update fo_party set name=:name,kind=:kind,tax_number=:tax,bank_name=:bank,bank_account=:account,version=version+1 where tenant_id=:tenantId and id=:id",b);
        audit.record("FINANCE_PARTY_SAVED","FINANCE_PARTY",in.requestId(),"{}");return external(party(p,in.requestId(),in.hotelId()));
    }
    public Map<String,Object> bankAccounts() {
        var p=prepare("finance.read","finance.pay","finance.configure");var list=rows("select d.* from fo_bank_account d where d.tenant_id=:tenantId and "+SCOPE+" order by d.name,d.id",base(p));
        if(!has(p,"finance.pay")&&!has(p,"finance.configure"))list.forEach(r->r.put("accountNumber",mask(Objects.toString(r.get("accountNumber"),""))));return Map.of("items",list,"total",list.size());
    }
    public Map<String,Object> saveBankAccount(BankAccount in) {
        var p=prepare("finance.configure");access.requireOrgScope(in.hotelId());requireHotel(p,in.hotelId());lock(p,"command:"+in.requestId());if(replayed(p,in.requestId(),in.requestId(),"BANK_ACCOUNT",in))return external(bank(p,in.requestId(),in.hotelId()));
        var b=base(p).addValue("id",in.requestId()).addValue("hotel",in.hotelId()).addValue("name",in.name().trim()).addValue("account",in.accountNumber().trim()).addValue("bank",in.bankName().trim()).addValue("date",in.openingDate()).addValue("balance",in.openingBalance());
        jdbc.update("insert into fo_bank_account(id,tenant_id,hotel_id,name,account_number,bank_name,opening_date,opening_balance,created_by) values(:id,:tenantId,:hotel,:name,:account,:bank,:date,:balance,:actorId)",b);if(new FinanceControlGuard(jdbc).installed()){var config=jdbc.queryForList("select company_id from finance_store_config where tenant_id=:tenantId and hotel_id=:hotel and enabled",b);if(config.size()!=1)throw conflict("请先启用实际法人主体配置再建立资金账户");jdbc.update("update fo_bank_account set company_id=:company where tenant_id=:tenantId and id=:id",b.addValue("company",config.getFirst().get("company_id")));}recordCommand(p,in.requestId(),in.requestId(),"BANK_ACCOUNT",in);audit.record("FINANCE_BANK_ACCOUNT_CREATED","FINANCE_BANK_ACCOUNT",in.requestId(),"{}");return external(bank(p,in.requestId(),in.hotelId()));
    }
    public Map<String,Object> bankTransactions(UUID accountId,int page) {
        var p=prepare("finance.read","finance.pay");if(page<0||page>10000)throw bad("分页无效");if(accountId!=null)bank(p,accountId,null);var b=base(p).addValue("account",accountId).addValue("offset",page*50);
        String filter="t.tenant_id=:tenantId and "+SCOPE+" and (cast(:account as uuid) is null or t.bank_account_id=:account)";
        long total=jdbc.queryForObject("select count(*) from fo_bank_transaction t join fo_bank_account d on d.tenant_id=t.tenant_id and d.id=t.bank_account_id where "+filter,b,Long.class);
        var list=rows("select t.*,d.name as bank_account_name,m.matched_amount,m.match_count,e.reason as explanation_reason,e.version as explanation_version,case when e.transaction_id is null then 'NONE' when e.transaction_hash_snapshot=t.source_hash and e.matched_amount_snapshot=m.matched_amount and e.match_count_snapshot=m.match_count then 'VALID' else 'STALE' end as explanation_status from fo_bank_transaction t join fo_bank_account d on d.tenant_id=t.tenant_id and d.id=t.bank_account_id left join lateral(select coalesce(sum(x.amount),0) as matched_amount,count(*) as match_count from fo_bank_match x where x.tenant_id=t.tenant_id and x.transaction_id=t.id) m on true left join fo_bank_explanation e on e.tenant_id=t.tenant_id and e.transaction_id=t.id where "+filter+" order by t.occurred_on desc,t.id limit 50 offset :offset",b);
        var guard=new FinanceControlGuard(jdbc);for(var row:list){BigDecimal net=guard.bankAllocated(p,UUID.fromString(row.get("id").toString()),LocalDate.of(9999,12,31));row.put("matchedAmount",net.toPlainString());row.put("unmatchedAmount",dec(row,"amount").subtract(net).toPlainString());if(guard.table("fcl_bank_explanation")){row.put("explanationStatus","LEGACY_NOTE_ONLY");row.put("explanationMeaning","旧往来备注不代表跨域净额已独立核对，请进入月结来源与银行核对。");}}
        return Map.of("items",list,"total",total,"page",page,"pageSize",50);
    }
    public Map<String,Object> explainBankTransaction(UUID transactionId,BankExplanation in) {
        if(new FinanceControlGuard(jdbc).table("fcl_bank_explanation"))throw conflict("请到核算与报表的月结核对，由独立复核人确认跨域净额银行未达；旧往来备注不能替代完整对账");
        var p=prepare("finance.review");if(in.reason()==null||in.reason().isBlank()||in.reason().length()>2000)throw bad("请填写非空的真实未达解释，最多2000字");
        lock(p,"bank-match");lock(p,"command:"+in.requestId());var b=base(p).addValue("id",transactionId);
        var txs=jdbc.queryForList("select * from fo_bank_transaction where tenant_id=:tenantId and id=:id",b);if(txs.isEmpty())throw missing();var transaction=txs.getFirst();bank(p,uid(transaction,"bank_account_id"),null);
        if(replayed(p,in.requestId(),transactionId,"BANK_EXPLANATION",in))return external(jdbc.queryForMap("select * from fo_bank_explanation where tenant_id=:tenantId and transaction_id=:id",b));
        var matched=jdbc.queryForMap("select coalesce(sum(amount),0) as amount,count(*) as count from fo_bank_match where tenant_id=:tenantId and transaction_id=:id",b);
        if(dec(matched,"amount").compareTo(dec(transaction,"amount"))>=0)throw conflict("流水已全额匹配，无需登记未达解释");
        var previous=jdbc.queryForList("select * from fo_bank_explanation where tenant_id=:tenantId and transaction_id=:id for update",b);
        int version=previous.isEmpty()?-1:((Number)previous.getFirst().get("version")).intValue();if(version!=in.expectedVersion())throw conflict("未达解释版本已变化，请刷新后核对");
        b.addValue("reason",in.reason().trim()).addValue("hash",transaction.get("source_hash")).addValue("matched",matched.get("amount")).addValue("count",matched.get("count"));
        jdbc.update("insert into fo_bank_explanation(tenant_id,transaction_id,reason,transaction_hash_snapshot,matched_amount_snapshot,match_count_snapshot,actor_id) values(:tenantId,:id,:reason,:hash,:matched,:count,:actorId) on conflict(tenant_id,transaction_id) do update set reason=excluded.reason,transaction_hash_snapshot=excluded.transaction_hash_snapshot,matched_amount_snapshot=excluded.matched_amount_snapshot,match_count_snapshot=excluded.match_count_snapshot,actor_id=excluded.actor_id,version=fo_bank_explanation.version+1,updated_at=now()",b);
        recordCommand(p,in.requestId(),transactionId,"BANK_EXPLANATION",in);audit.record("FINANCE_BANK_UNMATCHED_EXPLAINED","FINANCE_BANK_TRANSACTION",transactionId,json(Map.of("reason",in.reason().trim(),"matchedAmount",matched.get("amount"),"matchCount",matched.get("count"),"transactionHash",transaction.get("source_hash"),"version",version+1)));
        return external(jdbc.queryForMap("select * from fo_bank_explanation where tenant_id=:tenantId and transaction_id=:id",b));
    }
    public Map<String,Object> importBankTransactions(BankImport in) {
        var p=prepare("finance.pay");bank(p,in.bankAccountId(),null);lock(p,"bank-import:"+in.bankAccountId());int imported=0,existing=0;
        if(in.rows().stream().map(BankRow::externalId).distinct().count()!=in.rows().size())throw bad("同一批次流水外部编号不可重复");
        for(var row:in.rows()){
            var b=base(p).addValue("account",in.bankAccountId()).addValue("external",row.externalId().trim());String hash=FinanceService.hash(json(row));var prior=jdbc.queryForList("select source_hash from fo_bank_transaction where tenant_id=:tenantId and bank_account_id=:account and external_id=:external",b);
            if(!prior.isEmpty()){if(!hash.equals(prior.getFirst().get("source_hash")))throw conflict("已导入流水内容不同："+row.externalId()+"，整批未生效");existing++;continue;}
            b.addValue("id",UUID.randomUUID()).addValue("batch",in.batchId()).addValue("date",row.occurredOn()).addValue("direction",row.direction()).addValue("amount",row.amount()).addValue("counterparty",row.counterparty().trim()).addValue("reference",row.reference().trim()).addValue("hash",hash);
            jdbc.update("insert into fo_bank_transaction(id,tenant_id,bank_account_id,batch_id,external_id,occurred_on,direction,amount,counterparty,reference,source_hash,created_by) values(:id,:tenantId,:account,:batch,:external,:date,:direction,:amount,:counterparty,:reference,:hash,:actorId)",b);imported++;
        }
        audit.record("FINANCE_BANK_IMPORTED","FINANCE_BANK_ACCOUNT",in.bankAccountId(),json(Map.of("batchId",in.batchId(),"imported",imported)));return Map.of("imported",imported,"existing",existing,"batchId",in.batchId());
    }
    public Map<String,Object> match(UUID transactionId,Match in) {
        var p=prepare("finance.pay");lock(p,"bank-match");lock(p,"command:"+in.requestId());if(replayed(p,in.requestId(),transactionId,"BANK_MATCH",in))return bankTransactions(null,0);
        if(in.allocations().stream().map(Allocation::settlementId).distinct().count()!=in.allocations().size())throw bad("同一流水的结算分配不可重复");var b=base(p).addValue("id",transactionId);
        // Immutable imported rows intentionally have no UPDATE grant; tenant advisory lock serializes allocations.
        var txs=jdbc.queryForList("select * from fo_bank_transaction where tenant_id=:tenantId and id=:id",b);if(txs.isEmpty())throw missing();var tx=txs.getFirst();var account=bank(p,uid(tx,"bank_account_id"),null);
        BigDecimal allocated=in.allocations().stream().map(Allocation::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal matched=new FinanceControlGuard(jdbc).bankAllocated(p,transactionId,LocalDate.of(9999,12,31));
        if(matched.add(allocated).compareTo(dec(tx,"amount"))>0)throw conflict("匹配金额超过银行流水净未匹配余额（包括报销、收入与供应商退款占用）");
        for(var allocation:in.allocations().stream().sorted(Comparator.comparing(a->a.settlementId().toString())).toList()){
            var ab=base(p).addValue("id",allocation.settlementId());var actions=jdbc.queryForList("select * from fo_action where tenant_id=:tenantId and id=:id",ab);if(actions.isEmpty())throw missing();var action=actions.getFirst();var d=load(p,uid(action,"document_id"),false);
            if(!uid(d,"hotel_id").equals(uid(account,"hotel_id"))||!uid(tx,"bank_account_id").equals(uid(action,"bank_account_id")))throw bad("流水与结算出入款账户或组织不同");
            String direction=Set.of("RECEIPT","REPAYMENT").contains(str(action,"action"))||str(action,"action").equals("REFUND")&&!str(d,"type").equals("REVENUE")?"IN":"OUT";
            if(!CASH.contains(str(action,"action"))||!direction.equals(str(tx,"direction")))throw bad("流水方向与结算动作不符");
            boolean net=new FinanceControlGuard(jdbc).table("fx_cash_correction");BigDecimal used=jdbc.queryForObject(net?"select fx_bank_action_matched_amount(:tenantId,:id,'9999-12-31'::date)":"select coalesce(sum(amount),0) from fo_bank_match where tenant_id=:tenantId and action_id=:id",ab,BigDecimal.class);BigDecimal effective=jdbc.queryForObject("select "+cashAmountSql("a","'9999-12-31'::date")+" from fo_action a where a.tenant_id=:tenantId and a.id=:id",ab,BigDecimal.class);if(used.add(allocation.amount()).compareTo(effective)>0)throw conflict("累计匹配超过差错更正后的真实净结算金额");
            ab.addValue("tx",transactionId).addValue("amount",allocation.amount()).addValue("request",in.requestId()).addValue("note",in.note());jdbc.update("insert into fo_bank_match(tenant_id,transaction_id,action_id,amount,request_id,note,actor_id) values(:tenantId,:tx,:id,:amount,:request,:note,:actorId)",ab);
        }
        recordCommand(p,in.requestId(),transactionId,"BANK_MATCH",in);audit.record("FINANCE_BANK_MATCHED","FINANCE_BANK_TRANSACTION",transactionId,json(in));return Map.of("transactionId",transactionId,"allocated",allocated.toPlainString(),"matchedAmount",matched.add(allocated).toPlainString());
    }
    public Map<String,Object> budgets() {
        var p=prepare("finance.read","finance.review","finance.configure");var list=rows("select d.*,coalesce(u.committed,0) as committed,coalesce(u.inflight,0) as inflight,coalesce(u.actual,0) as actual from fo_budget d left join lateral (select sum(committed) committed,sum(inflight) inflight,sum(actual) actual from fo_budget_usage u where u.tenant_id=d.tenant_id and u.budget_id=d.id) u on true where d.tenant_id=:tenantId and "+SCOPE+" order by d.month desc,d.category_code,d.id",base(p));return Map.of("items",list,"total",list.size());
    }
    public Map<String,Object> saveBudget(Budget in) {
        var p=prepare("finance.configure");access.requireOrgScope(in.hotelId());requireHotel(p,in.hotelId());YearMonth month=parseMonth(in.month());lock(p,"budget:"+in.requestId());var b=base(p).addValue("id",in.requestId()).addValue("hotel",in.hotelId()).addValue("month",month.atDay(1)).addValue("category",in.categoryCode().trim()).addValue("amount",in.amount()).addValue("control",in.control());
        var old=jdbc.queryForList("select * from fo_budget where tenant_id=:tenantId and id=:id for update",b);if(old.isEmpty()){if(in.expectedVersion()!=0)throw conflict("新预算版本应为0");jdbc.update("insert into fo_budget(id,tenant_id,hotel_id,month,category_code,amount,control,created_by) values(:id,:tenantId,:hotel,:month,:category,:amount,:control,:actorId)",b);}else{
            version(old.getFirst(),in.expectedVersion());if(!uid(old.getFirst(),"hotel_id").equals(in.hotelId())||!date(old.getFirst(),"month").equals(month.atDay(1))||!str(old.getFirst(),"category_code").equals(in.categoryCode().trim()))throw bad("已有预算不可更换组织、期间或类别");
            BigDecimal used=jdbc.queryForObject("select coalesce(sum(committed+inflight+actual),0) from fo_budget_usage where tenant_id=:tenantId and budget_id=:id",b,BigDecimal.class);if(in.control().equals("BLOCK")&&in.amount().compareTo(used)<0)throw conflict("强控制预算不能小于已占用金额");jdbc.update("update fo_budget set amount=:amount,control=:control,version=version+1,updated_at=now() where tenant_id=:tenantId and id=:id",b);
        }audit.record("FINANCE_BUDGET_SAVED","FINANCE_BUDGET",in.requestId(),json(in));return external(jdbc.queryForMap("select * from fo_budget where tenant_id=:tenantId and id=:id",b));
    }
    public Map<String,Object> calendar() {
        var p=prepare();var b=base(p);var items=rows("select d.id,d.hotel_id,d.type,d.title,d.status,d.amount,d.due_date,d.applicant_id,d.cashier_account_id from fo_document d where d.tenant_id=:tenantId and "+VISIBLE+" and d.due_date is not null and d.status not in ('REJECTED','CANCELLED') and not exists(select 1 from fo_action a where a.tenant_id=d.tenant_id and a.document_id=d.id and a.action in ('TAX_FILE','DISPOSE')) order by d.due_date,d.id",b);return Map.of("items",items,"total",items.size(),"includesCrossDay",true);
    }
    public Map<String,Object> reports(UUID hotelId,String monthValue) {
        var p=prepare("finance.read");if(hotelId!=null)access.requireOrgScope(hotelId);YearMonth month=parseMonth(monthValue);var b=base(p).addValue("hotel",hotelId).addValue("start",month.atDay(1)).addValue("end",month.plusMonths(1).atDay(1));
        String filter="d.tenant_id=:tenantId and "+VISIBLE+" and (cast(:hotel as uuid) is null or d.hotel_id=:hotel)";
        var totals=rows("select d.type,count(*) as document_count,sum(d.amount) as amount,count(*) filter(where d.status='REVIEW') as pending_review from fo_document d where "+filter+" and d.business_date>=:start and d.business_date<:end and d.status not in ('REJECTED','CANCELLED') group by d.type order by d.type",b);
        String balanceSql="select d.id,d.hotel_id,d.type,d.title,d.amount,d.business_date,d.due_date,coalesce(a.paid,0) as paid,coalesce(a.refunded,0) as refunded,coalesce(a.received,0) as received,coalesce(a.cleared,0) as cleared from fo_document d left join lateral(select sum(amount) filter(where action='PAYMENT') paid,sum(amount) filter(where action='REFUND') refunded,sum(amount) filter(where action='RECEIPT') received,sum(amount) filter(where action in ('WRITEOFF','REPAYMENT','AMORTIZE')) cleared from fo_action a where a.tenant_id=d.tenant_id and a.document_id=d.id and a.occurred_on<:end) a on true where "+filter+" and d.status='APPROVED' and d.business_date<:end and d.type in ('PAYABLE','LOAN','PREPAYMENT','REVENUE','CONTRACT') order by d.business_date,d.id";var balances=rows(balanceSql.replace("sum(amount)","sum("+cashAmountSql("a","(cast(:end as date)-1)")+")"),b);
        var incomplete=jdbc.queryForObject("select count(*) from fo_document d where "+filter+" and d.status='APPROVED' and d.business_date>=:start and d.business_date<:end and not exists(select 1 from fa_source_event e where e.tenant_id=d.tenant_id and e.metadata->>'documentId'=d.id::text and e.status='POSTED') and d.type not in ('CONTRACT','TAX_TASK','INTERCOMPANY')",b,Integer.class);
        return Map.of("month",month.toString(),"basis","BUSINESS_SUBLEDGER_NOT_GENERAL_LEDGER","totals",totals,"balances",balances,"dataPresent",!totals.isEmpty(),"unpostedSourceDocuments",incomplete,"completeness",List.of("仅包含中台实际登记的业务单据；缺少的外部来源不按零值补齐","预算、付款和成本是不同口径；总账报表以核算模块已过账分录为准"));
    }
    private BigDecimal validate(TenantPrincipal p,Document in,UUID targetId) {
        if(!TYPES.contains(in.type())||in.lines()==null||in.lines().isEmpty()||in.lines().size()>500)throw bad("业务类型或明细无效");
        if(json(in.details()).length()>100000)throw bad("业务资料过长");rejectFloating(in.details());
        BigDecimal amount=BigDecimal.ZERO;for(var line:in.lines()){
            if(line.quantity()==null||line.quantity().signum()<=0||line.unitPrice()==null||line.unitPrice().signum()<0)throw bad("数量须大于0，单价不可为负");
            if(line.departmentId()!=null){access.requireOrgScope(line.departmentId());int n=jdbc.queryForObject("select count(*) from org_unit_closure where tenant_id=:tenantId and ancestor_id=:hotel and descendant_id=:department",base(p).addValue("hotel",in.hotelId()).addValue("department",line.departmentId()),Integer.class);if(n==0)throw bad("明细部门不属于所选组织");}
            amount=amount.add(line.quantity().multiply(line.unitPrice()).setScale(2,RoundingMode.HALF_UP));
        }if(amount.signum()<=0||amount.precision()-amount.scale()>14)throw bad("单据金额必须大于0且不超金额上限");
        Map<String,Object> x=in.details();switch(in.type()){
            case "CONTRACT" -> {required(x,"contractNumber","startDate","endDate","paymentPlan");LocalDate start=localDate(x,"startDate"),end=localDate(x,"endDate");if(end.isBefore(start))throw bad("合同结束日期早于开始日期");
                if(!(x.get("paymentPlan") instanceof List<?> plan)||plan.isEmpty()||plan.size()>120)throw bad("请填写真实付款计划");BigDecimal scheduled=BigDecimal.ZERO;for(Object item:plan){if(!(item instanceof Map<?,?> m))throw bad("付款计划格式无效");LocalDate day;try{day=LocalDate.parse(Objects.toString(m.get("date")));}catch(Exception e){throw bad("付款计划日期无效");}if(day.isBefore(start)||day.isAfter(end))throw bad("付款计划超出合同期间");BigDecimal value;try{value=new BigDecimal(Objects.toString(m.get("amount"))).setScale(2,RoundingMode.UNNECESSARY);}catch(Exception e){throw bad("付款计划金额无效");}if(value.signum()<=0)throw bad("付款计划金额须大于0");scheduled=scheduled.add(value);}if(scheduled.compareTo(amount)>0)throw bad("付款计划超过合同总金额");}
            case "PAYABLE" -> {required(x,"acceptanceReference","invoiceReference","orderReference","treatment");if(!Set.of("EXPENSE","INVENTORY","ASSET","PREPAID").contains(str(x,"treatment")))throw bad("应付必须由财务明确费用/存货/资产/预付性质，不自动当作费用");if(in.sourceId()!=null&&"PROCUREMENT_RECEIPT".equals(in.sourceType())){
                var b=base(p).addValue("id",in.sourceId()).addValue("hotel",in.hotelId());var receipts=jdbc.queryForList("select r.status from procurement_receipt r join procurement_order o on o.tenant_id=r.tenant_id and o.id=r.order_id where r.tenant_id=:tenantId and r.id=:id and o.hotel_id=:hotel",b);if(receipts.isEmpty()||!"APPROVED".equals(str(receipts.getFirst(),"status")))throw bad("采购来源批次未完成主管复核，请核对真实收货来源");}}
            case "LOAN" -> {required(x,"borrowerAccountId","purpose","repayBy");requireAccount(p,uuidField(x,"borrowerAccountId"));if(localDate(x,"repayBy").isBefore(in.businessDate()))throw bad("借款归还日期早于业务日期");}
            case "PREPAYMENT" -> {required(x,"benefitStart","benefitEnd","contractReference","recognitionMethod");if(localDate(x,"benefitEnd").isBefore(localDate(x,"benefitStart")))throw bad("受益期间无效");if(!Set.of("STRAIGHT_LINE","MANUAL").contains(str(x,"recognitionMethod")))throw bad("摊销方法无效");}
            case "ASSET" -> {required(x,"assetCode","inServiceDate","usefulMonths","residualAmount","method");localDate(x,"inServiceDate");int months=intField(x,"usefulMonths");if(months<1||months>1200)throw bad("折旧月数须在1至1200之间");BigDecimal residual=decimalField(x,"residualAmount");if(residual.signum()<0||residual.compareTo(amount)>=0||!str(x,"method").equals("STRAIGHT_LINE"))throw bad("残值或折旧方法无效");if(x.get("physicalAssetId")!=null&&!str(x,"physicalAssetId").isBlank()){
                UUID physical=uuidField(x,"physicalAssetId");int count=jdbc.queryForObject("select count(*) from store_fixed_asset where tenant_id=:tenantId and id=:id and hotel_org_unit_id=:hotel",base(p).addValue("id",physical).addValue("hotel",in.hotelId()),Integer.class);if(count!=1)throw bad("关联实物资产不属于本门店");}}
            case "PAYROLL" -> {access.requirePermission("finance.payroll");required(x,"payrollMonth","confirmedByAccountId","employeeAccountIds");parseMonth(str(x,"payrollMonth"));UUID confirmed=uuidField(x,"confirmedByAccountId");requireAccount(p,confirmed);if(confirmed.equals(p.actorId()))throw bad("经确认工资表的确认人不能为申请人");if(!(x.get("employeeAccountIds") instanceof List<?> ids)||ids.isEmpty()||ids.size()>500)throw bad("工资表必须关联实际员工账号");for(Object account:ids)try{requireAccount(p,UUID.fromString(account.toString()));}catch(IllegalArgumentException e){throw bad("员工账号格式无效");}}
            case "REVENUE" -> {required(x,"externalSystem","externalReference","nightAuditDate","revenueKind");localDate(x,"nightAuditDate");if(!Set.of("ROOM","OTHER","DEPOSIT","PREPAID").contains(str(x,"revenueKind")))throw bad("收入性质无效；退款请在原收入/押金单执行退款以校验真实可退余额");if(in.sourceId()==null)throw bad("来源导入必须提供稳定的sourceId，避免重跑重复确认");}
            case "INTERCOMPANY" -> {required(x,"counterpartyHotelId","basisReference","counterpartyReviewerId");UUID other=uuidField(x,"counterpartyHotelId");if(other.equals(in.hotelId()))throw bad("往来双方不能为同一组织");requireHotel(p,other);requireAccount(p,uuidField(x,"counterpartyReviewerId"));}
            case "TAX_TASK" -> {required(x,"taxPeriod","taxType","dueDate","policyReference");parseMonth(str(x,"taxPeriod"));localDate(x,"dueDate");if(in.dueDate()==null||!in.dueDate().equals(localDate(x,"dueDate")))throw bad("税务任务截止日期必须一致");}
            default -> throw bad("未知业务类型");
        }
        if(!str(x,"recognizedSourceDocumentId").isBlank()){
            if(!Set.of("ASSET","PREPAYMENT").contains(in.type()))throw bad("只有资产卡片/待摊可承接既有购买核算来源");
            UUID sourceId=uuidField(x,"recognizedSourceDocumentId");lock(p,"recognized-source:"+sourceId);
            if(jdbc.queryForObject("select count(*) from fo_document where tenant_id=:tenantId and recognized_source_document_id=:source and id<>:id and status not in ('REJECTED','CANCELLED')",base(p).addValue("source",sourceId).addValue("id",targetId),Integer.class)>0)throw conflict("该应付来源已被有效财务卡片承接，不能重复分配、付款或计提");
            var source=load(p,sourceId,false);String expected=in.type().equals("ASSET")?"ASSET":"PREPAID";
            if(!uid(source,"hotel_id").equals(in.hotelId())||!str(source,"type").equals("PAYABLE")||!str(source,"status").equals("APPROVED")||!str(obj(source,"details"),"treatment").equals(expected)||dec(source,"amount").compareTo(amount)!=0)throw bad("承接来源必须为本组织已批准且同金额、同性质的购买应付单");
            if(in.budgetId()!=null&&!in.budgetId().equals(uid(source,"budget_id")))throw bad("承接卡片沿用原来源预算，不可重复占用其他预算");
        }
        return amount.setScale(2,RoundingMode.UNNECESSARY);
    }
    public void authorizeUpload(UUID documentId,String kind,UUID actionId) {
        var p=prepare();var d=load(p,documentId,false);
        if(!Set.of("CONTRACT","INVOICE","ACCEPTANCE","TRANSACTION","PAYMENT","PAYROLL","OTHER").contains(kind))throw bad("凭证种类无效");
        if(actionId!=null){access.requirePermission("finance.pay");if(!kind.equals("PAYMENT")||!str(d,"status").equals("APPROVED")||!p.actorId().equals(uid(d,"cashier_account_id"))||p.actorId().equals(uid(d,"applicant_id")))throw forbidden();
            int n=jdbc.queryForObject("select count(*) from fo_action where tenant_id=:tenantId and document_id=:doc and id=:action and action in ('PAYMENT','RECEIPT','REFUND','REPAYMENT')",base(p).addValue("doc",documentId).addValue("action",actionId),Integer.class);if(n!=1)throw bad("回单必须关联本单真实收付款记录");
        }else{access.requirePermission("finance.submit");if(!p.actorId().equals(uid(d,"applicant_id"))||!EDITABLE.contains(str(d,"status")))throw conflict("已提交单据不能更换原始证据；出纳补回单必须关联收付款编号");}
    }
    public Map<String,Object> registerAttachment(UUID documentId,UUID id,String kind,UUID actionId,String name,String type,long size,String hash,String key,String scan,Map<String,Object> extraction) {
        var p=prepare();load(p,documentId,true);authorizeUpload(documentId,kind,actionId);var b=base(p).addValue("doc",documentId).addValue("id",id);
        var prior=jdbc.queryForList("select * from fo_attachment where tenant_id=:tenantId and id=:id",b);if(!prior.isEmpty()){var r=prior.getFirst();if(!documentId.equals(uid(r,"document_id"))||!Objects.equals(actionId,uid(r,"action_id"))||!kind.equals(str(r,"kind"))||!hash.equals(str(r,"sha256")))throw conflict("凭证编号已用于不同原件");return detail(documentId);}
        if(size<=0||size>20L*1024*1024||!hash.matches("[a-f0-9]{64}")||!key.startsWith(p.tenantId()+"/"+documentId+"/"))throw bad("原件元数据无效");
        b.addValue("action",actionId).addValue("kind",kind).addValue("name",name).addValue("type",type).addValue("size",size).addValue("hash",hash).addValue("key",key).addValue("scan",scan).addValue("extraction",json(extraction));
        jdbc.update("insert into fo_attachment(id,tenant_id,document_id,action_id,kind,original_name,media_type,size_bytes,sha256,object_key,scan_status,extraction,uploaded_by) values(:id,:tenantId,:doc,:action,:kind,:name,:type,:size,:hash,:key,:scan,cast(:extraction as jsonb),:actorId)",b);
        jdbc.update("update fo_document set version=version+1,updated_at=now() where tenant_id=:tenantId and id=:doc",b);audit.record("FINANCE_OPERATION_EVIDENCE_UPLOADED","FINANCE_OPERATION",documentId,json(Map.of("attachmentId",id,"kind",kind,"sha256",hash)));return detail(documentId);
    }
    public Map<String,Object> attachment(UUID documentId,UUID id) {
        var p=prepare();load(p,documentId,false);var found=jdbc.queryForList("select * from fo_attachment where tenant_id=:tenantId and document_id=:doc and id=:id",base(p).addValue("doc",documentId).addValue("id",id));if(found.isEmpty())throw missing();audit.record("FINANCE_OPERATION_EVIDENCE_DOWNLOADED","FINANCE_OPERATION",documentId,json(Map.of("attachmentId",id)));return found.getFirst();
    }
    private void notifyCurrent(TenantPrincipal p,UUID id) {
        var d=load(p,id,false);String status=str(d,"status");UUID recipient;String kind;
        if(status.equals("REVIEW")){int node=((Number)d.get("review_index")).intValue();recipient=new FinanceDelegationControl(jdbc).current(p,"OPERATION",id,node,UUID.fromString(stringList(d.get("approval_route")).get(node)));kind="REVIEW_PENDING";}
        else if(status.equals("APPROVED")&&!Set.of("REVENUE","INTERCOMPANY").contains(str(d,"type"))){recipient=uid(d,"cashier_account_id");kind="PAYMENT_PENDING";}
        else if(status.equals("RETURNED")){recipient=uid(d,"applicant_id");kind="RETURNED";}else return;
        if(recipient==null)return;
        jdbc.update("insert into notification(tenant_id,recipient_account_id,notification_type,title,content,source_type,source_id,idempotency_key) values(:tenantId,:recipient,:type,'财务业务待办','有一笔财务业务等待您处理。请进入中台核对真实单据和原件；办理权限以实时状态为准。','FINANCE_OPERATION',:id,:key) on conflict(tenant_id,recipient_account_id,idempotency_key) do nothing",base(p).addValue("recipient",recipient).addValue("type","FINANCE_OPERATION_"+kind).addValue("id",id).addValue("key","finance-operation:"+id+":"+status+":"+d.get("review_index")+":"+d.get("version")));
    }
    private void usage(TenantPrincipal p,Map<String,Object> d,BigDecimal committed,BigDecimal inflight,BigDecimal actual) {
        if(uid(d,"recognized_source_document_id")!=null)return; // Original payable owns the one budget reservation.
        UUID budgetId=uid(d,"budget_id");if(budgetId==null)return;var configured=budget(p,budgetId,uid(d,"hotel_id"),date(d,"business_date"));
        var b=base(p).addValue("budget",budgetId).addValue("id",uid(d,"id")).addValue("committed",committed).addValue("inflight",inflight).addValue("actual",actual);
        BigDecimal other=jdbc.queryForObject("select coalesce(sum(committed+inflight+actual),0) from fo_budget_usage where tenant_id=:tenantId and budget_id=:budget and document_id<>:id",b,BigDecimal.class);
        if(str(configured,"control").equals("BLOCK")&&other.add(committed).add(inflight).add(actual).compareTo(dec(configured,"amount"))>0)throw conflict("预算超额且为强控制，不能提交；可由授权财务调整预算后重试");
        jdbc.update("insert into fo_budget_usage(tenant_id,budget_id,document_id,committed,inflight,actual) values(:tenantId,:budget,:id,:committed,:inflight,:actual) on conflict(tenant_id,document_id) do update set budget_id=excluded.budget_id,committed=excluded.committed,inflight=excluded.inflight,actual=excluded.actual,updated_at=now()",b);
    }
    private void convertUsage(TenantPrincipal p,Map<String,Object> d,BigDecimal amount) {
        accounting.convertSourceBudgetUsage(p,d,amount);
    }
    private void releaseCommitment(TenantPrincipal p,Map<String,Object> d,BigDecimal amount) {
        if(uid(d,"budget_id")==null)return;budget(p,uid(d,"budget_id"),uid(d,"hotel_id"),date(d,"business_date"));
        var b=base(p).addValue("id",uid(d,"id")).addValue("amount",amount);
        jdbc.update("update fo_budget_usage set committed=greatest(committed-:amount,0),updated_at=now() where tenant_id=:tenantId and document_id=:id",b);
    }
    void releaseLoanCommitment(TenantPrincipal p,Map<String,Object> loan,BigDecimal amount){if(!"LOAN".equals(loan.get("type")))throw bad("只能释放真实借款已清偿承诺");releaseCommitment(p,loan,amount);}
    private BigDecimal writeoffReceived(TenantPrincipal p,UUID documentId) {
        return jdbc.queryForObject("select coalesce(sum(amount),0) from fo_action where tenant_id=:tenantId and related_document_id=:id and action='WRITEOFF'",base(p).addValue("id",documentId),BigDecimal.class);
    }
    private BigDecimal recurringAllocated(TenantPrincipal p,UUID documentId,YearMonth month) {
        return jdbc.queryForObject("select coalesce(sum(r.amount),0) from fa_recurring_run r join fa_recurring_plan p on p.tenant_id=r.tenant_id and p.id=r.plan_id where r.tenant_id=:tenantId and p.source_id=:source and p.source_type in ('FO_ASSET','FO_PREPAYMENT') and (cast(:month as date) is null or r.month=:month)",base(p).addValue("source",documentId).addValue("month",month==null?null:month.atDay(1)),BigDecimal.class);
    }
    /** A prepayment approval is authorization, not money or an already recognized asset. */
    BigDecimal recognizedBasis(TenantPrincipal p,Map<String,Object> d,LocalDate asOf) {
        UUID parent=uid(d,"recognized_source_document_id");
        if(parent!=null){
            var source=load(p,parent,false);if(!Objects.equals(uid(d,"company_id"),uid(source,"company_id")))throw conflict("承接来源法人主体与财务卡片不一致");
            BigDecimal recognized=jdbc.queryForObject("select coalesce(sum(amount),0) from fa_source_event where tenant_id=:tenantId and metadata->>'documentId'=cast(:source as text) and event_key='RECOGNITION' and status='POSTED' and accounting_date<=:asOf",base(p).addValue("source",parent).addValue("asOf",asOf),BigDecimal.class);
            if(recognized.compareTo(dec(d,"amount"))<0)throw conflict("承接应付来源尚未在本期完成核算过账，不能据批准额度计提");
            return dec(d,"amount");
        }
        if(str(d,"type").equals("PREPAYMENT"))return jdbc.queryForObject("select coalesce(sum(case when a.action='PAYMENT' then "+cashAmountSql("a",":asOf")+" when a.action='REFUND' then -"+cashAmountSql("a",":asOf")+" else 0 end),0) from fo_action a where a.tenant_id=:tenantId and a.document_id=:id and a.occurred_on<=:asOf",base(p).addValue("id",uid(d,"id")).addValue("asOf",asOf),BigDecimal.class).max(BigDecimal.ZERO);
        return dec(d,"amount");
    }
    private Map<String,Object> budget(TenantPrincipal p,UUID id,UUID hotel,LocalDate day) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key,103))",base(p).addValue("key",p.tenantId()+":financial-budget"),Object.class);
        var rows=jdbc.queryForList("select * from fo_budget where tenant_id=:tenantId and id=:id for update",base(p).addValue("id",id));if(rows.isEmpty())throw bad("预算不存在");var row=rows.getFirst();access.requireOrgScope(uid(row,"hotel_id"));if(!uid(row,"hotel_id").equals(hotel)||!date(row,"month").equals(YearMonth.from(day).atDay(1)))throw bad("预算不属于所选组织/期间");return row;
    }
    private UUID action(TenantPrincipal p,Map<String,Object> d,String action,BigDecimal amount,UUID requestId,String note,String reference,String receipt,UUID bank,UUID related,LocalDate day,Map<String,Object> snapshot) {
        var frozen=new LinkedHashMap<>(snapshot);frozen.put("evidenceIds",jdbc.queryForList("select id,sha256 from fo_attachment where tenant_id=:tenantId and document_id=:doc order by id",base(p).addValue("doc",uid(d,"id"))));
        UUID id=UUID.randomUUID();var b=base(p).addValue("id",id).addValue("doc",uid(d,"id")).addValue("action",action).addValue("amount",amount).addValue("day",day).addValue("reference",reference).addValue("note",note).addValue("receipt",blank(receipt)).addValue("bank",bank).addValue("related",related).addValue("request",requestId).addValue("snapshot",json(frozen));
        jdbc.update("insert into fo_action(id,tenant_id,document_id,actor_id,action,amount,occurred_on,reference,note,receipt_reference,bank_account_id,related_document_id,request_id,snapshot) values(:id,:tenantId,:doc,:actorId,:action,:amount,:day,:reference,:note,:receipt,:bank,:related,:request,cast(:snapshot as jsonb))",b);return id;
    }
    private void event(Map<String,Object> d,String key,BigDecimal amount,LocalDate day,UUID sourceId,Map<String,String> metadata) {
        var meta=new LinkedHashMap<>(metadata);meta.put("documentId",uid(d,"id").toString());meta.put("documentType",str(d,"type"));meta.put("sourceType",str(d,"source_type"));meta.put("sourceId",Objects.toString(d.get("source_id"),""));meta.put("companyId",Objects.toString(d.get("company_id"),""));
        // Different economic events have different rule keys. Payments must never reuse an expense recognition rule.
        if(str(d,"type").equals("REVENUE"))meta.put("revenueKind",str(obj(d,"details"),"revenueKind"));
        String sourceType="FO_"+str(d,"type");if(str(d,"type").equals("REVENUE"))sourceType+="_"+str(obj(d,"details"),"revenueKind");if(str(d,"type").equals("PAYABLE"))sourceType+="_"+str(obj(d,"details"),"treatment");
        accounting.recordSourceEvent(uid(d,"hotel_id"),sourceType,sourceId,key,date(d,"business_date"),day,amount,str(d,"title"),meta);
    }
    private String cashAmountSql(String alias,String day){return new FinanceControlGuard(jdbc).table("fx_cash_correction")?"fx_cash_effective_amount("+alias+".tenant_id,'OPERATION',"+alias+".id,"+alias+".amount,"+day+")":alias+".amount";}
    private BigDecimal sum(TenantPrincipal p,UUID id,String action) {return new FinanceControlGuard(jdbc).operationAmount(p,id,action);}
    private int sumCount(TenantPrincipal p,UUID id,String action) {return jdbc.queryForObject("select count(*) from fo_action where tenant_id=:tenantId and document_id=:id and action=:action",base(p).addValue("id",id).addValue("action",action),Integer.class);}
    private Map<String,Object> load(TenantPrincipal p,UUID id,boolean lock) {
        var rows=jdbc.queryForList("select d.*,h.name as hotel_name,u.display_name as applicant_name from fo_document d join org_unit h on h.tenant_id=d.tenant_id and h.id=d.hotel_id join user_account u on u.tenant_id=d.tenant_id and u.id=d.applicant_id where d.tenant_id=:tenantId and d.id=:id and "+visibleSql()+(lock?" for update of d":""),base(p).addValue("id",id));if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    private String visibleSql(){if(!new FinanceDelegationControl(jdbc).installed())return VISIBLE;return VISIBLE.replace("(:reviewer and d.approval_route @> cast(:actorRoute as jsonb))","(:reviewer and (d.approval_route @> cast(:actorRoute as jsonb) or exists(select 1 from fd_route dr where dr.tenant_id=d.tenant_id and dr.source_kind='OPERATION' and dr.source_id=d.id and fd_current_reviewer(dr.tenant_id,dr.source_kind,dr.source_id,dr.node,dr.original_id)=:actorId)))");}
    private static Set<String> typeActions(String type){return switch(type){case "CONTRACT","PAYABLE"->Set.of("PAYMENT","REFUND");case "LOAN"->Set.of("PAYMENT","REPAYMENT","WRITEOFF");case "PREPAYMENT"->Set.of("PAYMENT","REFUND","AMORTIZE");case "ASSET"->Set.of("PAYMENT","REFUND","DEPRECIATE","DISPOSE");case "PAYROLL"->Set.of("PAYMENT");case "REVENUE"->Set.of("RECEIPT","REFUND","ISSUE_INVOICE","RED_INVOICE");case "INTERCOMPANY"->Set.of("COUNTERSIGN","PAYMENT","RECEIPT");case "TAX_TASK"->Set.of("TAX_FILE","PAYMENT");default->Set.of();};}
    private List<String> allowedActions(TenantPrincipal p,Map<String,Object> d){
        if(!str(d,"status").equals("APPROVED")||p.actorId().equals(uid(d,"applicant_id")))return List.of();
        UUID id=uid(d,"id");var result=new ArrayList<String>();var x=obj(d,"details");
        for(String action:List.of("PAYMENT","RECEIPT","REFUND","REPAYMENT","WRITEOFF","DEPRECIATE","AMORTIZE","DISPOSE","COUNTERSIGN","ISSUE_INVOICE","RED_INVOICE","TAX_FILE")){
            if(!typeActions(str(d,"type")).contains(action))continue;
            if(action.equals("DISPOSE"))continue; // Do not present an unimplemented accounting action as a successful disposal.
            if(CASH.contains(action)&&uid(d,"recognized_source_document_id")!=null)continue;
            if(str(d,"type").equals("INTERCOMPANY")&&!action.equals("COUNTERSIGN")&&sumCount(p,id,"COUNTERSIGN")==0)continue;
            if(Set.of("DEPRECIATE","AMORTIZE").contains(action)&&jdbc.queryForObject("select count(*) from fa_recurring_plan where tenant_id=:tenantId and source_id=:source and source_type in ('FO_ASSET','FO_PREPAYMENT')",base(p).addValue("source",id),Integer.class)>0)continue;
            if(Set.of("TAX_FILE","ISSUE_INVOICE","RED_INVOICE").contains(action)&&!has(p,"finance.tax"))continue;
            if(CASH.contains(action)){if(!has(p,"finance.pay")||!p.actorId().equals(uid(d,"cashier_account_id")))continue;}
            else if(!has(p,"finance.review"))continue;
            if(action.equals("COUNTERSIGN")&&(!p.actorId().toString().equals(str(x,"counterpartyReviewerId"))||sumCount(p,id,action)>0))continue;
            if(action.equals("TAX_FILE")&&sumCount(p,id,action)>0)continue;
            if(action.equals("DISPOSE")&&sumCount(p,id,action)>0)continue;
            if(action.equals("DEPRECIATE")&&(sumCount(p,id,"DISPOSE")>0||new FinanceControlGuard(jdbc).disposed(p,id)))continue;
            result.add(action);
        }return result;
    }
    private void balances(Map<String,Object> row){
        BigDecimal amount=new BigDecimal(row.get("amount").toString()),paid=moneyValue(row,"paidTotal").subtract(moneyValue(row,"refundTotal")),received=moneyValue(row,"receiptTotal"),cleared=moneyValue(row,"repaidTotal").add(moneyValue(row,"writtenOffTotal"));
        BigDecimal settled,outstanding;String basis;
        switch(row.get("type").toString()){
            case "LOAN"->{settled=cleared;outstanding=paid.subtract(cleared);basis="已发放借款减还款及实际费用核销，不是未付款金额";}
            case "REVENUE"->{settled=received.subtract(moneyValue(row,"refundTotal"));outstanding=amount.subtract(received);basis="应收确认减累计收款；已退款独立披露，不自动再次形成应收";}
            case "INTERCOMPANY"->{settled=paid.max(received);outstanding=amount.subtract(settled);basis="双方确认往来与实际资金结算，不代表已抵销合并";}
            case "PAYABLE"->{settled=paid.add(moneyValue(row,"writeoffReceivedTotal"));outstanding=amount.subtract(settled);basis="批准金额减净付款和已确认借款抵扣；同一费用不能重复清偿";}
            default->{settled=paid;outstanding=amount.subtract(paid);basis="已批准总额减净付款，付款不等同成本确认";}
        }
        row.put("settledAmount",settled.toPlainString());row.put("outstandingAmount",outstanding.max(BigDecimal.ZERO).toPlainString());row.put("balanceBasis",basis);
        if(row.get("type").equals("PREPAYMENT"))row.put("unamortizedAmount",amount.subtract(moneyValue(row,"amortizationTotal")).toPlainString());
        if(row.get("type").equals("ASSET"))row.put("netBookValue",amount.subtract(moneyValue(row,"depreciationTotal")).toPlainString());
    }
    private static BigDecimal moneyValue(Map<String,Object> row,String key){return new BigDecimal(Objects.toString(row.get(key),"0"));}
    private boolean canReview(TenantPrincipal p,Map<String,Object> d) {if(!has(p,"finance.review")||p.actorId().equals(uid(d,"applicant_id"))||!str(d,"status").equals("REVIEW"))return false;var route=stringList(d.get("approval_route"));int index=((Number)d.get("review_index")).intValue();return index<route.size()&&p.actorId().equals(new FinanceDelegationControl(jdbc).current(p,"OPERATION",uid(d,"id"),index,UUID.fromString(route.get(index))));}
    private Map<String,Object> party(TenantPrincipal p,UUID id,UUID hotel) {var list=jdbc.queryForList("select * from fo_party where tenant_id=:tenantId and id=:id",base(p).addValue("id",id));if(list.isEmpty()||!hotel.equals(uid(list.getFirst(),"hotel_id")))throw bad("交易对方不属于所选组织");return list.getFirst();}
    private Map<String,Object> bank(TenantPrincipal p,UUID id,UUID hotel) {var list=jdbc.queryForList("select * from fo_bank_account where tenant_id=:tenantId and id=:id",base(p).addValue("id",id));if(list.isEmpty())throw missing();var row=list.getFirst();access.requireOrgScope(uid(row,"hotel_id"));if(hotel!=null&&!hotel.equals(uid(row,"hotel_id")))throw bad("资金账户不属于本单组织");return row;}
    private void requireHotel(TenantPrincipal p,UUID id) {if(jdbc.queryForObject("select count(*) from org_unit where tenant_id=:tenantId and id=:id and status='ACTIVE' and unit_type in ('HOTEL','GROUP')",base(p).addValue("id",id),Integer.class)!=1)throw bad("所属门店/集团组织不可用");}
    private void requireAccount(TenantPrincipal p,UUID id) {if(jdbc.queryForObject("select count(*) from user_account where tenant_id=:tenantId and id=:id and status='ACTIVE'",base(p).addValue("id",id),Integer.class)!=1)throw bad("相关人员账号不存在或已停用");}
    private boolean replayed(TenantPrincipal p,UUID requestId,UUID resource,String operation,Object command) {var rows=jdbc.queryForList("select * from fo_command where tenant_id=:tenantId and request_id=:request",base(p).addValue("request",requestId));if(rows.isEmpty())return false;var old=rows.getFirst();if(!resource.equals(uid(old,"resource_id"))||!operation.equals(str(old,"operation"))||!FinanceService.hash(json(command)).equals(str(old,"request_hash")))throw conflict("请求编号已用于不同业务内容，请勿重用");return true;}
    private void recordCommand(TenantPrincipal p,UUID request,UUID resource,String operation,Object command) {jdbc.update("insert into fo_command(tenant_id,request_id,resource_id,operation,request_hash) values(:tenantId,:request,:resource,:operation,:hash)",base(p).addValue("request",request).addValue("resource",resource).addValue("operation",operation).addValue("hash",FinanceService.hash(json(command))));}
    private void version(Map<String,Object> d,int expected) {if(((Number)d.get("version")).intValue()!=expected)throw conflict("单据已在其他页面处理，请刷新查看最新状态");}
    private TenantPrincipal prepare(String... permissions) {if(permissions.length==0)access.requireAnyPermission("finance.submit","finance.read","finance.review","finance.pay","finance.configure");else access.requireAnyPermission(permissions);var p=access.principal();database.apply(p.tenantId());return p;}
    private MapSqlParameterSource base(TenantPrincipal p) {return new MapSqlParameterSource("tenantId",p.tenantId()).addValue("actorId",p.actorId()).addValue("tenantScope",p.hasTenantScope()).addValue("scopeIds",p.orgScopes().isEmpty()?List.of(new UUID(0,0)):p.orgScopes()).addValue("owner",has(p,"finance.submit")).addValue("reader",has(p,"finance.read")).addValue("reviewer",has(p,"finance.review")).addValue("payer",has(p,"finance.pay")).addValue("payroll",has(p,"finance.payroll")).addValue("tax",has(p,"finance.tax")).addValue("actorRoute",json(List.of(p.actorId().toString())));}
    private static boolean has(TenantPrincipal p,String permission) {return p.hasPermission(permission)||p.hasPermission("*");}
    private void lock(TenantPrincipal p,String key) {jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(:key,89))",Map.of("key",p.tenantId()+":"+key));}
    private List<Map<String,Object>> rows(String sql,MapSqlParameterSource b) {return jdbc.queryForList(sql,b).stream().map(this::external).toList();}
    private Map<String,Object> external(Map<String,Object> row) {
        var out=new LinkedHashMap<String,Object>();row.forEach((key,value)->{
            Object converted=value;
            if(value!=null&&Set.of("details","party_snapshot","snapshot","metadata","extraction").contains(key))converted=parse(value);
            else if(value!=null&&Set.of("lines","approval_route").contains(key))converted=parseList(value);
            out.put(camel(key),sanitize(converted,key));
        });return out;
    }
    private Object sanitize(Object value,String key) {
        if(value==null)return null;
        if(Set.of("bank_account","bankAccount","account_number","accountNumber").contains(key)&&!has(access.principal(),"finance.pay")&&!has(access.principal(),"finance.configure"))return mask(value.toString());
        if(value instanceof BigDecimal decimal)return decimal.toPlainString();
        if(value instanceof Map<?,?> map){var safe=new LinkedHashMap<String,Object>();map.forEach((k,v)->safe.put(camel(k.toString()),sanitize(v,k.toString())));return safe;}
        if(value instanceof Collection<?> values)return values.stream().map(item->sanitize(item,"")).toList();
        return value;
    }
    private static String camel(String value){var name=new StringBuilder();boolean upper=false;for(char c:value.toCharArray()){if(c=='_')upper=true;else{name.append(upper?Character.toUpperCase(c):c);upper=false;}}return name.toString();}
    private Map<String,Object> obj(Map<String,Object> row,String key) {return parse(row.get(key));}
    private Map<String,Object> parse(Object value) {try{return value==null?new LinkedHashMap<>():mapper.readerFor(new TypeReference<LinkedHashMap<String,Object>>(){}).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(value.toString());}catch(Exception e){throw new IllegalStateException("业务资料读取失败",e);}}
    private List<Object> parseList(Object value) {try{return mapper.readerFor(new TypeReference<List<Object>>(){}).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(value.toString());}catch(Exception e){throw new IllegalStateException("业务明细读取失败",e);}}
    private List<String> stringList(Object value) {return parseList(value).stream().map(Object::toString).toList();}
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static UUID uid(Map<String,Object> row,String key) {return row.get(key)==null?null:UUID.fromString(row.get(key).toString());}
    private static String str(Map<String,Object> row,String key) {return Objects.toString(row.get(key),"");}
    private static String blank(String value) {return value==null?"":value.trim();}
    private static BigDecimal dec(Map<String,Object> row,String key) {return new BigDecimal(row.get(key).toString());}
    private static LocalDate date(Map<String,Object> row,String key) {return LocalDate.parse(row.get(key).toString());}
    private static String mask(String value) {return value.length()<5?"****":"****"+value.substring(value.length()-4);}
    private static void rejectFloating(Object value) {if(value instanceof Double||value instanceof Float)throw bad("业务资料中的小数金额请以十进制字符串传输，避免浮点金额精度丢失");if(value instanceof Map<?,?> map)map.values().forEach(FinanceOperationsService::rejectFloating);else if(value instanceof Collection<?> values)values.forEach(FinanceOperationsService::rejectFloating);}
    private static void required(Map<String,Object> x,String... keys) {for(String key:keys)if(x.get(key)==null||x.get(key).toString().isBlank())throw bad("业务资料缺少："+key);}
    private static LocalDate localDate(Map<String,Object> x,String key) {try{return LocalDate.parse(x.get(key).toString());}catch(Exception e){throw bad("日期无效："+key);}}
    private static UUID uuidField(Map<String,Object> x,String key) {try{return UUID.fromString(x.get(key).toString());}catch(Exception e){throw bad("账号/组织编号无效："+key);}}
    private static int intField(Map<String,Object> x,String key) {try{return new BigDecimal(x.get(key).toString()).intValueExact();}catch(Exception e){throw bad("整数无效："+key);}}
    private static BigDecimal decimalField(Map<String,Object> x,String key) {try{return new BigDecimal(x.get(key).toString()).setScale(2,RoundingMode.UNNECESSARY);}catch(Exception e){throw bad("金额无效："+key);}}
    private static YearMonth parseMonth(String value) {try{var month=YearMonth.parse(value);if(month.getYear()<2000||month.getYear()>2200)throw bad("月份超出范围");return month;}catch(Exception e){throw bad("月份应为YYYY-MM");}}
    private static ResponseStatusException bad(String message) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private static ResponseStatusException missing() {return new ResponseStatusException(HttpStatus.NOT_FOUND,"单据不存在或当前身份无权查看");}
    private static ResponseStatusException forbidden() {return new ResponseStatusException(HttpStatus.FORBIDDEN,"当前人员不是本环节授权办理人，或不在组织范围内");}
}
