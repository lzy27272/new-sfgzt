package cn.sifangguan.hotelaios.academy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.Resource;
import java.util.*;
@RestController
@RequestMapping("/api/v1/academy")
public class AcademyController {
    private final AcademySupport s;private final AcademyAdministration admin;private final AcademyPlans plans;
    private final AcademyLearning learning;private final AcademyMedia media;
    public AcademyController(AcademySupport s,AcademyAdministration admin,AcademyPlans plans,AcademyLearning learning,AcademyMedia media){
        this.s=s;this.admin=admin;this.plans=plans;this.learning=learning;this.media=media;
    }
    @GetMapping("/admin") public Object catalogue(){return s.wire(admin.catalogue());}
    @PostMapping("/courses") public Object createCourse(@Valid @RequestBody AcademyModels.Course body){return s.wire(admin.saveCourse(null,body));}
    @PutMapping("/courses/{id}") public Object saveCourse(@PathVariable UUID id,@Valid @RequestBody AcademyModels.Course body){return s.wire(admin.saveCourse(id,body));}
    @GetMapping("/courses/{id}") public Object course(@PathVariable UUID id){return s.wire(admin.course(id));}
    @PostMapping("/courses/{id}/versions") public Object cloneCourse(@PathVariable UUID id){return s.wire(admin.cloneCourse(id));}
    @PostMapping("/courses/{id}/publish") public Object publishCourse(@PathVariable UUID id,@RequestBody AcademyModels.Version body){return s.wire(admin.publishCourse(id,body.revision()));}
    @PostMapping("/courses/{id}/media") public Object upload(@PathVariable UUID id,@RequestParam int revision,@RequestPart("file") MultipartFile file)throws Exception{return s.wire(media.upload(id,revision,file));}
    @GetMapping("/courses/{id}/media") public ResponseEntity<Resource> media(@PathVariable UUID id,@RequestParam(required=false) UUID planId,@RequestParam(required=false) Integer page){return media.content(id,planId,page);}
    @PostMapping("/questions") public Object question(@Valid @RequestBody AcademyModels.Question body){return s.wire(admin.saveQuestion(body.id(),body));}
    public record ImportQuestions(@NotNull @Valid @Size(min=1,max=500) List<AcademyModels.Question> questions){}
    @PostMapping("/questions/import") public Object importQuestions(@Valid @RequestBody ImportQuestions body){return s.wire(admin.importQuestions(body.questions()));}
    @GetMapping("/maps/{id}") public Object map(@PathVariable UUID id){return s.wire(admin.map(id));}
    @PostMapping("/maps") public Object createMap(@Valid @RequestBody AcademyModels.GrowthMap body){return s.wire(admin.saveMap(null,body));}
    @PutMapping("/maps/{id}") public Object saveMap(@PathVariable UUID id,@Valid @RequestBody AcademyModels.GrowthMap body){return s.wire(admin.saveMap(id,body));}
    @PostMapping("/maps/{id}/versions") public Object cloneMap(@PathVariable UUID id){return s.wire(admin.cloneMap(id));}
    @PostMapping("/maps/{id}/publish") public Object publishMap(@PathVariable UUID id,@RequestBody AcademyModels.Version body){return s.wire(admin.publishMap(id,body.revision()));}
    @GetMapping("/me") public Object mine(){return s.wire(plans.mine());}
    @GetMapping("/plans/{id}") public Object plan(@PathVariable UUID id){return s.wire(plans.detail(id));}
    @PostMapping("/sessions") public Object session(@Valid @RequestBody AcademyModels.Session body){return s.wire(learning.start(body));}
    @PostMapping("/sessions/{id}/heartbeat") public Object heartbeat(@PathVariable UUID id,@Valid @RequestBody AcademyModels.Heartbeat body){return s.wire(learning.heartbeat(id,body));}
    @GetMapping("/exams/{id}") public Object exam(@PathVariable UUID id){return s.wire(plans.exam(id));}
    @PostMapping("/exams/{id}/start") public Object startExam(@PathVariable UUID id){return s.wire(plans.startExam(id));}
    @PutMapping("/exams/{id}/answers") public Object answers(@PathVariable UUID id,@Valid @RequestBody AcademyModels.Responses body){return s.wire(plans.answer(id,body,false));}
    @PostMapping("/exams/{id}/submit") public Object submit(@PathVariable UUID id,@Valid @RequestBody AcademyModels.Responses body){return s.wire(plans.answer(id,body,true));}
    @GetMapping("/team") public Object team(){return s.wire(plans.team());}
    @GetMapping("/team/{id}") public Object teamDetail(@PathVariable UUID id){return s.wire(plans.teamDetail(id));}
    @PostMapping("/team/{id}/attempts") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void extra(@PathVariable UUID id,@Valid @RequestBody AcademyModels.ExtraAttempt body){plans.addAttempt(id,body);}
    @PostMapping("/team/{id}/remind") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remind(@PathVariable UUID id){plans.remind(id);}
}
