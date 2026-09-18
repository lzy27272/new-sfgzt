package cn.sifangguan.hotelaios.shared.imports;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XlsxWorkbookTest {
    private final XlsxWorkbook workbook = new XlsxWorkbook();

    @Test
    void generatedTemplateCanBeReadWithoutLosingChineseValuesOrEmptyCells() {
        LinkedHashMap<String, List<List<String>>> sheets = new LinkedHashMap<>();
        sheets.put("物品与期初库存", List.of(
                List.of("物品编码*", "物品名称*", "分类*", "期初数量*"),
                List.of("C-001", "牙刷", "耗品", "20"),
                List.of("C-002", "洗发液", "耗品", "")
        ));
        sheets.put("固定资产", List.of(List.of("资产编号*", "资产名称*")));

        XlsxWorkbook.WorkbookData result = workbook.read(workbook.write(sheets));

        assertThat(result.sheets()).hasSize(2);
        assertThat(result.sheet("物品与期初库存").rows().get(1))
                .containsExactly("C-001", "牙刷", "耗品", "20");
        assertThat(result.sheet("物品与期初库存").rows().get(2))
                .containsExactly("C-002", "洗发液", "耗品", "");
    }

    @Test
    void rejectsMacroContentEvenWhenFileIsNamedXlsx() {
        byte[] workbookBytes = workbook.write(sampleSheets());
        byte[] disguisedMacro = withEntry(workbookBytes, "xl/vbaProject.bin", "macro".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> workbook.read(disguisedMacro))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("宏");
    }

    @Test
    void rejectsExternalLinksDeclaredOutsideWorkbookRelationships() {
        byte[] workbookBytes = workbook.write(sampleSheets());
        byte[] externalRelationship = withEntry(
                workbookBytes,
                "xl/worksheets/_rels/sheet1.xml.rels",
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
                                        Target="https://example.invalid/collect" TargetMode="External"/>
                        </Relationships>
                        """.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> workbook.read(externalRelationship))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("外部链接");
    }

    @Test
    void rejectsOversizedCellTextBeforeBuildingImportRows() {
        byte[] workbookBytes = workbook.write(sampleSheets());
        String oversizedCell = "X".repeat(10_001);
        byte[] oversizedWorkbook = replaceEntry(
                workbookBytes,
                "xl/worksheets/sheet1.xml",
                ("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>%s</t></is></c></row></sheetData>
                        </worksheet>
                        """).formatted(oversizedCell).getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> workbook.read(oversizedWorkbook))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单元格文本");
    }

    private LinkedHashMap<String, List<List<String>>> sampleSheets() {
        LinkedHashMap<String, List<List<String>>> sheets = new LinkedHashMap<>();
        sheets.put("物品与期初库存", List.of(List.of("物品编码*")));
        sheets.put("固定资产", List.of(List.of("资产编号*")));
        return sheets;
    }

    private byte[] withEntry(byte[] source, String name, byte[] content) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zipOutput = new ZipOutputStream(output, StandardCharsets.UTF_8);
             ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(source), StandardCharsets.UTF_8)) {
            ZipEntry sourceEntry;
            while ((sourceEntry = zipInput.getNextEntry()) != null) {
                zipOutput.putNextEntry(new ZipEntry(sourceEntry.getName()));
                zipInput.transferTo(zipOutput);
                zipOutput.closeEntry();
            }
            zipOutput.putNextEntry(new ZipEntry(name));
            zipOutput.write(content);
            zipOutput.closeEntry();
            zipOutput.finish();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] replaceEntry(byte[] source, String name, byte[] content) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zipOutput = new ZipOutputStream(output, StandardCharsets.UTF_8);
             ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(source), StandardCharsets.UTF_8)) {
            ZipEntry sourceEntry;
            while ((sourceEntry = zipInput.getNextEntry()) != null) {
                if (name.equals(sourceEntry.getName())) continue;
                zipOutput.putNextEntry(new ZipEntry(sourceEntry.getName()));
                zipInput.transferTo(zipOutput);
                zipOutput.closeEntry();
            }
            zipOutput.putNextEntry(new ZipEntry(name));
            zipOutput.write(content);
            zipOutput.closeEntry();
            zipOutput.finish();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
