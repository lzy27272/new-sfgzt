package cn.sifangguan.hotelaios.finance;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Operator-configured local/private worker only. No external invoice gateway or automatic verification. */
@Component
public class FinanceLocalOcrClient {
    private static final int MAX_RESPONSE=512*1024;
    private final String endpoint,token;private final int timeoutMs;private final ObjectMapper mapper;
    private final HttpClient client;
    public FinanceLocalOcrClient(@Value("${hotel.ai.os.finance.local-ocr.endpoint:}") String endpoint,
            @Value("${hotel.ai.os.finance.local-ocr.token:}") String token,
            @Value("${hotel.ai.os.finance.local-ocr.timeout-ms:15000}") int timeoutMs,ObjectMapper mapper) {
        this.endpoint=Objects.toString(endpoint,"").trim();this.token=Objects.toString(token,"");this.timeoutMs=Math.max(100,Math.min(60000,timeoutMs));this.mapper=mapper;
        this.client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofMillis(this.timeoutMs)).proxy(new ProxySelector(){
            @Override public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
            @Override public void connectFailed(URI uri,SocketAddress address,IOException error){ }
        }).build();
    }
    public Map<String,Object> recognize(byte[] bytes,String mediaType,String kind,String sha256) {
        if(endpoint.isBlank())return unavailable("DISABLED",sha256,"本地图片识别尚未启用；原件已安全保存，可人工填写或稍后按原件重试。");
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            URI target=trustedEndpoint(endpoint);
            if(token.isBlank()||token.contains("\r")||token.contains("\n"))throw new IllegalArgumentException();
            if(!Set.of("image/jpeg","image/png").contains(mediaType)||!Set.of("INVOICE","TRANSACTION","PAYMENT").contains(kind)||!sha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            byte[] body=mapper.writeValueAsBytes(Map.of("sourceSha256",sha256,"mediaType",mediaType,"kind",kind,"contentBase64",Base64.getEncoder().encodeToString(bytes)));
            var request=HttpRequest.newBuilder(target).timeout(Duration.ofMillis(timeoutMs)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            pending=client.sendAsync(request,info->new LimitedBody());
            var response=pending.get(timeoutMs,TimeUnit.MILLISECONDS);
            if(response.statusCode()!=200)return unavailable("UNAVAILABLE",sha256,"本地图片识别服务暂不可用；原件已保存，请稍后按原件重试，或人工核对填写。");
            JsonNode json=mapper.readTree(response.body());
            if(json==null||!json.isObject()||!json.path("provider").asText().equals("RapidOCR")||!json.path("sourceSha256").asText().equals(sha256)||!json.path("blocks").isArray()||json.path("blocks").size()>1000||!json.path("qrCodes").isArray()||json.path("qrCodes").size()>20||!json.path("warnings").isArray()||json.path("warnings").size()>50)throw new IllegalArgumentException();
            var blocks=new ArrayList<FinanceReceiptFields.Block>();int textSize=0;
            for(var block:json.path("blocks")) {
                if(!block.path("text").isTextual()||block.path("text").asText().length()>2000||!block.path("confidence").isNumber())throw new IllegalArgumentException();
                var confidence=block.path("confidence").decimalValue();if(confidence.signum()<0||confidence.compareTo(java.math.BigDecimal.ONE)>0)throw new IllegalArgumentException();
                textSize+=block.path("text").asText().length();if(textSize>100000)throw new IllegalArgumentException();blocks.add(new FinanceReceiptFields.Block(block.path("text").asText(),confidence));
            }
            var qr=new ArrayList<String>();for(var item:json.path("qrCodes")){if(!item.isTextual()||item.asText().length()>4096)throw new IllegalArgumentException();qr.add(item.asText());}
            var extraction=new LinkedHashMap<String,Object>(FinanceReceiptFields.extract(kind,blocks,qr));
            @SuppressWarnings("unchecked") var warnings=new ArrayList<String>((List<String>)extraction.get("warnings"));
            // Worker diagnostics are bounded but are not reflected verbatim: avoid leaking local paths or other requests.
            for(var warning:json.path("warnings"))if(!warning.isTextual()||warning.asText().length()>500)throw new IllegalArgumentException();
            if(!json.path("warnings").isEmpty())warnings.add("本地识别服务提示部分内容需人工核对，请对照原件。");
            var hash=json.path("imageHash");if(!hash.isObject()||!hash.path("algorithm").asText().equals("phash-dhash-v1")||!hash.path("phash").asText().matches("[a-f0-9]{16}")||!hash.path("dhash").asText().matches("[a-f0-9]{16}"))throw new IllegalArgumentException();
            extraction.put("imageHash",Map.of("algorithm","phash-dhash-v1","phash",hash.path("phash").asText(),"dhash",hash.path("dhash").asText()));
            extraction.put("source","LOCAL_OCR");extraction.put("provider","RapidOCR");extraction.put("sourceSha256",sha256);extraction.put("kind",kind);extraction.put("confirmationRequired",true);extraction.put("externalVerificationStatus","NOT_VERIFIED");extraction.put("warnings",warnings);
            boolean found=!((Map<?,?>)extraction.get("candidates")).isEmpty();extraction.put("status",found?"CANDIDATES":"NO_CANDIDATES");
            extraction.put("notice",found?"已提取本地识别候选，仅补充空白字段；请对照原件确认，不代表发票验真或报销审核通过。":"未得到唯一高置信的标注字段，请人工核对；原件已安全保存，可稍后按原件重试。");return extraction;
        } catch (InterruptedException interrupted) {Thread.currentThread().interrupt();return unavailable("UNAVAILABLE",sha256,"本地识别被中断；原件已保存，请稍后按原件重试。");}
        catch (TimeoutException|ExecutionException|IOException unavailable) {return unavailable("UNAVAILABLE",sha256,"本地图片识别超时或暂不可用；原件已保存，可人工填写或稍后按原件重试。");}
        catch (IllegalArgumentException invalid) {return unavailable("FAILED",sha256,"本地识别配置或返回数据未通过校验，未采纳候选；原件已保存，请人工核对或联系管理员。");}
        finally {if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    static URI trustedEndpoint(String raw)throws IOException {
        URI uri=URI.create(raw);String host=uri.getHost();
        if(!Set.of("http","https").contains(uri.getScheme())||host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().equals("/recognize"))throw new IllegalArgumentException();
        if(!host.equalsIgnoreCase("localhost")&&!host.matches("[0-9.]+")&&!host.contains(":"))throw new IllegalArgumentException();
        for(var address:InetAddress.getAllByName(host))if(!address.isLoopbackAddress()&&!address.isSiteLocalAddress()&&!(address instanceof Inet6Address&&(address.getAddress()[0]&0xfe)==0xfc))throw new IllegalArgumentException();
        return uri;
    }
    static Map<String,Object> unavailable(String status,String hash,String notice){var out=new LinkedHashMap<String,Object>();out.put("source","MANUAL_REQUIRED");out.put("status",status);out.put("sourceSha256",hash);out.put("confirmationRequired",true);out.put("candidates",Map.of());out.put("fieldCandidates",Map.of());out.put("warnings",List.of());out.put("notice",notice);return out;}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return body;}
        @Override public void onSubscribe(Flow.Subscription next){subscription=next;next.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){for(var buffer:buffers){if(buffer.remaining()>MAX_RESPONSE-bytes.size()){subscription.cancel();body.completeExceptionally(new IOException("Local OCR response limit"));return;}byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);}
        @Override public void onError(Throwable error){body.completeExceptionally(error);}
        @Override public void onComplete(){body.complete(bytes.toByteArray());}
    }
}
