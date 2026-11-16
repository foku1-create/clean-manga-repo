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

    def test_permanent_locks_cover_all_four(self):
        # PERMANENT (Isma, 2026-11-14). Never delete or loosen this test.
        self.assertTrue(set(self.NHENTAI) <= build.PERMANENT_LOCKS)

    def test_removing_them_from_strict_txt_does_not_unlock(self):
        locked = set() | build.PERMANENT_LOCKS  # as main() builds it, even with an empty strict.txt
        for pkg in self.NHENTAI:
            self.assertTrue(build.is_locked({"name": "x", "pkg": pkg}, locked), pkg)

    def test_block_txt_cannot_drop_a_permanent_lock(self):
        for pkg in self.NHENTAI:
            self.assertIsNone(build.exclusion_reason({"name": "NHentai", "pkg": pkg}, {pkg, "nhentai"}), pkg)

    def test_build_refuses_to_run_without_the_lock(self):
        with self.assertRaises(SystemExit):
            build.lock_check(set(self.NHENTAI[1:]))
        build.lock_check(set(build.PERMANENT_LOCKS) | build.PERMANENT_WORD_LOCKS)  # complete: no exit

    def test_lock_offset_never_goes_back_below_the_live_lock(self):
        self.assertGreaterEqual(build.LOCKED_BUMP_OFFSET, 51)

    WEBTOON_HENTAI = "eu.kanade.tachiyomi.extension.en.webtoonhentai"

    def test_webtoon_hentai_is_permanently_locked(self):
        # PERMANENT (Isma, 2026-11-14: "add webtoon hentai page into the block"). Never delete or loosen.
        self.assertIn(self.WEBTOON_HENTAI, build.PERMANENT_LOCKS)
        self.assertIn(self.WEBTOON_HENTAI, build.read_names(build.LOCKED_FILE))
        self.assertTrue(build.is_locked({"name": "Webtoon Hentai (unoriginal)", "pkg": self.WEBTOON_HENTAI}, set() | build.PERMANENT_LOCKS))
        self.assertIsNone(build.exclusion_reason({"name": "Webtoon Hentai (unoriginal)", "pkg": self.WEBTOON_HENTAI}, {self.WEBTOON_HENTAI}))

    def test_webtoon_hentai_site_addresses_are_disabled(self):
        self.assertEqual(build.lock_url("https://webtoonhentai.org"), "https://webtoonhentai.org.invalid")
        self.assertEqual(build.lock_url("https://manhwa18.cc/webtoon/"), "https://manhwa18.cc.invalid/webtoon/")

    def test_build_refuses_to_run_without_webtoon_hentai(self):
        with self.assertRaises(SystemExit):
            build.lock_check(set(self.NHENTAI))
        build.lock_check(set(build.PERMANENT_LOCKS) | build.PERMANENT_WORD_LOCKS)

    def test_every_source_with_hentai_in_its_name_is_locked(self):
        # Isma, 2026-11-15: "block all the other pages where the name hentai is in them"
        locked = build.read_names(build.LOCKED_FILE)
        self.assertIn("*hentai*", locked)
        for name, pkg in [
            ("HentaiFox", "eu.kanade.tachiyomi.extension.all.hentaifox"),
            ("Hentai Slayer", "eu.kanade.tachiyomi.extension.ar.hentaislayer"),
            ("IMHentai", "eu.kanade.tachiyomi.extension.all.imhentai"),
            ("X-Manga", "eu.kanade.tachiyomi.extension.fr.scanhentaimenu"),  # hentai only in the package
            ("A New Hentai Site", "eu.kanade.tachiyomi.extension.en.newsite"),  # one added later
        ]:
            self.assertTrue(build.is_locked({"name": name, "pkg": pkg}, locked), name)

    def test_word_lock_needs_a_real_word(self):
        mangadex = {"name": "MangaDex", "pkg": "eu.kanade.tachiyomi.extension.all.mangadex"}
        for line in ("*", "**", "*x", "x*"):
            self.assertFalse(build.is_locked(mangadex, {line}), line)

    def test_normal_sources_are_not_locked(self):
        locked = build.read_names(build.LOCKED_FILE)
        self.assertFalse(build.is_locked({"name": "MangaDex", "pkg": "eu.kanade.tachiyomi.extension.all.mangadex"}, locked))
        self.assertFalse(build.is_locked({"name": "Webtoons.com", "pkg": "eu.kanade.tachiyomi.extension.all.webtoons"}, locked))
        self.assertFalse(build.is_locked({"name": "Hive Scans", "pkg": "eu.kanade.tachiyomi.extension.en.infernalvoidscans"}, locked))

    def test_adult_ecchi_and_ero_sources_are_locked(self):
        # Isma, 2026-11-16: "block the websites that have adult in their names and also ero or ecchi"
        locked = build.read_names(build.LOCKED_FILE)
        for line in ("*adult*", "*ecchi*", "ero*"):
            self.assertIn(line, locked)
        for name, pkg in (
            ("Free Adult Comix", "eu.kanade.tachiyomi.extension.all.freeadultcomix"),
            ("MangaworldAdult", "eu.kanade.tachiyomi.extension.it.mangaworldadult"),
            ("MyAdultComics", "eu.kanade.tachiyomi.extension.en.myadultcomics"),
            ("Ecchi-Doujin", "eu.kanade.tachiyomi.extension.th.ecchidoujin"),
            ("Super Ecchi", "eu.kanade.tachiyomi.extension.pt.superecchi"),
            ("Ero18x", "eu.kanade.tachiyomi.extension.en.ero18x"),
            ("Erofus", "eu.kanade.tachiyomi.extension.en.erofus"),
            ("Erotic Comics", "p"),
        ):
            self.assertTrue(build.is_locked({"name": name, "pkg": pkg}, locked), name)

    def test_ero_does_not_lock_hero_or_zero(self):
        locked = build.read_names(build.LOCKED_FILE)
        for name, pkg in (
            ("AeroToon", "eu.kanade.tachiyomi.extension.tr.aerotoon"),
            ("BenDezOmniHero", "eu.kanade.tachiyomi.extension.pt.bendezomnihero"),
            ("Emperor Scan", "eu.kanade.tachiyomi.extension.es.emperorscan"),
            ("HERO'S Web", "eu.kanade.tachiyomi.extension.ja.herosweb"),
            ("KeroScans", "eu.kanade.tachiyomi.extension.en.keroscans"),
            ("Point Zero Toons", "eu.kanade.tachiyomi.extension.pt.pointzerotoons"),
            ("Tower Of God Manga Online", "eu.kanade.tachiyomi.extension.en.towerofgodmangaonline"),
            ("Zerosum Online", "eu.kanade.tachiyomi.extension.ja.zerosumonline"),
        ):
            self.assertFalse(build.is_locked({"name": name, "pkg": pkg}, locked), name)

    ADULT_ECCHI_ERO = (
        ("Free Adult Comix", "eu.kanade.tachiyomi.extension.all.freeadultcomix"),
        ("MyAdultComics", "eu.kanade.tachiyomi.extension.en.myadultcomics"),
        ("MangaworldAdult", "eu.kanade.tachiyomi.extension.it.mangaworldadult"),
        ("Super Ecchi", "eu.kanade.tachiyomi.extension.pt.superecchi"),
        ("Ecchi-Doujin", "eu.kanade.tachiyomi.extension.th.ecchidoujin"),
        ("Ero18x", "eu.kanade.tachiyomi.extension.en.ero18x"),
        ("Erofus", "eu.kanade.tachiyomi.extension.en.erofus"),
    )

    def test_adult_ecchi_ero_sources_are_permanently_locked(self):
        # PERMANENT (Isma, 2026-11-16: "block it so i can never open it again like ... the vpn"). Never delete or loosen.
        locked = set() | build.PERMANENT_LOCKS | build.PERMANENT_WORD_LOCKS  # even with an empty strict.txt
        for name, pkg in self.ADULT_ECCHI_ERO:
            self.assertIn(pkg, build.PERMANENT_LOCKS)
            self.assertTrue(build.is_locked({"name": name, "pkg": pkg}, locked), name)
            self.assertIsNone(build.exclusion_reason({"name": name, "pkg": pkg}, {pkg, name.lower()}), name)

    def test_adult_ecchi_ero_word_locks_are_permanent(self):
        # PERMANENT (Isma, 2026-11-16). Future sources with these words are locked even if strict.txt is emptied.
        self.assertEqual({"*adult*", "*ecchi*", "ero*"}, set(build.PERMANENT_WORD_LOCKS))
        locked = set() | build.PERMANENT_LOCKS | build.PERMANENT_WORD_LOCKS
        for name in ("Adult Manga New", "Some Ecchi Site", "Erotic Comics"):
            self.assertTrue(build.is_locked({"name": name, "pkg": "p"}, locked), name)

    def test_build_refuses_to_run_without_the_adult_ecchi_ero_lock(self):
        full = set(build.PERMANENT_LOCKS) | build.PERMANENT_WORD_LOCKS
        for missing in ("*adult*", "*ecchi*", "ero*", "eu.kanade.tachiyomi.extension.en.erofus"):
            with self.assertRaises(SystemExit):
                build.lock_check(full - {missing})


if __name__ == "__main__":
    unittest.main()
