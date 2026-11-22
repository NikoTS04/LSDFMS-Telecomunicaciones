"""PACK paralelo: equivalencia, recuperación y fallos de mantenimiento."""

from pathlib import Path
import json
import random
import selectors
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
class ParallelPackTest(unittest.TestCase):
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
        self.root = Path(self.temp.name)

    def java(self, *args, main="com.hpc.lsdfms.Main"):
        return subprocess.run(["java", "-Xmx128m", "-cp", self.classes.name,
                               main, *map(str, args)], capture_output=True, text=True, timeout=30)

    def fixture(self, directory):
        directory.mkdir()
        expected = {}
        rng = random.Random(42)
        for file_id in (1, 3, 10):
            raw = bytearray()
            active = bytearray()
            for i in range(200):
                deleted = i % 4 == 0
                payload = rng.randbytes(rng.randrange(80))
                record = HEADER.pack(deleted, len(payload), i % 9, 1000 + i) + payload
                raw.extend(record)
                if not deleted:
                    active.extend(record)
            (directory / f"F_{file_id}.dat").write_bytes(raw)
            expected[f"F_{file_id}.dat"] = bytes(active)
        return expected

    def verify(self, directory, expected):
        entries = []
        for name, active in expected.items():
            self.assertEqual((directory / name).read_bytes(), active)
            offset = 0
            while offset < len(active):
                deleted, length, key, _ = HEADER.unpack_from(active, offset)
                self.assertEqual(deleted, 0)
                entries.append((key, int(Path(name).stem[2:]), offset))
                offset += 21 + length
        self.assertEqual(list(ENTRY.iter_unpack(
            (directory / "index_phone.idx").read_bytes())), sorted(entries))
        self.assertFalse((directory / ".lsdfms-maintenance").exists())
        self.assertEqual(self.java("search", directory, 3).returncode, 0)

    def test_equivalence_idempotence_and_immutable_source(self):
        source = self.root / "source"
        expected = self.fixture(source)
        pristine = {path.name: path.read_bytes() for path in source.glob("*.dat")}
        baseline = self.root / "sequential"
        shutil.copytree(source, baseline)
        self.assertEqual(self.java("pack", baseline).returncode, 0)
        index = (baseline / "index_phone.idx").read_bytes()
        for workers in (1, 2, 3, 8):
            with self.subTest(workers=workers):
                dataset = self.root / f"parallel-{workers}"
                shutil.copytree(source, dataset)
                self.assertEqual(self.java("index", dataset).returncode, 0)
                result = self.java("pack-parallel", dataset, workers)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.verify(dataset, expected)
                self.assertEqual((dataset / "index_phone.idx").read_bytes(), index)
                result = self.java("pack-parallel", dataset, workers)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(int(result.stdout.strip().split("\t")[3]), 0)
                self.assertFalse(list(dataset.glob("*.tmp")))
        self.assertEqual({path.name: path.read_bytes() for path in source.glob("*.dat")}, pristine)

    def test_empty_all_deleted_and_no_deleted(self):
        dataset = self.root / "empty"
        dataset.mkdir()
        (dataset / "F_1.dat").write_bytes(b"")
        (dataset / "F_2.dat").write_bytes(HEADER.pack(1, 0, 7, 0))
        (dataset / "F_8.dat").write_bytes(HEADER.pack(0, 1, 7, 5) + b"x")
        result = self.java("pack-parallel", dataset, 8)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.verify(dataset, {"F_1.dat": b"", "F_2.dat": b"",
                              "F_8.dat": HEADER.pack(0, 1, 7, 5) + b"x"})

    def test_all_files_deleted_produces_empty_index(self):
        dataset = self.root / "deleted"
        dataset.mkdir()
        for file_id in (1, 5):
            (dataset / f"F_{file_id}.dat").write_bytes(HEADER.pack(1, 3, 7, 0) + b"old")
        result = self.java("pack-parallel", dataset, 8)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "PACK_PARALLEL\t48\t0\t48\t0")
        self.verify(dataset, {"F_1.dat": b"", "F_5.dat": b""})
        self.assertEqual((dataset / "index_phone.idx").stat().st_size, 0)

    def test_corrupt_preflight_and_pending_maintenance(self):
        dataset = self.root / "corrupt"
        expected = self.fixture(dataset)
        self.assertEqual(self.java("index", dataset).returncode, 0)
        before = (dataset / "index_phone.idx").read_bytes()
        (dataset / "F_2.dat").write_bytes(b"x")
        self.assertEqual(self.java("pack-parallel", dataset, 2).returncode, 1)
        self.assertEqual((dataset / "index_phone.idx").read_bytes(), before)
        self.assertFalse((dataset / ".lsdfms-maintenance").exists())
        (dataset / "F_2.dat").unlink()
        (dataset / ".lsdfms-maintenance").write_text("interrupted sequential PACK")
        self.assertEqual(self.java("pack-parallel", dataset, 2).returncode, 1)
        self.assertEqual(self.java("recover-parallel", dataset, 2).returncode, 0)
        self.verify(dataset, expected)
        self.assertEqual(self.java("recover-parallel", dataset, 2).returncode, 1)

    def test_invalid_arguments(self):
        dataset = self.root / "args"
        self.fixture(dataset)
        for operation in ("pack-parallel", "recover-parallel"):
            for workers in ("0", "-1", "257", "bad", "2147483648"):
                self.assertEqual(self.java(operation, dataset, workers).returncode, 2)
            self.assertEqual(self.java(operation, dataset).returncode, 2)
            self.assertEqual(self.java(operation, dataset, 2, "extra").returncode, 2)
            self.assertEqual(self.java(operation, dataset / "missing", 2).returncode, 1)

    def test_worker_failure_cancellation_and_resource_bounds(self):
        result = self.java(self.root, main="com.hpc.lsdfms.core.ParallelPackFaultTest")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_benchmark_on_fresh_copies(self):
        dataset = self.root / "generated"
        output = self.root / "results"
        subprocess.run([sys.executable, str(ROOT / "scripts/generate_cdr.py"),
                        "--output", str(dataset), "--files", "2", "--total-size", "16KiB"],
                       capture_output=True, text=True, check=True)
        before = {path.name: path.read_bytes() for path in dataset.glob("*.dat")}
        command = [sys.executable, str(ROOT / "scripts/benchmark_pack.py"), str(dataset),
                   "--classes", self.classes.name, "--output", str(output), "--workers", "2",
                   "--warmups", "1", "--repetitions", "1", "--samples", "20", "--heap", "128m"]
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        summary = json.loads((output / "summary.json").read_text())
        self.assertTrue(summary["source_unchanged"])
        self.assertEqual(len(summary["results"]), 2)
        self.assertGreater(summary["reclaimed"], 0)
        self.assertEqual({path.name: path.read_bytes() for path in dataset.glob("*.dat")}, before)
        self.assertEqual(len((output / "runs.csv").read_text().splitlines()), 5)
        self.assertNotEqual(subprocess.run(command, capture_output=True, timeout=30).returncode, 0)
        # Una fila sobrevivió al reinicio, pero su archivo de recursos quedó vacío.
        (output / "summary.json").unlink()
        checkpoint = (output / "runs.csv").read_text().splitlines(keepends=True)[:-1]
        (output / "parallel-2-measured-1.time.json").write_text("")
        result = subprocess.run(command + ["--resume"], capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((output / "runs.csv").read_text().splitlines(keepends=True)[:4], checkpoint)
        resumed = json.loads((output / "summary.json").read_text())
        self.assertTrue(resumed["resumed"])
        self.assertEqual(len(list((output / "interrupted").rglob("*.log"))), 1)
        self.assertEqual(len(list((output / "interrupted").rglob("runs-before-resume.csv"))), 1)
        self.assertEqual({path.name: path.read_bytes() for path in dataset.glob("*.dat")}, before)

    def test_real_crash_and_cross_mode_recovery(self):
        for phase, recovery in (("before", "recover"), ("after", "recover-parallel"),
                                ("index", "recover-parallel")):
            with self.subTest(phase=phase):
                dataset = self.root / phase
                expected = self.fixture(dataset)
                self.assertEqual(self.java("index", dataset).returncode, 0)
                process = subprocess.Popen(["java", "-cp", self.classes.name,
                    "com.hpc.lsdfms.core.PackCrashProbe", str(dataset), phase, "parallel"],
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                try:
                    with selectors.DefaultSelector() as selector:
                        selector.register(process.stdout, selectors.EVENT_READ)
                        self.assertTrue(selector.select(timeout=10), "Probe no alcanzó el punto de caída")
                    self.assertEqual(process.stdout.readline().strip(), "READY")
                    self.assertEqual(self.java("search", dataset, 3).returncode, 1)
                    process.kill()
                    process.communicate(timeout=10)
                finally:
                    if process.poll() is None:
                        process.kill()
                    process.communicate(timeout=10)
                for operation in (("search", dataset, 3), ("index", dataset),
                                  ("index-parallel", dataset, 2)):
                    self.assertEqual(self.java(*operation).returncode, 1)
                args = (recovery, dataset, 2) if recovery.endswith("parallel") else (recovery, dataset)
                result = self.java(*args)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.verify(dataset, expected)
