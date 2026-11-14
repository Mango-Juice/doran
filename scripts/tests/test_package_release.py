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
        streams.append({'codec_type': 'audio'})
    return {'format': {'duration': str(duration)}, 'streams': streams}


class PackageReleaseTests(unittest.TestCase):
    def test_silent_demo_accepts_target_and_rejects_audio(self):
        self.assertEqual(module.inspect_video(video())['audio_tracks'], 0)
        with self.assertRaises(ValueError):
            module.inspect_video(video(audio=True))

    def test_demo_duration_contract(self):
        for seconds in [149, 211, float('nan')]:
            with self.assertRaises(ValueError):
                module.inspect_video(video(seconds))

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


if __name__ == '__main__':
    unittest.main()
