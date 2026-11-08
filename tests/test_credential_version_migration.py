"""The V53 fresh-install schema gains credential_version exactly once through V69."""
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]


class CredentialVersionMigrationTest(unittest.TestCase):
    def test_v53_baseline_leaves_credential_version_to_v69(self):
        baseline = (ROOT / "docker/mysql-init/00-schema.sql").read_text()
        migration_path = (ROOT / "OJ-Backend/oj-admin/src/main/resources/db/migration"
                          / "V69__user_credential_version.sql")
        migration = migration_path.read_text()

        self.assertNotRegex(baseline, r"(?i)\bcredential_version\b")
        self.assertEqual(1, len(re.findall(r"(?i)ADD\s+COLUMN\s+`credential_version`", migration)))
        self.assertRegex(migration, r"(?is)`?credential_version`?\s+BIGINT\s+NOT\s+NULL\s+DEFAULT\s+0")


if __name__ == "__main__":
    unittest.main()
