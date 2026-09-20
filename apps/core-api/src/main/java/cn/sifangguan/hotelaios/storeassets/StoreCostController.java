package cn.sifangguan.hotelaios.storeassets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/store-resources/hotels/{hotelId}/costs")
public class StoreCostController {
    private final StoreCostService service;
    private final StoreRoomNightCostService roomNightCosts;
    public StoreCostController(StoreCostService service, StoreRoomNightCostService roomNightCosts) {
        this.service=service; this.roomNightCosts=roomNightCosts;
    }

    // Legacy path remains an alias, but returns the versioned ROOM_USE contract (never relabeled nights).
    @GetMapping({"/per-room-use", "/per-room-night"})
    public StoreRoomNightCostService.Analysis perRoomUse(@PathVariable UUID hotelId,
            @RequestParam(defaultValue="MONTH") String periodType, @RequestParam(required=false) String period) {
        return roomNightCosts.analyze(hotelId,periodType,period);
    }

    @GetMapping
    public Map<String,Object> preview(@PathVariable UUID hotelId,@RequestParam(required=false) String month) {
        return service.preview(hotelId,month);
    }
    @GetMapping("/operating-summary")
    public StoreRoomNightCostService.OperatingSummary operating(@PathVariable UUID hotelId,
            @RequestParam(defaultValue="MONTH") String periodType,@RequestParam(required=false) String period,
            @RequestParam(required=false) String from,@RequestParam(required=false) String to) {
        return roomNightCosts.operating(hotelId,periodType,period,from,to);
    }
    @GetMapping("/entries")
    public Map<String,Object> entries(@PathVariable UUID hotelId,@RequestParam String month,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="ALL") String kind,
            @RequestParam(required=false) String day) { return service.entries(hotelId,month,page,kind,day); }
    @GetMapping("/closed-months")
    public Map<String,Object> history(@PathVariable UUID hotelId,@RequestParam(defaultValue="0") int page) {
        return service.history(hotelId,page);
    }
    @PostMapping("/close")
    public Map<String,Object> close(@PathVariable UUID hotelId,@Valid @RequestBody Close input) {
        return service.close(hotelId,input);
    }
    public record Close(@NotBlank @Pattern(regexp="\\d{4}-\\d{2}") String month,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String previewToken,
            @NotBlank @Size(max=1000) String note,boolean acknowledgeWarnings) { }
}
