package com.zhaozhan.wxchatgptbridge;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

final class OAuthClient {
    private static final String AUTH = "https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN = "https://auth.openai.com/api/accounts/oauth/token";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String REQUIRED = "chatgpt.tokens.use.direct";
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Result { void completed(String error); }
    private OAuthClient() {}

    private static String random(int count) {
        byte[] value = new byte[count];
        new SecureRandom().nextBytes(value);
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static void finish(Result callback, String error) {
        ACTIVE.set(false);
        MAIN.post(() -> callback.completed(error));
    }

    static void start(Context context, Result callback) {
        if (!ACTIVE.compareAndSet(false, true)) {
            callback.completed("请先完成当前登录");
            return;
        }
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject existing = SessionStore.read(app);
                String issued = existing == null ? "" : existing.optString("client_id", "");
                String state = random(32), nonce = random(32), verifier = random(48);
                String challenge = Base64.encodeToString(
                        MessageDigest.getInstance("SHA-256")
                                .digest(verifier.getBytes(StandardCharsets.US_ASCII)),
                        Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                try (ServerSocket listener = new ServerSocket(
                        0, 4, InetAddress.getByName("127.0.0.1"))) {
                    listener.setSoTimeout(180000);
                    String redirect = "http://127.0.0.1:" + listener.getLocalPort() + "/auth/callback";
                    Uri.Builder url = Uri.parse(AUTH).buildUpon()
                            .appendQueryParameter("client_id",
                                    issued.isEmpty() ? "dynamic_agent_client" : issued)
                            .appendQueryParameter("ext_agent_host_id", SessionStore.hostId(app))
                            .appendQueryParameter("response_type", "code")
                            .appendQueryParameter("redirect_uri", redirect)
                            .appendQueryParameter("scope",
                                    "openid profile email offline_access resource.invoke " + REQUIRED)
                            .appendQueryParameter("resource", RESOURCE)
                            .appendQueryParameter("state", state)
                            .appendQueryParameter("nonce", nonce)
                            .appendQueryParameter("code_challenge_method", "S256")
                            .appendQueryParameter("code_challenge", challenge);
                    if (issued.isEmpty()) url.appendQueryParameter("agent_name_hint", "WeChat ChatGPT Bridge");
                    Intent browser = new Intent(Intent.ACTION_VIEW, url.build());
                    browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(browser);

                    Uri result = readCallback(listener);
                    String returnedState = result.getQueryParameter("state");
                    if (returnedState == null || !MessageDigest.isEqual(
                            state.getBytes(StandardCharsets.UTF_8),
                            returnedState.getBytes(StandardCharsets.UTF_8)))
                        throw new IOException("登录状态校验失败");
                    if (result.getQueryParameter("error") != null)
                        throw new IOException("授权未完成");
                    String code = result.getQueryParameter("code");
                    String callbackClient = result.getQueryParameter("client_id");
                    if (code == null || code.isEmpty()) throw new IOException("授权未完成");
                    if (issued.isEmpty()) {
                        if (callbackClient == null || callbackClient.isEmpty()
                                || "dynamic_agent_client".equals(callbackClient))
                            throw new IOException("未取得有效客户端标识");
                        issued = callbackClient;
                    } else if (callbackClient != null && !issued.equals(callbackClient)) {
                        throw new IOException("登录客户端不匹配");
                    }
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("grant_type", "authorization_code");
                    fields.put("client_id", issued);
                    fields.put("code", code);
                    fields.put("code_verifier", verifier);
                    fields.put("redirect_uri", redirect);
                    fields.put("resource", RESOURCE);
                    JSONObject token = Http.postForm(TOKEN, fields);
                    if (!hasScope(token.optString("scope", ""), REQUIRED))
                        throw new IOException("尚未授权使用 ChatGPT 套餐");
                    JSONObject identity = validateIdToken(token.getString("id_token"), issued, nonce);
                    String subject = identity.getString("sub");
                    if (existing != null && !existing.optString("subject", subject).equals(subject))
                        throw new IOException("登录账号与已保存的账号不一致");
                    JSONObject session = new JSONObject();
                    session.put("client_id", issued);
                    session.put("issuer", "https://auth.openai.com");
                    session.put("subject", subject);
                    session.put("email", identity.optString("email", ""));
                    session.put("id_token", token.getString("id_token"));
                    session.put("access_token", token.getString("access_token"));
                    session.put("refresh_token", token.getString("refresh_token"));
                    session.put("scope", token.getString("scope"));
                    session.put("expires_at", System.currentTimeMillis()
                            + token.optLong("expires_in", 3600) * 1000L);
                    SessionStore.save(app, session);
                    if (!SessionStore.prefs(app).edit().putBoolean("enabled", true).commit())
                        throw new IOException("无法启用接入");
                }
                finish(callback, null);
            } catch (Exception e) {
                String message = e instanceof IOException ? e.getMessage() : "登录未完成，请重试";
                finish(callback, message == null ? "登录未完成" : message);
            }
        }, "chatgpt-oauth").start();
    }

    private static Uri readCallback(ServerSocket listener) throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            try (Socket socket = listener.accept()) {
                socket.setSoTimeout(10000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String first = reader.readLine();
                if (first == null || first.length() > 8192) continue;
                String[] parts = first.split(" ", 3);
                if (parts.length < 2 || !"GET".equals(parts[0])
                        || !parts[1].startsWith("/auth/callback?")) continue;
                byte[] body = ("<html><meta charset='utf-8'><body>"
                        + "Authorization returned. You can return to the app."
                        + "</body></html>").getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                        + "Cache-Control: no-store\r\nConnection: close\r\nContent-Length: "
                        + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                return Uri.parse("http://127.0.0.1" + parts[1]);
            }
        }
        throw new IOException("未收到有效的授权回调");
    }

    private static boolean hasScope(String granted, String required) {
        for (String scope : granted.split(" ")) if (required.equals(scope)) return true;
        return false;
    }

    private static byte[] base64url(String s) {
        return Base64.decode(s, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static JSONObject validateIdToken(String token, String client, String nonce) throws Exception {
        String[] pieces = token.split("\\.");
        if (pieces.length != 3) throw new IOException("身份凭据格式错误");
        JSONObject header = new JSONObject(new String(base64url(pieces[0]), StandardCharsets.UTF_8));
        JSONObject claims = new JSONObject(new String(base64url(pieces[1]), StandardCharsets.UTF_8));
        if (!"RS256".equals(header.optString("alg"))) throw new IOException("身份签名算法不受支持");
        JSONObject jwks = Http.getJson("https://auth.openai.com/.well-known/jwks.json", null);
        JSONArray keys = jwks.getJSONArray("keys");
        JSONObject key = null;
        for (int i = 0; i < keys.length(); i++) {
            JSONObject candidate = keys.getJSONObject(i);
            if (header.optString("kid").equals(candidate.optString("kid"))
                    && "RSA".equals(candidate.optString("kty"))) { key = candidate; break; }
        }
        if (key == null) throw new IOException("无法验证登录签名");
        java.security.PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(new BigInteger(1, base64url(key.getString("n"))),
                        new BigInteger(1, base64url(key.getString("e")))));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update((pieces[0] + "." + pieces[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(base64url(pieces[2]))) throw new IOException("登录签名无效");
        boolean audience = client.equals(claims.optString("aud"));
        JSONArray audiences = claims.optJSONArray("aud");
        if (audiences != null) for (int i = 0; i < audiences.length(); i++)
            if (client.equals(audiences.optString(i))) audience = true;
        if (!"https://auth.openai.com".equals(claims.optString("iss")) || !audience
                || claims.optLong("exp", 0) * 1000L <= System.currentTimeMillis()
                || claims.optLong("nbf", 0) * 1000L > System.currentTimeMillis() + 60000
                || !nonce.equals(claims.optString("nonce"))
                || claims.optString("sub", "").isEmpty())
            throw new IOException("登录身份校验失败");
        return claims;
    }

    static String accessToken(Context context) throws Exception {
        synchronized (SessionStore.LOCK) {
            JSONObject session = SessionStore.read(context);
            if (session == null || session.optString("refresh_token", "").isEmpty())
                throw new IOException("请先登录 ChatGPT");
            if (session.optLong("expires_at", 0) > System.currentTimeMillis() + 60000)
                return session.getString("access_token");
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("grant_type", "refresh_token");
            fields.put("client_id", session.getString("client_id"));
            fields.put("refresh_token", session.getString("refresh_token"));
            fields.put("resource", RESOURCE);
            JSONObject refreshed = Http.postForm(TOKEN, fields);
            String scope = refreshed.optString("scope", session.optString("scope", ""));
            if (!hasScope(scope, REQUIRED)) throw new IOException("ChatGPT 套餐授权已失效");
            session.put("scope", scope);
            session.put("access_token", refreshed.getString("access_token"));
            session.put("refresh_token", refreshed.optString("refresh_token",
                    session.getString("refresh_token")));
            session.put("expires_at", System.currentTimeMillis()
                    + refreshed.optLong("expires_in", 3600) * 1000L);
            SessionStore.save(context, session);
            return session.getString("access_token");
        }
    }

    static boolean disconnect(Context context) {
        synchronized (SessionStore.LOCK) {
            boolean revoked = false;
            try {
                JSONObject session = SessionStore.read(context);
                if (session == null || session.optString("refresh_token", "").isEmpty()) {
                    revoked = true;
                } else {
                    String endpoint = Http.getJson(
                            "https://auth.openai.com/.well-known/openid-configuration", null)
                            .getString("revocation_endpoint");
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("token", session.getString("refresh_token"));
                    fields.put("token_type_hint", "refresh_token");
                    fields.put("client_id", session.getString("client_id"));
                    Http.revoke(endpoint, fields);
                    revoked = true;
                }
            } catch (Exception ignored) {
                // 清除本地登录状态后由界面提示远端撤销尚未确认。
            }
            SessionStore.clear(context);
            return revoked;
        }
    }
}
