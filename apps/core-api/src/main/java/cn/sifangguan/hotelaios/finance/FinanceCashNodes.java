package cn.sifangguan.hotelaios.finance;

/** A frozen cash instruction is executable only while its real economic source is still current. */
public final class FinanceCashNodes {
 private FinanceCashNodes(){}
 /** Alias is an internal SQL identifier, never a request parameter. All column/table names are fixed. */
 public static String sourceCurrentSql(String alias){
  if(alias==null||!alias.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("cash source alias must be an internal SQL identifier");
  return "(("+alias+".source_kind='CLAIM' and exists(select 1 from finance_claim cash_source_claim where cash_source_claim.tenant_id="+alias+".tenant_id and cash_source_claim.id="+alias+".source_id and cash_source_claim.hotel_id="+alias+".hotel_id and cash_source_claim.company_id="+alias+".company_id and cash_source_claim.status='APPROVED' and cash_source_claim.version="+alias+".source_version)) or ("+alias+".source_kind='OPERATION' and exists(select 1 from fo_document cash_source_operation where cash_source_operation.tenant_id="+alias+".tenant_id and cash_source_operation.id="+alias+".source_id and cash_source_operation.hotel_id="+alias+".hotel_id and cash_source_operation.company_id="+alias+".company_id and cash_source_operation.status='APPROVED' and cash_source_operation.version="+alias+".source_version)))";
 }
}
