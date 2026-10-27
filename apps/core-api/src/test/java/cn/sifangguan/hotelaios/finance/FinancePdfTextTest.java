package cn.sifangguan.hotelaios.finance;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class FinancePdfTextTest {
    private static final String NUMBER="00000000000000123456";

    @Test void visualLabelValueOrderIsUsedWhenContentStreamWritesAllLabelsFirst() throws Exception {
        byte[] original=syntheticInvoice(false);
        try(var document=Loader.loadPDF(original)) {
            // Reproduce the original failure: drawing order separates labels from their visible values.
            assertThat(FinanceEvidenceService.extract(new PDFTextStripper().getText(document))).isEmpty();
        }
        assertThat(FinanceEvidenceService.inspect(original,"application/pdf"))
                .containsEntry("source","PDF_TEXT").containsEntry("invoiceNumber",NUMBER)
                .containsEntry("amount","1234.56").containsEntry("confirmationRequired",true);
    }

    @Test void conflictingVisibleInvoiceNumbersAreStillNotAutomaticallySelected() throws Exception {
        assertThat(FinanceEvidenceService.inspect(syntheticInvoice(true),"application/pdf"))
                .doesNotContainKey("invoiceNumber").containsEntry("amount","1234.56")
                .containsEntry("confirmationRequired",true);
    }

    private static byte[] syntheticInvoice(boolean conflicting) throws Exception {
        try(var document=new PDDocument()) {
            document.addPage(new PDPage());
            Path windows=Path.of("C:/Windows/Fonts/simhei.ttf");
            if(Files.isRegularFile(windows))return writeInvoice(document,PDType0Font.load(document,windows.toFile()),conflicting);
            // The existing Ubuntu Core CI job installs fonts-wqy-zenhei before running PDF tests.
            Path linux=Path.of("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc");
            assertThat(linux).as("Chinese PDF font installed by the Core CI job").isRegularFile();
            try(var collection=new TrueTypeCollection(linux.toFile())) {
                var font=collection.getFontByName("WenQuanYiZenHei");
                assertThat(font).as("WenQuanYiZenHei font in the installed collection").isNotNull();
                return writeInvoice(document,PDType0Font.load(document,font,true),conflicting);
            }
        }
    }

    private static byte[] writeInvoice(PDDocument document,PDFont font,boolean conflicting) throws IOException {
        try(var bytes=new ByteArrayOutputStream()) {
            try(var stream=new PDPageContentStream(document,document.getPage(0))) {
                text(stream,font,"发票号码：",50,700);
                text(stream,font,"价税合计（小写）￥",50,650);
                if(conflicting)text(stream,font,"发票号码：",50,600);
                text(stream,font,"仅为匿名合成测试明细",50,550);
                text(stream,font,NUMBER,260,700);
                text(stream,font,"1234.56",260,650);
                if(conflicting)text(stream,font,"00000000000000654321",260,600);
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static void text(PDPageContentStream stream,PDFont font,String value,float x,float y) throws IOException {
        stream.beginText();stream.setFont(font,12);stream.newLineAtOffset(x,y);stream.showText(value);stream.endText();
    }
}
