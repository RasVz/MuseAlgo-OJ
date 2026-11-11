import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('ai_init', Path(__file__).parents[1] / 'ai-services-init.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class KeyInitializationTest(unittest.TestCase):
    def test_create_and_repeat_preserve_key(self):
        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory).resolve() / 'keys' / 'master'
            self.assertTrue(module.initialize(key, lambda: None))
            before = key.read_bytes()
            self.assertFalse(module.initialize(key, lambda: self.fail('must reuse existing key')))
            self.assertEqual(before, key.read_bytes())
            self.assertEqual(key.stat().st_mode & 0o777, 0o600)

    def test_unknown_database_and_ciphertext_prevent_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory).resolve() / 'master'
            def refuse():
                raise RuntimeError('restore original key')
            with self.assertRaises(RuntimeError):
                module.initialize(key, refuse)
            self.assertFalse(key.exists())

    def test_invalid_file_and_symlink_are_not_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory).resolve() / 'master'
            key.write_text('invalid')
            with self.assertRaises(RuntimeError): module.initialize(key)
            self.assertEqual(key.read_text(), 'invalid')
            link = key.with_name('link')
            link.symlink_to(key)
            with self.assertRaises(RuntimeError): module.initialize(link)


if __name__ == '__main__':
    unittest.main()
