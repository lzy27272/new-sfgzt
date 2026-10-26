package cn.sifangguan.hotelaios.finance;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** Receipt-type-specific, labelled suggestions. An OCR observation is never invoice verification. */
final class FinanceReceiptFields {
    record Block(String text, BigDecimal confidence) { }
    private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.90");
    private static final String NUMBER = "([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)(?![0-9.,])";
    private static final String SEP = "[:：]?[(（]?[¥￥]?";
    private final Map<String,LinkedHashMap<String,BigDecimal>> values = new LinkedHashMap<>();

    static Map<String,Object> extract(String kind, List<Block> blocks, List<String> qrCodes) {
        var fields = new FinanceReceiptFields();
        boolean buyer = false; BigDecimal sectionConfidence = BigDecimal.ZERO;
        for (int index=0; index<blocks.size(); index++) {
            Block block=blocks.get(index); String text=normalize(block.text());
            if (text.contains("销售方") || text.contains("销方信息")) buyer=false;
            if (text.contains("购买方") || text.contains("购方信息")) { buyer=true; sectionConfidence=block.confidence(); }
            String joined=text; BigDecimal confidence=block.confidence();
            // Only join a value immediately following an otherwise empty labelled block.
            if (index+1<blocks.size() && text.matches(".*(?:发票号码|发票代码|开票日期|价税合计|小写|税号|纳税人识别号|统一社会信用代码|名称|交易金额|交易总金额|实付金额|实际支付|支付金额|付款金额|实际付款)[:：]?[)）]?$")) {
                joined+=normalize(blocks.get(index+1).text()); confidence=confidence.min(blocks.get(index+1).confidence());
            }
            if (kind.equals("INVOICE")) {
                fields.capture("invoiceNumber",joined,"(?:发票号码|发票号)[:：]?([0-9]{20}|[0-9]{8})(?![0-9])",confidence);
                fields.capture("invoiceCode",joined,"发票代码[:：]?([0-9]{12}|[0-9]{10})(?![0-9])",confidence);
                fields.capture("amount",joined,"(?:价税合计(?:[(（]?小写[)）]?)?|[(（]小写[)）]|小写金额|金额小写)"+SEP+NUMBER,confidence);
                fields.capture("invoiceDate",joined,"开票日期[:：]?([0-9]{4}(?:年|[-./])[0-9]{1,2}(?:月|[-./])[0-9]{1,2}日?|[0-9]{8})(?![0-9])",confidence);
                fields.capture("buyerTaxNumber",joined,"(?:购买方|购方)(?:信息)?(?:纳税人识别号|税号|统一社会信用代码)[:：]?([A-Z0-9]{15,20})(?![A-Z0-9])",confidence);
                fields.capture("buyerName",joined,"(?:购买方名称|购方名称)[:：]?([^:：]{2,120}?)(?=纳税人识别号|统一社会信用代码|税号|地址|电话|开户行|$)",confidence);
                if (buyer) {
                    BigDecimal scoped=confidence.min(sectionConfidence);
                    fields.capture("buyerTaxNumber",joined,"(?:纳税人识别号|统一社会信用代码|税号)[:：]?([A-Z0-9]{15,20})(?![A-Z0-9])",scoped);
                    fields.capture("buyerName",joined,"(?:购买方信息)?名称[:：]?([^:：]{2,120}?)(?=纳税人识别号|统一社会信用代码|税号|地址|电话|开户行|$)",scoped);
                }
            } else if (kind.equals("TRANSACTION")) {
                fields.capture("transactionAmount",joined,"(?:交易总金额|交易金额)"+SEP+NUMBER,confidence);
            } else if (kind.equals("PAYMENT")) {
                fields.capture("paymentAmount",joined,"(?:实付金额|实际支付(?:金额)?|实际付款(?:金额)?|支付金额|付款金额|本次付款)"+SEP+NUMBER,confidence);
            }
        }
        var warnings=new ArrayList<String>();
        if (kind.equals("INVOICE")) for (String qr:qrCodes) if(!fields.invoiceQr(qr)) warnings.add("二维码格式未确证，未将二维码内容猜测为发票字段，请对照原件。");
        var candidates=new LinkedHashMap<String,Object>(); var alternatives=new LinkedHashMap<String,Object>();
        fields.values.forEach((name,observations)->{
            alternatives.put(name,observations.entrySet().stream().limit(8).map(e->Map.of("value",e.getKey(),"confidence",e.getValue())).toList());
            if (observations.size()==1 && observations.values().iterator().next().compareTo(MIN_CONFIDENCE)>=0) candidates.put(name,observations.keySet().iterator().next());
            else warnings.add(observations.size()>1 ? "字段 "+name+" 有多个不一致候选，未自动填写。" : "字段 "+name+" 识别置信度不足，未自动填写。");
        });
        var result=new LinkedHashMap<String,Object>();result.putAll(candidates);result.put("candidates",candidates);result.put("fieldCandidates",alternatives);result.put("warnings",warnings);return result;
    }
    private static String normalize(String text) { return Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("[\\s\\u00a0]+",""); }
    private void capture(String name,String text,String regex,BigDecimal confidence) {
        var matcher=Pattern.compile(regex).matcher(text);while(matcher.find())add(name,matcher.group(1),confidence);
    }
    private void add(String name,String raw,BigDecimal confidence) {
        String value=raw;
        try {
            if (Set.of("amount","transactionAmount","paymentAmount").contains(name)) {
                if(!raw.matches("(?:0|[1-9][0-9]{0,13}|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?")) return;
                var amount=new BigDecimal(raw.replace(",",""));if(amount.precision()-amount.scale()>14)return;value=amount.setScale(2).toPlainString();
            } else if(name.equals("invoiceDate")) {
                String day=raw.replace("年","-").replace("月","-").replace("日","").replace('.','-').replace('/','-');
                value=day.matches("[0-9]{8}")?LocalDate.parse(day,DateTimeFormatter.BASIC_ISO_DATE).toString():LocalDate.parse(day,DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(java.time.format.ResolverStyle.STRICT)).toString();
            }
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) { return; }
        var field=values.computeIfAbsent(name,key->new LinkedHashMap<>());
        if(field.size()<9||field.containsKey(value))field.merge(value,confidence,BigDecimal::max);
    }
    private boolean invoiceQr(String raw) {
        String[] parts=normalize(raw).split(",",-1);
        if(parts.length!=8||!parts[0].equals("01")||!Set.of("01","04","10","20").contains(parts[1])||!parts[2].matches("[0-9]{10}|[0-9]{12}")||!parts[3].matches("[0-9]{8}")||!parts[4].matches("[0-9]{1,16}\\.[0-9]{2}")||!parts[5].matches("[0-9]{8}")||!parts[6].matches("[0-9]{20}|" )||!parts[7].matches("[a-fA-F0-9]{4,64}"))return false;
        try { LocalDate.parse(parts[5],DateTimeFormatter.BASIC_ISO_DATE); } catch (java.time.DateTimeException invalid) {return false;}
        add("invoiceCode",parts[2],BigDecimal.ONE);add("invoiceNumber",parts[3],BigDecimal.ONE);add("invoiceDate",parts[5],BigDecimal.ONE);
        // QR amount may be tax-exclusive depending on invoice type. Never use it as gross invoice amount.
        return true;
    }
}
