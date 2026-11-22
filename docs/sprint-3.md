# Sprint 3 · INDEX paralelo

Rama: `feat/sprint-3-parallel-index`, basada en sprint 2.

## Contrato

- `index <directorio>` conserva el baseline secuencial.
- `index-parallel <directorio> <trabajadores>` admite 1–256 trabajadores y usa
  como máximo `min(trabajadores, archivos)` hilos propios de un `ExecutorService`.
- Conteo y extracción por archivo, canales/buffers propios y rangos de destino
  disjuntos. Ordenación de tuplas completas sobre primitivos: heapsort de runs
  en paralelo y merge por rondas con arreglo auxiliar.
- Mismo orden `(phoneNumber, fileId, byteOffset)` y mismos bytes que el baseline.
- Conserva exclusión, rechazo de mantenimiento pendiente y publicación atómica.
- Ante fallo, cancela tareas pendientes, espera que terminen y solo entonces
  libera el bloqueo. No publica índices parciales.
- Memoria: como máximo un cuarto del heap para arreglos del índice y su auxiliar;
  si no cabe se informa error, sin ordenación externa (sprint 5).
- No implementa PACK paralelo; PACK y recuperación siguen siendo secuenciales.

## Medición

Se generará un dataset nuevo de 8 archivos, ≥2 GiB, semilla 42, borrado aproximado
del 20%. No se compacta ese dataset durante las mediciones de INDEX.
El script de benchmark guardará entorno, configuración, tiempos crudos, integridad
y medianas. Un calentamiento y cinco mediciones por configuración, sin manipular
la caché del SO. No se exige speedup positivo.

## Cierre

`make test`: **45 pruebas correctas** (36 previas y 9 nuevas), sin dependencias
externas ni warnings. Casos nuevos:

- Igualdad byte a byte y oráculo completo con 1/2/3/4/8 trabajadores.
- Runs impares, vacíos, un solo archivo, duplicados y claves int64 extremas.
- Offsets >32 bits y rechazo del exceso de presupuesto con heap de 32 MiB.
- Argumentos inválidos, fuentes corruptas, mantenimiento pendiente y error
  de publicación; conservación del índice anterior y limpieza de temporales.
- Fallos coordinados en conteo y extracción: se observa la excepción original,
  se interrumpe al compañero y se termina el executor antes de liberar el lock.
- Interrupción del llamador conservando su flag y cancelación durante ordenación.
- Bloqueo entre procesos también sobre `index-parallel`.
- Benchmark completo con fixture pequeño y rechazo de sobrescritura de informes.

Dataset de ≥2 GiB generado y validado; ver [resultados del 50%](resultados-50.md)
para los tiempos y límites de la medición. Los fallos se probaron sin alterar
el dataset masivo, sobre fixtures desechables.

Medianas sobre 5 ejecuciones: **11,696 s secuencial / 4,614 s paralelo con 4
trabajadores**, speedup **2,535×**, eficiencia por trabajador **63,37%**.
Los 12 índices (incluidos calentamientos) tienen el mismo SHA-256.
El hito del 50% queda validado en este equipo, sin adelantar los requisitos del 100%.
