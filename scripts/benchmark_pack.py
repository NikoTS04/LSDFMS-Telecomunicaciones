#!/usr/bin/env python3
"""PACK -> INDEX sobre copias frescas; no compacta el dataset fuente."""

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import statistics
import struct
import tempfile

from benchmark_index import ROOT, command_output, run_timed, validate_index

HEADER = struct.Struct(">Biqq")


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def expected_active(path):
    """Oráculo independiente, memoria acotada incluso para payloads grandes."""
    digest = hashlib.sha256()
    active = deleted = size = 0
    total = path.stat().st_size
    with path.open("rb") as stream:
        while header := stream.read(HEADER.size):
            if len(header) != HEADER.size:
                raise ValueError(f"Cabecera truncada: {path}")
            flag, length, _, _ = HEADER.unpack(header)
            if flag not in (0, 1) or length < 0 or length > total - stream.tell():
                raise ValueError(f"Registro inválido: {path}")
            if flag:
                stream.seek(length, 1)
                deleted += 1
            else:
                digest.update(header)
                remaining = length
                while remaining:
                    block = stream.read(min(remaining, 1024 * 1024))
                    if not block:
                        raise ValueError(f"Payload truncado: {path}")
                    digest.update(block)
                    remaining -= len(block)
                active += 1
                size += HEADER.size + length
    return {"sha256": digest.hexdigest(), "active": active, "deleted": deleted, "bytes": size}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--classes", type=Path, default=ROOT / "build/classes")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--heap", default="1g")
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--samples", type=int, default=1000)
    parser.add_argument("--resume", action="store_true",
                        help="Reanuda solo filas verificadas con las mismas fuentes/clases/configuración")
    args = parser.parse_args()
    if not 1 <= args.workers <= 256 or min(args.warmups, args.repetitions, args.samples) < 1:
        parser.error("Configuración fuera de límites")
    dataset = args.dataset.resolve()
    if (dataset / ".lsdfms-maintenance").exists():
        parser.error("Recuperar primero el dataset fuente")
    manifest = json.loads((dataset / "manifest.json").read_text())
    sources = sorted(dataset.glob("F_*.dat"))
    if not sources or any(path.is_symlink() or not path.is_file() for path in sources):
        parser.error("Se requieren fuentes regulares, no enlaces")
    if not (args.classes / "com/hpc/lsdfms/Main.class").is_file():
        parser.error("Compilar primero con make build")
    expected = {path.name: expected_active(path) for path in sources}
    source_hashes = {path.name: digest_file(path) for path in sources}
    before = sum(path.stat().st_size for path in sources)
    after = sum(record["bytes"] for record in expected.values())
    active = sum(record["active"] for record in expected.values())
    deleted = sum(record["deleted"] for record in expected.values())
    if (before != manifest["totals"]["bytes"] or active != manifest["totals"]["active"]
            or deleted != manifest["totals"]["deleted"]):
        parser.error("Fuentes no coinciden con el manifiesto")
    if not args.resume:
        args.output.mkdir(parents=True, exist_ok=False)
    output = args.output.resolve()
    java = [shutil.which("java") or "java", f"-Xmx{args.heap}", "-cp",
            str(args.classes.resolve()), "com.hpc.lsdfms.Main"]
    metadata = {
        "date_utc": datetime.now(timezone.utc).isoformat(),
        "platform": platform.platform(), "python": platform.python_version(),
        "logical_cpus": os.cpu_count(), "config": {
            key: str(value) if isinstance(value, Path) else value
            for key, value in vars(args).items() if key != "resume"},
        "scope": "Wall JVM: bloqueo, preflight, compactación/validación/force, reemplazos, INDEX y cierre",
        "cache": "Sin vaciar caché SO; copias frescas antes de cada ejecución, fuera del reloj",
        "source_sha256": source_hashes, "expected_active": expected, "commands": {},
        "resource_measurement": "wait4 por PID" if hasattr(os, "wait4") else "GNU time si disponible",
        "java_source_sha256": {
            str(path.relative_to(ROOT)): digest_file(path) for path in sorted((ROOT / "src").rglob("*.java"))},
        "class_sha256": {
            str(path.relative_to(args.classes)): digest_file(path)
            for path in sorted(args.classes.rglob("*.class"))},
    }
    for name, command in {
        "jdk": ["java", "-version"], "cpu": ["lscpu"], "memory": ["free", "-b"],
        "filesystem": ["df", "-T", str(dataset)],
        "git": ["git", "-C", str(ROOT), "rev-parse", "HEAD"],
    }.items():
        if shutil.which(command[0]):
            metadata[name] = command_output(command)
    fields = ["engine", "workers", "phase", "repetition", "seconds", "rss_kib",
              "user_seconds", "system_seconds", "bytes_before", "bytes_after",
              "reclaimed", "active", "index_sha256"]
    rows = []
    baseline = None
    plan = []
    for iteration in range(args.warmups + args.repetitions):
        phase = "warmup" if iteration < args.warmups else "measured"
        repetition = iteration + 1 if phase == "warmup" else iteration - args.warmups + 1
        for engine, workers in (("sequential", 1), ("parallel", args.workers)):
            plan.append((engine, min(workers, len(sources)), phase, repetition))
    archive = None
    if args.resume:
        if (output / "summary.json").exists():
            parser.error("La serie ya está completa")
        previous = json.loads((output / "metadata.json").read_text())
        for key in ("config", "source_sha256", "expected_active", "java_source_sha256",
                    "class_sha256", "platform", "python", "logical_cpus", "jdk"):
            if previous.get(key) != metadata.get(key):
                parser.error(f"No se puede reanudar: cambió {key}")
        with (output / "runs.csv").open(newline="") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames != fields:
                parser.error("CSV de reanudación con esquema distinto")
            integer_fields = {"workers", "repetition", "bytes_before", "bytes_after", "reclaimed", "active"}
            text_fields = {"engine", "phase", "index_sha256"}
            raw_rows = list(reader)
            for raw in raw_rows:
                row = {key: value if key in text_fields else
                       (None if value == "" else int(value) if key in integer_fields else float(value))
                       for key, value in raw.items()}
                if len(rows) >= len(plan) or tuple(row[key] for key in
                        ("engine", "workers", "phase", "repetition")) != plan[len(rows)]:
                    parser.error("CSV fuera del orden esperado")
                if tuple(row[key] for key in ("bytes_before", "bytes_after", "reclaimed", "active")) != (
                        before, after, before - after, active):
                    parser.error("Conteos del checkpoint incompatibles")
                if row["seconds"] <= 0:
                    parser.error("Duración de checkpoint inválida")
                label_workers = 1 if row["engine"] == "sequential" else args.workers
                label = f"{row['engine']}-{label_workers}-{row['phase']}-{row['repetition']}"
                try:
                    usage = json.loads((output / f"{label}.time.json").read_text())
                    log = (output / f"{label}.log").read_text().strip()
                except (FileNotFoundError, json.JSONDecodeError):
                    # Tras un reinicio, una fila puede sobrevivir y su artefacto no.
                    # Solo se conserva el prefijo completo; el resto se vuelve a medir.
                    break
                if not log:
                    break
                if any(row[key] != usage[key] for key in
                       ("rss_kib", "user_seconds", "system_seconds")):
                    parser.error("Recursos del checkpoint no coinciden")
                expected_log = "PACK" if row["engine"] == "sequential" else "PACK_PARALLEL"
                expected_log += f"\t{before}\t{after}\t{before-after}\t{active}"
                if log != expected_log:
                    parser.error("Salida de checkpoint no coincide")
                if baseline is None:
                    baseline = row["index_sha256"]
                if len(baseline) != 64 or row["index_sha256"] != baseline:
                    parser.error("Hash de checkpoint inválido")
                rows.append(row)
        archive = output / "interrupted" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f")
        if len(rows) < len(raw_rows):
            archive.mkdir(parents=True, exist_ok=True)
            checkpoint = output / "runs.csv"
            shutil.copyfile(checkpoint, archive / "runs-before-resume.csv")
            lines = checkpoint.read_text().splitlines(keepends=True)
            checkpoint.write_text("".join(lines[:len(rows) + 1]))
        metadata = previous
        metadata.setdefault("resumptions", []).append({
            "date_utc": datetime.now(timezone.utc).isoformat(), "completed_runs": len(rows),
            "discarded_incomplete_rows": len(raw_rows) - len(rows),
            "archive": str(archive.relative_to(output)),
            "note": "Solo filas escritas tras validación; repite cualquier ejecución sin fila completa"})
    else:
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    completed = len(rows)
    with (output / "runs.csv").open("a" if args.resume else "w", newline="") as csvfile:
        writer = csv.DictWriter(csvfile, fieldnames=fields, lineterminator="\n")
        if not args.resume:
            writer.writeheader()
        position = 0
        for iteration in range(args.warmups + args.repetitions):
            phase = "warmup" if iteration < args.warmups else "measured"
            repetition = iteration + 1 if phase == "warmup" else iteration - args.warmups + 1
            for engine, workers in (("sequential", 1), ("parallel", args.workers)):
                position += 1
                if position <= completed:
                    continue
                label = f"{engine}-{workers}-{phase}-{repetition}"
                if archive is not None:
                    for suffix in (".log", ".time.json"):
                        artifact = output / (label + suffix)
                        if artifact.exists():
                            archive.mkdir(parents=True, exist_ok=True)
                            artifact.rename(archive / artifact.name)
                # Solo se elimina el directorio temporal creado por esta ejecución.
                with tempfile.TemporaryDirectory(prefix=".benchmark-pack-", dir=dataset.parent) as tmp:
                    target = Path(tmp)
                    for path in sources:
                        shutil.copyfile(path, target / path.name)
                    operation = ["pack", str(target)] if engine == "sequential" else [
                        "pack-parallel", str(target), str(workers)]
                    metadata["commands"][label] = java + operation
                    seconds, usage = run_timed(java + operation, output / f"{label}.time.json",
                                              output / f"{label}.log")
                    validation = validate_index(target, active, args.samples, 42)
                    if baseline is None:
                        baseline = validation["sha256"]
                    if validation["sha256"] != baseline:
                        raise ValueError("Índices distintos entre motores")
                    for name, record in expected.items():
                        if ((target / name).stat().st_size != record["bytes"]
                                or digest_file(target / name) != record["sha256"]):
                            raise ValueError(f"Los activos cambiaron en {name}")
                    if ((target / ".lsdfms-maintenance").exists()
                            or list(target.glob("*.tmp"))):
                        raise ValueError("Mantenimiento no finalizado")
                    row = {"engine": engine, "workers": min(workers, len(sources)),
                           "phase": phase, "repetition": repetition, "seconds": seconds,
                           **usage, "bytes_before": before, "bytes_after": after,
                           "reclaimed": before - after, "active": active, "index_sha256": baseline}
                    writer.writerow(row)
                    csvfile.flush()
                    metadata["commands"][label] = java + operation
                    (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
                    rows.append(row)
                    print(f"{label}: {seconds:.3f}s, {before-after} bytes recuperados, integridad OK",
                          flush=True)
    # Comprueba que el benchmark nunca haya alterado los datos originales.
    if {path.name: digest_file(path) for path in sources} != source_hashes:
        raise ValueError("El dataset fuente cambió durante la medición")
    seq = statistics.median(row["seconds"] for row in rows
                            if row["engine"] == "sequential" and row["phase"] == "measured")
    summary = []
    for engine in ("sequential", "parallel"):
        selected = [row for row in rows if row["engine"] == engine and row["phase"] == "measured"]
        times = [row["seconds"] for row in selected]
        median = statistics.median(times)
        summary.append({"engine": engine, "workers": selected[0]["workers"],
                        "median_seconds": median, "min_seconds": min(times), "max_seconds": max(times),
                        "stdev_seconds": statistics.stdev(times) if len(times) > 1 else 0,
                        "speedup": seq / median, "efficiency": seq / median / selected[0]["workers"],
                        "peak_rss_kib": max((row["rss_kib"] for row in selected
                                            if row["rss_kib"] is not None), default=None)})
    (output / "summary.json").write_text(json.dumps({
        "results": summary, "source_unchanged": True, "bytes_before": before, "bytes_after": after,
        "reclaimed": before - after, "active": active, "deleted_removed": deleted,
        "index_sha256": baseline, "sampled_references_per_run": min(active, args.samples),
        "active_content": "SHA-256 completo por archivo contra oráculo Python",
        "resumed": bool(metadata.get("resumptions"))
    }, indent=2) + "\n")
    (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")


if __name__ == "__main__":
    main()
