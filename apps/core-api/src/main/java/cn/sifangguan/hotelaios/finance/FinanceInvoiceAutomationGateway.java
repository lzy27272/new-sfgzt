package cn.sifangguan.hotelaios.finance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.io.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;

/** Configurable provider contract, not XML text extraction pretending to be OCR/tax validation. */
@Component
public class FinanceInvoiceAutomationGateway {
 private final String ocr,verify,key,provider;private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
 public FinanceInvoiceAutomationGateway(@Value("${hotel.ai.os.finance.invoice.ocr-endpoint:}")String ocr,@Value("${hotel.ai.os.finance.invoice.verification-endpoint:}")String verify,@Value("${hotel.ai.os.finance.invoice.api-key:}")String key,@Value("${hotel.ai.os.finance.invoice.provider:}")String provider){this.ocr=ocr;this.verify=verify;this.key=key;this.provider=provider;}
 public boolean ready(String op){return !key.isBlank()&&!provider.isBlank()&&!(op.equals("OCR")?ocr:verify).isBlank();}
 public String provider(){return provider;}
 public Map<String,Object> context(){return Map.of("ocrReady",ready("OCR"),"verificationReady",ready("VERIFY"),"provider",provider,"setupPath","服务端配置 hotel.ai.os.finance.invoice.ocr-endpoint / verification-endpoint / api-key / provider；每次提交仍须明确授权外传原件或验证字段","automaticTaxDeduction",false);}
 public Map<String,Object> call(String op,UUID request,Map<String,Object> payload,boolean queryOnly){if(!ready(op))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"真实OCR/发票验证服务未配置，不能将本地解析或人工确认冒充外部处理");URI endpoint;try{endpoint=URI.create(op.equals("OCR")?ocr:verify);if(!"https".equals(endpoint.getScheme())&&!Set.of("127.0.0.1","localhost","[::1]").contains(endpoint.getHost()))throw new IllegalArgumentException();if(endpoint.getUserInfo()!=null||endpoint.getFragment()!=null)throw new IllegalArgumentException();}catch(Exception e){throw bad("发票服务端点必须为受信HTTPS地址");}
  try{HttpRequest.Builder builder;if(queryOnly){URI query=URI.create(endpoint+(endpoint.getQuery()==null?"?":"&")+"requestId="+request);builder=HttpRequest.newBuilder(query).GET();}else builder=HttpRequest.newBuilder(endpoint).POST(HttpRequest.BodyPublishers.ofString(json(payload))).header("Content-Type","application/json");var response=client.send(builder.timeout(Duration.ofSeconds(45)).header("Authorization","Bearer "+key).header("Idempotency-Key",request.toString()).build(),HttpResponse.BodyHandlers.ofInputStream());try(var input=response.body()){byte[] bytes=input.readNBytes(512*1024+1);if(bytes.length>512*1024)throw new IOException("response bound");if(response.statusCode()!=200)throw new IOException("provider result unavailable");var result=map(JSON.readValue(bytes,Map.class));if(!provider.equals(result.get("provider"))||!request.toString().equals(result.get("requestId")))throw new IOException("provider identity mismatch");return result;}}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"外部处理结果尚不确定，已保留原请求号；请查询原请求结果，不要盲目重发原件");}
 }
}
