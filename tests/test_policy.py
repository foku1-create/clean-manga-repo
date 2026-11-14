import unittest

import build


class RepositoryPolicyTests(unittest.TestCase):
    """Since 2026-11-05 every site is published with the tag guard; only block.txt leaves one out."""

    def test_safe_extension_is_kept_with_tag_guard(self):
        extension = {"name": "Clean Source", "pkg": "example.clean", "warning": build.SAFE}
        self.assertIsNone(build.exclusion_reason(extension, set()))
        self.assertEqual(build.guard_level(extension), "false")

    def test_mixed_extension_is_kept_with_tag_guard(self):
        extension = {"name": "Mixed Source", "pkg": "example.mixed", "warning": build.MIXED}
        self.assertIsNone(build.exclusion_reason(extension, set()))
        self.assertEqual(build.guard_level(extension), "true")

    def test_adult_extension_is_kept_without_strict_check(self):
        extension = {"name": "Adult Source", "pkg": "example.adult", "warning": build.NSFW}
        self.assertIsNone(build.exclusion_reason(extension, set()))
        self.assertEqual(build.guard_level(extension), "true")

    def test_unlabelled_extension_is_kept_with_tag_guard(self):
        extension = {"name": "Unknown Source", "pkg": "example.unknown"}
        self.assertIsNone(build.exclusion_reason(extension, set()))
        self.assertEqual(build.guard_level(extension), "true")

    def test_no_extension_gets_the_strict_guard(self):
        for warning in (build.SAFE, build.MIXED, build.NSFW, None):
            self.assertNotEqual(build.guard_level({"warning": warning}), "strict")

    def test_blocklist_still_leaves_an_extension_out(self):
        extension = {"name": "Bad Source", "pkg": "example.bad", "warning": build.SAFE}
        self.assertEqual(build.exclusion_reason(extension, {"bad source"}), "block.txt")


class LockedExtensionTests(unittest.TestCase):
    """strict.txt (2026-11-14): blocked inside the app, not removed."""

    NHENTAI = [
        "eu.kanade.tachiyomi.extension.all.nhentaicom",
        "eu.kanade.tachiyomi.extension.all.nhentaito",
        "eu.kanade.tachiyomi.extension.all.nhentaiwebsite",
        "eu.kanade.tachiyomi.extension.all.nhentaixxx",
    ]

    def test_all_four_nhentai_sources_are_locked(self):
        locked = build.read_names(build.LOCKED_FILE)
        for pkg in self.NHENTAI:
            self.assertTrue(build.is_locked({"name": "x", "pkg": pkg}, locked), pkg)

    def test_nhentai_is_published_not_removed(self):
        block = build.read_names("block.txt")
        for pkg in self.NHENTAI:
            self.assertIsNone(build.exclusion_reason({"name": "x", "pkg": pkg}, block), pkg)

    def test_locked_match_by_name_or_package(self):
        self.assertTrue(build.is_locked({"name": "NHentai.to", "pkg": "p"}, {"nhentai.to"}))
        self.assertFalse(build.is_locked({"name": "MangaDex", "pkg": "all.mangadex"}, {"nhentai.to"}))

    def test_locked_version_is_above_the_normal_one(self):
        for bump in (1, 10, 99 - build.LOCKED_BUMP_OFFSET):
            self.assertGreater(build.code_bump(bump, True), build.code_bump(bump, False))
            self.assertEqual(build.code_bump(bump, False), bump)
            self.assertLessEqual(build.code_bump(bump, True), 99)

    def test_locked_version_is_above_the_first_lock(self):
        # 2026-11-14 the first locked build used offset 50; devices that have it must update again
        self.assertGreater(build.code_bump(10, True), 10 + 50)

    def test_locked_bump_never_spills_into_the_upstream_digits(self):
        with self.assertRaises(SystemExit):
            build.code_bump(100 - build.LOCKED_BUMP_OFFSET, True)

    def test_site_addresses_are_disabled(self):
        self.assertEqual(build.lock_url("https://nhentai.to"), "https://nhentai.to.invalid")
        self.assertEqual(build.lock_url("https://nhentai.jp.net/g/1"), "https://nhentai.jp.net.invalid/g/1")
        self.assertEqual(build.lock_url("http://9hentai.uk"), "http://9hentai.uk.invalid")

    def test_lock_leaves_other_text_alone_and_is_idempotent(self):
        for text in ("NHentai.to", "https:////", "eu.kanade.tachiyomi.multisrc.x", "https://nhentai.to.invalid"):
            self.assertEqual(build.lock_url(text), text)

    def test_lock_class_rewrites_only_url_constants(self):
        def utf8(s):
            b = s.encode()
            return b"\x01" + len(b).to_bytes(2, "big") + b
        pool = utf8("https://nhentai.to") + b"\x08\x00\x01" + utf8("NHentai.to") + b"\x03\x00\x00\x00\x2a"
        cls = b"\xca\xfe\xba\xbe\x00\x00\x00\x34" + (5).to_bytes(2, "big") + pool + b"\x00\x21TAIL"
        out, changed = build.lock_class(cls)
        self.assertEqual(changed, 1)
        self.assertIn(b"https://nhentai.to.invalid", out)
        self.assertIn(b"NHentai.to", out)
        self.assertTrue(out.endswith(b"\x00\x21TAIL"))
        again, changed_again = build.lock_class(out)
        self.assertEqual(again, out)
        self.assertEqual(changed_again, 1)

    def test_normal_sources_are_not_locked(self):
        locked = build.read_names(build.LOCKED_FILE)
        self.assertFalse(build.is_locked({"name": "MangaDex", "pkg": "eu.kanade.tachiyomi.extension.all.mangadex"}, locked))


if __name__ == "__main__":
    unittest.main()
