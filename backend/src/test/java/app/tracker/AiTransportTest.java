package app.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import java.io.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.*;
import static org.junit.jupiter.api.Assertions.*;

class AiTransportTest {
    private final ObjectMapper json=new ObjectMapper();
    private int secretReads;
    private AiTailorService service(StubHttp http) {
        var secrets=(SecretsManagerClient)Proxy.newProxyInstance(SecretsManagerClient.class.getClassLoader(),new Class<?>[]{SecretsManagerClient.class},
                (proxy,method,args)->{if(!method.getName().equals("getSecretValue"))throw new AssertionError(method.getName());
                    secretReads++;return GetSecretValueResponse.builder().secretString("{\"OPENAI_API_KEY\":\"dummy-test-key\"}").build();});
        return new AiTailorService(json,secrets,"dummy-secret-arn","gpt-5-mini",http);
    }
    private String requestBody(HttpRequest request) throws Exception {
        var result=new CompletableFuture<byte[]>();var bytes=new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer buffer){byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);}
            public void onError(Throwable error){result.completeExceptionally(error);}
            public void onComplete(){result.complete(bytes.toByteArray());}
        });
        return new String(result.get(5,TimeUnit.SECONDS),StandardCharsets.UTF_8);
    }
    @Test void actualRequestIncludesFactualPromptSourceJobPrivacyAndBoundedTimeout() throws Exception {
        var http=new StubHttp(200,"{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"STUDENT CV - Java and Git. Built an internship tracker and wrote unit tests.\"}]}]}");
        var ai=service(http);
        assertTrue(ai.tailor("Original student CV","Internship description").contains("Java"));
        var input=json.readTree(requestBody(http.request));
        assertEquals("gpt-5-mini",input.path("model").asText()); assertFalse(input.path("store").asBoolean(true));
        assertTrue(input.path("instructions").asText().contains("Never invent"));
        assertTrue(input.path("input").asText().contains("Original student CV"));
        assertTrue(input.path("input").asText().contains("Internship description"));
        assertEquals("https://api.openai.com/v1/responses",http.request.uri().toString());
        assertEquals("Bearer dummy-test-key",http.request.headers().firstValue("Authorization").orElseThrow());
        assertTrue(http.request.timeout().orElseThrow().compareTo(Duration.ofSeconds(180))<0);
        ai.tailor("Original student CV","Another internship"); assertEquals(1,secretReads);
    }
    @Test void incompleteOrRefusedModelOutputIsNotReturnedAsASuccessfulCv() {
        for(String response:new String[]{"{\"status\":\"incomplete\",\"output\":[]}",
                "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"Unavailable\"}]}]}"})
            assertThrows(AiTailorService.UpstreamException.class,()->service(new StubHttp(200,response)).tailor("CV","Job"));
    }
    @Test void httpBillingErrorsUseSafeReadableMessagesInsteadOfProviderSecrets() {
        var error=assertThrows(AiTailorService.UpstreamException.class,()->service(new StubHttp(429,
                "{\"error\":{\"code\":\"credit_balance_exhausted\",\"message\":\"dummy-test-key\"}}")).tailor("CV","Job"));
        assertTrue(error.getMessage().contains("API credits"));assertFalse(error.getMessage().contains("dummy-test-key"));
    }
    @Test void transportFailureDoesNotPretendToGenerateOutput() {
        var http=new StubHttp(200,"");http.unavailable=true;
        assertThrows(IOException.class,()->service(http).tailor("CV","Job"));
    }
    private static class StubHttp extends HttpClient {
        final int status;final String body;HttpRequest request;boolean unavailable;
        StubHttp(int status,String body){this.status=status;this.body=body;}
        @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler)throws IOException {
            this.request=request;if(unavailable)throw new IOException("Synthetic connection failure");
            return new HttpResponse<>(){
                public int statusCode(){return status;}public HttpRequest request(){return request;}
                public Optional<HttpResponse<T>> previousResponse(){return Optional.empty();}
                public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}public T body(){return (T)body;}
                public Optional<SSLSession> sslSession(){return Optional.empty();}public URI uri(){return request.uri();}
                public Version version(){return Version.HTTP_2;}
            };
        }
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}public Optional<Duration> connectTimeout(){return Optional.of(Duration.ofSeconds(10));}
        public Redirect followRedirects(){return Redirect.NEVER;}public Optional<ProxySelector> proxy(){return Optional.empty();}
        public SSLContext sslContext(){try{return SSLContext.getDefault();}catch(Exception e){throw new AssertionError(e);}}
        public SSLParameters sslParameters(){return new SSLParameters();}public Optional<Authenticator> authenticator(){return Optional.empty();}
        public Version version(){return Version.HTTP_2;}public Optional<Executor> executor(){return Optional.empty();}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler){throw new UnsupportedOperationException();}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler,HttpResponse.PushPromiseHandler<T> push){throw new UnsupportedOperationException();}
    }
}
