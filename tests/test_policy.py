import unittest

import build


class RepositoryPolicyTests(unittest.TestCase):
    def test_safe_extension_is_kept(self):
        extension = {"name": "Clean Source", "pkg": "example.clean", "warning": build.SAFE}
        self.assertIsNone(build.exclusion_reason(extension, set()))

    def test_mixed_extension_is_removed(self):
        extension = {"name": "Mixed Source", "pkg": "example.mixed", "warning": build.MIXED}
        self.assertEqual(build.exclusion_reason(extension, set()), "not marked safe (mixed)")

    def test_adult_extension_is_removed(self):
        extension = {"name": "Adult Source", "pkg": "example.adult", "warning": build.NSFW}
        self.assertEqual(build.exclusion_reason(extension, set()), "not marked safe (adult)")

    def test_unlabelled_extension_is_removed(self):
        extension = {"name": "Unknown Source", "pkg": "example.unknown"}
        self.assertEqual(build.exclusion_reason(extension, set()), "not marked safe (unlabelled)")

    def test_blocklist_overrides_safe_label(self):
        extension = {"name": "Bad Safe Source", "pkg": "example.bad", "warning": build.SAFE}
        self.assertEqual(build.exclusion_reason(extension, {"bad safe source"}), "block.txt")


if __name__ == "__main__":
    unittest.main()
