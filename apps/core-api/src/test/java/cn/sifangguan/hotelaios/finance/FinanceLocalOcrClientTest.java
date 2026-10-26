package cn.sifangguan.hotelaios.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FinanceLocalOcrClientTest {
    private HttpServer server;private String mode;private Map<?,?> last;private String authorization;
    private final ObjectMapper json=new ObjectMapper();private final String sha="a".repeat(64);
    @BeforeEach void start()throws Exception{mode="OK";server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/recognize",exchange->{last=json.readValue(exchange.getRequestBody().readAllBytes(),Map.class);authorization=exchange.getRequestHeaders().getFirst("Authorization");if(mode.equals("SLOW")){try{Thread.sleep(500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}byte[] bytes;int status=200;
        if(mode.equals("LARGE"))bytes=new byte[600000];else{
            var result=new LinkedHashMap<String,Object>();result.put("provider",mode.equals("PROVIDER")?"remote":"RapidOCR");result.put("sourceSha256",mode.equals("SHA")?"b".repeat(64):last.get("sourceSha256"));result.put("blocks",List.of(Map.of("text","发票号码00001234","confidence",mode.equals("CONFIDENCE")?2:0.99)));result.put("qrCodes",List.of());result.put("warnings",List.of());result.put("imageHash",Map.of("algorithm","phash-dhash-v1","phash","0".repeat(16),"dhash","1".repeat(16)));bytes=json.writeValueAsBytes(result);
        }if(mode.equals("DOWN"))status=503;if(mode.equals("REDIRECT")){status=302;exchange.getResponseHeaders().set("Location","https://example.com/recognize");}exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}exchange.close();});server.start();}
    @AfterEach void stop(){server.stop(0);}
    private FinanceLocalOcrClient client(){return new FinanceLocalOcrClient("http://127.0.0.1:"+server.getAddress().getPort()+"/recognize","test-token",3000,json);}
    @Test void localRequestBindsBytesKindAndHashWithoutUsingExternalGateway(){byte[] bytes="exact-original".getBytes(StandardCharsets.UTF_8);var out=client().recognize(bytes,"image/jpeg","INVOICE",sha);assertThat(out).containsEntry("invoiceNumber","00001234").containsEntry("sourceSha256",sha).containsEntry("confirmationRequired",true).containsEntry("status","CANDIDATES");assertThat(authorization).isEqualTo("Bearer test-token");assertThat(last.get("contentBase64")).isEqualTo(Base64.getEncoder().encodeToString(bytes));assertThat(last.get("kind")).isEqualTo("INVOICE");}
    @Test void disabledWorkerDoesNotAttemptNetwork(){assertThat(new FinanceLocalOcrClient("","",15000,json).recognize(new byte[1],"image/png","INVOICE",sha)).containsEntry("status","DISABLED");assertThat(last).isNull();}
    @Test void mismatchedHashProviderAndConfidenceFailClosedWithoutCandidates(){for(String invalid:List.of("SHA","PROVIDER","CONFIDENCE")){mode=invalid;var out=client().recognize(new byte[1],"image/png","INVOICE",sha);assertThat(out).containsEntry("status","FAILED").doesNotContainKey("invoiceNumber");}}
    @Test void oversizedResponseUnavailableAndRedirectNeverAdoptsFields(){for(String invalid:List.of("LARGE","DOWN","REDIRECT")){mode=invalid;var out=client().recognize(new byte[1],"image/png","INVOICE",sha);assertThat(out).containsEntry("status","UNAVAILABLE").doesNotContainKey("invoiceNumber");}}
    @Test void timeoutIsBoundedAndDoesNotAdoptLateCandidates(){mode="SLOW";long start=System.nanoTime();var out=new FinanceLocalOcrClient("http://127.0.0.1:"+server.getAddress().getPort()+"/recognize","test-token",100,json).recognize(new byte[1],"image/png","INVOICE",sha);assertThat(out).containsEntry("status","UNAVAILABLE").doesNotContainKey("invoiceNumber");assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isLessThan(java.time.Duration.ofSeconds(2));}
    @Test void publicTargetsAndUnsupportedEndpointShapesAreRejectedBeforeSending()throws Exception{
        assertThat(FinanceLocalOcrClient.trustedEndpoint("http://127.0.0.1:8765/recognize").getHost()).isEqualTo("127.0.0.1");
        assertThat(FinanceLocalOcrClient.trustedEndpoint("http://192.168.1.2/recognize").getHost()).isEqualTo("192.168.1.2");
        for(String target:List.of("https://example.com/recognize","http://8.8.8.8/recognize","http://169.254.169.254/recognize","file:///recognize","http://user@127.0.0.1/recognize","http://127.0.0.1/recognize?x=y"))assertThatThrownBy(()->FinanceLocalOcrClient.trustedEndpoint(target)).isInstanceOf(IllegalArgumentException.class);
        assertThat(last).isNull();
    }
}
