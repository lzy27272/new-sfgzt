package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
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

/** Separate immutable original store: never applies operational photo rotations, watermarks or retention jobs. */
@Service
public class FinanceEvidenceService {
    private static final long LIMIT=20L*1024*1024;
    private final Path root;
    private final FinanceService service;
    private final AccessPolicy access;
    private final AttachmentMalwareScanner scanner;
    public FinanceEvidenceService(@Value("${app.finance.attachments-root:var/finance-evidence}") String root,FinanceService service,AccessPolicy access,AttachmentMalwareScanner scanner) {
        this.root=Path.of(root).toAbsolutePath().normalize();this.service=service;this.access=access;this.scanner=scanner;
    }
    public Map<String,Object> upload(UUID claim,UUID attachment,String kind,MultipartFile file) {
        service.authorizeUpload(claim);
        if(!Set.of("INVOICE","TRANSACTION","PAYMENT").contains(kind)) throw bad("凭证类型无效");
        if(file.isEmpty()||file.getSize()>LIMIT) throw bad("单份凭证应为1字节至20MB的PDF、JPG或PNG");
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
            Map<String,Object> extraction=inspect(bytes,type);
            var result=service.registerAttachment(claim,attachment,kind,name,type,bytes.length,hash,key,scan,extraction);
            stored=true;
            // Concurrent identical retries can return an already committed attachment; discard only this request's unused bytes.
            stored=key.equals(service.attachment(claim,attachment).get("object_key"));
            return result;
        } catch(ResponseStatusException e) {throw e;}
        catch(IOException|IllegalArgumentException e) {throw bad("文件无法安全读取；请上传未加密的PDF或有效JPG/PNG，原始草稿仍可保留");}
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
            return new Download(record.get("original_name").toString(),record.get("media_type").toString(),new ByteArrayResource(bytes));
        } catch(IOException e) {throw new ResponseStatusException(HttpStatus.CONFLICT,"原始凭证缺失或完整性校验失败，请联系管理员恢复备份；系统不会用其他文件替代");}
    }
    private Path resolve(String key) {
        if(!key.matches("[a-f0-9-]{36}/[a-f0-9-]{36}/[a-f0-9-]{36}\\.original")) throw bad("凭证索引无效");
        var result=root.resolve(key).normalize();if(!result.startsWith(root)) throw bad("凭证索引无效");return result;
    }
    static String mediaType(byte[] bytes) {
        if(bytes.length>=5 && new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
        if(bytes.length>=3 && (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255) return "image/jpeg";
        if(bytes.length>=8 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        throw bad("仅支持真实的PDF、JPG或PNG，不接受改扩展名的文件");
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
