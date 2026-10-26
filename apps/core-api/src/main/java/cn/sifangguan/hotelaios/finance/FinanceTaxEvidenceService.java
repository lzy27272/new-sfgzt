package cn.sifangguan.hotelaios.finance;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Read-only access to an already archived original, not a copied or rewritten tax rendition. */
@Service
public class FinanceTaxEvidenceService {
 private final Path root;private final FinanceInputTaxService service;private final AccessPolicy access;private final AttachmentMalwareScanner scanner;
 public FinanceTaxEvidenceService(@Value("${app.finance.attachments-root:var/finance-evidence}")String root,FinanceInputTaxService service,AccessPolicy access,AttachmentMalwareScanner scanner){this.root=Path.of(root).toAbsolutePath().normalize();this.service=service;this.access=access;this.scanner=scanner;}
 public FinanceEvidenceService.Download download(UUID basis){var row=service.original(basis);String key=Objects.toString(row.get("objectKey"),""),scan=Objects.toString(row.get("scanStatus"),"");if(!scanner.canDownload(scan)||"SANITIZED_IMAGE".equals(scan))throw new ResponseStatusException(HttpStatus.CONFLICT,"原始票据尚未完成安全扫描，不能以改写图片代替原件");if(!key.matches("[a-f0-9-]{36}/[a-f0-9-]{36}/[a-f0-9-]{36}\\.original")||!key.startsWith(access.principal().tenantId()+"/"))throw new ResponseStatusException(HttpStatus.CONFLICT,"税务原件索引无效");Path path=root.resolve(key).normalize();if(!path.startsWith(root))throw new ResponseStatusException(HttpStatus.CONFLICT,"税务原件路径无效");try{if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=Long.parseLong(row.get("sizeBytes").toString()))throw new IOException();byte[] bytes=Files.readAllBytes(path);if(!FinanceService.hash(bytes).equals(row.get("sha256")))throw new IOException();return new FinanceEvidenceService.Download(row.get("originalName").toString(),row.get("mediaType").toString(),new ByteArrayResource(bytes));}catch(IOException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"原件缺失或完整性校验失败，请恢复真实备份；系统不会替换或重绘原件");}}
}
