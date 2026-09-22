package cn.sifangguan.hotelaios.finance;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.List;

/** Deterministic controls; deliberately not represented as tax verification or an AI decision. */
public final class FinanceRules {
    private FinanceRules() { }
    public static String invoiceKey(String code, String number) {
        String c=digits(code), n=digits(number);
        if(n.matches("[0-9]{20}") && c.isEmpty()) return "DIGITAL:"+n;
        if(c.matches("[0-9]{10}|[0-9]{12}") && n.matches("[0-9]{8}")) return "TRADITIONAL:"+c+":"+n;
        throw new IllegalArgumentException("数电发票请填写20位完整号码并留空代码；传统发票请填写10/12位代码和8位号码");
    }
    public static String digits(String value) {
        return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).replaceAll("\\s","");
    }
    public static String taxNumber(String value) { return digits(value).toUpperCase(Locale.ROOT); }
    public static boolean differs(BigDecimal invoice, BigDecimal transaction, BigDecimal payment, BigDecimal requested) {
        return invoice.compareTo(transaction)!=0 || transaction.compareTo(payment)!=0 || payment.compareTo(requested)!=0;
    }
    public static BigDecimal invoiceTotal(List<FinanceModels.Invoice> invoices) {
        return invoices.stream().map(FinanceModels.Invoice::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    public static String csv(Object value) {
        String text=value==null?"":value.toString();
        if(text.matches("(?s)^[\\s]*[=+@\\-].*")) text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }
}
