import tempfile
from pathlib import Path
import unittest
import zipfile

from tests.build_database_fixtures import (
    MAINTENANCE_CLASS,
    PROPERTIES_LAUNCHER,
    build_variants,
)


class DatabaseFixtureBuilderTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / 'candidate.jar'
        with zipfile.ZipFile(self.source, 'w', compression=zipfile.ZIP_DEFLATED) as jar:
            entries = {
                'META-INF/MANIFEST.MF': b'Main-Class: org.springframework.boot.loader.launch.JarLauncher\n',
                PROPERTIES_LAUNCHER: b'launcher-bytes',
                MAINTENANCE_CLASS: b'maintenance-bytes',
                'BOOT-INF/classes/com/musealgo/oj/OjApplication.class': b'app-bytes',
                'BOOT-INF/lib/example.jar': b'nested-jar-bytes',
                'BOOT-INF/classes/db/migration/V62__previous.sql': b'v62-bytes',
                'BOOT-INF/classes/db/migration/V63__current.sql': b'v63-bytes',
                'BOOT-INF/classes/db/migration/V64__future.sql': b'v64-bytes',
            }
            for name, content in entries.items():
                jar.writestr(name, content)
        self.output = self.root / 'fixtures'

    def tearDown(self):
        self.temporary.cleanup()

    def test_v62_fixture_removes_only_newer_migrations_and_retains_maintenance(self):
        report = build_variants(self.source, self.output, 'musealgo-oj/backend:candidate')
        v62_path = self.output / 'v62' / 'app.jar'
        with zipfile.ZipFile(self.source) as original, zipfile.ZipFile(v62_path) as fixture:
            self.assertIn(MAINTENANCE_CLASS, fixture.namelist())
            self.assertIn('BOOT-INF/classes/db/migration/V62__previous.sql', fixture.namelist())
            self.assertNotIn('BOOT-INF/classes/db/migration/V63__current.sql', fixture.namelist())
            self.assertNotIn('BOOT-INF/classes/db/migration/V64__future.sql', fixture.namelist())
            for name in fixture.namelist():
                self.assertEqual(original.read(name), fixture.read(name), name)
        self.assertEqual(['BOOT-INF/classes/db/migration/V63__current.sql',
                          'BOOT-INF/classes/db/migration/V64__future.sql'], report['v62']['removed_entries'])
        self.assertIn('62', map(str, report['v62']['migration_versions']))
        self.assertEqual('v62-schema', (self.output / 'v62' / 'Dockerfile').read_text().split('kind="')[1].split('"')[0])

    def test_legacy_fixture_removes_only_maintenance_entrypoint_class(self):
        report = build_variants(self.source, self.output, 'musealgo-oj/backend:candidate')
        legacy_path = self.output / 'legacy-maintenance' / 'app.jar'
        with zipfile.ZipFile(self.source) as original, zipfile.ZipFile(legacy_path) as fixture:
            self.assertNotIn(MAINTENANCE_CLASS, fixture.namelist())
            self.assertIn(PROPERTIES_LAUNCHER, fixture.namelist())
            self.assertEqual(set(original.namelist()) - {MAINTENANCE_CLASS}, set(fixture.namelist()))
            for name in fixture.namelist():
                self.assertEqual(original.read(name), fixture.read(name), name)
        self.assertEqual([MAINTENANCE_CLASS], report['legacy_maintenance']['removed_entries'])
        self.assertIn('org.musealgo.test-fixture="true"',
                      (self.output / 'legacy-maintenance' / 'Dockerfile').read_text())

    def test_rejects_non_spring_boot_jar(self):
        with zipfile.ZipFile(self.source, 'w') as jar:
            jar.writestr('not-an-app.txt', b'no')
        with self.assertRaisesRegex(ValueError, 'DatabaseMaintenance.class'):
            build_variants(self.source, self.output, 'musealgo-oj/backend:candidate')

    def test_rejects_untrusted_base_image_syntax(self):
        with self.assertRaisesRegex(ValueError, 'Invalid base image'):
            build_variants(self.source, self.output, 'candidate; RUN touch /tmp/pwned')


if __name__ == '__main__':
    unittest.main()
