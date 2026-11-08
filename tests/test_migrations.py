import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('migrations', ROOT / 'scripts/check_migrations.py')
M = importlib.util.module_from_spec(spec)
spec.loader.exec_module(M)


def migration_name(version, label=None):
    return f'V{version}__{label or f"migration_{version}"}.sql'


def make_repository(root, versions, with_lock=True):
    migrations = root / M.MIGRATION_REL
    migrations.mkdir(parents=True)
    for version, content in versions.items():
        (migrations / migration_name(version)).write_text(content)
    lock = root / M.LOCK_REL
    lock.parent.mkdir(parents=True, exist_ok=True)
    if with_lock:
        lock.write_text(json.dumps({
            migration_name(version): hashlib.sha256(content.encode()).hexdigest()
            for version, content in versions.items()
        }, indent=2) + '\n')
    subprocess.run(['git', 'init', '-q'], cwd=root, check=True)
    subprocess.run(['git', 'config', 'user.email', 'migration-test@example.invalid'], cwd=root, check=True)
    subprocess.run(['git', 'config', 'user.name', 'Migration Test'], cwd=root, check=True)
    subprocess.run(['git', 'add', '.'], cwd=root, check=True)
    subprocess.run(['git', 'commit', '-qm', 'trusted migration baseline'], cwd=root, check=True)
    return migrations, lock, subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()


class MigrationHistoryTest(unittest.TestCase):
    def test_repository_migrations_match_frozen_history(self):
        self.assertEqual(M.check(), len(json.loads(M.LOCK.read_text())))

    def test_new_migration_requires_registration_and_trusted_base(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations, lock, base = make_repository(root, {1: 'SELECT 1;\n', 2: 'SELECT 2;\n'})
            (migrations / migration_name(3)).write_text('SELECT 3;\n')
            with self.assertRaisesRegex(ValueError, '尚未登记'):
                M.check(migrations, lock)
            with self.assertRaisesRegex(ValueError, 'trusted-base'):
                M.check(migrations, lock, record_new=True)
            self.assertEqual(3, M.check(migrations, lock, record_new=True, trusted_base=base, repo=root))
            self.assertEqual(3, len(json.loads(lock.read_text())))
            self.assertEqual(3, M.check(migrations, lock, trusted_base=base, repo=root))

    def test_record_new_never_rewrites_an_existing_entry(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations, lock, base = make_repository(root, {1: 'SELECT 1;\n'})
            new_path = migrations / migration_name(2)
            new_path.write_text('SELECT 2;\n')
            manifest = json.loads(lock.read_text())
            manifest[new_path.name] = hashlib.sha256(b'earlier unpublished draft').hexdigest()
            lock.write_text(json.dumps(manifest, indent=2) + '\n')
            with self.assertRaisesRegex(ValueError, 'SHA-256不匹配'):
                M.check(migrations, lock, trusted_base=base, repo=root)
            with self.assertRaisesRegex(ValueError, 'SHA-256不匹配'):
                M.check(migrations, lock, record_new=True, trusted_base=base, repo=root)
            self.assertNotEqual(hashlib.sha256(new_path.read_bytes()).hexdigest(), json.loads(lock.read_text())[new_path.name])

    def test_historical_sql_and_manifest_cannot_be_changed_together(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations, lock, base = make_repository(root, {1: 'SELECT 1;\n', 2: 'SELECT 2;\n'})
            old = migrations / migration_name(1)
            old.write_text('SELECT 999;\n')
            manifest = json.loads(lock.read_text())
            manifest[old.name] = hashlib.sha256(old.read_bytes()).hexdigest()
            lock.write_text(json.dumps(manifest, indent=2) + '\n')
            with self.assertRaisesRegex(ValueError, '可信Git基线'):
                M.check(migrations, lock, record_new=True, trusted_base=base, repo=root)

    def test_deleting_historical_file_is_rejected_even_if_manifest_is_edited(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations, lock, base = make_repository(root, {1: 'SELECT 1;\n', 2: 'SELECT 2;\n'})
            (migrations / migration_name(1)).unlink()
            manifest = json.loads(lock.read_text())
            manifest.pop(migration_name(1))
            lock.write_text(json.dumps(manifest, indent=2) + '\n')
            with self.assertRaisesRegex(ValueError, '连续|可信Git基线'):
                M.check(migrations, lock, trusted_base=base, repo=root)

    def test_initial_lock_can_be_created_from_trusted_git_tree(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations, lock, base = make_repository(root, {1: 'SELECT 1;\n', 2: 'SELECT 2;\n'}, with_lock=False)
            (migrations / migration_name(3)).write_text('SELECT 3;\n')
            self.assertEqual(3, M.check(migrations, lock, record_new=True, trusted_base=base, repo=root))
            actual = json.loads(lock.read_text())
            self.assertEqual({migration_name(1), migration_name(2), migration_name(3)}, set(actual))

    def test_migration_gaps_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations = root / 'migration'
            migrations.mkdir()
            (migrations / migration_name(1)).write_text('SELECT 1;')
            (migrations / migration_name(3)).write_text('SELECT 3;')
            with self.assertRaisesRegex(ValueError, '不连续'):
                M.check(migrations, root / 'lock.json')

    def test_malformed_names_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations = root / 'migration'
            migrations.mkdir()
            (migrations / 'V01__leading_zero.sql').write_text('SELECT 1;')
            with self.assertRaisesRegex(ValueError, '文件名'):
                M.check(migrations, root / 'lock.json')

    def test_nested_migration_file_is_rejected_in_worktree(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations, lock, _ = make_repository(root, {1: 'SELECT 1;\n'})
            nested = migrations / 'future'
            nested.mkdir()
            (nested / migration_name(2, 'nested')).write_text('SELECT 2;\n')
            with self.assertRaisesRegex(ValueError, '子目录'):
                M.check(migrations, lock)

    def test_nested_file_in_trusted_git_tree_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations, _, _ = make_repository(root, {1: 'SELECT 1;\n'})
            nested = migrations / 'future'
            nested.mkdir()
            (nested / migration_name(2, 'nested')).write_text('SELECT 2;\n')
            subprocess.run(['git', 'add', '.'], cwd=root, check=True)
            subprocess.run(['git', 'commit', '-qm', 'nested migration'], cwd=root, check=True)
            base = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
            with self.assertRaisesRegex(ValueError, '可信Git基线.*子目录'):
                M._trusted_snapshot(root, base)

    def test_repeatable_and_callback_sql_are_rejected_in_worktree(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations, lock, _ = make_repository(root, {1: 'SELECT 1;\n'})
            (migrations / 'R__extra.sql').write_text('SELECT 2;\n')
            with self.assertRaisesRegex(ValueError, '文件名'):
                M.check(migrations, lock)
            (migrations / 'R__extra.sql').unlink()
            (migrations / 'beforeMigrate.sql').write_text('SELECT 3;\n')
            with self.assertRaisesRegex(ValueError, '文件名'):
                M.check(migrations, lock)
            (migrations / 'beforeMigrate.sql').unlink()
            (migrations / 'v2__lowercase.sql').write_text('SELECT 4;\n')
            with self.assertRaisesRegex(ValueError, '文件名'):
                M.check(migrations, lock)

    def test_callback_sql_is_rejected_in_trusted_git_tree(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations, _, _ = make_repository(root, {1: 'SELECT 1;\n'})
            (migrations / 'beforeMigrate.sql').write_text('SELECT 2;\n')
            subprocess.run(['git', 'add', '.'], cwd=root, check=True)
            subprocess.run(['git', 'commit', '-qm', 'callback migration'], cwd=root, check=True)
            base = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
            with self.assertRaisesRegex(ValueError, '文件名'):
                M._trusted_snapshot(root, base)

    def test_duplicate_versions_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations = root / 'migration'
            migrations.mkdir()
            (migrations / migration_name(1)).write_text('SELECT 1;')
            (migrations / migration_name(1, 'duplicate')).write_text('SELECT 2;')
            with self.assertRaisesRegex(ValueError, '重复'):
                M.check(migrations, root / 'lock.json')

    def test_insertion_into_frozen_history_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations, lock, base = make_repository(root, {1: 'SELECT 1;\n', 2: 'SELECT 2;\n'})
            (migrations / migration_name(1, 'hotfix')).write_text('SELECT 1.5;\n')
            with self.assertRaisesRegex(ValueError, '重复|已发布|连续'):
                M.check(migrations, lock, record_new=True, trusted_base=base, repo=root)

    def test_modified_lock_in_trusted_base_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            migrations, lock, base = make_repository(root, {1: 'SELECT 1;\n'})
            lock.write_text('{}\n')
            with self.assertRaisesRegex(ValueError, '可信历史项|覆盖范围'):
                M.check(migrations, lock, trusted_base=base, repo=root)


if __name__ == '__main__':
    unittest.main()
