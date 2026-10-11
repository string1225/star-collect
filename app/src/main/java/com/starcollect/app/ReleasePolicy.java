package com.starcollect.app;

import java.net.URI;
import java.util.List;
import java.util.Locale;

public final class ReleasePolicy {
    private ReleasePolicy() {}
    public static final String REPOSITORY = "string1225/star-collect", APK_NAME = "star-collect.apk";
    public static final String MIRROR = "https://home.sunny-string.cn/github-release/" + REPOSITORY;
    public static final String ORIGIN = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    public static final long MAX_BYTES = 100 * 1024 * 1024L;
    public static final class Version implements Comparable<Version> {
        public final int major, minor, patch;
        private Version(int a, int b, int c) { major = a; minor = b; patch = c; }
        public static Version parse(String text) {
            if (text == null || !text.matches("v?(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})")) throw new IllegalArgumentException("版本标签格式应为 v主版本.次版本.修订号");
            String[] p = text.replaceFirst("^v", "").split("\\.");
            return new Version(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }
        @Override public int compareTo(Version v) { int c = Integer.compare(major, v.major); if (c == 0) c = Integer.compare(minor, v.minor); return c == 0 ? Integer.compare(patch, v.patch) : c; }
        @Override public String toString() { return major + "." + minor + "." + patch; }
    }
    public static final class Asset {
        public final String name, url, digest;
        public final long size;
        public Asset(String name, String url, long size, String digest) { this.name = name; this.url = url; this.size = size; this.digest = digest; }
    }
    public static final class Release {
        public final String tag, notes;
        public final Version version;
        public final Asset asset;
        private Release(String tag, String notes, Version version, Asset asset) { this.tag = tag; this.notes = notes; this.version = version; this.asset = asset; }
        public String mirrorUrl() { return MIRROR + "/download/" + tag + "/" + APK_NAME; }
    }
    public static Release select(String tag, boolean draft, boolean prerelease, String notes, List<Asset> assets) {
        if (draft || prerelease) throw new IllegalArgumentException("只接受正式发布版本");
        Version version = Version.parse(tag);
        for (Asset a : assets) if (APK_NAME.equals(a.name)) {
            if (!a.url.equals("https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + APK_NAME)) throw new IllegalArgumentException("APK 地址不属于本仓库");
            if (a.size < 1 || a.size > MAX_BYTES) throw new IllegalArgumentException("APK 大小无效");
            if (a.digest == null || !a.digest.matches("sha256:[a-fA-F0-9]{64}")) throw new IllegalArgumentException("版本缺少 SHA-256 校验值");
            String description = notes == null ? "" : notes;
            return new Release(tag, description.substring(0, Math.min(3000, description.length())), version, a);
        }
        throw new IllegalArgumentException("版本中没有 " + APK_NAME);
    }
    public static boolean allowedRedirect(String url) {
        try {
            URI u = URI.create(url); String host = u.getHost();
            return "https".equals(u.getScheme()) && u.getUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443)
                    && List.of("github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "home.sunny-string.cn").contains(host == null ? "" : host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) { return false; }
    }
}
