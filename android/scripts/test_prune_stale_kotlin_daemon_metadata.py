import unittest

from prune_stale_kotlin_daemon_metadata import prune_stale_versions


class PruneStaleKotlinDaemonMetadataTest(unittest.TestCase):
    def test_removes_only_mismatched_daemon_client_component(self):
        source = """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
  <components>
    <component group="org.jetbrains.kotlin" name="kotlin-daemon-client" version="2.2.21">
      <artifact name="kotlin-daemon-client-2.2.21.jar"><sha256 value="a"/></artifact>
    </component>
    <component group="org.jetbrains.kotlin" name="kotlin-daemon-client" version="2.4.10">
      <artifact name="kotlin-daemon-client-2.4.10.jar"><sha256 value="b"/></artifact>
    </component>
    <component group="org.jetbrains.kotlin" name="kotlin-stdlib" version="2.4.10">
      <artifact name="kotlin-stdlib-2.4.10.jar"><sha256 value="c"/></artifact>
    </component>
  </components>
</verification-metadata>
"""
        updated, removed = prune_stale_versions(source, "2.2.21")
        self.assertEqual(removed, ["2.4.10"])
        self.assertIn('name="kotlin-daemon-client" version="2.2.21"', updated)
        self.assertNotIn('name="kotlin-daemon-client" version="2.4.10"', updated)
        self.assertIn('name="kotlin-stdlib" version="2.4.10"', updated)

    def test_fails_if_expected_daemon_client_is_missing(self):
        source = """<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
  <components>
    <component group="org.jetbrains.kotlin" name="kotlin-daemon-client" version="2.4.10">
      <artifact name="kotlin-daemon-client-2.4.10.jar"><sha256 value="b"/></artifact>
    </component>
  </components>
</verification-metadata>"""
        with self.assertRaises(ValueError):
            prune_stale_versions(source, "2.2.21")


if __name__ == "__main__":
    unittest.main()
