package com.interntrack;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** API Gateway is the only authenticated API endpoint used by the Android client. */
final class ApiClient {
    interface Callback { void success(JSONObject data); void failure(String error); }
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AuthManager auth;
    private final Context context;
    private final DemoRepository demo;

    ApiClient(Context context, AuthManager auth) {
        this.context = context.getApplicationContext();
        this.auth = auth;
        demo = BuildConfig.DEMO_MODE ? new DemoRepository(new File(this.context.getFilesDir(), "demo")) : null;
    }
    void get(String path, Callback callback) { request("GET", path, null, callback); }
    void post(String path, JSONObject body, Callback callback) { request("POST", path, body, callback); }
    void put(String path, JSONObject body, Callback callback) { request("PUT", path, body, callback); }
    void delete(String path, Callback callback) { request("DELETE", path, null, callback); }

    private void deliver(Callback cb, JSONObject obj, String error) {
        main.post(() -> { if (error == null) cb.success(obj); else cb.failure(error); });
    }
    private void request(String method, String path, JSONObject body, Callback cb) {
        if (demo != null) {
            EXECUTOR.execute(() -> {
                try { deliver(cb, demo.request(method, path, body), null); }
                catch (Exception ex) { deliver(cb, null, ex.getMessage()); }
            });
            return;
        }
        auth.withAccessToken((token, error) -> {
            if (error != null) { deliver(cb, null, error); return; }
            EXECUTOR.execute(() -> {
                try { deliver(cb, execute(method, BuildConfig.API_URL + path, body, token), null); }
                catch (Exception ex) { deliver(cb, null, ex.getMessage()); }
            });
        });
    }
    static JSONObject execute(String method, String url, JSONObject body, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(110000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = conn.getOutputStream()) { output.write(bytes); }
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String response = stream == null ? "{}" : new String(readAll(stream, 128 * 1024), StandardCharsets.UTF_8);
            JSONObject parsed = new JSONObject(response);
            if (code >= 400) throw new Exception(parsed.optString("message", "HTTP " + code));
            return parsed;
        } finally { conn.disconnect(); }
    }
    static byte[] readAll(InputStream input, int maxBytes) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (bytes.size() + count > maxBytes) throw new Exception("File or response exceeds size limit");
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }
    void upload(ContentResolver resolver, Uri localUri, String filename, long size, Callback callback) {
        if (demo != null) {
            EXECUTOR.execute(() -> {
                try { deliver(callback, demo.importCv(resolver.openInputStream(localUri), filename, size), null); }
                catch (Exception ex) { deliver(callback, null, ex.getMessage()); }
            });
            return;
        }
        auth.withAccessToken((token, error) -> {
            if (error != null) { deliver(callback, null, error); return; }
            EXECUTOR.execute(() -> {
                try {
                    if (size > 10 * 1024 * 1024) throw new Exception("File must be at most 10 MB");
                    byte[] bytes = readAll(resolver.openInputStream(localUri), 10 * 1024 * 1024);
                    if (bytes.length == 0 || (size > 0 && bytes.length != size)) throw new Exception("File is empty or changed while reading");
                    JSONObject init = new JSONObject().put("name", filename).put("size", bytes.length);
                    JSONObject upload = execute("POST", BuildConfig.API_URL + "/cvs/upload-url", init, token);
                    HttpURLConnection conn = (HttpURLConnection) new URL(upload.getString("uploadUrl")).openConnection();
                    try {
                        conn.setRequestMethod("PUT");
                        conn.setConnectTimeout(12000);
                        conn.setReadTimeout(60000);
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", upload.getString("contentType"));
                        conn.setFixedLengthStreamingMode(bytes.length);
                        try (OutputStream output = conn.getOutputStream()) { output.write(bytes); }
                        int code = conn.getResponseCode();
                        if (code < 200 || code >= 300) throw new Exception("S3 upload failed (HTTP " + code + ")");
                    } finally { conn.disconnect(); }
                    JSONObject cv = execute("POST", BuildConfig.API_URL + "/cvs/" + upload.getString("id") + "/complete", new JSONObject(), token);
                    deliver(callback, cv, null);
                } catch (Exception ex) { deliver(callback, null, ex.getMessage()); }
            });
        });
    }
    void publicTips(Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                if (demo != null) {
                    JSONObject tips = new JSONObject(new String(readAll(context.getAssets().open("tips.json"), 64 * 1024), StandardCharsets.UTF_8));
                    deliver(callback, tips, null);
                    return;
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(BuildConfig.ASSET_BASE_URL + "/tips.json").openConnection();
                try {
                    conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
                    if (conn.getResponseCode() != 200) throw new Exception("Help content not yet uploaded to CloudFront");
                    JSONObject tips = new JSONObject(new String(readAll(conn.getInputStream(), 64 * 1024), StandardCharsets.UTF_8));
                    deliver(callback, tips, null);
                } finally { conn.disconnect(); }
            } catch (Exception ex) { deliver(callback, null, ex.getMessage()); }
        });
    }
    void exportDemoCv(ContentResolver resolver, String id, Uri destination, Callback callback) {
        if (demo == null) { deliver(callback, null, "Local CV export is only available in the demo"); return; }
        EXECUTOR.execute(() -> {
            try {
                demo.exportCv(id, resolver.openOutputStream(destination, "wt"));
                deliver(callback, new JSONObject().put("message", "CV saved"), null);
            } catch (Exception ex) { deliver(callback, null, ex.getMessage()); }
        });
    }
}
