#!/usr/bin/env python3
"""Demo reproducible, sin frameworks ni cambios en datasets existentes."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HEADER = struct.Struct(">Biqq")
ENTRY = struct.Struct(">qiq")


def records(directory):
    for path in sorted(directory.glob("F_*.dat")):
        raw = path.read_bytes()  # Solo dataset pequeño de exposición.
        offset = 0
        while offset < len(raw):
            flag, length, phone, timestamp = HEADER.unpack_from(raw, offset)
            end = offset + HEADER.size + length
            if flag not in (0, 1) or length < 0 or end > len(raw):
                raise ValueError("Fixture inválido")
            yield path, offset, flag, phone, timestamp, raw[offset:end]
            offset = end


def oracle(directory):
    active = {}
    entries = []
    for path, offset, flag, phone, _, raw in records(directory):
        active.setdefault(path.name, bytearray())
        if not flag:
            active[path.name].extend(raw)
            entries.append((phone, int(path.stem[2:]), offset))
    return {name: bytes(raw) for name, raw in active.items()}, sorted(entries)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--auto", action="store_true", help="Sin pausas")
    parser.add_argument("--recovery", action="store_true", help="Incluye fallo controlado")
    parser.add_argument("--output", type=Path, help="Directorio NUEVO; por defecto uno único en build/demos")
    args = parser.parse_args()
    if not args.auto and not sys.stdin.isatty():
        parser.error("Usar --auto cuando no hay terminal interactiva")
    for tool in ("java", "javac"):
        if not shutil.which(tool):
            parser.error(f"Falta {tool}: instalar JDK 17+")
    if args.output:
        args.output.mkdir(parents=True, exist_ok=False)
        output = args.output.resolve()
    else:
        base = ROOT / "build/demos"
        base.mkdir(parents=True, exist_ok=True)
        output = Path(tempfile.mkdtemp(prefix="exposicion-", dir=base))
    classes = output / "classes"
    classes.mkdir()
    log = output / "transcript.txt"
    events = []

    def say(text):
        print(text, flush=True)
        with log.open("a", encoding="utf-8") as stream:
            stream.write(text + "\n")

    def stage(title, explanation):
        say(f"\n=== {title} ===\n{explanation}")
        if not args.auto:
            input("Enter para continuar...")

    def run(command, expected=0):
        result = subprocess.run(list(map(str, command)), capture_output=True, text=True, timeout=60,
                                encoding="utf-8")
        events.append({"command": list(map(str, command)), "returncode": result.returncode})
        with log.open("a", encoding="utf-8") as stream:
            stream.write("$ " + " ".join(map(str, command)) + "\n" + result.stdout + result.stderr)
        if result.returncode != expected:
            raise RuntimeError(f"Resultado inesperado; revisar {log}\n{result.stderr}")
        return result.stdout

    java = ["java", "-Dfile.encoding=UTF-8", "-Xmx128m", "-cp", str(classes)]

    def cli(*command, external=False, expected=0):
        flags = ["-Dlsdfms.index.mode=external", "-Dlsdfms.index.memoryMiB=4",
                 "-Dlsdfms.index.chunkEntries=128", "-Dlsdfms.index.fanIn=2"] if external else []
        return run(java[:1] + flags + java[1:] + ["com.hpc.lsdfms.Main", *command], expected)

    say(f"LSDFMS · DEMOSTRACIÓN\nArtefactos conservados en: {output}")
    sources = sorted((ROOT / "src").rglob("*.java")) + sorted((ROOT / "demo/java").rglob("*.java"))
    run(["javac", "--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-Werror",
         "-d", classes, *sources])
    stage("1 · DATOS", "4 archivos × 250 registros; semilla 42; borrado aproximado del 20%.")
    data = output / "data"
    run([sys.executable, ROOT / "scripts/generate_cdr.py", "--output", data,
         "--files", "4", "--records-per-file", "250", "--seed", "42"])
    # Fixture explícito de duplicados, sin cambiar flags, longitudes ni payloads.
    candidates = [record for record in records(data) if record[2] == 0]
    deleted_seen = set()
    selected = None
    for record in records(data):
        if record[2]:
            deleted_seen.add(record[0])
        elif record[0] in deleted_seen:
            selected = record
            break
    if selected is None:
        raise AssertionError("La muestra no permite mostrar un cambio de offset")
    phone = selected[3]
    targets = [selected] + [r for r in candidates if (r[0], r[1]) != (selected[0], selected[1])][:2]
    for path, offset, *_ in targets:
        with path.open("r+b") as stream:
            stream.seek(offset + 5)
            stream.write(struct.pack(">q", phone))
    say(f"Fixture didáctico: mismo teléfono {phone} en 3 activos.")
    say(cli("inspect", data).strip())
    active, expected_entries = oracle(data)
    before = sum(path.stat().st_size for path in data.glob("F_*.dat"))
    deleted = sum(1 for r in records(data) if r[2])
    (output / "fixture.json").write_text(json.dumps({
        "seed": 42, "duplicate_phone": phone, "modified_headers": [
            {"file": r[0].name, "offset": r[1]} for r in targets]}, indent=2) + "\n")

    def verify_index(expected_entries):
        actual = list(ENTRY.iter_unpack((data / "index_phone.idx").read_bytes()))
        if actual != expected_entries:
            raise AssertionError("Índice distinto del oráculo")
        return hashlib.sha256((data / "index_phone.idx").read_bytes()).hexdigest()

    stage("2 · INDEX", "Baseline y paralelo deben producir exactamente las mismas tuplas.")
    cli("index", data)
    baseline = verify_index(expected_entries)
    cli("index-parallel", data, 4)
    if verify_index(expected_entries) != baseline:
        raise AssertionError("Índices no equivalentes")
    say(f"Índices idénticos: {len(expected_entries)} entradas. SHA-256: {baseline}")
    stage("3 · CONSULTA", "Búsqueda de todas las coincidencias de un teléfono conocido.")
    result = cli("search", data, phone)
    say("Teléfono\tArchivo\tOffset\tTimestamp")
    for row in result.splitlines()[1:-1]:
        say("\t".join(row.split("\t", 4)[:4]))
    say(result.splitlines()[-1] + " · payloads completos en transcript.txt")
    before_reference = next(entry for entry in expected_entries
                            if entry[1:] == (int(selected[0].stem[2:]), selected[1]))
    before_events = sorted((r[0].name, r[4], r[5][21:]) for r in records(data)
                           if not r[2] and r[3] == phone)
    stage("4 · RUTA EXTERNA", "Ruta forzada: bloques de 128 entradas, fan-in 2. No es una prueba >RAM.")
    cli("index-parallel", data, 4, external=True)
    if verify_index(expected_entries) != baseline:
        raise AssertionError("Índice externo no equivalente")
    say("Ruta externa verificada contra el índice en memoria y el oráculo.")
    stage("5 · PACK → INDEX", "Se conservan todos los bytes activos; cambian las posiciones físicas.")
    say(cli("pack-parallel", data, 4).strip())
    for name, expected_bytes in active.items():
        if (data / name).read_bytes() != expected_bytes:
            raise AssertionError("PACK alteró activos")
    _, packed_entries = oracle(data)
    packed_hash = verify_index(packed_entries)
    after_events = sorted((r[0].name, r[4], r[5][21:]) for r in records(data)
                          if not r[2] and r[3] == phone)
    if before_events != after_events:
        raise AssertionError("Consulta perdió eventos")
    after_offset = next(r[1] for r in records(data) if r[0].name == selected[0].name
                        and r[4] == selected[4] and r[5][21:] == selected[5][21:])
    if after_offset >= before_reference[2]:
        raise AssertionError("No se mostró el cambio de offset")
    cli("search", data, phone)
    after = sum(path.stat().st_size for path in data.glob("F_*.dat"))
    say(f"ANTES: {before} bytes; {len(expected_entries)} activos; {deleted} borrados.")
    say(f"DESPUÉS: {after} bytes; mismos activos; 0 borrados.\nRECUPERADOS: {before-after} bytes.")
    say(f"Mismo CDR en {selected[0].name}: offset {before_reference[2]} → {after_offset}.")
    say(cli("pack-parallel", data, 4).strip())
    if verify_index(packed_entries) != packed_hash:
        raise AssertionError("PACK no idempotente")
    if args.recovery:
        stage("6 · RECUPERACIÓN", "Fallo inyectado real en una copia desechable; no es un corte eléctrico.")
        failure_data = output / "failure"
        failure_data.mkdir()
        for path in data.glob("F_*.dat"):
            shutil.copyfile(path, failure_data / path.name)
        say(run(java + ["com.hpc.lsdfms.core.RecoveryDemo", failure_data]).strip())
        if not (failure_data / ".lsdfms-maintenance").is_file():
            raise AssertionError("Falta marcador de mantenimiento")
        cli("search", failure_data, phone, expected=1)
        say("Consulta rechazada mientras hay mantenimiento pendiente.")
        say(cli("recover-parallel", failure_data, 2).strip())
        cli("search", failure_data, phone)
        for path in data.glob("F_*.dat"):
            if path.read_bytes() != (failure_data / path.name).read_bytes():
                raise AssertionError("Recuperación alteró activos")
        say("Consulta habilitada tras recuperación; activos intactos.")
    report = {"ok": True, "phone": phone, "active": len(expected_entries), "deleted_before": deleted,
              "bytes_before": before, "bytes_after": after, "reclaimed": before-after,
              "offset_before": before_reference[2], "offset_after": after_offset,
              "memory_parallel_external_sha256": baseline, "packed_sha256": packed_hash,
              "recovery": args.recovery, "commands": events}
    (output / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    say(f"\nDEMO CORRECTA · resumen y transcripción: {output}")


if __name__ == "__main__":
    main()
