package cn.sifangguan.hotelaios.dataretention;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class DataRetentionController {
    private final DataRetentionService service;

    public DataRetentionController(DataRetentionService service) {
        this.service = service;
    }

    @GetMapping("/data-retention-policy")
    public DataRetentionModels.Policy policy() {
        return service.policy();
    }

    @PutMapping("/data-retention-policy")
    public DataRetentionModels.Policy updatePolicy(
            @Valid @RequestBody DataRetentionModels.UpdatePolicy request
    ) {
        return service.updatePolicy(request);
    }

    @GetMapping("/data-retention-preview")
    public DataRetentionModels.CleanupPreview cleanupPreview() {
        return service.cleanupPreview();
    }
}
