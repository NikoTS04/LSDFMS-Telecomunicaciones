"""Servicio de dataset (delta, versiones, mantenimiento en segundo plano) y guardias manuales."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "src" / "com" / "hpc" / "lsdfms" / "core"


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requiere JDK 17+")
class DatasetServiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classes = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.classes.cleanup)
        sources = list((ROOT / "src").rglob("*.java"))
        sources += list((ROOT / "tests" / "java" / "com").rglob("*.java"))
        subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-Xlint:all",
                        "-Werror", "-d", cls.classes.name, *map(str, sources)],
                       check=True, capture_output=True, text=True)

    def test_service_behaviour_and_concurrency(self):
        with tempfile.TemporaryDirectory() as temp:
            result = subprocess.run(
                ["java", "-cp", self.classes.name, "com.hpc.lsdfms.core.DatasetServiceTest", temp],
                capture_output=True, text=True, timeout=300)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_new_classes_do_not_use_java_util_concurrent(self):
        for name in ("DatasetService", "MaintenanceWorker", "ReadWriteGuard"):
            text = (CORE / f"{name}.java").read_text()
            self.assertNotIn("java.util.concurrent", text, name)
