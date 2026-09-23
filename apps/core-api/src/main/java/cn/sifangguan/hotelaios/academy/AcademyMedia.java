package cn.sifangguan.hotelaios.academy;

import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import static cn.sifangguan.hotelaios.academy.AcademySupport.*;

@Service
@Transactional
public class AcademyMedia {
    private final AcademySupport s;
    private final AcademyPlans plans;
    private final AcademyAdministration admin;
    private final Path root;
    public AcademyMedia(AcademySupport s,AcademyPlans plans,AcademyAdministration admin,
            @Value("${app.academy.storage-root:var/attachments/academy}") String directory){
        this.s=s;this.plans=plans;this.admin=admin;this.root=Path.of(directory).toAbsolutePath().normalize();
    }
    public Map<String,Object> upload(UUID id,int revision,MultipartFile file) throws Exception{
        var actor=s.prepare("academy.manage");s.scope(null);
        var course=s.one("select * from academy_course where tenant_id=:tid and id=:id for update",s.params().addValue("id",id));
        if(!Set.of("DRAFT","READY").contains(str(course,"state"))||integer(course,"revision")!=revision)throw conflict("课件已更新或发布，请刷新后创建新版本");
        boolean video="VIDEO".equals(str(course,"kind"));
        long limit=video?200L*1024*1024:30L*1024*1024;
        if(file.isEmpty()||file.getSize()>limit)throw bad(video?"视频需小于 200 MB":"PPT 需小于 30 MB");
        String original=Optional.ofNullable(file.getOriginalFilename()).orElse("").replaceAll("[\\\\/\\r\\n]","_");
        String lower=original.toLowerCase(Locale.ROOT);
        if(video?!lower.endsWith(".mp4"):!(lower.endsWith(".ppt")||lower.endsWith(".pptx")))throw bad("请上传 MP4 视频或 PPT/PPTX 演示文稿");
        String key=actor.tenantId()+"/"+id+"/"+UUID.randomUUID();
        Path folder=resolve(key);Files.createDirectories(folder);
        // Only this new random directory is removed on a failed transaction; published versions are immutable.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)cleanup(folder);}
        });
        Path source=folder.resolve(video?"video.mp4":lower.endsWith(".pptx")?"source.pptx":"source.ppt");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=file.getInputStream();OutputStream out=Files.newOutputStream(source,StandardOpenOption.CREATE_NEW)){
            byte[] buffer=new byte[65536];long size=0;int n;
            while((n=in.read(buffer))!=-1){size+=n;if(size>limit)throw bad("文件大小超出限制");out.write(buffer,0,n);digest.update(buffer,0,n);}
        }
        Double duration=null;Integer count=null;List<Integer> seconds=new ArrayList<>();
        try{
            if(video){duration=mp4Duration(source);if(duration<=0||duration>7200)throw bad("视频需包含有效画面，时长不超过 120 分钟");}
            else{
                try(var show=SlideShowFactory.create(source.toFile())){
                    count=show.getSlides().size();
                    if(count<1||count>200)throw bad("PPT 页数须为 1 至 200 页");
                    Dimension size=show.getPageSize();
                    if(size.width<=0||size.height<=0)throw bad("PPT 页面尺寸无效");
                    double scale=Math.min(1400.0/size.width,1000.0/size.height);
                    int width=Math.max(1,(int)(size.width*scale)),height=Math.max(1,(int)(size.height*scale));
                    int page=0;
                    for(var slide:show.getSlides()){
                        var img=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
                        var graphics=img.createGraphics();
                        try{
                            graphics.setColor(Color.WHITE);graphics.fillRect(0,0,width,height);
                            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                            graphics.scale(scale,scale);slide.draw(graphics);
                            ImageIO.write(img,"png",folder.resolve("page-"+(++page)+".png").toFile());
                        }finally{graphics.dispose();img.flush();}
                        seconds.add(5);
                    }
                }
            }
        }catch(org.springframework.web.server.ResponseStatusException error){throw error;}
        catch(Exception error){throw bad("课件无法解析，请检查文件是否损坏、加密或包含不支持的内容");}
        s.db.update("""
            update academy_course set state='READY',object_key=:key,original_name=:name,sha256=:hash,size_bytes=:size,
              duration_seconds=:duration,page_count=:count,page_seconds=cast(:seconds as jsonb),media_type=:type,
              processing_note=:note,revision=revision+1 where tenant_id=:tid and id=:id
            """,s.params().addValue("id",id).addValue("key",key).addValue("name",original.substring(0,Math.min(200,original.length())))
            .addValue("hash",HexFormat.of().formatHex(digest.digest())).addValue("size",file.getSize())
            .addValue("duration",duration).addValue("count",count).addValue("seconds",s.encode(seconds))
            .addValue("type",video?"video/mp4":"image/png").addValue("note",video?"请预览确认视频可正常播放":"请逐页预览版式；演示动画以静态页面呈现"));
        s.audit("ACADEMY_MEDIA_UPLOADED",id,Map.of("size",file.getSize(),"kind",str(course,"kind")));
        return admin.course(id);
    }
    public ResponseEntity<Resource> content(UUID id,UUID planId,Integer page){
        Map<String,Object> course;
        if(planId==null){
            s.prepare("academy.manage");s.scope(null);
            course=s.one("select * from academy_course where tenant_id=:tid and id=:id",s.params().addValue("id",id));
        }else{
            s.prepare("academy.learn");course=plans.accessibleCourse(planId,id);
            if(page!=null){
                var plan=plans.ownPlan(planId,false);
                Integer completed=s.db.queryForObject("select coalesce(max(completed_pages),0) from academy_progress where tenant_id=:tid and employee_id=:employee and course_id=:course",
                    s.params().addValue("employee",id(plan,"employee_id")).addValue("course",id),Integer.class);
                if(page>completed+1)throw conflict("请先完成前面的课件页面");
            }
        }
        if(course.get("object_key")==null)throw missing();
        boolean video="VIDEO".equals(str(course,"kind"));
        if(!video && (page==null||page<1||page>integer(course,"page_count")))throw bad("课件页码无效");
        Path path=resolve(str(course,"object_key")).resolve(video?"video.mp4":"page-"+page+".png");
        if(!Files.isRegularFile(path))throw missing();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(video?"video/mp4":"image/png"))
            .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
            .body(new FileSystemResource(path));
    }
    private Path resolve(String key){
        Path path=root.resolve(key).normalize();if(!path.startsWith(root))throw missing();return path;
    }
    private void cleanup(Path folder){
        if(!folder.normalize().startsWith(root)||folder.equals(root))return;
        try(var paths=Files.walk(folder)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        catch(IOException ignored){/* A failed upload can be reclaimed by an operator; never touch another version. */}
    }
    /** Parse ISO BMFF movie timing and require a video handler; never trust browser supplied duration. */
    static double mp4Duration(Path path) throws IOException{
        try(var file=new RandomAccessFile(path.toFile(),"r")){
            var metadata=new double[]{0,0};
            scanAtoms(file,0,file.length(),0,metadata);
            if(metadata[1]!=1)throw new IOException("No video track");
            return metadata[0];
        }
    }
    private static void scanAtoms(RandomAccessFile file,long start,long end,int depth,double[] result)throws IOException{
        if(depth>5)throw new IOException("Invalid MP4 nesting");
        long cursor=start;int count=0;
        while(cursor+8<=end){
            if(++count>10000)throw new IOException("Invalid MP4 atom count");
            file.seek(cursor);long size=Integer.toUnsignedLong(file.readInt());int type=file.readInt();long header=8;
            if(size==1){size=file.readLong();header=16;}else if(size==0)size=end-cursor;
            if(size<header||size>end-cursor)throw new IOException("Invalid MP4 atom");
            long data=cursor+header,next=cursor+size;
            if(type==0x6d6f6f76||type==0x7472616b||type==0x6d646961)scanAtoms(file,data,next,depth+1,result);
            else if(type==0x6d766864){
                file.seek(data);int version=file.readUnsignedByte();file.skipBytes(3);
                int skip=version==1?16:8;if(version>1||size<header+4+skip+4+(version==1?8:4))throw new IOException("Invalid timing");
                file.skipBytes(skip);long scale=Integer.toUnsignedLong(file.readInt());
                long duration=version==1?file.readLong():Integer.toUnsignedLong(file.readInt());
                if(scale<=0||duration<=0)throw new IOException("Invalid duration");result[0]=duration/(double)scale;
            }else if(type==0x68646c72 && next-data>=12){
                file.seek(data+8);if(file.readInt()==0x76696465)result[1]=1;
            }
            cursor=next;
        }
    }
}
