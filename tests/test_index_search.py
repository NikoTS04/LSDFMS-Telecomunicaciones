"""Oráculo independiente Python para INDEX y búsqueda Java sin frameworks."""

from pathlib import Path
import random
import shutil
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
HEADER = struct.Struct(">Biqq")
ENTRY = struct.Struct(">qiq")


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requiere JDK 17+")
class IndexSearchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classes = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.classes.cleanup)
        subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-Xlint:all",
                        "-Werror", "-d", cls.classes.name,
                        *map(str, (ROOT / "src").rglob("*.java"))], check=True,
                       capture_output=True, text=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def java(self, *args, heap=None):
        return subprocess.run(["java", *([f"-Xmx{heap}"] if heap else []),
                               "-cp", self.classes.name, "com.hpc.lsdfms.Main",
                               *map(str, args)], capture_output=True, text=True)

    def write_records(self, file_id, records):
        data = bytearray()
        entries = []
        for deleted, phone, payload in records:
            if not deleted:
                entries.append((phone, file_id, len(data)))
            data.extend(HEADER.pack(deleted, len(payload), phone, 1735689600000))
            data.extend(payload)
        (self.directory / f"F_{file_id}.dat").write_bytes(data)
        return entries

    def index(self):
        result = self.java("index", self.directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        return list(ENTRY.iter_unpack((self.directory / "index_phone.idx").read_bytes()))

    def test_randomized_tuple_oracle_and_repeatability(self):
        rng = random.Random(42)
        expected = []
        for file_id in (10, 1, 2147483647, 3):
            records = [(rng.randrange(4) == 0, rng.randrange(12), rng.randbytes(rng.randrange(80)))
                       for _ in range(400)]
            expected.extend(self.write_records(file_id, records))
        self.assertEqual(self.index(), sorted(expected))
        before = (self.directory / "index_phone.idx").read_bytes()
        self.index()
        self.assertEqual((self.directory / "index_phone.idx").read_bytes(), before)
        self.assertFalse(list(self.directory.glob("*.tmp")))

    def test_duplicate_search_and_escaping(self):
        self.write_records(10, [(0, 7, b"last"), (1, 7, b"deleted")])
        self.write_records(3, [(0, 7, "á\t\n\\\r".encode()), (0, 1, b"other"),
                               (0, 7, b"again")])
        self.index()
        result = self.java("search", self.directory, 7)
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = result.stdout.splitlines()
        self.assertIn("7\t3\t0\t1735689600000\tá\\t\\n\\\\\\r", rows)
        self.assertEqual(rows[-1], "TOTAL\t3")
        self.assertNotIn("deleted", result.stdout)
        for key in (-1, 0, 2, 8, 9223372036854775807):
            result = self.java("search", self.directory, key)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.splitlines()[-1], "TOTAL\t0")

    def test_empty_files_and_all_deleted(self):
        self.write_records(1, [])
        self.write_records(8, [(1, 123, b"x")])
        self.assertEqual(self.index(), [])
        result = self.java("search", self.directory, 123)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("TOTAL\t0", result.stdout)

    def test_signed_long_keys_and_sort_extremes(self):
        keys = [9223372036854775807, 0, -9223372036854775808, -1, 1]
        expected = self.write_records(1, [(0, key, b"") for key in keys])
        self.assertEqual(self.index(), sorted(expected))
        for key in keys:
            result = self.java("search", self.directory, key)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("TOTAL\t1", result.stdout)

    def test_invalid_source_keeps_previous_index_and_cleans_temp(self):
        self.write_records(1, [(0, 7, b"ok")])
        self.index()
        target = self.directory / "index_phone.idx"
        before = target.read_bytes()
        for content in (b"x", HEADER.pack(2, 0, 7, 0),
                        HEADER.pack(0, -1, 7, 0), HEADER.pack(0, 3, 7, 0) + b"x"):
            (self.directory / "F_2.dat").write_bytes(content)
            result = self.java("index", self.directory)
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertEqual(target.read_bytes(), before)
            self.assertFalse(list(self.directory.glob("*.tmp")))

    def test_invalid_index_and_references(self):
        self.write_records(1, [(0, 7, b"ok")])
        target = self.directory / "index_phone.idx"
        self.assertEqual(self.java("search", self.directory, 7).returncode, 1)
        for content in (b"x", ENTRY.pack(7, 2, 0), ENTRY.pack(7, 0, 0),
                        ENTRY.pack(7, 1, -1), ENTRY.pack(7, 1, 999),
                        ENTRY.pack(7, 1, 1)):
            target.write_bytes(content)
            self.assertEqual(self.java("search", self.directory, 7).returncode, 1)
        target.write_bytes(ENTRY.pack(7, 1, 0))
        for data in (HEADER.pack(1, 0, 7, 0), HEADER.pack(0, 0, 8, 0),
                     HEADER.pack(0, 5, 7, 0), b"x"):
            (self.directory / "F_1.dat").write_bytes(data)
            self.assertEqual(self.java("search", self.directory, 7).returncode, 1)

    def test_invalid_arguments_and_missing_sources(self):
        for args in (("index",), ("index", self.directory, "extra"),
                     ("search", self.directory), ("search", self.directory, "bad"),
                     ("search", self.directory, "9223372036854775808"),
                     ("pack", self.directory)):
            self.assertEqual(self.java(*args).returncode, 2)
        for directory in (self.directory, self.directory / "missing"):
            self.assertEqual(self.java("index", directory).returncode, 1)
        (self.directory / "F_2147483648.dat").touch()
        self.assertEqual(self.java("index", self.directory).returncode, 1)

    def test_publication_error_cleans_temporary(self):
        self.write_records(1, [(0, 1, b"x")])
        (self.directory / "index_phone.idx").mkdir()
        (self.directory / "index_phone.idx" / "keep").write_text("keep")
        result = self.java("index", self.directory)
        self.assertEqual(result.returncode, 1)
        self.assertTrue((self.directory / "index_phone.idx" / "keep").is_file())
        self.assertFalse(list(self.directory.glob("*.tmp")))

    def test_offset_beyond_32_bits(self):
        # Archivo disperso: dos payloads enormes se saltan sin cargarlos en RAM.
        path = self.directory / "F_1.dat"
        with path.open("wb") as stream:
            for _ in range(2):
                stream.write(HEADER.pack(1, 2147483647, 1, 0))
                stream.seek(2147483647, 1)
            offset = stream.tell()
            stream.write(HEADER.pack(0, 0, 7, 1735689600000))
        self.assertEqual(self.index(), [(7, 1, offset)])
        result = self.java("search", self.directory, 7, heap="32m")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"7\t1\t{offset}\t", result.stdout)

    def test_memory_budget_preserves_existing_index(self):
        target = self.directory / "index_phone.idx"
        target.write_bytes(b"previous-index")
        with (self.directory / "F_1.dat").open("wb") as stream:
            block = HEADER.pack(0, 0, 7, 0) * 10000
            for _ in range(50):
                stream.write(block)
        result = self.java("index", self.directory, heap="32m")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("presupuesto", result.stderr)
        self.assertNotIn("OutOfMemoryError", result.stderr)
        self.assertEqual(target.read_bytes(), b"previous-index")
        self.assertFalse(list(self.directory.glob("*.tmp")))

    def test_small_sort_sizes_and_duplicate_ranges(self):
        for size in range(1, 10):
            with self.subTest(size=size):
                records = [(0, i % 3, b"") for i in reversed(range(size))]
                expected = self.write_records(1, records)
                self.assertEqual(self.index(), sorted(expected))
                for key in range(3):
                    result = self.java("search", self.directory, key)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    count = sum(entry[0] == key for entry in expected)
                    self.assertIn(f"TOTAL\t{count}", result.stdout)

    def test_does_not_follow_source_or_index_symlinks(self):
        external = self.directory / "external"
        external.write_bytes(HEADER.pack(0, 0, 7, 0))
        source = self.directory / "F_1.dat"
        source.symlink_to(external)
        self.assertEqual(self.java("index", self.directory).returncode, 1)
        source.unlink()
        self.write_records(1, [(0, 7, b"")])
        index = self.directory / "index_phone.idx"
        external.write_bytes(ENTRY.pack(7, 1, 0))
        index.symlink_to(external)
        self.assertEqual(self.java("search", self.directory, 7).returncode, 1)
