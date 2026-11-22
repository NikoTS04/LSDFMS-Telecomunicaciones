# Hito del 50% · Resultados de INDEX

## Alcance

Se valida el hito técnico de §5.1 de la especificación en el equipo disponible.
INDEX/PACK secuenciales proceden de los sprints 1–2; este incremento añade INDEX
paralelo, equivalencia, cancelación y evidencia masiva. No implica entrega final:
PACK paralelo, ordenación externa y escalabilidad completa siguen pendientes.

## Dataset y entorno

- 8 archivos nuevos, semilla 42, payload de 128–512 bytes.
- **2.147.485.228 bytes**, 6.297.625 registros.
- Activos: **5.038.289**; borrados: **1.259.336** (~20%).
- Índice: **100.765.780 bytes**, exactamente `activos × 20`.
- Equipo: AMD Ryzen 7 7735HS, 8 núcleos físicos / 16 lógicos, ~13,33 GiB RAM.
- Linux CachyOS, almacenamiento NVMe WD PC SN5000S, sistema de archivos Btrfs.
- OpenJDK **27.0.1.1**, código compilado con `javac --release 17`.
- Heap máximo: `-Xmx1g`. Presupuesto de arreglos: un cuarto del heap; el paralelo
  incluye principal y auxiliar. Buffers: 21 bytes directos por escaneo de archivo
  y 64 KiB directos para la escritura final.
- Dataset local en `data/sprint3-2gib`, ignorado por Git y conservado sin compactar.
  Se versiona su manifiesto, no los binarios. La generación no se cronometró.

## Método

```sh
make build
python3 scripts/generate_cdr.py --output data/sprint3-2gib --files 8 --total-size 2GiB --deleted-ratio 0.20 --seed 42
python3 scripts/benchmark_index.py data/sprint3-2gib --output docs/benchmarks/sprint3-2gib-posix --workers 4 --heap 1g --warmups 1 --repetitions 5 --samples 1000
```

Los destinos deben ser nuevos; no se sobrescriben datos ni informes existentes.

- Una JVM nueva por ejecución; 1 calentamiento y 5 ejecuciones medidas por motor.
- Alternancia secuencial/paralelo en cada repetición, siempre en ese orden.
- Tiempo wall desde arranque de proceso hasta terminación, incluyendo bloqueo,
  dos pasadas de cabeceras, ordenación, escritura, `force` y cierre.
- Generación, validación, hash y consultas quedan fuera del intervalo medido.
- Caché del SO **no controlada**, con calentamiento previo y datos recién generados;
  no representa rendimiento de disco frío. Equipo no aislado del resto del sistema.
- 4 trabajadores solicitados y efectivos, por debajo de 16 CPUs lógicas disponibles.
- `S = mediana(Tseq) / mediana(Tpar)`; `E = S / 4`.
  Se informa además `S / 16` para la definición literal por núcleos lógicos.
- RSS por PID mediante `os.wait4` (biblioteca estándar), distinto del heap máximo.

## Resultados

Ejecución con RSS:
[`benchmarks/sprint3-2gib-posix/summary.json`](benchmarks/sprint3-2gib-posix/summary.json).

| Motor | Trabajadores | Mediana (s) | Mín–máx (s) | Desviación (s) | S | S/p | S/16 | RSS máximo (KiB) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Secuencial | 1 | 11,696 | 11,563–11,799 | 0,106 | 1,000 | 100% | 6,25% | 169.600 |
| Paralelo | 4 | 4,614 | 4,603–4,795 | 0,081 | **2,535** | **63,37%** | **15,84%** | 265.628 |

Cada ejecución produjo 5.038.289 entradas con integridad correcta.
Heap configurado: 1 GiB para ambos; RSS observado ~165,6 / 259,4 MiB. El temporal
de publicación tiene 100.765.780 bytes; mientras coexiste con el índice anterior,
los dos suman 201.531.560 bytes, además del dataset. Este tamaño se deriva del
formato, no de un perfil continuo de ocupación del disco.

Con 4 trabajadores el tiempo wall cae ~60,5%, a costa de más memoria y CPU
acumulada: los tiempos user+system por proceso están en
[`runs.csv`](benchmarks/sprint3-2gib-posix/runs.csv). No se mide solo tiempo CPU
ni se atribuye toda la mejora a la lectura física del NVMe.

Se conserva también la primera comparativa exploratoria en
[`benchmarks/sprint3-2gib/`](benchmarks/sprint3-2gib/): mediana secuencial 11,812 s,
paralela 4,767 s, speedup 2,478×. No había GNU time, por lo que sus campos RSS/CPU
son desconocidos (`null` o vacíos), **no cero**. No se mezclan ambas series.

## Integridad

- En cada ejecución: recorrido completo del índice comprobando orden estricto de
  tuplas y conteo, además de SHA-256 idéntico al baseline.
- 1.000 referencias muestreadas por ejecución, semilla 42, validando archivo,
  offset, flag activo, longitud y teléfono del registro fuente.
  Es la misma muestra en las repeticiones: no son 12.000 referencias distintas.
- 10 consultas por búsqueda binaria real al finalizar, incluida una clave duplicada.
- Hash compartido:
  `0144b024eea462517d2bd581eb2d161a3e52d4dc04a1492081c07d3b5f9d6fd9`.
- Fixtures pequeños: oráculo independiente de todas las tuplas y pruebas de
  duplicados, IDs no consecutivos, extremos int64 y offsets superiores a 32 bits.

No se valida exhaustivamente cada puntero del dataset masivo mediante lectura
aleatoria: el recorrido de orden/conteos es completo y los punteros se muestrean.

## Límites e interpretación

El pool se limita por archivos; los escaneos pueden verse limitados por I/O y por
millones de lecturas pequeñas. Las runs se ordenan con heapsort y la última mezcla
tiene una única tarea. Escritura/publicación final son secuenciales. El paralelo
también consume un arreglo auxiliar que el baseline no necesita; no se compara
únicamente “el mismo algoritmo con más hilos”.

No se ha perfilado cada fase ni demostrado qué recurso domina. No extrapolar
resultados a discos fríos, otra JVM, más hilos o datasets mayores que RAM.
La matriz 1/2/4/8/16 y perfiles CPU/I/O corresponden al sprint 5.

## Artefactos reproducibles

- `metadata.json`: entorno, comandos exactos, manifiesto y hashes de fuentes/clases.
- `runs.csv`: tiempos crudos, recursos, hashes y conteos (warmups separados).
- `*.time.json` y `*.log`: recursos y salida por ejecución.
- `summary.json`: medianas, dispersión, S/E y consultas verificadas.
- [Contrato y pruebas](sprint-3.md).

Los metadatos registran el commit base y árbol con cambios porque se midió antes
del commit de cierre; los hashes de fuentes y clases identifican la versión medida.
