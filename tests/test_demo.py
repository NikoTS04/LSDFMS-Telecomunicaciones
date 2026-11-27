"""Aceptación del paquete de exposición, sin modificar datos existentes."""
from pathlib import Path
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


class PresentationTest(unittest.TestCase):
    def test_charts_are_valid_and_grounded(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run([sys.executable, ROOT / "scripts/presentation_charts.py",
                                     "--output", tmp], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            for name in ("index-speedup.svg", "index-vs-heap.svg"):
                self.assertEqual(ET.parse(Path(tmp) / name).getroot().tag,
                                 "{http://www.w3.org/2000/svg}svg")
            sources = json.loads((Path(tmp) / "sources.json").read_text())
            self.assertEqual(len(sources["index_series"]["2GiB"]), 5)

    @unittest.skipUnless(shutil.which("java") and shutil.which("javac"), "Requiere JDK")
    def test_automatic_demo_and_recovery(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "new"
            command = [sys.executable, ROOT / "scripts/demo.py", "--auto", "--recovery",
                       "--output", output]
            result = subprocess.run(command, capture_output=True, text=True, timeout=180)
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads((output / "summary.json").read_text())
            self.assertTrue(report["ok"] and report["recovery"])
            self.assertGreater(report["reclaimed"], 0)
            self.assertLess(report["offset_after"], report["offset_before"])
            before = (output / "summary.json").read_bytes()
            repeated = subprocess.run(command, capture_output=True, text=True, timeout=30)
            self.assertNotEqual(repeated.returncode, 0)
            self.assertEqual((output / "summary.json").read_bytes(), before)
