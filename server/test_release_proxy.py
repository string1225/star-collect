import hashlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import release_proxy as proxy


class ProxyTests(unittest.TestCase):
    def setUp(self):
        proxy.metadata_cache.clear()
        self.content = b"example-apk"
        self.asset = {"id": 7, "name": "star-collect.apk", "size": len(self.content), "digest": "sha256:" + hashlib.sha256(self.content).hexdigest(), "browser_download_url": "https://github.com/string1225/star-collect/releases/download/v0.1.1/star-collect.apk"}
        self.release = {"tag_name": "v0.1.1", "draft": False, "prerelease": False, "assets": [self.asset]}

    def test_allowlist_and_path_validation(self):
        self.assertEqual(proxy.route("/github-release/string1225/star-collect/latest"), ("string1225/star-collect", None, None))
        for path in ["/github-release/other/repo/latest", "/github-release/https://evil.test/latest", "/github-release/string1225/star-collect/download/../../key.apk", "/github-release/string1225/star-collect/latest?url=http://127.0.0.1"]:
            self.assertIsNone(proxy.route(path))

    def test_verified_download_and_cache(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(proxy, "CACHE", Path(folder)), patch.object(proxy, "release", return_value=self.release), patch.object(proxy, "upstream", return_value=io.BytesIO(self.content)) as fetch:
            target, digest = proxy.cached_asset("string1225/star-collect", "v0.1.1", "star-collect.apk")
            self.assertEqual(target.read_bytes(), self.content)
            proxy.cached_asset("string1225/star-collect", "v0.1.1", "star-collect.apk")
            self.assertEqual(fetch.call_count, 1)

    def test_corrupt_download_is_never_cached(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(proxy, "CACHE", Path(folder)), patch.object(proxy, "release", return_value=self.release), patch.object(proxy, "upstream", return_value=io.BytesIO(b"bad")):
            with self.assertRaises(ValueError):
                proxy.cached_asset("string1225/star-collect", "v0.1.1", "star-collect.apk")
            self.assertEqual(list(Path(folder).iterdir()), [])

    def test_asset_cannot_point_to_other_origin(self):
        self.asset["browser_download_url"] = "http://127.0.0.1/secret"
        with patch.object(proxy, "release", return_value=self.release), self.assertRaises(ValueError):
            proxy.cached_asset("string1225/star-collect", "v0.1.1", "star-collect.apk")

    def test_metadata_cache_preserves_github_digest(self):
        import json
        with patch.object(proxy, "upstream", return_value=io.BytesIO(json.dumps(self.release).encode())) as fetch:
            first = proxy.release("string1225/star-collect")
            self.assertEqual(first["assets"][0]["digest"], self.asset["digest"])
            self.assertIn("mirror_download_url", first["assets"][0])
            self.assertEqual(proxy.release("string1225/star-collect"), first)
            self.assertEqual(fetch.call_count, 1)

    def test_prerelease_is_rejected(self):
        import json
        self.release["prerelease"] = True
        with patch.object(proxy, "upstream", return_value=io.BytesIO(json.dumps(self.release).encode())), self.assertRaises(ValueError):
            proxy.release("string1225/star-collect")


if __name__ == "__main__":
    unittest.main()
