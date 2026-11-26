"""Equivalencia de INDEX paralelo, límites, errores y cancelación."""

from pathlib import Path
import json
import random
import shutil
import struct
import subprocess
import tempfile
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
HEADER = struct.Struct(">Biqq")
ENTRY = struct.Struct(">qiq")


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requiere JDK 17+")
class ParallelIndexTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classes = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.classes.cleanup)
        sources = list((ROOT / "src").rglob("*.java"))
        sources += list((ROOT / "tests/java/com").rglob("*.java"))
        subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-Xlint:all",
                        "-Werror", "-d", cls.classes.name, *map(str, sources)],
                       check=True, capture_output=True, text=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def java(self, *args, main="com.hpc.lsdfms.Main", heap="128m"):
        return subprocess.run(["java", f"-Xmx{heap}", "-Dlsdfms.index.mode=memory", "-cp", self.classes.name,
                               main, *map(str, args)], capture_output=True, text=True, timeout=30)

    def write(self, file_id, keys):
        raw = bytearray()
        expected = []
        for i, key in enumerate(keys):
            deleted = i % 5 == 0
            payload = b"x" * (i % 43)
            if not deleted:
                expected.append((key, file_id, len(raw)))
            raw.extend(HEADER.pack(deleted, len(payload), key, 0) + payload)
        (self.directory / f"F_{file_id}.dat").write_bytes(raw)
        return expected

    def test_randomized_full_tuple_oracle(self):
        rng = random.Random(42)
        expected = []
        for file_id in (1, 3, 10, 2147483647):
            expected.extend(self.write(file_id, [rng.randrange(-20, 20) for _ in range(2500)]))
        self.assertEqual(self.java("index", self.directory).returncode, 0)
        baseline = (self.directory / "index_phone.idx").read_bytes()
        for workers in (1, 2, 3, 4, 8):
            with self.subTest(workers=workers):
                result = self.java("index-parallel", self.directory, workers)
                self.assertEqual(result.returncode, 0, result.stderr)
                actual = (self.directory / "index_phone.idx").read_bytes()
                self.assertEqual(actual, baseline)
                self.assertEqual(list(ENTRY.iter_unpack(actual)), sorted(expected))
                result = self.java("search", self.directory, 3)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(f"TOTAL\t{sum(item[0] == 3 for item in expected)}", result.stdout)

    def test_small_odd_ranges_empty_and_single_files(self):
        for size in (0, 1, 2, 3, 7, 16, 31):
            expected = []
            for file_id in (1, 2, 3):
                expected.extend(self.write(file_id, list(reversed(range(size)))))
            result = self.java("index-parallel", self.directory, 3)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(list(ENTRY.iter_unpack(
                (self.directory / "index_phone.idx").read_bytes())), sorted(expected))
        (self.directory / "F_2.dat").unlink()
        (self.directory / "F_3.dat").unlink()
        self.assertEqual(self.java("index-parallel", self.directory, 8).returncode, 0)

    def test_extreme_keys_and_offsets(self):
        expected = self.write(3, [0, -9223372036854775808, 9223372036854775807, -1, 0, 1])
        path = self.directory / "F_1.dat"
        with path.open("wb") as stream:
            stream.write(HEADER.pack(1, 2147483647, 0, 0))
            stream.seek(2147483647, 1)
            offset = stream.tell()
            stream.write(HEADER.pack(0, 0, 1, 0))
        expected.append((1, 1, offset))
        result = self.java("index-parallel", self.directory, 2)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(list(ENTRY.iter_unpack(
            (self.directory / "index_phone.idx").read_bytes())), sorted(expected))

    def test_invalid_workers_and_arguments(self):
        self.write(1, [1, 2])
        for workers in ("0", "-1", "257", "bad", "2147483648"):
            self.assertEqual(self.java("index-parallel", self.directory, workers).returncode, 2)
        self.assertEqual(self.java("index-parallel", self.directory).returncode, 2)
        self.assertEqual(self.java("index-parallel", self.directory, 2, "extra").returncode, 2)
        self.assertEqual(self.java("index-parallel", self.directory / "missing", 2).returncode, 1)

    def test_corrupt_source_and_marker_preserve_index(self):
        self.write(1, [1, 2, 3])
        self.assertEqual(self.java("index", self.directory).returncode, 0)
        index = self.directory / "index_phone.idx"
        before = index.read_bytes()
        (self.directory / "F_2.dat").write_bytes(b"truncated")
        self.assertEqual(self.java("index-parallel", self.directory, 2).returncode, 1)
        self.assertEqual(index.read_bytes(), before)
        (self.directory / "F_2.dat").unlink()
        (self.directory / ".lsdfms-maintenance").write_text("pending")
        self.assertEqual(self.java("index-parallel", self.directory, 2).returncode, 1)
        self.assertEqual(index.read_bytes(), before)
        self.assertFalse(list(self.directory.glob("*.tmp")))

    def test_memory_budget(self):
        with (self.directory / "F_1.dat").open("wb") as stream:
            block = HEADER.pack(0, 0, 7, 0) * 10000
            for _ in range(30):
                stream.write(block)
        target = self.directory / "index_phone.idx"
        target.write_bytes(b"previous")
        result = self.java("index-parallel", self.directory, 2, heap="32m")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("presupuesto", result.stderr)
        self.assertEqual(target.read_bytes(), b"previous")

    def test_worker_failure_interruption_and_shutdown(self):
        result = self.java(self.directory, main="com.hpc.lsdfms.core.ParallelFailureTest")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_publication_error(self):
        self.write(1, [1, 2, 3])
        target = self.directory / "index_phone.idx"
        target.mkdir()
        (target / "keep").write_bytes(b"keep")
        result = self.java("index-parallel", self.directory, 2)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual((target / "keep").read_bytes(), b"keep")
        self.assertFalse(list(self.directory.glob("*.tmp")))

    def test_benchmark_small_pipeline(self):
        dataset = self.directory / "data"
        output = self.directory / "results"
        subprocess.run([sys.executable, str(ROOT / "scripts/generate_cdr.py"),
                        "--output", str(dataset), "--files", "2", "--total-size", "16KiB"],
                       capture_output=True, text=True, check=True)
        command = [sys.executable, str(ROOT / "scripts/benchmark_index.py"), str(dataset),
                   "--classes", self.classes.name, "--output", str(output),
                   "--allow-small", "--warmups", "1", "--repetitions", "1",
                   "--workers", "2", "--samples", "20", "--heap", "128m"]
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        summary = json.loads((output / "summary.json").read_text())
        self.assertEqual(len(summary["results"]), 2)
        self.assertEqual(len(summary["validation"]["query_checks"]), 10)
        self.assertEqual(len((output / "runs.csv").read_text().splitlines()), 5)
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        self.assertNotEqual(result.returncode, 0, "No debe sobrescribir resultados existentes")

    def test_external_multiple_passes_and_pack(self):
        expected = []
        for file_id in (1, 3, 10):
            expected.extend(self.write(file_id, [i % 13 for i in reversed(range(300))]))
        command = ["java", "-Xmx32m", "-Dlsdfms.index.mode=external",
                   "-Dlsdfms.index.memoryMiB=4", "-Dlsdfms.index.chunkEntries=17",
                   "-Dlsdfms.index.fanIn=2", "-cp", self.classes.name, "com.hpc.lsdfms.Main"]
        for operation in (["index", str(self.directory)],
                          ["index-parallel", str(self.directory), "3"]):
            result = subprocess.run(command + operation, capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(list(ENTRY.iter_unpack(
                (self.directory / "index_phone.idx").read_bytes())), sorted(expected))
            self.assertFalse(list(self.directory.glob(".lsdfms-index-*")))
        reference = self.directory / "reference"
        reference.mkdir()
        for path in self.directory.glob("F_*.dat"):
            shutil.copyfile(path, reference / path.name)
        self.assertEqual(self.java("pack", reference).returncode, 0)
        result = subprocess.run(command + ["pack-parallel", str(self.directory), "3"],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        for path in reference.glob("*.dat"):
            self.assertEqual(path.read_bytes(), (self.directory / path.name).read_bytes())
        self.assertEqual((reference / "index_phone.idx").read_bytes(),
                         (self.directory / "index_phone.idx").read_bytes())

    def test_automatic_external_under_small_heap(self):
        with (self.directory / "F_1.dat").open("wb") as stream:
            for _ in range(30):
                stream.write(HEADER.pack(0, 0, 7, 0) * 10000)
        result = subprocess.run(["java", "-Xmx32m", "-cp", self.classes.name,
                                 "com.hpc.lsdfms.Main", "index-parallel",
                                 str(self.directory), "2"],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.directory / "index_phone.idx").stat().st_size, 6000000)
        self.assertFalse(list(self.directory.glob(".lsdfms-index-*")))

    def test_external_worker_failures_and_recovery(self):
        for main in ("ParallelFailureTest", "ParallelPackFaultTest"):
            target = self.directory / main
            target.mkdir()
            result = subprocess.run(["java", "-Xmx128m", "-Dlsdfms.index.mode=external",
                                     "-Dlsdfms.index.memoryMiB=4", "-cp", self.classes.name,
                                     "com.hpc.lsdfms.core." + main, str(target)],
                                    capture_output=True, text=True, timeout=60)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse(list(target.rglob(".lsdfms-index-*")))

    def test_external_publication_failure_cleans_workspace(self):
        self.write(1, list(range(50)))
        target = self.directory / "index_phone.idx"
        target.mkdir()
        (target / "keep").write_text("keep")
        result = subprocess.run(["java", "-Xmx32m", "-Dlsdfms.index.mode=external",
                                 "-cp", self.classes.name, "com.hpc.lsdfms.Main",
                                 "index-parallel", str(self.directory), "2"],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 1)
        self.assertEqual((target / "keep").read_text(), "keep")
        self.assertFalse(list(self.directory.glob(".lsdfms-index-*")))

    def test_external_invalid_runs_and_merge_interruption(self):
        result = self.java(self.directory, main="com.hpc.lsdfms.core.ExternalMergeTest")
        self.assertEqual(result.returncode, 0, result.stderr)
