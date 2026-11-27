#!/usr/bin/env python3
"""SVG estático desde resultados reales; sin dependencias de gráficos."""
import argparse
import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def svg(title, subtitle, body, footer):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="600" viewBox="0 0 1000 600" role="img" aria-label="{html.escape(title)}">
<rect width="1000" height="600" fill="#fff"/>
<style>text{{font-family:Arial,sans-serif;fill:#172033}} .small{{font-size:16px}}</style>
<text x="45" y="45" font-size="28">{html.escape(title)}</text>
<text x="45" y="78" class="small">{html.escape(subtitle)}</text>
{body}
<text x="45" y="550" class="small">{html.escape(footer)}</text>
<text x="45" y="580" font-size="13">Fuente: docs/benchmarks/ · scripts/presentation_charts.py</text>
</svg>
'''


def generate(output):
    output.mkdir(parents=True, exist_ok=True)
    body = []
    evidence = {}
    for tick in range(6):
        y = 470 - tick * 65
        body.append(f'<path d="M90 {y} H900" stroke="#ddd"/><text x="55" y="{y+5}">{tick}×</text>')
    for i, workers in enumerate((1, 2, 4, 8, 16)):
        body.append(f'<text x="{105+i*185}" y="500">{workers}</text>')
    body.append('<text x="350" y="525">Trabajadores (categorías)</text>')
    for j, (size, color) in enumerate((("64MiB", "#2563eb"), ("512MiB", "#d97706"), ("2GiB", "#15803d"))):
        source = ROOT / f"docs/benchmarks/sprint5-index-{size}/summary.json"
        summary = json.loads(source.read_text())
        rows = sorted((r for r in summary["results"] if r["engine"] == "parallel"),
                      key=lambda r: r["effective_workers"])
        if [r["effective_workers"] for r in rows] != [1, 2, 4, 8, 16]:
            raise ValueError("La serie no tiene los trabajadores efectivos esperados")
        evidence[size] = rows
        points = [(110+i*185, 470-r["speedup"]*65) for i, r in enumerate(rows)]
        if any(y < 120 for _, y in points):
            raise ValueError("Ampliar escala vertical para estos resultados")
        body.append(f'<polyline points="{" ".join(f"{x},{y:.2f}" for x,y in points)}" fill="none" stroke="{color}" stroke-width="3"/>')
        for (x, y), row in zip(points, rows):
            body.append(f'<circle cx="{x}" cy="{y:.2f}" r="5" fill="{color}"/>')
        body.append(f'<text x="{110+j*270}" y="115" fill="{color}" style="fill:{color}">{size} · serie paralela</text>')
    (output / "index-speedup.svg").write_text(svg(
        "INDEX: mejora respecto al baseline secuencial",
        "Medianas de 5 medidas; 1 calentamiento; heap 512 MiB; caché SO no controlada",
        "\n".join(body), "Más trabajadores no siempre ayudan. S = mediana(Tseq) / mediana(Tpar)."),
        encoding="utf-8")
    source = ROOT / "docs/benchmarks/sprint5-large-index/summary.json"
    results = json.loads(source.read_text())
    entries = results[0]["validation"]["entries"]
    values = [("Índice final", entries * 20 / 1024**2, "#2563eb"),
              ("Heap máximo configurado", 512, "#d97706"),
              ("RSS secuencial observado", results[0]["rss_kib"] / 1024, "#475569"),
              ("RSS paralelo observado", results[1]["rss_kib"] / 1024, "#15803d")]
    body = []
    for i, (label, value, color) in enumerate(values):
        y = 160 + i * 85
        body.append(f'<text x="45" y="{y}">{label}</text><rect x="340" y="{y-28}" width="{value/900*570:.2f}" height="45" fill="{color}"/>'
                    f'<text x="{350+value/900*570:.2f}" y="{y}">{value:.1f} MiB</text>')
    (output / "index-vs-heap.svg").write_text(svg(
        "Viabilidad: índice mayor que el heap",
        "Dataset de 4 GiB · ambos motores completaron INDEX y produjeron el mismo hash",
        "\n".join(body), "Tamaño de archivo, límite heap y RSS son magnitudes distintas. Una ejecución por motor."),
        encoding="utf-8")
    (output / "sources.json").write_text(json.dumps({
        "index_series": evidence, "large_index": values,
        "notes": "Valores copiados de resultados reales; sin proyecciones ni mediciones de demo."
    }, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "docs/exposicion/graficos")
    generate(parser.parse_args().output)
