package cn.sifangguan.hotelaios.finance;
import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class FinanceMigrationParserTest {
 @Test void csvHandlesQuotesEmbeddedNewlineAndBomWithoutLosingLeadingZeros(){var rows=FinanceMigrationParser.parse("source.csv",("\ufeff编号,摘要\r\n0000123,\"第一行,第二项\n下一行\"\r\n").getBytes(StandardCharsets.UTF_8),Map.of("sourceKey","编号","name","摘要"));assertThat(rows.getFirst()).containsEntry("sourceKey","0000123").containsEntry("name","第一行,第二项\n下一行");}
 @Test void malformedCsvUnknownHeaderAndFakeExtensionAreRejected(){assertThatThrownBy(()->FinanceMigrationParser.csv("a\n\"未结束".getBytes(StandardCharsets.UTF_8))).hasMessageContaining("未闭合");assertThatThrownBy(()->FinanceMigrationParser.parse("x.csv","a\nb\n".getBytes(StandardCharsets.UTF_8),Map.of("name","不存在"))).hasMessageContaining("表头不存在");assertThatThrownBy(()->FinanceMigrationParser.parse("x.json","{}".getBytes(StandardCharsets.UTF_8),Map.of())).hasMessageContaining("不接受");}
 @Test void realXlsxPreservesTextAmountsAndRejectsExecutableFormulas()throws Exception{byte[] valid;try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){var sheet=workbook.createSheet("金蝶余额");sheet.createRow(0).createCell(0).setCellValue("编号");sheet.getRow(0).createCell(1).setCellValue("金额");sheet.createRow(1).createCell(0).setCellValue("0000123456789012345678");sheet.getRow(1).createCell(1).setCellValue(12.35);workbook.write(output);valid=output.toByteArray();}var rows=FinanceMigrationParser.parse("真实.xlsx",valid,Map.of("sourceKey","编号","remainingAmount","金额"));assertThat(rows.getFirst()).containsEntry("sourceKey","0000123456789012345678").containsEntry("remainingAmount","12.35");try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(valid));var output=new ByteArrayOutputStream()){workbook.getSheetAt(0).getRow(1).getCell(1).setCellFormula("1+1");workbook.write(output);assertThatThrownBy(()->FinanceMigrationParser.parse("公式.xlsx",output.toByteArray(),Map.of("sourceKey","编号"))).hasMessageContaining("包含公式");}}
}
