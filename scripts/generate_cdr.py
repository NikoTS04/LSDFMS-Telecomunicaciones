#!/usr/bin/env python3
"""Genera CDR Big-Endian en streaming usando exclusivamente la biblioteca estándar."""

import argparse
from decimal import Decimal
import json
from pathlib import Path
import random
import re
import struct
import sys


HEADER = struct.Struct(">Biqq")  # byte, int32, int64, int64; sin padding
DEFAULT_SIZE = 8 * 1024**2
START_TIMESTAMP_MS = 1735689600000  # 2025-01-01T00:00:00Z
TIMESTAMP_SPAN_MS = 30 * 24 * 60 * 60 * 1000
UNITS = {
    "": 1, "B": 1,
    "KB": 1000, "MB": 1000**2, "GB": 1000**3,
    "KIB": 1024, "MIB": 1024**2, "GIB": 1024**3,
}


def positive_int(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("debe ser un entero positivo")
    return number


def size_bytes(value):
    match = re.fullmatch(r"(\d+(?:\.\d+)?)\s*(B|KB|MB|GB|KiB|MiB|GiB)?", value, re.I)
    if not match:
        raise argparse.ArgumentTypeError("usa bytes o unidades B/KB/MB/GB/KiB/MiB/GiB")
    number = Decimal(match[1]) * UNITS[(match[2] or "").upper()]
    if number < 1 or number != int(number):
        raise argparse.ArgumentTypeError("el tamaño debe equivaler a un número entero positivo de bytes")
    return int(number)


def payload_for(rng, length):
    """Texto ASCII (subconjunto UTF-8) con metadatos y tamaño exacto en bytes."""
    prefix = (
        f"service={rng.choice(('VOZ', 'SMS', 'DATA'))};"
        f"cell={rng.randrange(10000, 100000)};"
        f"duration={rng.randrange(3601)};"
        f"roaming={rng.choice(('PE', 'CL', 'CO', 'EC', 'US'))};meta="
    ).encode("utf-8")
    padding = length - len(prefix)
    return prefix + rng.randbytes((padding + 1) // 2).hex().encode("ascii")[:padding]


def parser():
    result = argparse.ArgumentParser(
        description="Genera F_1.dat ... F_N.dat y manifest.json sin cargar el dataset en RAM.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    result.add_argument("--output", type=Path, default=Path("data"), help="directorio vacío (se admite .gitkeep)")
    result.add_argument("--files", type=positive_int, default=8, help="número de archivos")
    mode = result.add_mutually_exclusive_group()
    mode.add_argument("--total-size", type=size_bytes, help="mínimo TOTAL del dataset; por defecto 8MiB")
    mode.add_argument("--records-per-file", type=positive_int, help="cantidad exacta de registros por archivo")
    result.add_argument("--min-payload", type=positive_int, default=128, help="mínimo de bytes por payload (>=64)")
    result.add_argument("--max-payload", type=positive_int, default=512, help="máximo de bytes por payload (<=1MiB)")
    result.add_argument("--deleted-ratio", type=float, default=0.20, help="probabilidad de borrado, entre 0 y 1")
    result.add_argument("--seed", type=int, default=42, help="semilla reproducible")
    return result


def generate(args):
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    if any(path.name != ".gitkeep" for path in output.iterdir()):
        raise ValueError(f"{output} no está vacío; usa otro directorio para no sobrescribir datos o índices")

    rng = random.Random(args.seed)
    total_size = args.total_size
    if total_size is None and args.records_per_file is None:
        total_size = DEFAULT_SIZE
    manifest = {
        "schema_version": 1,
        "format": {"endianness": "big", "header_bytes": HEADER.size, "header_struct": HEADER.format},
        "config": {
            "files": args.files, "seed": args.seed, "total_size": total_size,
            "records_per_file": args.records_per_file,
            "min_payload": args.min_payload, "max_payload": args.max_payload,
            "deleted_ratio": args.deleted_ratio, "start_timestamp_ms": START_TIMESTAMP_MS,
        },
        "files": [],
        "totals": {"bytes": 0, "records": 0, "active": 0, "deleted": 0},
    }
    for file_id in range(1, args.files + 1):
        target = 0
        if total_size is not None:
            quotient, remainder = divmod(total_size, args.files)
            target = quotient + (file_id <= remainder)
        entry = {"file_id": file_id, "name": f"F_{file_id}.dat",
                 "bytes": 0, "records": 0, "active": 0, "deleted": 0}
        # xb también protege frente a una creación concurrente del mismo archivo.
        with (output / entry["name"]).open("xb", buffering=1024**2) as stream:
            while (entry["records"] < args.records_per_file if args.records_per_file is not None
                   else entry["bytes"] < target):
                length = rng.randint(args.min_payload, args.max_payload)
                deleted = int(rng.random() < args.deleted_ratio)
                phone = rng.randrange(51900000000, 52000000000)
                timestamp = START_TIMESTAMP_MS + rng.randrange(TIMESTAMP_SPAN_MS)
                payload = payload_for(rng, length)
                stream.write(HEADER.pack(deleted, len(payload), phone, timestamp))
                stream.write(payload)
                entry["records"] += 1
                entry["deleted"] += deleted
                entry["active"] += 1 - deleted
                entry["bytes"] += HEADER.size + len(payload)
        manifest["files"].append(entry)
        for key in manifest["totals"]:
            manifest["totals"][key] += entry[key]
        print(f"{entry['name']}: {entry['bytes']:,} bytes, {entry['records']:,} registros, "
              f"{entry['deleted']:,} borrados", flush=True)

    # Solo existe un manifiesto al terminar todos los archivos satisfactoriamente.
    with (output / "manifest.json").open("x", encoding="utf-8") as stream:
        json.dump(manifest, stream, indent=2, ensure_ascii=False)
        stream.write("\n")
    totals = manifest["totals"]
    print(f"TOTAL: {totals['bytes']:,} bytes, {totals['records']:,} registros. "
          f"Manifiesto: {output / 'manifest.json'}")
    return manifest


def main():
    cli = parser()
    args = cli.parse_args()
    if not 64 <= args.min_payload <= args.max_payload <= 1024**2:
        cli.error("se requiere 64 <= min-payload <= max-payload <= 1048576")
    if not 0 <= args.deleted_ratio <= 1:
        cli.error("deleted-ratio debe estar entre 0 y 1")
    if args.total_size is not None and args.total_size < args.files:
        cli.error("total-size debe ser al menos el número de archivos")
    try:
        generate(args)
    except (OSError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nGeneración interrumpida; los archivos parciales se conservan sin manifiesto.", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
