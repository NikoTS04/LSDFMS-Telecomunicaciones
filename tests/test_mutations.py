"""Mutaciones de registros (alta, baja lógica, actualización) y cola sincronizada."""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requiere JDK 17+")
class MutationTest(unittest.TestCase):
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

    def java(self, main, *args):
        return subprocess.run(["java", "-cp", self.classes.name, main, *map(str, args)],
                              capture_output=True, text=True, timeout=120)

    def test_record_writer_append_delete_update_and_pack(self):
        generated = self.directory / "generated"
        subprocess.run([sys.executable, str(ROOT / "scripts" / "generate_cdr.py"),
                        "--output", str(generated), "--files", "2", "--total-size", "64KiB"],
                       check=True, capture_output=True, text=True)
        result = self.java("com.hpc.lsdfms.io.RecordWriterTest", self.directory, generated)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_sync_queue_and_result_slot(self):
        result = self.java("com.hpc.lsdfms.core.SyncQueueTest")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_queue_sources_do_not_use_java_util_concurrent(self):
        for name in ("SyncQueue", "ResultSlot"):
            text = (ROOT / "src/com/hpc/lsdfms/core" / f"{name}.java").read_text()
            self.assertNotIn("java.util.concurrent", text)
