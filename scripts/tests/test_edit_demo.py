"""Validate publication constraints without recording or rendering media."""
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('edit_demo', Path(__file__).parents[1]/'edit-demo.py')
editor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(editor)


class SilentDemoTest(unittest.TestCase):
    def test_srt_rounds_across_minute(self):
        self.assertEqual(editor.srt_time(59.9996), '00:01:00,000')

    def test_caption_requires_readable_two_line_text(self):
        self.assertEqual(editor.caption_text('발표 시간을 정정합니다.\n내가 쓴 글은 유지됩니다.'),
                         '발표 시간을 정정합니다.\n내가 쓴 글은 유지됩니다.')
        for text in ['a\nb\nc', '가'*45, '', 'a\x00']:
            with self.subTest(text=text), self.assertRaises(ValueError):
                editor.caption_text(text)

    def test_synthetic_input_does_not_require_output_audio(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base/'source.mp4').touch()
            (base/'font.ttf').touch()
            scene = {'source': 'source.mp4', 'start_seconds': 0, 'end_seconds': 142,
                     'surface': 'Pleos', 'synthetic_input': True, 'core_unedited': True,
                     'captions': [{'start_seconds': 2, 'end_seconds': 5, 'text': '가상 사례'}]}
            config = {'silent_output': True, 'font': str(base/'font.ttf'),
                      'version': {'source_commit': 'a'*40, 'car_apk_sha256': 'b'*64,
                                  'mobile_apk_sha256': 'c'*64}, 'scenes': [scene]}
            media = {'streams': [{'codec_type': 'video'}], 'format': {'duration': '150'}}
            with patch.object(editor, 'probe', return_value=media), patch.object(editor.shutil, 'which', return_value='/tool'):
                self.assertEqual(editor.inspect(config, base)[1][0]['duration'], 142)
                scene['playback_speed'] = 2
                with self.assertRaises(ValueError):
                    editor.inspect(config, base)
                del scene['playback_speed']
                scene['captions'][0]['end_seconds'] = 143
                with self.assertRaises(ValueError):
                    editor.inspect(config, base)

    def test_encoder_has_no_audio_output(self):
        self.assertIn('-an', editor.encode_args())
        self.assertNotIn('-c:a', editor.encode_args())


if __name__ == '__main__':
    unittest.main()
