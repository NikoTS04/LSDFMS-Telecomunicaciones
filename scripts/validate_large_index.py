#!/usr/bin/env python3
"""Validación INDEX > heap, dos motores, sin compactar el dataset original."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time

from benchmark_index import ROOT, command_output, validate_index


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()
    manifest = json.loads((args.dataset / "manifest.json").read_text())
    expected = manifest["totals"]["active"]
    if expected * 20 <= 512 * 1024**2:
        parser.error("El índice activo debe superar 512 MiB")
    if not hasattr(os, "wait4"):
        parser.error("Esta prueba requiere POSIX wait4")
    args.output.mkdir(parents=True, exist_ok=False)
    metadata = {"manifest": manifest, "heap_bytes": 512 * 1024**2,
                "jdk": command_output(["java", "-version"]),
                "sources": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                            for p in sorted((ROOT / "src").rglob("*.java"))},
                "sampling": "picos de temporales y descriptores muestreados cada 100 ms"}
    (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    baseline = None
    results = []
    for engine in ("sequential", "parallel"):
        operation = ["index", str(args.dataset)] if engine == "sequential" else [
            "index-parallel", str(args.dataset), str(args.workers)]
        command = [shutil.which("java") or "java", "-Xmx512m",
                   "-cp", str(ROOT / "build/classes"), "com.hpc.lsdfms.Main", *operation]
        stop = threading.Event()
        peak = {"temporary_logical_bytes": 0, "temporary_allocated_bytes": 0, "open_fds": 0}
        with (args.output / (engine + ".log")).open("wb") as log:
            started = time.perf_counter()
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)

            def monitor():
                while not stop.is_set():
                    logical = allocated = 0
                    for directory in args.dataset.glob(".lsdfms-index-*"):
                        for path in directory.glob("*"):
                            try:
                                stat = path.stat()
                            except FileNotFoundError:
                                continue
                            logical += stat.st_size
                            allocated += stat.st_blocks * 512
                    peak["temporary_logical_bytes"] = max(peak["temporary_logical_bytes"], logical)
                    peak["temporary_allocated_bytes"] = max(peak["temporary_allocated_bytes"], allocated)
                    try:
                        peak["open_fds"] = max(peak["open_fds"], len(list(
                            Path(f"/proc/{process.pid}/fd").iterdir())))
                    except FileNotFoundError:
                        pass
                    stop.wait(0.1)

            thread = threading.Thread(target=monitor)
            thread.start()
            try:
                _, status, usage = os.wait4(process.pid, 0)
                process.returncode = os.waitstatus_to_exitcode(status)
            finally:
                stop.set()
                thread.join()
                if process.returncode is None:
                    process.kill()
                    process.wait()
            elapsed = time.perf_counter() - started
            if process.returncode:
                raise RuntimeError(f"{engine} falló: revisar log")
        validation = validate_index(args.dataset, expected, 1000, 42)
        if baseline is None:
            baseline = validation["sha256"]
        if validation["sha256"] != baseline:
            raise ValueError("Índices no coinciden")
        result = {"engine": engine, "command": command, "seconds": elapsed,
                  "rss_kib": usage.ru_maxrss, "user_seconds": usage.ru_utime,
                  "system_seconds": usage.ru_stime, **peak, "validation": validation}
        results.append(result)
        (args.output / (engine + ".json")).write_text(json.dumps(result, indent=2) + "\n")
        print(f"{engine}: {elapsed:.3f}s, índice {expected*20} bytes, integridad OK", flush=True)
    (args.output / "summary.json").write_text(json.dumps(results, indent=2) + "\n")


if __name__ == "__main__":
    main()
