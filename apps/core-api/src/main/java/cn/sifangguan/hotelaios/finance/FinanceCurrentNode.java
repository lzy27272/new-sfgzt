package cn.sifangguan.hotelaios.finance;

import java.util.Map;

/** Shared source identity for reminders and the final, real-identity delivery check. */
public final class FinanceCurrentNode {
 private FinanceCurrentNode() {}
 public static String sourceType(Map<String,Object> item) {
  String domain=String.valueOf(item.get("domain")),kind=String.valueOf(item.get("kind")),type=String.valueOf(item.get("documentType"));
  return switch(domain) {
   case "CLAIM" -> "FINANCE";
   case "OPERATION" -> "FINANCE_OPERATION";
   case "CASH_INSTRUCTION" -> kind.equals("work")?"FINANCE_CASH_STALE":"FINANCE_CASH";
   case "ACCRUAL" -> "FINANCE_ACCRUAL";
   case "FINANCE_BUSINESS" -> "FINANCE_"+type;
   case "ACCOUNTING" -> "FINANCE_ACCOUNTING";
   case "LEDGER_CHANGE" -> "FINANCE_LEDGER";
   case "INSIGHT" -> "FINANCE_INSIGHT";
   case "INSIGHT_ACTION" -> "FINANCE_INSIGHT_ACTION";
   case "CASH_ERROR" -> "FINANCE_CASH_ERROR";
   case "AR_ADJUSTMENT" -> "FINANCE_AR_ADJUSTMENT";
   case "AR_RECOVERY" -> "FINANCE_AR_RECOVERY";
   case "TAX_CALENDAR" -> "FINANCE_TAX_CALENDAR";
   case "BUDGET_EXCEPTION" -> "FINANCE_BUDGET_EXCEPTION";
   case "DELEGATION" -> "FINANCE_DELEGATION";
   case "CLOSE" -> type.equals("REGISTER")?"FINANCE_CLOSE_REGISTER":"FINANCE_CLOSE_BANK";
   case "ARCHIVE_REQUEST" -> "FINANCE_ARCHIVE_REQUEST";
   case "ACCRUAL_POLICY" -> "FINANCE_ACCRUAL_POLICY";
   case "SUPPLY_INVOICE_RELEASE" -> "FINANCE_SUPPLY_INVOICE_RELEASE";
   case "ASSET_GAIN" -> "FINANCE_ASSET_GAIN";
   case "INPUT_BASIS" -> "FINANCE_INPUT_BASIS";
   case "TAX_ACCRUAL" -> "FINANCE_TAX_ACCRUAL";
   case "LOAN_OVERDUE" -> "FINANCE_LOAN_OVERDUE";
   default -> null;
  };
 }
}
