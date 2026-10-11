package com.starcollect.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/** No automatic network activity. Every check/download is initiated by a parent action. */
public final class ManualUpdater {
    private final Context context;
    public ManualUpdater(Context context) { this.context = context.getApplicationContext(); }
    private boolean testMode() { return BuildConfig.DEBUG && !BuildConfig.UPDATE_TEST_URL.isEmpty(); }
    public ReleasePolicy.Release check() throws Exception {
        List<String> urls = testMode() ? List.of(BuildConfig.UPDATE_TEST_URL) : List.of(ReleasePolicy.MIRROR + "/latest", ReleasePolicy.ORIGIN);
        Exception failure = null;
        for (String url : urls) {
            try {
                HttpURLConnection c = open(url);
                try {
                    if (c.getResponseCode() == 404) { if (url.equals(urls.get(urls.size() - 1))) return null; else continue; }
                    if (c.getResponseCode() != 200) throw new IOException("版本服务返回 HTTP " + c.getResponseCode());
                    byte[] body = readBounded(c.getInputStream(), 1024 * 1024);
                    JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8)); JSONArray a = json.getJSONArray("assets");
                    List<ReleasePolicy.Asset> assets = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) { JSONObject asset = a.getJSONObject(i); assets.add(new ReleasePolicy.Asset(asset.getString("name"), asset.getString("browser_download_url"), asset.getLong("size"), asset.optString("digest", ""))); }
                    return ReleasePolicy.select(json.getString("tag_name"), json.optBoolean("draft", false), json.optBoolean("prerelease", false), json.optString("body", ""), assets);
                } finally { c.disconnect(); }
            } catch (Exception e) { failure = e; }
        }
        throw new IOException("检查更新失败，请检查网络后重试：" + (failure == null ? "没有版本信息" : failure.getMessage()), failure);
    }
    public File download(ReleasePolicy.Release release, IntConsumer progress) throws Exception {
        File directory = new File(context.getFilesDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新目录");
        File target = new File(directory, "update.apk"), part = new File(directory, "download.part.apk");
        List<String> urls = testMode() ? List.of(BuildConfig.UPDATE_TEST_URL.replaceFirst("/latest$", "/download/" + release.tag + "/star-collect.apk")) : List.of(release.mirrorUrl(), release.asset.url);
        Exception failure = null;
        for (String url : urls) {
            try {
                HttpURLConnection c = open(url);
                try {
                    if (c.getResponseCode() != 200) throw new IOException("下载返回 HTTP " + c.getResponseCode());
                    MessageDigest digest = MessageDigest.getInstance("SHA-256"); long count = 0; int last = -1;
                    try (InputStream input = c.getInputStream(); OutputStream output = Files.newOutputStream(part.toPath())) {
                        byte[] buffer = new byte[64 * 1024]; int n;
                        while ((n = input.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
                            count += n; if (count > release.asset.size) throw new IOException("APK 大小不符"); output.write(buffer, 0, n); digest.update(buffer, 0, n);
                            int percent = (int) (count * 100 / release.asset.size); if (percent != last) { last = percent; progress.accept(percent); }
                        }
                    }
                    if (count != release.asset.size || !hex(digest.digest()).equalsIgnoreCase(release.asset.digest.substring(7))) throw new IOException("APK 文件校验失败，请重试");
                    validate(part, release.version.toString());
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    return target;
                } finally { c.disconnect(); }
            } catch (Exception e) { Files.deleteIfExists(part.toPath()); failure = e; }
        }
        throw new IOException("下载更新失败：" + (failure == null ? "网络异常" : failure.getMessage()), failure);
    }
    public void validate(File file, String expectedVersion) throws Exception {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags), candidate = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (candidate == null || !context.getPackageName().equals(candidate.packageName)) throw new IOException("APK 包名不匹配");
        long current = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        long newer = Build.VERSION.SDK_INT >= 28 ? candidate.getLongVersionCode() : candidate.versionCode;
        if (newer <= current || !expectedVersion.equals(candidate.versionName)) throw new IOException("APK 版本不匹配或不是新版本");
        if (candidate.applicationInfo == null || candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) throw new IOException("新版不支持当前 Android 版本");
        if (!certificates(installed).equals(certificates(candidate))) throw new IOException("APK 签名与当前应用不同，已拒绝安装");
    }
    private Set<String> certificates(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signatures == null || signatures.length == 0) throw new IOException("APK 没有有效签名");
        Set<String> result = new HashSet<>(); for (Signature signature : signatures) result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))); return result;
    }
    private HttpURLConnection open(String url) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            URL address = new URL(url);
            boolean fixture = testMode() && "http".equals(address.getProtocol()) && ("10.0.2.2".equals(address.getHost()) || "127.0.0.1".equals(address.getHost())) && address.getUserInfo() == null;
            if (!fixture && !ReleasePolicy.allowedRedirect(url)) throw new IOException("不允许的下载地址");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); c.setConnectTimeout(10_000); c.setReadTimeout(20_000); c.setInstanceFollowRedirects(false); c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "StarCollect/" + BuildConfig.VERSION_NAME); c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("Connection", "close");
            int code; try { code = c.getResponseCode(); } catch (Exception e) { c.disconnect(); throw e; }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) { String location = c.getHeaderField("Location"); c.disconnect(); if (location == null) throw new IOException("无效的重定向"); url = new URL(new URL(url), location).toString(); continue; }
            return c;
        }
        throw new IOException("重定向次数过多");
    }
    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = stream.read(buffer)) != -1) { if (output.size() + n > maximum) throw new IOException("版本响应过大"); output.write(buffer, 0, n); } return output.toByteArray();
        }
    }
    private static String hex(byte[] bytes) { StringBuilder text = new StringBuilder(); for (byte b : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return text.toString(); }
}
