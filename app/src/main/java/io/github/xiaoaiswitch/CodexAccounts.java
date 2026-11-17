package io.github.xiaoaiswitch;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import io.github.xiaoaiswitch.core.CodexClient;
import io.github.xiaoaiswitch.core.CodexOAuth;
import java.security.KeyStore;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class CodexAccounts {
    private static CodexAccounts instance;
    private final SharedPreferences preferences;
    private final CodexOAuth oauth = new CodexOAuth();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile CodexOAuth.Device pending;
    private volatile String error = "";
    private CodexOAuth.Tokens tokens;
    record Status(boolean loggedIn, String plan, String email, boolean pending, String error, String code, String url) {}

    static synchronized CodexAccounts get(Context context) {
        if (instance == null) instance = new CodexAccounts(context.getApplicationContext());
        return instance;
    }

    private CodexAccounts(Context context) {
        preferences = context.getSharedPreferences("codex_secure", 0);
        try {
            String stored = read("tokens");
            if (!stored.isEmpty()) tokens = CodexOAuth.Tokens.stored(new JSONObject(stored));
            String flow = read("pending");
            if (!flow.isEmpty()) {
                JSONObject json = new JSONObject(flow);
                CodexOAuth.Device device = new CodexOAuth.Device(json.getString("id"), json.getString("code"),
                        json.getString("url"), json.getInt("interval"), json.getLong("expiresAt"));
                if (device.expiresAt() > System.currentTimeMillis()) { pending = device; worker.execute(() -> poll(device)); }
                else preferences.edit().remove("pending").apply();
            }
        } catch (Exception failure) { error = "Codex 登录凭据需要重新登录"; }
    }

    synchronized Status status() {
        return new Status(tokens != null, tokens == null ? "" : tokens.plan(), tokens == null ? "" : tokens.email(), pending != null,
                error, pending == null ? "" : pending.code(), pending == null ? "" : pending.url());
    }

    CodexOAuth.Device start() throws Exception {
        CodexOAuth.Device device = oauth.start();
        synchronized (this) {
            pending = device;
            error = "";
            write("pending", new JSONObject().put("id", device.id()).put("code", device.code()).put("url", device.url())
                    .put("interval", device.interval()).put("expiresAt", device.expiresAt()).toString());
        }
        worker.execute(() -> poll(device));
        return device;
    }

    private void poll(CodexOAuth.Device device) {
        try {
            while (pending == device) {
                CodexOAuth.Tokens result = oauth.poll(device);
                if (result != null) {
                    synchronized (this) {
                        if (pending != device) return;
                        write("tokens", result.json().toString());
                        tokens = result;
                        pending = null;
                        preferences.edit().remove("pending").apply();
                        error = "";
                    }
                    return;
                }
                Thread.sleep(Math.max(1, device.interval()) * 1000L);
            }
        } catch (Exception failure) {
            synchronized (this) {
                if (pending != device) return;
                pending = null;
                preferences.edit().remove("pending").apply();
                error = failure instanceof java.io.IOException ? failure.getMessage() : "Codex 登录结果处理失败";
            }
        }
    }

    synchronized CodexOAuth.Tokens credentials(boolean force, String previousAccess) throws Exception {
        if (tokens == null) throw new IllegalStateException("请先登录 Codex 订阅");
        boolean same = previousAccess == null || previousAccess.isEmpty() || previousAccess.equals(tokens.accessToken());
        if ((force && same) || tokens.expiresAt() < System.currentTimeMillis() + 60000) {
            CodexOAuth.Tokens refreshed = oauth.refresh(tokens);
            write("tokens", refreshed.json().toString());
            tokens = refreshed;
        }
        return tokens;
    }

    synchronized void logout() {
        pending = null;
        tokens = null;
        error = "";
        preferences.edit().clear().apply();
    }

    synchronized void cancelLogin() {
        pending = null;
        preferences.edit().remove("pending").apply();
    }

    JSONObject models() throws Exception { return CodexClient.models(credentials(false, null)); }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias("xiaoaiswitch_codex")) return (SecretKey) store.getKey("xiaoaiswitch_codex", null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder("xiaoaiswitch_codex", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private void write(String name, String text) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                + Base64.encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        preferences.edit().putString(name, value).apply();
    }

    private String read(String name) throws Exception {
        String value = preferences.getString(name, "");
        if (value.isEmpty()) return "";
        String[] parts = value.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
