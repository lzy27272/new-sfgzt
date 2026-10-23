package cn.sifangguan.hotelaios.finance;

import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.ss.usermodel.*;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType;
import org.apache.fontbox.ttf.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/** Renders the already authorized report result; it performs no second financial query. */
final class FinanceAccountingExportRenderer {
 private FinanceAccountingExportRenderer() {}
 private static final Map<String,String> REPORTS;
 static {var map=new LinkedHashMap<String,String>();map.put("TRIAL_BALANCE","科目余额与试算平衡");map.put("GENERAL_LEDGER","总账");map.put("DETAIL_LEDGER","明细账");map.put("CASH_JOURNAL","现金银行日记账");map.put("INCOME_STATEMENT","利润表");map.put("BALANCE_SHEET","资产负债表");map.put("CASH_FLOW","现金流量表");REPORTS=Collections.unmodifiableMap(map);}
 private static final Set<String> AMOUNTS=Set.of("openingDebit","openingCredit","beginningNet","periodDebit","periodCredit","endingNet","debit","credit","amount","revenue","expense","netProfit","assets","liabilities","equity","unclosedProfit","difference","beginningCash","endingCash","netMovement");
 static byte[] render(Map<String,Object> report,String format,String reportType) {
  if(!Set.of("xlsx","pdf").contains(format))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"导出格式仅支持xlsx或pdf");
  if(!"ALL".equals(reportType)&&!REPORTS.containsKey(reportType))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"未知报表类型");
  var sections=sections(report,reportType);
  try {return "xlsx".equals(format)?xlsx(report,sections):pdf(report,sections);}
  catch(ResponseStatusException e){throw e;}
  catch(IOException|IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"报表导出失败："+e.getMessage(),e);}
 }
 private record Section(String name,List<Map<String,Object>> rows) {}
 @SuppressWarnings("unchecked") private static List<Section> sections(Map<String,Object> report,String type) {
  var sections=new ArrayList<Section>();for(var entry:REPORTS.entrySet()) {if(!type.equals("ALL")&&!type.equals(entry.getKey()))continue;String key=switch(entry.getKey()){case "TRIAL_BALANCE"->"trialBalance";case "GENERAL_LEDGER"->"generalLedger";case "DETAIL_LEDGER"->"detailLedger";case "CASH_JOURNAL"->"cashJournal";case "INCOME_STATEMENT"->"incomeStatement";case "BALANCE_SHEET"->"balanceSheet";default->"cashFlowStatement";};Object value=report.get(key);
   if(value instanceof List<?> rows)sections.add(new Section(entry.getValue(),(List<Map<String,Object>>)rows));
   else if(value instanceof Map<?,?> source) {var map=(Map<String,Object>)source;var rows=new ArrayList<Map<String,Object>>();if(map.get("lines") instanceof List<?> lines)rows.addAll((List<Map<String,Object>>)lines);if(map.get("categories") instanceof Map<?,?> categories)categories.forEach((k,v)->rows.add(Map.of("category",k,"amount",v)));map.forEach((k,v)->{if(!(v instanceof Collection<?>)&&!(v instanceof Map<?,?>))rows.add(Map.of("metric",k,"amount",v));});sections.add(new Section(entry.getValue(),rows));}
   else throw new ResponseStatusException(HttpStatus.CONFLICT,"所选报表结果缺失，不能导出其他报表替代");
  }
  var completeness=(Map<String,Object>)report.get("completeness");sections.add(new Section("数据完整性检查",(List<Map<String,Object>>)completeness.get("checks")));return sections;
 }
 private static Map<String,Object> metadata(Map<String,Object> report) {
  var result=new LinkedHashMap<String,Object>();var book=(Map<?,?>)report.get("book");for(String key:List.of("id","name","companyId","hotelId"))result.put("book."+key,book.get(key));for(String key:List.of("month","currency","amountUnit","dateBasis","taxBasis","generatedAt","reportVersion","status","detailAccess"))result.put(key,report.get(key));result.put("interpretation","仅依据已过账分录和已确认期初；INCOMPLETE不代表实际收入或成本为零。未假设税率、未执行外部报税。");return result;
 }
 private static byte[] xlsx(Map<String,Object> report,List<Section> sections) throws IOException {
  try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
   var money=workbook.createCellStyle();money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00;[Red](#,##0.00)"));var heading=workbook.createCellStyle();var font=workbook.createFont();font.setBold(true);heading.setFont(font);heading.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);var text=workbook.createCellStyle();text.setWrapText(true);text.setDataFormat(workbook.createDataFormat().getFormat("@"));
   var meta=workbook.createSheet("报表口径与版本");int rowNumber=0;for(var entry:metadata(report).entrySet()){var row=meta.createRow(rowNumber++);row.createCell(0,CellType.STRING).setCellValue(entry.getKey());row.createCell(1,CellType.STRING).setCellValue(Objects.toString(entry.getValue(),""));}meta.setColumnWidth(0,24*256);meta.setColumnWidth(1,100*256);meta.setDefaultColumnStyle(1,text);meta.createFreezePane(0,1);
   for(var section:sections) {var sheet=workbook.createSheet(section.name());var keys=new LinkedHashSet<String>();section.rows().forEach(r->keys.addAll(r.keySet()));if(keys.isEmpty())keys.add("无已过账分录（请结合完整性说明）");var headers=new ArrayList<>(keys);var header=sheet.createRow(0);for(int i=0;i<headers.size();i++){var cell=header.createCell(i,CellType.STRING);cell.setCellValue(headers.get(i));cell.setCellStyle(heading);sheet.setColumnWidth(i,Math.min(48,Math.max(16,headers.get(i).length()+4))*256);}int index=1;
    for(var record:section.rows()){var row=sheet.createRow(index++);for(int column=0;column<headers.size();column++){String key=headers.get(column);Object value=record.get(key);var cell=row.createCell(column);if(value!=null&&AMOUNTS.contains(key)){var amount=new BigDecimal(value.toString());if(amount.stripTrailingZeros().precision()>15)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"金额超过Excel数值有效精度，请使用PDF或屏幕精确金额核对");cell.getCTCell().setT(STCellType.N);cell.getCTCell().setV(amount.toPlainString());cell.setCellStyle(money);}else{cell.setCellValue(Objects.toString(value,""));cell.setCellStyle(text);}}}
    sheet.createFreezePane(0,1);if(!section.rows().isEmpty())sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,section.rows().size(),0,headers.size()-1));
   }
   workbook.getProperties().getCoreProperties().setTitle("财务账簿及三表 "+report.get("month"));workbook.getProperties().getCoreProperties().setDescription("reportVersion="+report.get("reportVersion"));workbook.write(output);return output.toByteArray();
  }
 }
 private static byte[] pdf(Map<String,Object> report,List<Section> sections) throws IOException {
  try(var document=new PDDocument();var font=loadFont(document);var output=new ByteArrayOutputStream()) {
   document.getDocumentInformation().setTitle("财务报表 "+report.get("month"));document.getDocumentInformation().setCustomMetadataValue("reportVersion",report.get("reportVersion").toString());
   try(var writer=new PdfWriter(document,font.font())) {writer.line("财务账簿与财务报表",16);for(var entry:metadata(report).entrySet())writer.line(entry.getKey()+"："+Objects.toString(entry.getValue(),""),9);
    for(var section:sections){writer.line("",9);writer.line(section.name(),14);if(section.rows().isEmpty())writer.line("无已过账分录；请结合报表口径与数据完整性核对。",10);int n=0;for(var row:section.rows()){writer.line("记录 "+(++n),10);for(var entry:row.entrySet())writer.line(entry.getKey()+"："+Objects.toString(entry.getValue(),""),9);}}
   }
   document.save(output);return output.toByteArray();
  }
 }
 private record FontHandle(PDFont font,TrueTypeCollection collection) implements AutoCloseable {public void close() throws IOException {if(collection!=null)collection.close();}}
 private static FontHandle loadFont(PDDocument document) throws IOException {
  var paths=new ArrayList<String>();String override=System.getProperty("hotel.ai.os.pdf.font.sans.path","").trim();if(!override.isEmpty())paths.add(override);paths.addAll(List.of("C:/Windows/Fonts/simhei.ttf","C:/Windows/Fonts/msyh.ttc","/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc","/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"));var errors=new ArrayList<String>();
  for(String path:paths){if(!Files.isRegularFile(Path.of(path)))continue;try {if(path.toLowerCase(Locale.ROOT).endsWith(".ttc")){var collection=new TrueTypeCollection(new File(path));try {for(String name:List.of("MicrosoftYaHei","WenQuanYiZenHei","NotoSansCJKsc-Regular")){var font=collection.getFontByName(name);if(font!=null)return new FontHandle(PDType0Font.load(document,font,!(font instanceof OpenTypeFont)),collection);}collection.close();}catch(Exception e){collection.close();throw e;}}else return new FontHandle(PDType0Font.load(document,new File(path)),null);}catch(IOException|RuntimeException e){errors.add(path+": "+e.getClass().getSimpleName());}}
  throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"未配置可用的PDF中文字体，请配置hotel.ai.os.pdf.font.sans.path；可先导出真实XLSX。"+String.join(";",errors));
 }
 private static final class PdfWriter implements AutoCloseable {
  private final PDDocument document;private final PDFont font;private PDPageContentStream stream;private float y;private int page;
  private PdfWriter(PDDocument document,PDFont font){this.document=document;this.font=font;}
  private void newPage() throws IOException {if(stream!=null)stream.close();var next=new PDPage(new PDRectangle(PDRectangle.A4.getHeight(),PDRectangle.A4.getWidth()));document.addPage(next);stream=new PDPageContentStream(document,next);y=next.getMediaBox().getHeight()-36;page++;draw("财务报表 / "+page,8);y-=25;}
  private void line(String value,float size) throws IOException {String clean=value.replace('\r',' ').replace('\n',' ').replace('\t',' ');var buffer=new StringBuilder();for(int offset=0;offset<clean.length();){int point=clean.codePointAt(offset);String character=new String(Character.toChars(point));offset+=Character.charCount(point);String candidate=buffer+character;if(font.getStringWidth(candidate)*size/1000>760&&!buffer.isEmpty()){write(buffer.toString(),size);buffer.setLength(0);}buffer.append(character);}write(buffer.toString(),size);}
  private void write(String value,float size) throws IOException {if(stream==null||y<42+size)newPage();draw(value,size);y-=size+5;}
  private void draw(String value,float size) throws IOException {stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(36,y);stream.showText(value);stream.endText();}
  @Override public void close() throws IOException {if(stream!=null)stream.close();}
 }
}
