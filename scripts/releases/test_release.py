import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location('release_tools', Path(__file__).with_name('release.py'))
TOOLS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOLS)


class ArtifactChecks(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.client = 'dev/wicklen/chronicles/client/Screen.class'
        self.payload = {'META-INF/MANIFEST.MF': b'Manifest-Version: 1.0\r\n\r\n',
                        'fabric.mod.json': json.dumps({'id': 'wicklen_chronicles', 'version': '0.4.2'}).encode(),
                        'dev/wicklen/chronicles/core/Rule.class': b'compiled rule', self.client: b'compiled screen'}

    def jar(self, name, updates=None):
        destination = self.root / name
        with zipfile.ZipFile(destination, 'w') as archive:
            for key, data in (self.payload | (updates or {})).items():
                archive.writestr(key, data)
        return destination

    def test_accepts_identical_payload_with_empty_historical_directories(self):
        TOOLS.verify_equivalent(self.jar('approved.jar', {'old-empty/': b''}), self.jar('built.jar'))

    def test_accepts_only_a_complete_proven_client_manifest(self):
        manifest = f'Manifest-Version: 1.0\r\nFabric-Loom-Client-Only-Entries: {self.client}\r\n\r\n'.encode()
        TOOLS.verify_equivalent(self.jar('approved.jar', {'META-INF/MANIFEST.MF': manifest}),
                                self.jar('built.jar'), {self.client})

    def test_rejects_unproven_client_manifest(self):
        manifest = b'Manifest-Version: 1.0\r\nFabric-Loom-Client-Only-Entries: missing.class\r\n\r\n'
        with self.assertRaisesRegex(ValueError, 'manifest differs'):
            TOOLS.verify_equivalent(self.jar('approved.jar', {'META-INF/MANIFEST.MF': manifest}),
                                    self.jar('built.jar'), {self.client})

    def test_rejects_different_compiled_code(self):
        with self.assertRaisesRegex(ValueError, 'payload differs'):
            TOOLS.verify_equivalent(self.jar('approved.jar', {self.client: b'other code'}), self.jar('built.jar'))

    def test_rejects_unexpected_resources(self):
        with self.assertRaisesRegex(ValueError, 'membership differs'):
            TOOLS.verify_equivalent(self.jar('approved.jar', {'extra.json': b'{}'}), self.jar('built.jar'))

    def test_rejects_wrong_mod_version(self):
        with self.assertRaisesRegex(ValueError, 'identity/version'):
            TOOLS.validate_mod(self.jar('mod.jar'), '0.4.1')

    def test_rejects_missing_bundled_core(self):
        del self.payload['dev/wicklen/chronicles/core/Rule.class']
        with self.assertRaisesRegex(ValueError, 'bundled independent core'):
            TOOLS.validate_mod(self.jar('mod.jar'), '0.4.2')


if __name__ == '__main__':
    unittest.main()
