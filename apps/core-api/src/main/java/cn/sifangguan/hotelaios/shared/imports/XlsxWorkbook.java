package cn.sifangguan.hotelaios.shared.imports;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Small, dependency-free XLSX reader/writer for controlled import templates.
 * It intentionally rejects formulas and external relationships: imports must
 * contain facts, not executable spreadsheet logic or remote content.
 */
@Component
public final class XlsxWorkbook {
    private static final int MAX_COMPRESSED_BYTES = 10 * 1024 * 1024;
    private static final int MAX_UNCOMPRESSED_BYTES = 20 * 1024 * 1024;
    private static final int MAX_ENTRY_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ROWS = 5000;
    private static final int MAX_COLUMNS = 100;
    private static final int MAX_TOTAL_CELLS = 100_000;
    private static final int MAX_CELL_CHARACTERS = 10_000;
    private static final int MAX_SHARED_STRINGS = 100_000;
    private static final int MAX_ZIP_ENTRIES = 200;
    private static final int MAX_SHEETS = 20;

    public WorkbookData read(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Excel文件不能为空");
        if (bytes.length > MAX_COMPRESSED_BYTES) throw new IllegalArgumentException("Excel文件不能超过10MB");
        Map<String, byte[]> entries = unzip(bytes);
        rejectExternalRelationships(entries);
        List<String> sharedStrings = sharedStrings(entries.get("xl/sharedStrings.xml"));
        byte[] workbookBytes = entries.get("xl/workbook.xml");
        byte[] relationshipBytes = entries.get("xl/_rels/workbook.xml.rels");
        if (workbookBytes == null || relationshipBytes == null) {
            throw new IllegalArgumentException("Excel工作簿结构不完整");
        }
        Map<String, String> targets = relationshipTargets(relationshipBytes);
        NodeList sheetNodes = xml(workbookBytes).getElementsByTagNameNS("*", "sheet");
        if (sheetNodes.getLength() > MAX_SHEETS) throw new IllegalArgumentException("Excel最多支持20张工作表");
        List<SheetData> sheets = new ArrayList<>();
        int totalRows = 0;
        int totalCells = 0;
        for (int index = 0; index < sheetNodes.getLength(); index++) {
            Element sheet = (Element) sheetNodes.item(index);
            String relationshipId = sheet.getAttributeNS(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
            if (relationshipId.isBlank()) relationshipId = sheet.getAttribute("r:id");
            String path = workbookTarget(targets.get(relationshipId));
            byte[] sheetBytes = path == null ? null : entries.get(path);
            if (sheetBytes != null) {
                List<List<String>> rows = parseSheet(sheetBytes, sharedStrings);
                totalRows += rows.size();
                if (totalRows > MAX_ROWS) throw new IllegalArgumentException("Excel合计最多支持5000行");
                totalCells += rows.stream().mapToInt(List::size).sum();
                if (totalCells > MAX_TOTAL_CELLS) throw new IllegalArgumentException("Excel有效单元格数量过多");
                sheets.add(new SheetData(sheet.getAttribute("name"), rows));
            }
        }
        if (sheets.isEmpty()) throw new IllegalArgumentException("Excel文件不包含可读取的工作表");
        return new WorkbookData(List.copyOf(sheets));
    }

    public byte[] write(LinkedHashMap<String, List<List<String>>> sheets) {
        if (sheets == null || sheets.isEmpty()) throw new IllegalArgumentException("至少需要一个工作表");
        if (sheets.size() > MAX_SHEETS) throw new IllegalArgumentException("Excel最多支持20张工作表");
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypes(sheets.size()));
            put(zip, "_rels/.rels", rootRelationships());
            put(zip, "xl/workbook.xml", workbookXml(new ArrayList<>(sheets.keySet())));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRelationships(sheets.size()));
            int index = 1;
            int totalRows = 0;
            int totalCells = 0;
            for (List<List<String>> rows : sheets.values()) {
                totalRows += rows.size();
                if (totalRows > MAX_ROWS) throw new IllegalArgumentException("Excel合计最多支持5000行");
                totalCells += rows.stream().mapToInt(List::size).sum();
                if (totalCells > MAX_TOTAL_CELLS) throw new IllegalArgumentException("Excel有效单元格数量过多");
                put(zip, "xl/worksheets/sheet" + index + ".xml", worksheet(rows));
                index++;
            }
            zip.finish();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成Excel文件", exception);
        }
    }

    private Map<String, byte[]> unzip(byte[] bytes) {
        Map<String, byte[]> result = new HashMap<>();
        int total = 0;
        int entryCount = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++entryCount > MAX_ZIP_ENTRIES) throw new IllegalArgumentException("Excel压缩包文件项过多");
                String name = entry.getName().replace('\\', '/');
                if (name.contains("..") || name.startsWith("/")) {
                    throw new IllegalArgumentException("Excel压缩包包含非法路径");
                }
                String lowerName = name.toLowerCase(java.util.Locale.ROOT);
                if (lowerName.endsWith("vbaproject.bin") || lowerName.contains("/activex/")
                        || lowerName.contains("/embeddings/") || lowerName.contains("/externallinks/")) {
                    throw new IllegalArgumentException("导入表不能包含宏、嵌入对象或外部链接");
                }
                boolean retained = name.equals("xl/sharedStrings.xml") || name.equals("xl/workbook.xml")
                        || name.equals("xl/_rels/workbook.xml.rels")
                        || name.startsWith("xl/worksheets/") && name.endsWith(".xml")
                        || lowerName.endsWith(".rels");
                ByteArrayOutputStream content = retained ? new ByteArrayOutputStream() : null;
                byte[] buffer = new byte[8192];
                int read;
                int entryBytes = 0;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    total += read;
                    entryBytes += read;
                    if (total > MAX_UNCOMPRESSED_BYTES) {
                        throw new IllegalArgumentException("Excel解压内容超过20MB限制");
                    }
                    if (entryBytes > MAX_ENTRY_BYTES) {
                        throw new IllegalArgumentException("Excel单个文件项超过8MB限制");
                    }
                    if (retained) content.write(buffer, 0, read);
                }
                if (retained) {
                    result.put(name, content.toByteArray());
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取Excel文件", exception);
        }
        return result;
    }

    private void rejectExternalRelationships(Map<String, byte[]> entries) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().toLowerCase(java.util.Locale.ROOT).endsWith(".rels")) continue;
            NodeList relationships = xml(entry.getValue()).getElementsByTagNameNS("*", "Relationship");
            for (int index = 0; index < relationships.getLength(); index++) {
                Element relationship = (Element) relationships.item(index);
                String target = relationship.getAttribute("Target").trim();
                String targetMode = relationship.getAttribute("TargetMode");
                if ("External".equalsIgnoreCase(targetMode)
                        || target.startsWith("//")
                        || target.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
                    throw new IllegalArgumentException("Excel不能包含外部链接");
                }
            }
        }
    }

    private Map<String, String> relationshipTargets(byte[] bytes) {
        NodeList relationships = xml(bytes).getElementsByTagNameNS("*", "Relationship");
        Map<String, String> result = new HashMap<>();
        for (int index = 0; index < relationships.getLength(); index++) {
            Element relationship = (Element) relationships.item(index);
            String type = relationship.getAttribute("Type");
            String targetMode = relationship.getAttribute("TargetMode");
            if ("External".equalsIgnoreCase(targetMode)) {
                throw new IllegalArgumentException("Excel不能包含外部链接");
            }
            if (type.endsWith("/worksheet")) {
                result.put(relationship.getAttribute("Id"), relationship.getAttribute("Target"));
            }
        }
        return result;
    }

    private String workbookTarget(String target) {
        if (target == null || target.isBlank()) return null;
        String normalized = target.replace('\\', '/');
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.contains("..")) throw new IllegalArgumentException("Excel工作表路径非法");
        return normalized.startsWith("xl/") ? normalized : "xl/" + normalized;
    }

    private List<String> sharedStrings(byte[] bytes) {
        if (bytes == null) return List.of();
        NodeList items = xml(bytes).getElementsByTagNameNS("*", "si");
        if (items.getLength() > MAX_SHARED_STRINGS) {
            throw new IllegalArgumentException("Excel共享文本数量过多");
        }
        List<String> result = new ArrayList<>();
        for (int index = 0; index < items.getLength(); index++) {
            Element item = (Element) items.item(index);
            NodeList texts = item.getElementsByTagNameNS("*", "t");
            StringBuilder value = new StringBuilder();
            for (int textIndex = 0; textIndex < texts.getLength(); textIndex++) {
                value.append(texts.item(textIndex).getTextContent());
            }
            result.add(limitedCell(value.toString()));
        }
        return result;
    }

    private List<List<String>> parseSheet(byte[] bytes, List<String> sharedStrings) {
        Document document = xml(bytes);
        if (document.getElementsByTagNameNS("*", "f").getLength() > 0) {
            throw new IllegalArgumentException("导入表不能包含公式，请粘贴为数值后重新上传");
        }
        NodeList rowNodes = document.getElementsByTagNameNS("*", "row");
        if (rowNodes.getLength() > MAX_ROWS) throw new IllegalArgumentException("Excel最多支持5000行");
        List<List<String>> rows = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rowNodes.getLength(); rowIndex++) {
            Element rowElement = (Element) rowNodes.item(rowIndex);
            int rowNumber = numericIndex(rowElement.getAttribute("r"), rowIndex + 1);
            if (rowNumber > MAX_ROWS) throw new IllegalArgumentException("Excel最多支持5000行");
            while (rows.size() < rowNumber - 1) rows.add(new ArrayList<>());
            NodeList cells = rowElement.getElementsByTagNameNS("*", "c");
            List<String> row = new ArrayList<>();
            for (int cellIndex = 0; cellIndex < cells.getLength(); cellIndex++) {
                Element cell = (Element) cells.item(cellIndex);
                int column = columnIndex(cell.getAttribute("r"));
                if (column >= MAX_COLUMNS) throw new IllegalArgumentException("Excel最多支持100列");
                while (row.size() <= column) row.add("");
                String type = cell.getAttribute("t");
                String value;
                if ("inlineStr".equals(type)) {
                    NodeList text = cell.getElementsByTagNameNS("*", "t");
                    value = text.getLength() == 0 ? "" : text.item(0).getTextContent();
                } else {
                    NodeList values = cell.getElementsByTagNameNS("*", "v");
                    value = values.getLength() == 0 ? "" : values.item(0).getTextContent();
                    if ("s".equals(type) && !value.isBlank()) {
                        int sharedIndex = Integer.parseInt(value);
                        value = sharedIndex >= 0 && sharedIndex < sharedStrings.size()
                                ? sharedStrings.get(sharedIndex) : "";
                    } else if ("b".equals(type)) {
                        value = "1".equals(value) ? "是" : "否";
                    }
                }
                row.set(column, limitedCell(value == null ? "" : value.trim()));
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private Document xml(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Excel XML结构无效", exception);
        }
    }

    private String worksheet(List<List<String>> rows) {
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<cols><col min=\"1\" max=\"100\" width=\"22\" customWidth=\"1\"/></cols><sheetData>");
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (row.size() > MAX_COLUMNS) throw new IllegalArgumentException("Excel最多支持100列");
            xml.append("<row r=\"").append(rowIndex + 1).append("\">");
            for (int column = 0; column < row.size(); column++) {
                String value = limitedCell(row.get(column));
                String reference = excelColumn(column + 1) + (rowIndex + 1);
                xml.append("<c r=\"").append(reference).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(xmlText(value)).append("</t></is></c>");
            }
            xml.append("</row>");
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    private String limitedCell(String value) {
        String normalized = value == null ? "" : value;
        if (normalized.length() > MAX_CELL_CHARACTERS) {
            throw new IllegalArgumentException("Excel单元格文本不能超过10000个字符");
        }
        return normalized;
    }

    private String contentTypes(int count) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                """);
        for (int index = 1; index <= count; index++) {
            xml.append("<Override PartName=\"/xl/worksheets/sheet").append(index)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return xml.append("</Types>").toString();
    }

    private String rootRelationships() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
                """;
    }

    private String workbookXml(List<String> names) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>
                """);
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            if (name == null || name.isBlank() || name.length() > 31) {
                throw new IllegalArgumentException("工作表名称不能为空且不能超过31个字符");
            }
            xml.append("<sheet name=\"").append(xmlText(name)).append("\" sheetId=\"")
                    .append(index + 1).append("\" r:id=\"rId").append(index + 1).append("\"/>");
        }
        return xml.append("</sheets></workbook>").toString();
    }

    private String workbookRelationships(int count) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                """);
        for (int index = 1; index <= count; index++) {
            xml.append("<Relationship Id=\"rId").append(index)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(index).append(".xml\"/>");
        }
        return xml.append("</Relationships>").toString();
    }

    private void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String excelColumn(int number) {
        StringBuilder result = new StringBuilder();
        int value = number;
        while (value > 0) {
            value--;
            result.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }
        return result.toString();
    }

    private int columnIndex(String reference) {
        int value = 0;
        int index = 0;
        while (index < reference.length() && Character.isLetter(reference.charAt(index))) {
            value = value * 26 + (Character.toUpperCase(reference.charAt(index)) - 'A' + 1);
            index++;
        }
        return Math.max(0, value - 1);
    }

    private int numericIndex(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String xmlText(String value) {
        return (value == null ? "" : value)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    public record WorkbookData(List<SheetData> sheets) {
        public SheetData sheet(String name) {
            return sheets.stream().filter(sheet -> sheet.name().trim().equals(name)).findFirst().orElse(null);
        }
    }

    public record SheetData(String name, List<List<String>> rows) {
    }
}
