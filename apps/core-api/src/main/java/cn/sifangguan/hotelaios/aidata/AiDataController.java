package cn.sifangguan.hotelaios.aidata;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
@RequestMapping("/api/v1/ai-data")
public class AiDataController {
    private final AiDataService data;
    private final AiConnectionService connections;
    public AiDataController(AiDataService data,AiConnectionService connections) {this.data=data;this.connections=connections;}
    @GetMapping("/catalog") public Map<String,Object> catalog(){return data.catalog();}
    @PostMapping(value="/query",headers="X-SFG-AI-Action=1")
    public Map<String,Object> query(@Valid @RequestBody AiDataModels.Query input){return data.query(input);}
    @GetMapping("/connections") public List<Map<String,Object>> connections(){return connections.list();}
    @PostMapping(value="/connections",headers="X-SFG-AI-Action=1")
    public Map<String,Object> connect(@Valid @RequestBody AiDataModels.Connection input){return connections.create(input);}
    @PostMapping(value="/connections/{id}/revoke",headers="X-SFG-AI-Action=1") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id){connections.revoke(id);}
    @GetMapping("/reports") public List<Map<String,Object>> reports(){return data.reports();}
    @GetMapping("/reports/{id}") public Map<String,Object> report(@PathVariable UUID id){return data.report(id);}
    @PostMapping(value="/reports",headers="X-SFG-AI-Action=1")
    public Map<String,Object> save(@Valid @RequestBody AiDataModels.Report input){return data.save(input);}
}
