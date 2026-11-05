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


if __name__ == "__main__":
    unittest.main()
