"""Contrato del generador y comprobación independiente del formato binario."""

import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "generate_cdr.py"
SPEC = importlib.util.spec_from_file_location("generate_cdr", SCRIPT)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class GeneratorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / "dataset"

    def run_generator(self, *args, output=None):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--output", str(output or self.output), *args],
            capture_output=True, text=True, check=False,
        )

    def generate(self, *args, output=None):
        result = self.run_generator(*args, output=output)
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(((output or self.output) / "manifest.json").read_text())

    def read_records(self, path):
        content = path.read_bytes()
        offset = 0
        records = []
        while offset < len(content):
            flag, length, phone, timestamp = struct.unpack_from(">Biqq", content, offset)
            payload = content[offset + 21:offset + 21 + length]
            self.assertEqual(len(payload), length)
            self.assertIn(flag, (0, 1))
            self.assertTrue(51900000000 <= phone < 52000000000)
            self.assertTrue(1735689600000 <= timestamp < 1738281600000)
            text = payload.decode("utf-8")
            self.assertIn("service=", text)
            self.assertIn(";duration=", text)
            records.append((flag, length))
            offset += 21 + length
        self.assertEqual(offset, len(content))
        return records

    def test_layout_variable_lengths_and_manifest(self):
        manifest = self.generate("--files", "3", "--records-per-file", "200")
        all_records = []
        for entry in manifest["files"]:
            path = self.output / entry["name"]
            records = self.read_records(path)
            self.assertEqual(len(records), 200)
            self.assertEqual(entry["bytes"], path.stat().st_size)
            self.assertEqual(entry["deleted"], sum(flag for flag, _ in records))
            self.assertEqual(entry["active"], 200 - entry["deleted"])
            self.assertTrue(all(128 <= length <= 512 for _, length in records))
            all_records.extend(records)
        self.assertGreater(len({length for _, length in all_records}), 1)
        self.assertEqual(manifest["format"]["header_bytes"], 21)
        self.assertEqual(manifest["totals"]["records"], 600)
        for key, value in manifest["totals"].items():
            self.assertEqual(value, sum(entry[key] for entry in manifest["files"]))

    def test_total_size_is_minimum_and_ends_on_record_boundary(self):
        manifest = self.generate("--files", "8", "--total-size", "32KiB")
        self.assertEqual(len(manifest["files"]), 8)
        self.assertGreaterEqual(manifest["totals"]["bytes"], 32768)
        self.assertLess(manifest["totals"]["bytes"], 32768 + 8 * (21 + 512))
        for entry in manifest["files"]:
            self.read_records(self.output / entry["name"])

    def test_reproducibility(self):
        args = ("--files", "2", "--records-per-file", "20", "--seed", "17")
        self.generate(*args)
        other = Path(self.temp.name) / "other"
        self.generate(*args, output=other)
        for path in self.output.iterdir():
            self.assertEqual(path.read_bytes(), (other / path.name).read_bytes())

    def test_different_seed_changes_data(self):
        self.generate("--files", "1", "--records-per-file", "10", "--seed", "1")
        other = Path(self.temp.name) / "other"
        self.generate("--files", "1", "--records-per-file", "10", "--seed", "2", output=other)
        self.assertNotEqual((self.output / "F_1.dat").read_bytes(), (other / "F_1.dat").read_bytes())

    def test_deletion_extremes_and_minimum_payload(self):
        for ratio in (0, 1):
            with self.subTest(ratio=ratio):
                output = Path(self.temp.name) / str(ratio)
                manifest = self.generate("--files", "1", "--records-per-file", "50",
                                         "--deleted-ratio", str(ratio), "--min-payload", "64",
                                         "--max-payload", "64", output=output)
                self.assertEqual(manifest["totals"]["deleted"], 50 * ratio)
                self.assertTrue(all(flag == ratio and length == 64
                                    for flag, length in self.read_records(output / "F_1.dat")))

    def test_approximate_deletion_ratio(self):
        manifest = self.generate("--files", "1", "--records-per-file", "10000")
        ratio = manifest["totals"]["deleted"] / 10000
        self.assertTrue(0.17 < ratio < 0.23, ratio)

    def test_refuses_overwrite_without_changing_files(self):
        self.generate("--files", "1", "--records-per-file", "5")
        before = {path.name: path.read_bytes() for path in self.output.iterdir()}
        result = self.run_generator("--files", "1", "--records-per-file", "10")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.output.iterdir()})

    def test_gitkeep_is_allowed(self):
        self.output.mkdir()
        (self.output / ".gitkeep").touch()
        self.generate("--files", "1", "--records-per-file", "1")
        self.assertTrue((self.output / ".gitkeep").is_file())

    def test_invalid_options_do_not_create_output(self):
        for args in (
            ("--files", "0"), ("--files", "abc"), ("--total-size", "invalid"),
            ("--total-size", "0"), ("--total-size", "1", "--files", "2"),
            ("--records-per-file", "-1"), ("--min-payload", "63"),
            ("--min-payload", "513"), ("--max-payload", "1048577"),
            ("--deleted-ratio", "-0.1"), ("--deleted-ratio", "1.1"),
            ("--deleted-ratio", "nan"), ("--deleted-ratio", "inf"),
            ("--total-size", "1MiB", "--records-per-file", "1"),
        ):
            with self.subTest(args=args):
                result = self.run_generator(*args)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertFalse(self.output.exists())

    def test_units_including_massive_targets_without_allocating(self):
        for text, expected in (("1", 1), ("1KB", 1000), ("1KiB", 1024),
                               ("1.5MiB", 1572864), ("2GiB", 2147483648),
                               ("10GB", 10000000000), ("10GiB", 10737418240)):
            with self.subTest(text=text):
                self.assertEqual(GENERATOR.size_bytes(text), expected)


if __name__ == "__main__":
    unittest.main()
