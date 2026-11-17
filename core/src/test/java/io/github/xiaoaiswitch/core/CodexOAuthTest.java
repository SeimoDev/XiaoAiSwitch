package io.github.xiaoaiswitch.core;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CodexOAuthTest {
    @Test public void deviceLoginPendingExchangeAndRefreshUseRealHttp() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger polls=new AtomicInteger();
        JSONObject claims=new JSONObject().put("email","fixture@example.test").put("exp",4102444800L)
                .put("https://api.openai.com/auth",new JSONObject().put("chatgpt_account_id","ACCOUNT").put("chatgpt_plan_type","plus"));
        String jwt="e30."+Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8))+".SIGNATURE";
        server.createContext("/api/accounts/deviceauth/usercode",x->{
            assertTrue(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8).contains("client_id"));
            byte[] b="{\"device_auth_id\":\"DEVICE\",\"user_code\":\"ABCD-1234\",\"interval\":\"5\"}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();
        });
        server.createContext("/api/accounts/deviceauth/token",x->{
            x.getRequestBody().readAllBytes();
            if(polls.incrementAndGet()==1){x.sendResponseHeaders(403,-1);x.close();return;}
            byte[] b="{\"authorization_code\":\"CODE\",\"code_verifier\":\"VERIFIER\",\"code_challenge\":\"CHALLENGE\"}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();
        });
        server.createContext("/oauth/token",x->{
            String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(body.contains("authorization_code")||body.contains("refresh_token"));
            JSONObject token=new JSONObject().put("access_token",jwt).put("refresh_token",body.contains("grant_type=refresh_token")?"REFRESH_NEW":"REFRESH")
                    .put("id_token",jwt).put("expires_in",3600);
            byte[] b=token.toString().getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();
        });
        server.start();
        try{
            CodexOAuth oauth=new CodexOAuth("http://127.0.0.1:"+server.getAddress().getPort());
            CodexOAuth.Device device=oauth.start();
            assertEquals("ABCD-1234",device.code());
            assertEquals(5,device.interval());
            assertNull(oauth.poll(device));
            CodexOAuth.Tokens token=oauth.poll(device);
            assertEquals("ACCOUNT",token.accountId());
            assertEquals("plus",token.plan());
            assertEquals("REFRESH_NEW",oauth.refresh(token).refreshToken());
            assertTrue(token.expiresAt()>System.currentTimeMillis());
        }finally{server.stop(0);}
    }
}
