# LSDFMS · Telecomunicaciones

Base para un motor de gestión de archivos CDR masivos en **Java 17+, sin dependencias externas**.
La [especificación técnica](docs/especificacion-tecnica.md) describe el objetivo completo;
el sprint 3 añade INDEX paralelo al baseline INDEX/PACK, búsqueda y recuperación.

## Documentación y planificación

- [Plan de cinco sprints hasta la entrega completa](docs/plan-sprints.md).
- [Contrato, límites y evidencia del sprint 1](docs/sprint-1.md).
- [PACK, exclusión y recuperación del sprint 2](docs/sprint-2.md).
- [Contrato de INDEX paralelo y medición del sprint 3](docs/sprint-3.md).
- [Resultados y evidencia del hito del 50%](docs/resultados-50.md).
- [Skills recomendadas y criterios de selección](docs/skills-recomendadas.md).
- [Dónde se utilizaría el motor, con un ejemplo de telecomunicaciones](docs/aplicaciones-del-motor.md).

## Requisitos

- JDK 17 o superior (`java` y `javac`).
- Python 3.10 o superior, únicamente biblioteca estándar.
- GNU Make para los comandos abreviados (opcional).

## Inicio rápido

```sh
make build
make generate  # 8 archivos, al menos 8 MiB en total; no sobrescribe datos
make inspect   # lee las cabeceras en Java y muestra totales
make test      # pruebas Python e interoperabilidad Python → Java
```

Sin Make, desde la raíz del repositorio:

```sh
mkdir -p build/classes
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d build/classes $(find src -name '*.java')
python3 scripts/generate_cdr.py --output data --files 8 --total-size 8MiB
java -cp build/classes com.hpc.lsdfms.Main inspect data
python3 -m unittest discover -s tests -v
```

## INDEX y búsqueda (sprint 1)

Después de `make generate`:

```sh
java -cp build/classes com.hpc.lsdfms.Main index data
java -cp build/classes com.hpc.lsdfms.Main search data 51987654321
```

`index` omite borrados y ordena tuplas completas por teléfono, fileId y offset;
reemplaza el índice anterior solo tras escritura completa, cierre y movimiento
atómico. `search` devuelve todas las coincidencias verificadas en TSV, seguidas de
`TOTAL`; cero coincidencias es éxito. El payload se muestra como UTF-8 con escapes
para barra inversa, tabulaciones y saltos de línea.

Salidas: `0` éxito, `1` error de datos/I/O o presupuesto de memoria, `2` argumentos
inválidos. Las operaciones se excluyen mediante un bloqueo cooperativo por dataset.
No modificar los `.dat` externamente entre indexación y consulta; reconstruir el
índice después de cualquier cambio.

La ordenación es en memoria: dos pasadas de cabeceras y arreglos primitivos
con límite conservador de un cuarto del heap máximo para entradas. Si se excede,
se informa error sin sustituir el índice. La ordenación externa
pertenece al sprint 5. Una consulta materializa solo
un payload a la vez (límite individual de un cuarto del heap máximo).
Ver [contrato y límites](docs/sprint-1.md).

## PACK y recuperación (sprint 2)

PACK modifica los archivos: usar una copia del dataset si se necesita conservar
los originales. El `manifest.json` del generador se conserva como evidencia de la
generación, no se actualiza y ya no describe los tamaños/conteos tras compactar.

```sh
java -cp build/classes com.hpc.lsdfms.Main pack data
# Solo si quedó mantenimiento pendiente tras un error:
java -cp build/classes com.hpc.lsdfms.Main recover data
```

`pack` elimina físicamente los borrados, conserva los bytes/orden de los activos
y reconstruye INDEX automáticamente. Imprime bytes antes/después/recuperados y
activos. Repetirlo sin nuevos borrados recupera cero bytes.

Todas las operaciones (incluidas búsquedas e inspección) usan `.lsdfms.lock`;
si otro proceso/hilo posee el bloqueo, fallan sin esperar. **No borrar ese archivo**.
El marcador persistente `.lsdfms-maintenance` bloquea INDEX y búsquedas hasta
completar la recuperación. `inspect` permite diagnosticar bajo exclusión.

Ante fallo, corregir espacio/permisos/datos y ejecutar `recover`; no quitar el
marcador manualmente. El reemplazo es atómico por archivo, no por dataset.
Los temporales de una caída abrupta no se reutilizan ni se borran automáticamente.
Ver [procedimiento, garantías y límites](docs/sprint-2.md).

## INDEX paralelo (sprint 3)

```sh
java -Xmx1g -cp build/classes com.hpc.lsdfms.Main index-parallel data 4
```

Acepta 1–256 trabajadores, limitados al número de archivos. Cuenta y extrae
cabeceras por archivo, ordena runs de tuplas primitivas en paralelo y las mezcla
con un arreglo auxiliar. Produce el mismo `index_phone.idx` que el baseline.
Los errores cancelan tareas y esperan su terminación antes de liberar el dataset.
Rechaza mantenimiento pendiente y conserva el índice anterior si no puede terminar.

El presupuesto de memoria incluye ambos juegos de arreglos (40 bytes por activo
como máximo, más buffers/JVM fuera de ese presupuesto). La ordenación externa
sigue pendiente. PACK/recover todavía usan el motor secuencial.

Comparativa reproducible en un directorio de resultados **nuevo**:

```sh
make build
python3 scripts/generate_cdr.py --output data/sprint3-2gib --files 8 --total-size 2GiB --deleted-ratio 0.20 --seed 42
python3 scripts/benchmark_index.py data/sprint3-2gib --output docs/benchmarks/mi-ejecucion --workers 4 --heap 1g --warmups 1 --repetitions 5
```

No ejecutar PACK ni otros procesos sobre ese dataset durante el benchmark.
El script guarda entorno, comandos, tiempos crudos, RSS mediante POSIX wait4
(o GNU time si está disponible),
hashes, validación de orden/conteos y muestreo reproducible de referencias.
No vacía la caché del SO. Los tiempos incluyen el arranque de una JVM por ejecución,
ambas pasadas, ordenación, escritura y `force`; las verificaciones quedan fuera.
La generación masiva y el benchmark son explícitos, nunca parte de `make test`.

## Generación de datos

Dataset para el hito del 50% (**8 archivos, al menos 2 GiB en total**, aproximadamente 20% borrados):

```sh
python3 scripts/generate_cdr.py --output data/2gib --files 8 --total-size 2GiB --deleted-ratio 0.20 --seed 42
java -cp build/classes com.hpc.lsdfms.Main inspect data/2gib
```

Para 10 GiB cambia `--total-size 2GiB` por `--total-size 10GiB`. Reserva espacio
adicional para temporales de `PACK` y archivos de índice.
No se generan datasets masivos automáticamente al compilar o ejecutar pruebas.

Muestra de tamaño exacto en registros:

```sh
python3 scripts/generate_cdr.py --output data/sample --files 2 --records-per-file 1000 --min-payload 64 --max-payload 1024
python3 scripts/generate_cdr.py --help
```

- `--total-size` y `--records-per-file` son excluyentes. Sin ambos se generan 8 MiB.
- El tamaño se distribuye entre archivos; cada archivo termina un registro completo.
  El exceso total es menor que `files × (21 + max-payload)` bytes.
- Unidades decimales `KB/MB/GB` y binarias `KiB/MiB/GiB`; sin unidad se interpretan bytes.
- Payloads entre 64 bytes y 1 MiB, texto UTF-8 con servicio VOZ/SMS/DATA, antena,
  duración, país y metadatos. Los valores por defecto son 128–512 bytes.
- Borrado con probabilidad independiente: 20% es aproximado, no una cuota exacta.
- Números sintéticos peruanos; las claves pueden repetirse. No son datos de clientes reales.
- Misma semilla y configuración producen los mismos archivos en el mismo entorno Python.
- Escritura con buffer de 1 MiB, sin acumular registros en RAM. Memoria independiente
  del tamaño total, salvo la pequeña lista de estadísticas por archivo.
- `manifest.json` registra configuración, formato, conteos y tamaños reales. No forma
  parte de los archivos binarios ni introduce cabeceras de archivo.
- El destino debe estar vacío (se admite `.gitkeep`). No hay `--force`: se protegen
  datasets e índices existentes. Para repetir, usa un directorio nuevo.
- Si falla o se interrumpe la generación, pueden quedar archivos parciales. Sin un
  manifiesto completo no se considera un dataset terminado; no hay reanudación automática.

## Contrato binario

Big-Endian, sin alineación ni cabecera global. Cada registro:

| Offset | Campo | Bytes |
| --- | --- | --- |
| 0 | `isDeleted` (`0` o `1`) | 1 |
| 1 | `recordLength` (longitud **solo** del payload, int32) | 4 |
| 5 | `phoneNumber` (int64) | 8 |
| 13 | `timestamp` (int64, epoch **milisegundos**) | 8 |
| 21 | `payload` (UTF-8) | `recordLength` |

Python: `struct.Struct(">Biqq")`. Java: `CdrFormat`, `FileChannel` y buffer directo reutilizable.
El formato del índice es `long phoneNumber + int fileId + long byteOffset`
(20 bytes); ambos motores lo construyen utilizando la serialización de `IndexEntry`.

`inspect` escanea únicamente archivos `F_<id>.dat` directamente dentro del directorio
indicado (no recursivamente). Valida flags, longitudes y truncamientos, sin leer ni
validar el contenido UTF-8 del payload. `search` verifica las referencias encontradas,
pero no certifica por escaneo completo la integridad de un índice externo.
No se deben modificar archivos durante la inspección.

## Estructura

```text
docs/especificacion-tecnica.md   Objetivos y criterios de aceptación
scripts/generate_cdr.py          Generador streaming reproducible
scripts/benchmark_index.py       Comparativa y verificación del hito del 50%
src/com/hpc/lsdfms/
  Main.java                     CLI: inspect, index, index-parallel, search, pack, recover, --help
  model/                        Contrato CDR y serialización del índice
  io/                           Escaneo, copia acotada, compactación y bloqueo de dataset
  core/                         INDEX secuencial/paralelo, PACK secuencial y recuperación
  search/                       Búsqueda binaria de rangos con verificación
tests/                          Pruebas sin frameworks externos
data/                           Datasets locales ignorados por Git
build/                          Clases compiladas ignoradas por Git
```

## Estado y siguientes hitos

- [x] Estructura compilable con JDK 17, sin Maven/Gradle ni librerías externas.
- [x] Generador configurable para datasets de 2–10 GiB; validación con muestras pequeñas.
- [x] Inspección Java de archivos generados y pruebas automatizadas del contrato.
- [x] Dataset de al menos 2 GiB generado y validado en el equipo disponible.
- [x] `SequentialEngine`: `INDEX` de referencia.
- [x] `SequentialEngine`: `PACK` de referencia, recuperación e integración con INDEX.
- [x] `ParallelEngine`: `INDEX` concurrente y ordenación sobre primitivos.
- [x] Búsqueda binaria y verificación de `fileId`/`byteOffset`.
- [ ] `PACK` paralelo, reemplazo atómico e invalidación/regeneración de índices.
- [ ] Ordenación externa para índices mayores que la RAM y benchmarks de escalabilidad.

El sprint 3 completa los criterios técnicos del **hito del 50%** en este equipo,
con pruebas y mediciones reales. No representa la entrega final: siguen pendientes
PACK paralelo, ordenación externa y análisis completo de escalabilidad.
