package cn.sifangguan.hotelaios.finance;

import javax.imageio.ImageIO;
import java.nio.file.*;

/** Test-only isolated scan process; validates the exact controlled JPEG/PNG bytes, not a production antivirus. */
public final class FinanceLocalOcrScannerFixture {
    public static void main(String[] args)throws Exception{
        byte[] bytes=Files.readAllBytes(Path.of(args[0]));
        boolean header=bytes.length>8&&((bytes[0]&255)==255&&(bytes[1]&255)==216||bytes[0]==(byte)137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71);
        System.exit(header&&ImageIO.read(new java.io.ByteArrayInputStream(bytes))!=null?0:2);
    }
}
