package com.starcollect.app;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ReleasePolicyTest {
    private ReleasePolicy.Asset asset(String url, String digest, long size) { return new ReleasePolicy.Asset("star-collect.apk", url, size, digest); }
    private String url() { return "https://github.com/string1225/star-collect/releases/download/v0.1.2/star-collect.apk"; }
    private String digest() { return "sha256:" + "a".repeat(64); }
    @Test public void numericVersionComparison() { assertTrue(ReleasePolicy.Version.parse("v0.1.10").compareTo(ReleasePolicy.Version.parse("0.1.9")) > 0); assertEquals("0.1.2", ReleasePolicy.Version.parse("v0.1.2").toString()); }
    @Test(expected = IllegalArgumentException.class) public void prereleaseTagRejected() { ReleasePolicy.Version.parse("v0.1.2-beta"); }
    @Test(expected = IllegalArgumentException.class) public void draftRejected() { ReleasePolicy.select("v0.1.2", true, false, "", List.of(asset(url(), digest(), 12))); }
    @Test(expected = IllegalArgumentException.class) public void prereleaseFlagRejected() { ReleasePolicy.select("v0.1.2", false, true, "", List.of(asset(url(), digest(), 12))); }
    @Test(expected = IllegalArgumentException.class) public void foreignRepositoryRejected() { ReleasePolicy.select("v0.1.2", false, false, "", List.of(asset(url().replace("string1225", "other"), digest(), 12))); }
    @Test(expected = IllegalArgumentException.class) public void missingDigestRejected() { ReleasePolicy.select("v0.1.2", false, false, "", List.of(asset(url(), "", 12))); }
    @Test(expected = IllegalArgumentException.class) public void oversizedApkRejected() { ReleasePolicy.select("v0.1.2", false, false, "", List.of(asset(url(), digest(), ReleasePolicy.MAX_BYTES + 1))); }
    @Test(expected = IllegalArgumentException.class) public void missingApkRejected() { ReleasePolicy.select("v0.1.2", false, false, "", List.of()); }
    @Test public void mirrorUsesFixedRepositoryAndPath() { assertEquals(ReleasePolicy.MIRROR + "/download/v0.1.2/star-collect.apk", ReleasePolicy.select("v0.1.2", false, false, "notes", List.of(asset(url(), digest(), 12))).mirrorUrl()); }
    @Test public void redirectRequiresHttpsAndKnownHosts() { assertFalse(ReleasePolicy.allowedRedirect("https://github.com.evil.test/update.apk")); assertFalse(ReleasePolicy.allowedRedirect("http://github.com/a.apk")); assertFalse(ReleasePolicy.allowedRedirect("https://token@github.com/a.apk")); assertFalse(ReleasePolicy.allowedRedirect("https://github.com:444/a.apk")); assertTrue(ReleasePolicy.allowedRedirect("https://release-assets.githubusercontent.com/a.apk")); }
}
