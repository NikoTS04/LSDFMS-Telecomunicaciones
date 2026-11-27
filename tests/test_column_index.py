"""Índices por columna (phone, timestamp, size): oráculo Python, rangos, borrados y PACK."""

from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
HEADER = struct.Struct(">Biqq")
ENTRY = struct.Struct(">qiq")
COLUMNS = {"phone": 2, "timestamp": 3, "size": 1}  # posición de la clave en (deleted, len, phone, ts)
FILES = {"phone": "index_phone.idx", "timestamp": "index_timestamp.idx", "size": "index_size.idx"}


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requiere JDK 17+")
class ColumnIndexTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classes = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.classes.cleanup)
        sources = list((ROOT / "src").rglob("*.java"))
        sources += list((ROOT / "tests" / "java" / "com").rglob("*.java"))
        subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-Xlint:all",
                        "-Werror", "-d", cls.classes.name, *map(str, sources)],
                       check=True, capture_output=True, text=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / "data"
        subprocess.run([sys.executable, str(ROOT / "scripts" / "generate_cdr.py"),
                        "--output", str(self.directory), "--files", "3",
                        "--records-per-file", "150", "--min-payload", "64",
                        "--max-payload", "300", "--deleted-ratio", "0.25"],
                       check=True, capture_output=True, text=True)

    def java(self, *args, props=(), main="com.hpc.lsdfms.Main"):
        return subprocess.run(["java", *props, "-cp", self.classes.name, main, *map(str, args)],
                              capture_output=True, text=True, timeout=120)

    def scan(self):
        """Registros (deleted, length, phone, timestamp, fileId, offset) de todos los .dat."""
        records = []
        for path in sorted(self.directory.glob("F_*.dat")):
            data, offset = path.read_bytes(), 0
            while offset < len(data):
                deleted, length, phone, timestamp = HEADER.unpack_from(data, offset)
                records.append((deleted, length, phone, timestamp, int(path.stem[2:]), offset))
                offset += 21 + length
        return records

    def oracle(self, column):
        position = COLUMNS[column]
        return sorted((r[position], r[4], r[5]) for r in self.scan() if not r[0])

    def index_entries(self, column):
        return list(ENTRY.iter_unpack((self.directory / FILES[column]).read_bytes()))

    def search(self, column, lo, hi, limit=None):
        args = ["search-range", self.directory, column, lo, hi]
        if limit is not None:
            args.append(limit)
        result = self.java(*args)
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = result.stdout.strip().splitlines()
        rows = [tuple(map(int, line.split("\t"))) for line in lines[1:-1]]
        return rows, lines[-1]

    def expected_rows(self, column, lo, hi):
        position = COLUMNS[column]
        rows = sorted((r[position], r[4], r[5], r[2], r[3], r[1]) for r in self.scan()
                      if not r[0] and lo <= r[position] <= hi)
        return [(phone, fid, off, ts, length) for _, fid, off, phone, ts, length in rows]

    def test_three_indexes_exist_sorted_and_equal_oracle(self):
        result = self.java("index-columns", self.directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), f"INDEX_COLUMNS\t{len(self.oracle('phone'))}")
        for column in COLUMNS:
            self.assertEqual(self.index_entries(column), self.oracle(column), column)
        baseline = self.directory.parent / "baseline"
        shutil.copytree(self.directory, baseline)
        (baseline / "index_phone.idx").unlink()
        self.assertEqual(self.java("index", baseline).returncode, 0)
        self.assertEqual((baseline / "index_phone.idx").read_bytes(),
                         (self.directory / "index_phone.idx").read_bytes())
        self.assertFalse(list(self.directory.glob(".lsdfms-*")) - set()
                         if list(self.directory.glob(".lsdfms-col-*")) else [])

    def test_range_exact_and_limit_equal_bruteforce(self):
        self.assertEqual(self.java("index-columns", self.directory).returncode, 0)
        for column in COLUMNS:
            keys = [entry[0] for entry in self.oracle(column)]
            lo, hi = keys[len(keys) // 5], keys[len(keys) * 3 // 5]
            for bounds in ((lo, hi), (keys[7], keys[7]), (keys[0], keys[-1]),
                           (keys[-1] + 1, keys[-1] + 99)):
                rows, total = self.search(column, *bounds)
                self.assertEqual(rows, self.expected_rows(column, *bounds), (column, bounds))
                self.assertEqual(total, f"TOTAL\t{len(rows)}")
            rows, total = self.search(column, keys[0], keys[-1], 5)
            self.assertEqual(rows, self.expected_rows(column, keys[0], keys[-1])[:5])
            self.assertEqual(total, "TOTAL\t5\tTRUNCADO")

    def test_records_deleted_after_indexing_are_skipped(self):
        self.assertEqual(self.java("index-columns", self.directory).returncode, 0)
        victims = [r for r in self.scan() if not r[0]][::3]
        for _, _, _, _, file_id, offset in victims:
            path = self.directory / f"F_{file_id}.dat"
            data = bytearray(path.read_bytes())
            data[offset] = 1
            path.write_bytes(data)
        for column in COLUMNS:
            lo = self.oracle(column)[0][0]
            hi = self.oracle(column)[-1][0]
            rows, total = self.search(column, lo, hi)
            self.assertEqual(rows, self.expected_rows(column, lo, hi))
            self.assertEqual(total, f"TOTAL\t{len(rows)}")

    def test_pack_rebuilds_every_column_index(self):
        self.assertEqual(self.java("index-columns", self.directory).returncode, 0)
        parallel = self.directory.parent / "parallel"
        shutil.copytree(self.directory, parallel)
        self.assertEqual(self.java("pack", self.directory).returncode, 0)
        self.assertEqual(self.java("pack-parallel", parallel, 3).returncode, 0)
        for column in COLUMNS:
            self.assertEqual(self.index_entries(column), self.oracle(column), column)
            self.assertEqual((parallel / FILES[column]).read_bytes(),
                             (self.directory / FILES[column]).read_bytes())
        self.assertFalse(list(self.directory.glob(".lsdfms-col-*")))

    def test_external_path_matches_oracle(self):
        result = self.java("index-columns", self.directory,
                           props=["-Dlsdfms.index.mode=external", "-Dlsdfms.index.chunkEntries=40"])
        self.assertEqual(result.returncode, 0, result.stderr)
        for column in COLUMNS:
            self.assertEqual(self.index_entries(column), self.oracle(column), column)

    def test_invalid_arguments_and_missing_index(self):
        self.assertEqual(self.java("search-range", self.directory, "bogus", 1, 2).returncode, 2)
        self.assertEqual(self.java("search-range", self.directory, "size", 1).returncode, 2)
        self.assertEqual(self.java("search-range", self.directory, "size", 9, 1).returncode, 2)
        self.assertEqual(self.java("search-range", self.directory, "size", 1, 2).returncode, 1)

    def test_threads_failures_search_and_held_access(self):
        result = self.java(self.temp.name,
                           main="com.hpc.lsdfms.core.ColumnIndexTest")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_builder_does_not_use_executors(self):
        text = (ROOT / "src/com/hpc/lsdfms/core/ColumnIndexBuilder.java").read_text()
        self.assertNotIn("Executor", text)
