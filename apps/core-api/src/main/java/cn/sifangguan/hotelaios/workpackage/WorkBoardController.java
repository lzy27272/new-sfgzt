package cn.sifangguan.hotelaios.workpackage;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class WorkBoardController {
    private final WorkBoardService service;
    public WorkBoardController(WorkBoardService service) { this.service = service; }

    @GetMapping("/{scope:my|team}/work-board")
    public Map<String, Object> board(
            @PathVariable String scope,
            @RequestParam(required = false) UUID orgUnitId,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID assignmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "PERIOD") String queue,
            @RequestParam(defaultValue = "false") boolean lateOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.board(scope.equals("my"), orgUnitId, departmentId, assignmentId,
                from, to, status, queue, lateOnly, page, size);
    }
}
