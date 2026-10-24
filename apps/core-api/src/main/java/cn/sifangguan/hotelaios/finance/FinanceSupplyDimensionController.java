package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/finance/supply/financial-dimensions")
public class FinanceSupplyDimensionController {
 private final FinanceSupplyDimensionService service;
 public FinanceSupplyDimensionController(FinanceSupplyDimensionService service){this.service=service;}
 @GetMapping public Map<String,Object> context(@RequestParam UUID hotelId){return service.context(hotelId);}
 @PostMapping("/items") public Map<String,Object> item(@Valid @RequestBody FinanceSupplyDimensionService.Item in){return service.item(in);}
 @PostMapping("/movements") public Map<String,Object> classify(@Valid @RequestBody FinanceSupplyDimensionService.Classification in){return service.classify(in);}
 @PostMapping("/procurement-lines") public Map<String,Object> procurementLine(@Valid @RequestBody FinanceSupplyDimensionService.ProcurementClassification in){return service.procurementLine(in);}
 @PostMapping("/price-costs") public Map<String,Object> priceCosts(@Valid @RequestBody FinanceSupplyDimensionService.PriceCosts in){return service.priceCosts(in);}
}
