package com.zhaozhan.wxchatgptbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONObject;

final class Http {
    private Http() {}

    static String form(Map<String, String> fields) throws Exception {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (result.length() > 0) result.append('&');
            result.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return result.toString();
    }

    static byte[] read(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > limit) throw new IOException("响应过大");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    static JSONObject getJson(String address, String bearer) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("连接失败（HTTP " + code + "）");
            return new JSONObject(new String(read(connection.getInputStream(), 1_000_000),
                    StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }

    static JSONObject postForm(String address, Map<String, String> fields) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);
            byte[] bytes = form(fields).getBytes(StandardCharsets.UTF_8);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("授权服务返回 HTTP " + code);
            return new JSONObject(new String(read(connection.getInputStream(), 1_000_000),
                    StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }

    static void revoke(String address, Map<String, String> fields) throws Exception {
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !"auth.openai.com".equalsIgnoreCase(url.getHost()))
            throw new IOException("无效的撤销地址");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);
            try (java.io.OutputStream out = connection.getOutputStream()) {
                out.write(form(fields).getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != 200) throw new IOException("远端撤销未确认");
        } finally { connection.disconnect(); }
    }
}
