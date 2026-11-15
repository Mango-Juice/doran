import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('package_release', Path(__file__).resolve().parents[1] / 'package-release.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def video(duration=180, audio=False):
    streams = [{'codec_type': 'video', 'codec_name': 'h264', 'width': 1920, 'height': 1080}]
    if audio:
        streams.append({'codec_type': 'audio', 'codec_name': 'aac'})
    return {'format': {'duration': str(duration)}, 'streams': streams}


class PackageReleaseTests(unittest.TestCase):
    def test_silent_or_single_aac_demo(self):
        self.assertEqual(module.inspect_video(video())['audio_tracks'], 0)
        self.assertEqual(module.inspect_video(video(audio=True))['audio_tracks'], 1)
        info = video(audio=True)
        info['streams'].append({'codec_type': 'audio', 'codec_name': 'aac'})
        with self.assertRaises(ValueError):
            module.inspect_video(info)
        info = video(audio=True)
        info['streams'][-1]['codec_name'] = 'mp3'
        with self.assertRaises(ValueError):
            module.inspect_video(info)

    def test_demo_duration_contract(self):
        for seconds in [0, -1, float('inf'), float('nan')]:
            with self.assertRaises(ValueError):
                module.inspect_video(video(seconds))

    def test_duration_has_no_editorial_length_limit(self):
        for seconds in [125.8, 149, 211]:
            self.assertEqual(module.inspect_video(video(seconds))['duration_seconds'], seconds)

    def test_rejects_wrong_resolution(self):
        info = video()
        info['streams'][0]['width'] = 1280
        with self.assertRaises(ValueError):
            module.inspect_video(info)

    def test_source_changes_fail_but_document_cleanup_is_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL, text=True).strip()
            git('init'); git('config', 'user.email', 'test@example.com'); git('config', 'user.name', 'Test')
            source = root / 'apps/car/app/src/main/Test.kt'
            source.parent.mkdir(parents=True)
            source.write_text('original')
            doc = root / 'apps/car/README.md'
            doc.write_text('old')
            git('add', '.'); git('commit', '-m', 'initial')
            initial = git('rev-parse', 'HEAD')
            doc.unlink()
            git('add', '-u'); git('commit', '-m', 'docs')
            current = git('rev-parse', 'HEAD')
            evidence = module.source_evidence(root, initial[:8], current, 'car')
            self.assertEqual(evidence['build_source_commit'], initial)
            source.write_text('changed')
            git('add', '.'); git('commit', '-m', 'product')
            with self.assertRaises(ValueError):
                module.source_evidence(root, initial, git('rev-parse', 'HEAD'), 'car')

    def test_local_snapshot_includes_uncommitted_inputs_without_claiming_build_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL, text=True).strip()
            git('init'); git('config', 'user.email', 'test@example.com'); git('config', 'user.name', 'Test')
            source = root / 'apps/car/app/src/main/Test.kt'
            source.parent.mkdir(parents=True)
            source.write_text('original')
            git('add', '.'); git('commit', '-m', 'initial')
            current = git('rev-parse', 'HEAD')
            before = module.local_source_evidence(root, current, 'car')
            source.write_text('local changes')
            changed = module.local_source_evidence(root, current, 'car')
            self.assertIsNone(changed['build_source_commit'])
            self.assertEqual(changed['source_base_commit'], current)
            self.assertFalse(changed['build_inputs_match_packaging_commit'])
            self.assertNotEqual(before['working_tree_input_sha256'], changed['working_tree_input_sha256'])
            added = source.with_name('New.kt')
            added.write_text('new implementation')
            with_new = module.local_source_evidence(root, current, 'car')
            self.assertNotEqual(with_new['working_tree_input_sha256'], changed['working_tree_input_sha256'])
            (root / 'apps/car/local.properties').write_text('private configuration')
            (root / 'apps/car/README.md').write_text('doc edit')
            self.assertEqual(with_new, module.local_source_evidence(root, current, 'car'))
            source.unlink()
            self.assertNotEqual(with_new, module.local_source_evidence(root, current, 'car'))


if __name__ == '__main__':
    unittest.main()
