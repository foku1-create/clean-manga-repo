import unittest

import build


class RepositoryPolicyTests(unittest.TestCase):
    def test_safe_extension_is_kept(self):
        extension = {"name": "Clean Source", "pkg": "example.clean", "warning": build.SAFE}
        self.assertIsNone(build.exclusion_reason(extension, set()))

    def test_mixed_extension_is_removed(self):
        extension = {"name": "Mixed Source", "pkg": "example.mixed", "warning": build.MIXED}
        self.assertEqual(build.exclusion_reason(extension, set()), "not marked safe (mixed)")

    def test_adult_extension_is_kept_for_strict_guard(self):
        extension = {"name": "Adult Source", "pkg": "example.adult", "warning": build.NSFW}
        self.assertIsNone(build.exclusion_reason(extension, set()))
        self.assertEqual(build.guard_level(extension), "strict")

    def test_unlabelled_extension_is_removed(self):
        extension = {"name": "Unknown Source", "pkg": "example.unknown"}
        self.assertEqual(build.exclusion_reason(extension, set()), "not marked safe (unlabelled)")

    def test_allowed_mixed_extension_is_kept_with_tag_guard(self):
        extension = {"name": "AllManga", "pkg": "eu.kanade.tachiyomi.extension.en.allanime", "warning": build.MIXED}
        self.assertIsNone(build.exclusion_reason(extension, set(), {"allmanga"}))
        self.assertEqual(build.guard_level(extension), "true")

    def test_allow_list_does_not_admit_unlabelled_or_other_mixed(self):
        unknown = {"name": "AllManga", "pkg": "example.unknown"}
        other = {"name": "Other Mixed", "pkg": "example.other", "warning": build.MIXED}
        self.assertEqual(build.exclusion_reason(unknown, set(), {"allmanga"}), "not marked safe (unlabelled)")
        self.assertEqual(build.exclusion_reason(other, set(), {"allmanga"}), "not marked safe (mixed)")

    def test_blocklist_wins_over_allow_list(self):
        extension = {"name": "AllManga", "pkg": "example.allmanga", "warning": build.MIXED}
        self.assertEqual(build.exclusion_reason(extension, {"allmanga"}, {"allmanga"}), "block.txt")

    def test_blocklist_overrides_safe_label(self):
        extension = {"name": "Bad Safe Source", "pkg": "example.bad", "warning": build.SAFE}
        self.assertEqual(build.exclusion_reason(extension, {"bad safe source"}), "block.txt")


if __name__ == "__main__":
    unittest.main()
