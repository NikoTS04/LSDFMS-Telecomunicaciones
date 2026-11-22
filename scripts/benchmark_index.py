#!/usr/bin/env python3
"""Benchmark reproducible de INDEX; biblioteca estándar, datos fuente inmutables."""

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import shutil
import statistics
import struct
import subprocess
import time
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
ENTRY = struct.Struct(">qiq")
HEADER = struct.Struct(">Biqq")


def command_output(command):
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    return {"command": command, "returncode": result.returncode,
            "stdout": result.stdout, "stderr": result.stderr}


def run_timed(command, timing, log):
    """wait4 entrega RSS por PID en POSIX, sin librerías ni herramientas opcionales."""
    started = time.perf_counter()
    if hasattr(os, "wait4"):
        with log.open("wb") as stream:
            process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT)
            try:
                _, status, usage = os.wait4(process.pid, 0)
                process.returncode = os.waitstatus_to_exitcode(status)
            finally:
                if process.returncode is None:
                    process.kill()
                    process.wait()
        seconds = time.perf_counter() - started
        if process.returncode:
            raise RuntimeError(f"Proceso falló ({process.returncode}); revisar {log}")
        rss = usage.ru_maxrss / 1024 if platform.system() == "Darwin" else usage.ru_maxrss
        resources = {"rss_kib": rss, "user_seconds": usage.ru_utime,
                     "system_seconds": usage.ru_stime}
    else:
        timed = command
        if Path("/usr/bin/time").is_file():
            timed = ["/usr/bin/time", "-f",
                     '{"rss_kib":%M,"user_seconds":%U,"system_seconds":%S}',
                     "-o", str(timing), *command]
        with log.open("wb") as stream:
            subprocess.run(timed, stdout=stream, stderr=subprocess.STDOUT, check=True)
        seconds = time.perf_counter() - started
        resources = json.loads(timing.read_text()) if timing.exists() else {
            "rss_kib": None, "user_seconds": None, "system_seconds": None}
    timing.write_text(json.dumps(resources) + "\n")
    return seconds, resources


def validate_index(dataset, expected, samples, seed):
    """Orden completo, conteo y muestreo reproducible de referencias."""
    path = dataset / "index_phone.idx"
    if path.stat().st_size != expected * ENTRY.size:
        raise ValueError("Tamaño del índice no coincide con activos del manifiesto")
    digest = hashlib.sha256()
    previous = None
    count = 0
    with path.open("rb") as stream:
        while block := stream.read(ENTRY.size * 32768):
            digest.update(block)
            for entry in ENTRY.iter_unpack(block):
                if previous is not None and entry <= previous:
                    raise ValueError("Tuplas no estrictamente ordenadas")
                previous = entry
                count += 1
    phones = []
    with path.open("rb") as stream:
        for position in random.Random(seed).sample(range(count), min(samples, count)):
            stream.seek(position * ENTRY.size)
            phone, file_id, offset = ENTRY.unpack(stream.read(ENTRY.size))
            source = dataset / f"F_{file_id}.dat"
            with source.open("rb") as data:
                if offset < 0 or source.stat().st_size - offset < HEADER.size:
                    raise ValueError("Offset inválido")
                data.seek(offset)
                deleted, length, actual, _ = HEADER.unpack(data.read(HEADER.size))
                if deleted != 0 or actual != phone or length < 0 or (
                        source.stat().st_size - offset - HEADER.size < length):
                    raise ValueError("Referencia no coincide con CDR activo")
            phones.append(phone)
    return {"sha256": digest.hexdigest(), "entries": count,
            "sampled_references": len(phones), "phones": phones}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--output", required=True, type=Path,
                        help="Directorio nuevo para resultados (no sobrescribe)")
    parser.add_argument("--classes", type=Path, default=ROOT / "build/classes")
    parser.add_argument("--workers", type=int, nargs="+", default=[4])
    parser.add_argument("--heap", default="1g")
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--samples", type=int, default=1000)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--allow-small", action="store_true",
                        help="Solo para comprobar el script con fixtures pequeños")
    args = parser.parse_args()
    if (args.warmups < 1 or args.repetitions < 1 or args.samples < 1
            or any(not 1 <= workers <= 256 for workers in args.workers)):
        parser.error("Configuración fuera de límites")
    dataset = args.dataset.resolve()
    manifest = json.loads((dataset / "manifest.json").read_text())
    sources = sorted(dataset.glob("F_*.dat"))
    total_bytes = sum(path.stat().st_size for path in sources)
    if not args.allow_small and (len(sources) < 8 or total_bytes < 2 * 1024**3):
        parser.error("El hito requiere ≥8 archivos y ≥2 GiB; usar --allow-small para fixtures")
    if total_bytes != manifest["totals"]["bytes"]:
        parser.error("Dataset modificado: tamaño no coincide con manifiesto")
    if not (args.classes / "com/hpc/lsdfms/Main.class").is_file():
        parser.error("Compilar primero con make build")
    args.output.mkdir(parents=True, exist_ok=False)
    output = args.output.resolve()
    java = [shutil.which("java") or "java", f"-Xmx{args.heap}", "-cp",
            str(args.classes.resolve()), "com.hpc.lsdfms.Main"]
    cores = os.cpu_count() or 1
    metadata = {
        "date_utc": datetime.now(timezone.utc).isoformat(),
        "platform": platform.platform(), "logical_cpus": cores,
        "affinity": sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None,
        "config": {key: str(value) if isinstance(value, Path) else value
                   for key, value in vars(args).items()},
        "dataset_bytes": total_bytes, "manifest": manifest,
        "timing": "Wall subprocess: inicio JVM, bloqueo, conteo, extracción, sort, escritura, force, cierre",
        "cache": "No controlada; calentamiento previo por configuración; sin vaciar caché SO",
        "resource_measurement": "wait4 por PID" if hasattr(os, "wait4") else "GNU time si disponible",
        "commands": {},
    }
    for name, command in {
        "jdk": ["java", "-version"], "cpu": ["lscpu"], "memory": ["free", "-b"],
        "filesystem": ["df", "-T", str(dataset)], "block_devices": ["lsblk", "-d", "-o", "NAME,MODEL,ROTA"],
        "jvm_system": ["java", "-XshowSettings:system", "-version"],
        "git": ["git", "-C", str(ROOT), "rev-parse", "HEAD"],
        "git_status": ["git", "-C", str(ROOT), "status", "--short"],
    }.items():
        if shutil.which(command[0]):
            metadata[name] = command_output(command)
    metadata["java_source_sha256"] = {
        str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted((ROOT / "src").rglob("*.java"))}
    metadata["class_sha256"] = {
        str(path.relative_to(args.classes)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(args.classes.rglob("*.class"))}
    (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    shutil.copyfile(dataset / "manifest.json", output / "manifest.json")
    configs = [("sequential", 1)] + [("parallel", p) for p in dict.fromkeys(args.workers)]
    rows = []
    baseline = None
    fields = ["engine", "requested_workers", "effective_workers", "phase", "repetition",
              "seconds", "rss_kib", "user_seconds", "system_seconds", "sha256", "entries"]
    with (output / "runs.csv").open("w", newline="") as csvfile:
        writer = csv.DictWriter(csvfile, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        # Alterna configuraciones dentro de cada repetición para reducir sesgo de orden.
        for iteration in range(args.warmups + args.repetitions):
            phase = "warmup" if iteration < args.warmups else "measured"
            repetition = iteration + 1 if phase == "warmup" else iteration - args.warmups + 1
            for engine, workers in configs:
                operation = ["index", str(dataset)] if engine == "sequential" else [
                    "index-parallel", str(dataset), str(workers)]
                command = java + operation
                label = f"{engine}-{workers}-{phase}-{repetition}"
                metadata["commands"][label] = command
                timing = output / f"{label}.time.json"
                seconds, usage = run_timed(command, timing, output / f"{label}.log")
                validation = validate_index(dataset, manifest["totals"]["active"],
                                            args.samples, args.seed)
                if baseline is None:
                    baseline = validation["sha256"]
                if validation["sha256"] != baseline:
                    raise ValueError("El índice difiere del baseline")
                row = {"engine": engine, "requested_workers": workers,
                       "effective_workers": min(workers, len(sources)) if engine == "parallel" else 1,
                       "phase": phase, "repetition": repetition, "seconds": seconds,
                       **usage, "sha256": baseline, "entries": validation["entries"]}
                rows.append(row)
                writer.writerow(row)
                csvfile.flush()
                print(f"{label}: {seconds:.3f}s, integridad OK", flush=True)
    # Ejercita también la búsqueda binaria real, fuera del intervalo medido.
    query_checks = []
    for phone in validation["phones"][:10]:
        result = subprocess.run(java + ["search", str(dataset), str(phone)],
                                capture_output=True, text=True, check=True)
        lines = result.stdout.splitlines()
        if not lines or not lines[-1].startswith("TOTAL\t"):
            raise ValueError("Consulta sin resumen")
        matches = int(lines[-1].split("\t")[1])
        if matches < 1 or matches != len(lines) - 2:
            raise ValueError("Consulta perdió coincidencias")
        query_checks.append({"phone": phone, "matches": matches})
    seq = statistics.median(row["seconds"] for row in rows
                            if row["engine"] == "sequential" and row["phase"] == "measured")
    summary = []
    for engine, workers in configs:
        selected = [row for row in rows if row["engine"] == engine
                    and row["requested_workers"] == workers and row["phase"] == "measured"]
        times = [row["seconds"] for row in selected]
        median = statistics.median(times)
        actual = selected[0]["effective_workers"]
        summary.append({"engine": engine, "requested_workers": workers,
                        "effective_workers": actual, "median_seconds": median,
                        "min_seconds": min(times), "max_seconds": max(times),
                        "stdev_seconds": statistics.stdev(times) if len(times) > 1 else 0,
                        "speedup": seq / median, "efficiency_workers": seq / median / actual,
                        "efficiency_logical_cores": seq / median / cores,
                        "peak_rss_kib": max((row["rss_kib"] for row in selected
                                             if row["rss_kib"] is not None), default=None)})
    (output / "summary.json").write_text(json.dumps({
        "results": summary, "sha256": baseline, "validation": {
            "entries": validation["entries"], "sampled_references_per_run": args.samples,
            "query_checks": query_checks}}, indent=2) + "\n")
    (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")


if __name__ == "__main__":
    main()
