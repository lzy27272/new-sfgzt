package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Test-only process protocol, not an antivirus implementation or a production bypass. */
public final class FinanceOriginalScannerFixture {
 private FinanceOriginalScannerFixture(){}
 static AttachmentMalwareScanner cleanScanner(){
  String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
  String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
  return new AttachmentMalwareScanner(executable,"-cp|"+classpath+"|"+FinanceOriginalScannerFixture.class.getName()+"|{file}",10,false,false);
 }
 public static void main(String[] args)throws Exception{
  byte[] original=Files.readAllBytes(Path.of(args[0]));
  String prefix=new String(original,0,Math.min(256,original.length),StandardCharsets.UTF_8).replace("\uFEFF","").stripLeading();
  boolean fixtureOriginal=prefix.startsWith("%PDF-")||prefix.startsWith("<?xml")||prefix.startsWith("<Invoice");
  System.exit(fixtureOriginal?0:2);
 }
}
