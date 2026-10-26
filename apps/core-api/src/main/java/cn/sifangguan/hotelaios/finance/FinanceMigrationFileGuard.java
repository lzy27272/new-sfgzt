package cn.sifangguan.hotelaios.finance;
import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.io.IOException;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;
/** Invoked at the HTTP boundary, before the transactional parser/service and before download. */
@Component
public class FinanceMigrationFileGuard {
 private final AttachmentMalwareScanner scanner;public FinanceMigrationFileGuard(AttachmentMalwareScanner scanner){this.scanner=scanner;}
 public void inspect(byte[] bytes){if(bytes.length<1||bytes.length>20*1024*1024)throw bad("迁移文件为空或超过20MB");Path temporary=null;try{temporary=Files.createTempFile("hotel-finance-migration-",".original");Files.write(temporary,bytes,StandardOpenOption.TRUNCATE_EXISTING);String status=scanner.scan(temporary);if(!scanner.canDownload(status)||status.equals("SANITIZED_IMAGE"))throw bad("迁移原始文件未通过安全扫描，不接受修改后的替代资料");}catch(IOException|IllegalArgumentException e){throw bad("迁移文件安全扫描未通过或扫描器不可用，已停止接收/下载；请联系管理员修复扫描配置");}finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){/* unique temporary file only; never a user path */}}}
}
