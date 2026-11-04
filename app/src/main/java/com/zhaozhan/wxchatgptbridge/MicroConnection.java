package com.zhaozhan.wxchatgptbridge;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class MicroConnection extends HttpURLConnection {
    private final Context context;
    private final ByteArrayOutputStream request = new ByteArrayOutputStream();
    private byte[] response = new byte[0];
    private boolean executed;

    MicroConnection(URL url, Context context) {
        super(url);
        this.context = context.getApplicationContext();
    }

    @Override public void setRequestProperty(String key, String value) {
        // 微插中的旧 API 密钥不传给接入服务。
        if (!"Authorization".equalsIgnoreCase(key)) super.setRequestProperty(key, value);
    }

    @Override public void addRequestProperty(String key, String value) {
        if (!"Authorization".equalsIgnoreCase(key)) super.addRequestProperty(key, value);
    }

    @Override public OutputStream getOutputStream() {
        connected = true;
        return request;
    }

    private synchronized void execute() throws IOException {
        if (executed) return;
        executed = true;
        if (!"POST".equals(method)) throw new IOException("不支持的请求方式");
        if (request.size() > 150_000) throw new IOException("请求过大");
        Bundle payload = new Bundle();
        String body = new String(request.toByteArray(), StandardCharsets.UTF_8);
        payload.putString("body", body);
        MediaRouter.Payload media = MediaRouter.resolveForBody(body);
        if (media != null && media.image != null) payload.putByteArray("image_jpeg", media.image);
        ParcelFileDescriptor readEnd = null;
        if (media != null && media.pdf != null) {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            readEnd = pipe[0];
            ParcelFileDescriptor writeEnd = pipe[1];
            byte[] pdf = media.pdf;
            Thread writer = new Thread(() -> {
                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) { out.write(pdf); }
                catch (IOException ignored) {}
            }, "wechat-pdf-transfer");
            writer.setDaemon(true);
            writer.start();
            payload.putParcelable("pdf_file", readEnd);
            payload.putString("file_extension", media.extension);
        }
        try {
            Bundle result = context.getContentResolver().call(
                    WechatHook.PROVIDER, "request", null, payload);
            if (result == null) throw new IOException("接入服务不可用");
            responseCode = result.getInt("status", 503);
            response = result.getString("body", "").getBytes(StandardCharsets.UTF_8);
        } catch (SecurityException e) {
            throw new IOException("接入服务拒绝请求");
        } catch (RuntimeException e) {
            throw new IOException("接入服务不可用");
        } finally {
            if (readEnd != null) try { readEnd.close(); } catch (IOException ignored) {}
        }
    }

    @Override public void connect() { connected = true; }
    @Override public void disconnect() { connected = false; }
    @Override public boolean usingProxy() { return false; }
    @Override public int getResponseCode() throws IOException { execute(); return responseCode; }
    @Override public InputStream getInputStream() throws IOException {
        execute();
        if (responseCode >= 400) throw new IOException("ChatGPT 请求失败");
        return new ByteArrayInputStream(response);
    }
    @Override public InputStream getErrorStream() {
        try { execute(); } catch (IOException ignored) { return null; }
        return responseCode >= 400 ? new ByteArrayInputStream(response) : null;
    }
}
