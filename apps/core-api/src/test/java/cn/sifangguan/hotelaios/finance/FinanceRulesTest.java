package cn.sifangguan.hotelaios.finance;

import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.*;

class FinanceRulesTest {
    @Test void preservesZerosAndNormalizesFullWidth() {
        assertThat(FinanceRules.invoiceKey("", "００１２３４５６７８９０１２３４５６７８")).isEqualTo("DIGITAL:00123456789012345678");
        assertThat(FinanceRules.invoiceKey("0012345678", "00000001")).isEqualTo("TRADITIONAL:0012345678:00000001");
        assertThatThrownBy(()->FinanceRules.invoiceKey("", "12345678")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->FinanceRules.invoiceKey("abc", "00123456789012345678")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void detectsDifferencesWithoutRoundingAndEscapesCsv() {
        assertThat(FinanceRules.differs(new BigDecimal("100.00"),new BigDecimal("100"),new BigDecimal("90.00"),new BigDecimal("90"))).isTrue();
        assertThat(FinanceRules.differs(new BigDecimal("0.10"),new BigDecimal("0.1"),new BigDecimal("0.10"),new BigDecimal("0.1"))).isFalse();
        assertThat(FinanceRules.csv("  =HYPERLINK(\"x\")")).startsWith("\"'");
    }
    @Test void extractsOnlyLabelledCandidatesAndNeverInventsPhotoOcr() {
        var result=FinanceEvidenceService.extract("发票号码：00123456789012345678 价税合计（小写）￥100.00");
        assertThat(result).containsEntry("invoiceNumber","00123456789012345678").containsEntry("amount","100.00").doesNotContainKey("buyerTaxNumber");
        assertThat(FinanceEvidenceService.extract("00123456789012345678 交易100.00")).isEmpty();
        assertThatThrownBy(()->FinanceEvidenceService.mediaType("<script>evil</script>".getBytes())).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectsPdfActiveContentAndKeepsOrdinaryPdfUnchanged() throws Exception {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());doc.save(bytes);
            assertThat(FinanceEvidenceService.inspect(bytes.toByteArray(),"application/pdf")).containsEntry("source","MANUAL_REQUIRED");
            doc.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('test')"));bytes.reset();doc.save(bytes);
            assertThatThrownBy(()->FinanceEvidenceService.inspect(bytes.toByteArray(),"application/pdf")).isInstanceOf(RuntimeException.class).hasMessageContaining("脚本");
        }
    }
    @Test void acceptsOriginalXmlAndOfdButBlocksExternalEntitiesAndZipTraversal() throws Exception {
        byte[] xml="<Invoice><Text>发票号码：00123456789012345678</Text></Invoice>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(FinanceEvidenceService.mediaType(xml)).isEqualTo("application/xml");
        assertThat(FinanceEvidenceService.inspect(xml,"application/xml")).containsEntry("source","XML_TEXT").containsEntry("externalVerificationStatus","NOT_VERIFIED");
        byte[] xxe="<!DOCTYPE Invoice [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><Invoice>&xxe;</Invoice>".getBytes();
        assertThatThrownBy(()->FinanceEvidenceService.mediaType(xxe)).hasMessageContaining("不安全");
        var bytes=new ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("OFD.xml"));zip.write("<OFD/>".getBytes());zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("Doc_0/Pages/Page_0/Content.xml"));zip.write(xml);zip.closeEntry();
        }
        assertThat(FinanceEvidenceService.mediaType(bytes.toByteArray())).isEqualTo("application/ofd");
        bytes.reset();try(var zip=new java.util.zip.ZipOutputStream(bytes)) {zip.putNextEntry(new java.util.zip.ZipEntry("../OFD.xml"));zip.write("<OFD/>".getBytes());zip.closeEntry();}
        assertThatThrownBy(()->FinanceEvidenceService.mediaType(bytes.toByteArray())).hasMessageContaining("不安全");
    }
}
