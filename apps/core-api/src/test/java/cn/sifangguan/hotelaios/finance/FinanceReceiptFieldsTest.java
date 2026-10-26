package cn.sifangguan.hotelaios.finance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FinanceReceiptFieldsTest {
    private FinanceReceiptFields.Block block(String text){return new FinanceReceiptFields.Block(text,new BigDecimal("0.98"));}
    private Map<String,Object> extract(String kind,String... text){return FinanceReceiptFields.extract(kind,Arrays.stream(text).map(this::block).toList(),List.of());}
    @Test void labelledInvoiceKeepsLeadingZerosAndBuyerSectionWithoutTakingSeller(){
        var result=extract("INVOICE","发票号码：00000000000000123456","发票代码：001234567890","开票日期：2026年10月26日","购买方信息","名称：真实购买酒店有限公司","统一社会信用代码：91520100123456789X","销售方信息","纳税人识别号：91520200987654321X","价税合计(大写)壹仟元(小写)￥1,234.56");
        assertThat(result).containsEntry("invoiceNumber","00000000000000123456").containsEntry("invoiceCode","001234567890").containsEntry("buyerTaxNumber","91520100123456789X").containsEntry("buyerName","真实购买酒店有限公司").containsEntry("invoiceDate","2026-10-26").containsEntry("amount","1234.56");
    }
    @Test void actualPaymentAndTransactionStaySeparateFromPriceAndTaxExclusiveSubtotal(){
        String[] text={"商品原价100.00","合计90.00","金额(不含税)80.00","交易金额90.00","实付金额85.50"};
        assertThat(extract("INVOICE",text)).doesNotContainKeys("amount","transactionAmount","paymentAmount");
        assertThat(extract("TRANSACTION",text)).containsEntry("transactionAmount","90.00").doesNotContainKeys("amount","paymentAmount");
        assertThat(extract("PAYMENT",text)).containsEntry("paymentAmount","85.50").doesNotContainKeys("amount","transactionAmount");
    }
    @Test void multipleValuesOrLowConfidenceNeverBecomeAutoFillAndRepeatedSameValueIsNotAmbiguous(){
        var different=extract("PAYMENT","实付金额80.00","实际支付90.00");assertThat(different).doesNotContainKey("paymentAmount");assertThat((List<?>)different.get("warnings")).hasSize(1);
        var low=FinanceReceiptFields.extract("INVOICE",List.of(new FinanceReceiptFields.Block("发票号码00001234",new BigDecimal("0.5"))),List.of());assertThat(low).doesNotContainKey("invoiceNumber");
        assertThat(extract("INVOICE","发票号码00001234","发票号码00001234")).containsEntry("invoiceNumber","00001234");
    }
    @Test void precisionInvalidDatesAndUnlabelledNumbersAreNotGuessed(){
        assertThat(extract("INVOICE","00001234","开票日期2026-02-30","价税合计￥100.001","发票号码123456789","纳税人识别号91520100123456789X")).doesNotContainKeys("invoiceNumber","invoiceDate","amount","buyerTaxNumber");
        assertThat(extract("PAYMENT","实付金额123456789012345.00")).doesNotContainKey("paymentAmount");
        assertThat(extract("PAYMENT","实付金额12,34.56")).doesNotContainKey("paymentAmount");
    }
    @Test void splitLabelRequiresImmediateValueAndConfidenceOnBothBlocks(){
        assertThat(extract("INVOICE","发票号码：","00001234")).containsEntry("invoiceNumber","00001234");
        var result=FinanceReceiptFields.extract("INVOICE",List.of(new FinanceReceiptFields.Block("发票号码：",new BigDecimal("0.4")),block("00001234")),List.of());assertThat(result).doesNotContainKey("invoiceNumber");
    }
    @Test void onlyValidatedVatQrCanSuggestIdentityAndQrAmountIsNeverAssumedGross(){
        var qr=FinanceReceiptFields.extract("INVOICE",List.of(),List.of("01,10,001234567890,00001234,90.00,20261026,12345678901234567890,ABCD"));
        assertThat(qr).containsEntry("invoiceNumber","00001234").containsEntry("invoiceCode","001234567890").doesNotContainKey("amount");
        assertThat(FinanceReceiptFields.extract("INVOICE",List.of(),List.of("https://evil.example/a,b,c,12345678"))).doesNotContainKey("invoiceNumber");
        assertThat(FinanceReceiptFields.extract("INVOICE",List.of(),List.of("01,10,001234567890,00001234,90.00,20260230,12345678901234567890,ABCD"))).doesNotContainKey("invoiceNumber");
    }
}
