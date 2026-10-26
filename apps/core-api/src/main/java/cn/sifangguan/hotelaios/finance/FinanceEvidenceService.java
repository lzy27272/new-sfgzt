package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.util.zip.ZipInputStream;

/** Separate immutable original store: never applies operational photo rotations, watermarks or retention jobs. */
@Service
public class FinanceEvidenceService {
    private static final long LIMIT=20L*1024*1024;
    private final Path root;
    private final FinanceService service;
    private final AccessPolicy access;
    private final AttachmentMalwareScanner scanner;
    private final FinanceLocalOcrClient localOcr;
    @Autowired
    public FinanceEvidenceService(@Value("${app.finance.attachments-root:var/finance-evidence}") String root,FinanceService service,AccessPolicy access,AttachmentMalwareScanner scanner,FinanceLocalOcrClient localOcr) {
        this.root=Path.of(root).toAbsolutePath().normalize();this.service=service;this.access=access;this.scanner=scanner;this.localOcr=localOcr;
    }
    public FinanceEvidenceService(String root,FinanceService service,AccessPolicy access,AttachmentMalwareScanner scanner){this(root,service,access,scanner,new FinanceLocalOcrClient("","",15000,FinanceV2Support.JSON));}
    public Map<String,Object> upload(UUID claim,UUID attachment,String kind,MultipartFile file) {
        service.authorizeUpload(claim);
        if(!Set.of("INVOICE","TRANSACTION","PAYMENT").contains(kind)) throw bad("凭证类型无效");
        if(file.isEmpty()||file.getSize()>LIMIT) throw bad("单份凭证应为1字节至20MB的PDF、OFD、XML、JPG或PNG");
        String name=Objects.toString(file.getOriginalFilename(),"凭证").replaceAll("[\\p{Cntrl}/\\\\]","_");
        if(name.length()>180) name=name.substring(name.length()-180);
        String key=access.principal().tenantId()+"/"+claim+"/"+UUID.randomUUID()+".original";
        Path destination=resolve(key);boolean stored=false;
        try {
            byte[] bytes;
            try(var input=file.getInputStream()) {bytes=input.readNBytes((int)LIMIT+1);}
            if(bytes.length==0||bytes.length>LIMIT) throw bad("凭证大小超过20MB");
            String hash=FinanceService.hash(bytes);
            Files.createDirectories(destination.getParent());
            Files.write(destination,bytes,StandardOpenOption.CREATE_NEW);
            String scan;
            try {scan=scanner.scan(destination);} catch(IllegalArgumentException e) {throw bad("凭证安全扫描未通过或扫描服务不可用，请联系管理员。原始文件未被修改，您可保留本机草稿后重试。");}
            if(!scanner.canDownload(scan)||"SANITIZED_IMAGE".equals(scan)) throw bad("财务凭证必须对原文件完成安全扫描，不能使用图片重编码替代扫描");
            String type=mediaType(bytes);
            Map<String,Object> extraction=inspectOriginal(bytes,type,kind,scan);
            var result=service.registerAttachment(claim,attachment,kind,name,type,bytes.length,hash,key,scan,extraction);
            stored=true;
            // Concurrent identical retries can return an already committed attachment; discard only this request's unused bytes.
            stored=key.equals(service.attachment(claim,attachment).get("object_key"));
            return result;
        } catch(ResponseStatusException e) {throw e;}
        catch(IOException|IllegalArgumentException e) {throw bad("文件无法安全读取；请上传未加密的PDF、有效OFD/XML或JPG/PNG，原始草稿仍可保留");}
        finally {if(!stored) {try {Files.deleteIfExists(destination);} catch(IOException ignored) { /* orphan cleanup must not affect committed evidence */ }}}
    }
    public Download download(UUID claim,UUID attachment) {
        var record=service.attachment(claim,attachment);
        if(!scanner.canDownload(record.get("scan_status").toString())||"SANITIZED_IMAGE".equals(record.get("scan_status"))) throw bad("原始凭证未通过安全扫描");
        Path path=resolve(record.get("object_key").toString());
        try {
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=((Number)record.get("size_bytes")).longValue()) throw new IOException();
            byte[] bytes=Files.readAllBytes(path);
            if(!FinanceService.hash(bytes).equals(record.get("sha256"))) throw new IOException();
            service.recordEvidenceRead(claim,attachment);
            return new Download(record.get("original_name").toString(),record.get("media_type").toString(),new ByteArrayResource(bytes));
        } catch(IOException e) {throw new ResponseStatusException(HttpStatus.CONFLICT,"原始凭证缺失或完整性校验失败，请联系管理员恢复备份；系统不会用其他文件替代");}
    }
    public Map<String,Object> uploadIntake(UUID id,MultipartFile file) {
        service.authorizeIntakeUpload(id);
        if(file.isEmpty()||file.getSize()>LIMIT)throw bad("收票原件应为1字节至20MB");
        String name=Objects.toString(file.getOriginalFilename(),"发票原件").replaceAll("[\\p{Cntrl}/\\\\]","_");if(name.length()>180)name=name.substring(name.length()-180);
        String key=access.principal().tenantId()+"/"+id+"/"+UUID.randomUUID()+".original";Path destination=resolve(key);boolean stored=false;
        try {
            byte[] bytes;try(var input=file.getInputStream()){bytes=input.readNBytes((int)LIMIT+1);}
            if(bytes.length==0||bytes.length>LIMIT)throw bad("原件超过20MB");
            Files.createDirectories(destination.getParent());Files.write(destination,bytes,StandardOpenOption.CREATE_NEW);
            String scan;try{scan=scanner.scan(destination);}catch(IllegalArgumentException e){throw bad("收票安全扫描不可用或未通过；请保留原件后重试");}
            if(!scanner.canDownload(scan)||"SANITIZED_IMAGE".equals(scan))throw bad("财务原件必须通过原文件扫描，不能以重编码结果代替");
            String type=mediaType(bytes);var extraction=inspectOriginal(bytes,type,"INVOICE",scan);
            var original=Map.<String,Object>of("originalName",name,"mediaType",type,"sizeBytes",bytes.length,"sha256",FinanceService.hash(bytes),"objectKey",key,"scanStatus",scan,"extraction",extraction);
            var result=service.registerIntakeOriginal(id,original);stored=key.equals(result.get("objectKey"));return service.intakeDetail(id);
        }catch(ResponseStatusException e){throw e;}catch(IOException e){throw bad("原件无法安全读取，请重新选择有效文件");}
        finally{if(!stored){try{Files.deleteIfExists(destination);}catch(IOException ignored){}}}
    }
    public Download downloadIntake(UUID id) {
        var original=service.intakeOriginal(id);
        if(!scanner.canDownload(original.get("scanStatus").toString())||"SANITIZED_IMAGE".equals(original.get("scanStatus")))throw bad("原件尚未通过安全扫描");
        Path path=resolve(original.get("objectKey").toString());
        try {
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=((Number)original.get("sizeBytes")).longValue())throw new IOException();
            byte[] bytes=Files.readAllBytes(path);if(!FinanceService.hash(bytes).equals(original.get("sha256")))throw new IOException();
            return new Download(original.get("originalName").toString(),original.get("mediaType").toString(),new ByteArrayResource(bytes));
        }catch(IOException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"原件缺失或完整性校验失败，请联系管理员恢复备份");}
    }
    public Map<String,Object> recognizeAttachment(UUID claim,UUID attachment){
        service.authorizeUpload(claim);var record=service.attachment(claim,attachment);
        if(record.get("retired_at")!=null)throw bad("已移除的凭证不能重新识别");
        String kind=record.get("kind").toString(),type=record.get("media_type").toString();
        if(!Set.of("image/jpeg","image/png").contains(type))throw bad("本地图片识别仅支持已保存的JPG或PNG原件");
        byte[] bytes=originalBytes(record.get("object_key").toString(),((Number)record.get("size_bytes")).longValue(),record.get("sha256").toString());
        String scan=rescan(resolve(record.get("object_key").toString()));
        try{return service.recordAttachmentRecognition(claim,attachment,inspectOriginal(bytes,type,kind,scan));}
        catch(IOException invalid){throw bad("原件图像无法安全解码，请核对已保存的文件");}
    }
    public Map<String,Object> recognizeIntake(UUID id){
        service.authorizeIntakeUpload(id);var original=service.intakeOriginal(id);String type=original.get("mediaType").toString();
        if(!Set.of("image/jpeg","image/png").contains(type))throw bad("本地图片识别仅支持已保存的JPG或PNG原件");
        byte[] bytes=originalBytes(original.get("objectKey").toString(),((Number)original.get("sizeBytes")).longValue(),original.get("sha256").toString());
        String scan=rescan(resolve(original.get("objectKey").toString()));
        try{return service.recordIntakeRecognition(id,inspectOriginal(bytes,type,"INVOICE",scan));}
        catch(IOException invalid){throw bad("原件图像无法安全解码，请核对已保存的文件");}
    }
    private String rescan(Path path){
        String scan;try{scan=scanner.scan(path);}catch(IllegalArgumentException invalid){throw bad("原件安全扫描未通过或扫描器不可用；原件仍保留，不执行识别");}
        if(!scanner.canDownload(scan)||"SANITIZED_IMAGE".equals(scan))throw bad("原始凭证未通过安全扫描，不执行识别");return scan;
    }
    private byte[] originalBytes(String key,long size,String sha256){
        Path path=resolve(key);
        try{if(size>LIMIT||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=size)throw new IOException();byte[] bytes=Files.readAllBytes(path);if(!FinanceService.hash(bytes).equals(sha256))throw new IOException();return bytes;}
        catch(IOException invalid){throw new ResponseStatusException(HttpStatus.CONFLICT,"原始凭证缺失或完整性校验失败，请联系管理员恢复备份；不能替换原件后重试");}
    }
    private Map<String,Object> inspectOriginal(byte[] bytes,String type,String kind,String scan)throws IOException {
        var extraction=new LinkedHashMap<String,Object>(inspect(bytes,type));
        if(Set.of("image/jpeg","image/png").contains(type)){
            String hash=FinanceService.hash(bytes);
            extraction.putAll("CLEAN".equals(scan)?localOcr.recognize(bytes,type,kind,hash):FinanceLocalOcrClient.unavailable("FAILED",hash,"原件尚未完成真实安全扫描，未调用图片识别；请人工填写并联系管理员完成扫描配置。"));
        }
        return extraction;
    }
    private Path resolve(String key) {
        if(!key.matches("[a-f0-9-]{36}/[a-f0-9-]{36}/[a-f0-9-]{36}\\.original")) throw bad("凭证索引无效");
        var result=root.resolve(key).normalize();if(!result.startsWith(root)) throw bad("凭证索引无效");return result;
    }
    static String mediaType(byte[] bytes) {
        if(bytes.length>=5 && new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
        if(bytes.length>=3 && (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255) return "image/jpeg";
        if(bytes.length>=8 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if(bytes.length>=4&&bytes[0]=='P'&&bytes[1]=='K'&&bytes[2]==3&&bytes[3]==4) {ofdText(bytes);return "application/ofd";}
        String prefix=new String(bytes,0,Math.min(bytes.length,200),StandardCharsets.UTF_8).replace("\uFEFF","").stripLeading();
        if(prefix.startsWith("<")) {xmlText(bytes);return "application/xml";}
        throw bad("仅支持真实的PDF、OFD、XML、JPG或PNG，不接受改扩展名的文件");
    }
    static Map<String,Object> inspect(byte[] bytes,String type) throws IOException {
        var result=new LinkedHashMap<String,Object>();result.put("confirmationRequired",true);
        if(type.equals("application/pdf")) {
            try(var document=Loader.loadPDF(bytes)) {
                if(document.isEncrypted()||document.getNumberOfPages()<1||document.getNumberOfPages()>30) throw bad("PDF须未加密且不超过30页");
                validatePdf(document.getDocumentCatalog().getCOSObject());
                var stripper=new PDFTextStripper();stripper.setEndPage(30);
                String text=stripper.getText(document);if(text.length()>100000) throw bad("PDF文字过多，请拆分后上传");
                result.put("source",text.isBlank()?"MANUAL_REQUIRED":"PDF_TEXT");
                result.putAll(extract(text));result.put("pages",document.getNumberOfPages());
                result.put("notice","仅提取PDF文字，不代表税务查验结果；请对照原件确认。照片或扫描件需人工录入。");
            }
        } else if(type.equals("application/xml")||type.equals("application/ofd")) {
            String text=type.equals("application/ofd")?ofdText(bytes):xmlText(bytes);
            result.put("source",type.equals("application/ofd")?"OFD_XML_TEXT":"XML_TEXT");result.putAll(extract(text));
            result.put("externalVerificationStatus","NOT_VERIFIED");result.put("previewMode","ORIGINAL_DOWNLOAD");
            result.put("notice","已安全解析原件文字；仅候选信息，不代表税务查验。OFD/XML原件可下载，不伪造图片预览。");
        } else {
            try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(input);if(!readers.hasNext()) throw bad("图片无法解码");
                var reader=readers.next();try {reader.setInput(input);long w=reader.getWidth(0),h=reader.getHeight(0);
                    if(w<=0||h<=0||w*h>40000000L) throw bad("凭证图片像素过大，请使用不超过4000万像素的原件副本");
                    if(reader.read(0)==null) throw bad("图片无法解码");result.put("width",w);result.put("height",h);
                } finally {reader.dispose();}
            }
            result.put("source","MANUAL_REQUIRED");result.put("notice","照片识别服务尚未接入，请人工核对发票号码、金额和购买方税号；文件原样保存。");
        }
        return result;
    }
    private static String xmlText(byte[] bytes) {
        try {
            var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            String rootName=document.getDocumentElement().getLocalName();
            if(rootName==null) rootName=document.getDocumentElement().getNodeName();
            if(Set.of("script","html","svg","iframe").contains(rootName.toLowerCase(Locale.ROOT))) throw bad("XML不是财务票据或OFD资料");
            String text=document.getDocumentElement().getTextContent();if(text.length()>100000) throw bad("XML文字过多");return text;
        } catch(ResponseStatusException e){throw e;} catch(Exception e){throw bad("XML/OFD结构无效或含不安全的外部实体");}
    }
    private static String ofdText(byte[] bytes) {
        boolean descriptor=false;int count=0;long expanded=0;var text=new StringBuilder();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
                String name=entry.getName();if(++count>1000||name.startsWith("/")||name.contains("\\")||Arrays.asList(name.split("/")).contains("..")) throw bad("OFD压缩结构不安全");
                if(entry.isDirectory()) continue;
                byte[] content=zip.readNBytes(4*1024*1024+1);expanded+=content.length;
                if(content.length>4*1024*1024||expanded>40L*1024*1024) throw bad("OFD解压后大小超出安全上限");
                if(name.equalsIgnoreCase("OFD.xml")) descriptor=true;
                if(name.toLowerCase(Locale.ROOT).endsWith(".xml")) text.append(xmlText(content)).append('\n');
                if(text.length()>100000) throw bad("OFD文字过多");
            }
        }catch(IOException e){throw bad("OFD压缩文件无效");}
        if(!descriptor) throw bad("ZIP不是有效OFD，缺少OFD.xml描述文件");return text.toString();
    }
    static Map<String,Object> extract(String text) {
        // Only labelled values are candidates. Never manufacture missing tax numbers or amounts.
        String value=text.replaceAll("[\\s\\u00a0]+","");var out=new LinkedHashMap<String,Object>();
        candidate(out,"invoiceNumber",value,"发票号码[:：]?([0-9]{20}|[0-9]{8})(?![0-9])");
        candidate(out,"invoiceCode",value,"发票代码[:：]?([0-9]{12}|[0-9]{10})(?![0-9])");
        candidate(out,"amount",value,"(?:价税合计.*?小写[)）]?|小写[)）]?)[:：]?[¥￥]?([0-9]+\\.[0-9]{2})(?![0-9])");
        return out;
    }
    private static void candidate(Map<String,Object> out,String key,String text,String regex) {
        var matcher=Pattern.compile(regex).matcher(text);if(matcher.find()) {String value=matcher.group(1);if(!matcher.find())out.put(key,value);}
    }
    private static void validatePdf(COSBase root) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<COSBase,Boolean>());var queue=new ArrayDeque<COSBase>();queue.add(root);
        var forbidden=Set.of("JS","JavaScript","OpenAction","AA","EmbeddedFiles","XFA","Launch","RichMedia");
        while(!queue.isEmpty()) {
            var value=queue.removeFirst();if(!seen.add(value))continue;if(seen.size()>100000)throw bad("PDF结构过于复杂");
            if(value instanceof COSObject object) {if(object.getObject()!=null)queue.add(object.getObject());}
            else if(value instanceof COSDictionary dictionary) for(var key:dictionary.keySet()) {
                if(forbidden.contains(key.getName()))throw bad("不接受包含脚本、自动动作或嵌入文件的PDF");
                var item=dictionary.getItem(key);if(item!=null)queue.add(item);
            }
            else if(value instanceof COSArray array) {for(var item:array) if(item!=null)queue.add(item);}
            else if(value instanceof COSName name && forbidden.contains(name.getName()))throw bad("不接受包含活动内容的PDF");
        }
    }
    private static ResponseStatusException bad(String message) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    public record Download(String name,String mediaType,Resource resource) { }
}
