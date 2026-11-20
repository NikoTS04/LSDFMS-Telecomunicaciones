"""Aceptación de PACK, recuperación persistente y exclusión entre procesos."""

from pathlib import Path
import random
import selectors
import shutil
import struct
import subprocess
import tempfile
import unittest

try:
    import fcntl
except ImportError:
    fcntl = None

ROOT = Path(__file__).resolve().parents[1]
HEADER = struct.Struct(">Biqq")
ENTRY = struct.Struct(">qiq")


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requiere JDK 17+")
class PackTest(unittest.TestCase):
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
        self.directory = Path(self.temp.name)

    def java(self, *args, main="com.hpc.lsdfms.Main"):
        return subprocess.run(["java", "-cp", self.classes.name, main, *map(str, args)],
                              capture_output=True, text=True, timeout=30)

    def records(self, file_id, records):
        raw, active = bytearray(), bytearray()
        for deleted, key, payload in records:
            record = HEADER.pack(deleted, len(payload), key, 1735689600000) + payload
            raw.extend(record)
            if not deleted:
                active.extend(record)
        (self.directory / f"F_{file_id}.dat").write_bytes(raw)
        return bytes(active)

    def verify_index(self):
        expected = []
        for path in self.directory.glob("F_*.dat"):
            data = path.read_bytes()
            offset = 0
            while offset < len(data):
                deleted, length, key, _ = HEADER.unpack_from(data, offset)
                self.assertEqual(deleted, 0)
                expected.append((key, int(path.stem[2:]), offset))
                offset += 21 + length
        index = (self.directory / "index_phone.idx").read_bytes()
        self.assertEqual(list(ENTRY.iter_unpack(index)), sorted(expected))

    def test_pack_exact_bytes_order_and_idempotence(self):
        rng = random.Random(20)
        expected = {}
        for file_id in (1, 3, 10):
            expected[file_id] = self.records(file_id, [
                (rng.randrange(4) == 0, rng.randrange(8), rng.randbytes(rng.randrange(150)))
                for _ in range(100)])
        before = sum(path.stat().st_size for path in self.directory.glob("F_*.dat"))
        result = self.java("pack", self.directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        after = sum(map(len, expected.values()))
        self.assertIn(f"PACK\t{before}\t{after}\t{before-after}\t", result.stdout)
        for file_id, content in expected.items():
            self.assertEqual((self.directory / f"F_{file_id}.dat").read_bytes(), content)
        self.verify_index()
        index = (self.directory / "index_phone.idx").read_bytes()
        self.assertFalse((self.directory / ".lsdfms-maintenance").exists())
        self.assertFalse(list(self.directory.glob("*.tmp")))
        result = self.java("pack", self.directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"PACK\t{after}\t{after}\t0\t", result.stdout)
        self.assertEqual((self.directory / "index_phone.idx").read_bytes(), index)
        result = self.java("search", self.directory, 3)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_empty_and_all_deleted_files(self):
        self.records(1, [])
        self.records(2, [(1, 7, b"payload")])
        result = self.java("pack", self.directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.directory / "F_2.dat").read_bytes(), b"")
        self.verify_index()

    def test_corrupt_preflight_preserves_existing_index(self):
        self.records(1, [(0, 7, b"ok")])
        self.assertEqual(self.java("index", self.directory).returncode, 0)
        before = (self.directory / "index_phone.idx").read_bytes()
        (self.directory / "F_2.dat").write_bytes(b"x")
        self.assertEqual(self.java("pack", self.directory).returncode, 1)
        self.assertEqual((self.directory / "index_phone.idx").read_bytes(), before)
        self.assertFalse((self.directory / ".lsdfms-maintenance").exists())

    def test_marker_blocks_index_and_search_after_restart(self):
        expected = self.records(1, [(1, 7, b"old"), (0, 7, b"keep")])
        self.assertEqual(self.java("index", self.directory).returncode, 0)
        (self.directory / ".lsdfms-maintenance").write_text("interrupted")
        for operation in (("search", self.directory, 7), ("index", self.directory),
                          ("pack", self.directory)):
            self.assertEqual(self.java(*operation).returncode, 1)
        self.assertEqual(self.java("inspect", self.directory).returncode, 0)
        orphan = self.directory / ".lsdfms-pack-orphan.tmp"
        orphan.write_bytes(b"not trusted")
        result = self.java("recover", self.directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.directory / "F_1.dat").read_bytes(), expected)
        self.assertEqual(orphan.read_bytes(), b"not trusted")
        self.verify_index()
        self.assertEqual(self.java("search", self.directory, 7).returncode, 0)
        self.assertEqual(self.java("recover", self.directory).returncode, 1)

    @unittest.skipIf(fcntl is None, "Requiere record locks POSIX")
    def test_exclusive_lock_between_processes(self):
        self.records(1, [(0, 7, b"keep")])
        with (self.directory / ".lsdfms.lock").open("a+b") as lock:
            # lockf usa fcntl/record locks, compatibles con FileLock de Java en Linux.
            fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            for operation in (("search", self.directory, 7), ("index", self.directory),
                              ("pack", self.directory), ("recover", self.directory),
                              ("inspect", self.directory)):
                result = self.java(*operation)
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertIn("ocupado", result.stderr)
        self.assertEqual(self.java("pack", self.directory).returncode, 0)

    def test_fault_injection_and_transfer_fallback(self):
        result = self.java(self.directory, main="com.hpc.lsdfms.core.PackFaultTest")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_low_level_transfer_loops(self):
        result = self.java(self.directory, main="com.hpc.lsdfms.io.FileTransfersTest")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_real_process_termination_and_recovery(self):
        for phase in ("before", "after"):
            with self.subTest(phase=phase):
                expected = self.records(1, [(1, 7, b"old"), (0, 7, b"keep")])
                self.records(3, [(0, 8, b"second")])
                self.assertEqual(self.java("index", self.directory).returncode, 0)
                process = subprocess.Popen([
                    "java", "-cp", self.classes.name, "com.hpc.lsdfms.core.PackCrashProbe",
                    str(self.directory), phase], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE, text=True)
                try:
                    with selectors.DefaultSelector() as selector:
                        selector.register(process.stdout, selectors.EVENT_READ)
                        self.assertTrue(selector.select(timeout=10), "Probe no alcanzó reemplazo")
                    self.assertEqual(process.stdout.readline().strip(), "READY")
                    self.assertEqual(self.java("search", self.directory, 7).returncode, 1)
                    process.kill()
                    process.communicate(timeout=10)
                finally:
                    if process.poll() is None:
                        process.kill()
                    process.communicate(timeout=10)
                self.assertTrue((self.directory / ".lsdfms-maintenance").is_file())
                self.assertEqual(self.java("index", self.directory).returncode, 1)
                self.assertEqual(self.java("search", self.directory, 7).returncode, 1)
                self.assertEqual(self.java("recover", self.directory).returncode, 0)
                self.assertEqual((self.directory / "F_1.dat").read_bytes(), expected)
                self.verify_index()
                self.assertEqual(self.java("search", self.directory, 7).returncode, 0)
