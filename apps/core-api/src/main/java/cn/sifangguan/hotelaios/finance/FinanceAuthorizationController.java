package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceAuthorizationService.*;
@RestController @RequestMapping("/api/v1/finance/authorizations")
public class FinanceAuthorizationController {
 private final FinanceAuthorizationService service;
 public FinanceAuthorizationController(FinanceAuthorizationService service){this.service=service;}
 @GetMapping public Map<String,Object> list(){return service.list();}
 @PostMapping("/delegations") public Map<String,Object> create(@Valid @RequestBody Delegation in){return service.createDelegation(in);}
 @PostMapping("/delegations/{id}/publish") public Map<String,Object> publish(@PathVariable UUID id,@Valid @RequestBody Decision in){return service.publishDelegation(id,in,false);}
 @PostMapping("/delegations/{id}/revoke") public Map<String,Object> revoke(@PathVariable UUID id,@Valid @RequestBody Decision in){return service.publishDelegation(id,in,true);}
 @PostMapping("/budget-exceptions") public Map<String,Object> exception(@Valid @RequestBody ExceptionRequest in){return service.requestException(in);}
 @PostMapping("/budget-exceptions/{id}/review") public Map<String,Object> review(@PathVariable UUID id,@Valid @RequestBody Decision in){return service.reviewException(id,in);}
}
