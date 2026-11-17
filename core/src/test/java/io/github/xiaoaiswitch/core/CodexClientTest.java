package io.github.xiaoaiswitch.core;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CodexClientTest {
    @Test public void actualSseHttpUsesOAuthAccountHeaderAndPreservesSystemPrompt() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> auth=new AtomicReference<>();
        AtomicReference<String> account=new AtomicReference<>();
        AtomicReference<JSONObject> request=new AtomicReference<>();
        server.createContext("/responses",x->{
            auth.set(x.getRequestHeaders().getFirst("Authorization"));account.set(x.getRequestHeaders().getFirst("ChatGPT-Account-Id"));
            request.set(new JSONObject(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] body=("data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"订阅成功\"}]}}\n\n"
                    +"data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type","text/event-stream");x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);x.close();
        });
        server.start();
        try{
            Profile profile=new Profile(true,"http://127.0.0.1:"+server.getAddress().getPort(),"IGNORED_API_KEY","CODEX_MODEL","CODEX_SYSTEM","codex_subscription");
            CodexOAuth.Tokens tokens=new CodexOAuth.Tokens("ACCESS","REFRESH","ID","ACCOUNT","plus","",Long.MAX_VALUE);
            JSONObject result=CodexClient.chat(profile,ChatProtocol.request(profile,new JSONArray(),"你好"),tokens,new ModelClient.Cancellation());
            assertEquals("Bearer ACCESS",auth.get());assertEquals("ACCOUNT",account.get());
            assertEquals("CODEX_SYSTEM",request.get().getString("instructions"));
            assertEquals("订阅成功",ChatProtocol.answer(result));
        }finally{server.stop(0);}
    }

    @Test public void truncatedAndFailedStreamsAreNotReportedAsSuccess() {
        String partial="data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"name\":\"tap\",\"call_id\":\"CALL\",\"arguments\":\"{}\"}}\n\n";
        assertThrows(java.io.IOException.class,()->CodexClient.readEvents(new BufferedReader(new StringReader(partial)),new ModelClient.Cancellation()));
        assertThrows(java.io.IOException.class,()->CodexClient.readEvents(new BufferedReader(new StringReader("data: {\"type\":\"response.failed\"}\n")),new ModelClient.Cancellation()));
    }
}
