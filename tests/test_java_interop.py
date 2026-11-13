"""Compila con JDK 17 y comprueba lectura de datos Python y errores estructurales."""

import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requiere JDK 17+")
class JavaInteropTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compilation = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.compilation.cleanup)
        sources = sorted(str(path) for path in (ROOT / "src").rglob("*.java"))
        sources.append(str(ROOT / "tests" / "java" / "FormatTest.java"))
        subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-Xlint:all",
                        "-Werror", "-d", cls.compilation.name, *sources],
                       capture_output=True, text=True, check=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def java(self, *args, main="com.hpc.lsdfms.Main"):
        return subprocess.run(["java", "-cp", self.compilation.name, main, *map(str, args)],
                              capture_output=True, text=True, check=False)

    def test_generated_files_match_java_totals(self):
        subprocess.run([sys.executable, str(ROOT / "scripts" / "generate_cdr.py"),
                        "--output", str(self.directory), "--files", "8", "--total-size", "64KiB"],
                       capture_output=True, text=True, check=True)
        manifest = json.loads((self.directory / "manifest.json").read_text())
        result = self.java("inspect", self.directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        total = manifest["totals"]
        expected = f"TOTAL\t{total['bytes']}\t{total['records']}\t{total['active']}\t{total['deleted']}"
        self.assertIn(expected, result.stdout)

    def test_rejects_corrupt_records(self):
        for content in (
            b"\x00",  # cabecera truncada
            struct.pack(">Biqq", 2, 0, 51912345678, 1735689600000),
            struct.pack(">Biqq", 0, -1, 51912345678, 1735689600000),
            struct.pack(">Biqq", 0, 5, 51912345678, 1735689600000) + b"ab",
            struct.pack(">Biqq", 0, 2147483647, 51912345678, 1735689600000),
        ):
            with self.subTest(content=content):
                (self.directory / "F_1.dat").write_bytes(content)
                result = self.java("inspect", self.directory)
                self.assertEqual(result.returncode, 1)
                self.assertIn("@ byte 0", result.stderr)

    def test_empty_file_is_structurally_valid(self):
        (self.directory / "F_1.dat").touch()
        result = self.java("inspect", self.directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("TOTAL\t0\t0\t0\t0", result.stdout)

    def test_empty_directory_and_missing_directory_fail(self):
        self.assertEqual(self.java("inspect", self.directory).returncode, 1)
        self.assertEqual(self.java("inspect", self.directory / "missing").returncode, 1)

    def test_help_and_unknown_commands(self):
        self.assertEqual(self.java("--help").returncode, 0)
        self.assertEqual(self.java("index", self.directory).returncode, 2)

    def test_index_serialization_contract(self):
        result = self.java(main="FormatTest")
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
