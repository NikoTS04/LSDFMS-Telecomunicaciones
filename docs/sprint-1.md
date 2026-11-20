# Sprint 1 · Contrato y validación

## Contrato acordado

- `index <directorio>` construye `index_phone.idx`, omitiendo borrados y
  ordenando por `(phoneNumber, fileId, byteOffset)`, todos ascendentes.
- `search <directorio> <phoneNumber>` devuelve todas las coincidencias, verificadas
  contra el archivo fuente, en filas TSV con teléfono, fileId, offset, timestamp
  y payload UTF-8 escapado (`\\`, `\t`, `\r`, `\n`). Al final imprime `TOTAL`.
- Salida de proceso: 0 para éxito (incluso sin coincidencias), 2 para argumentos
  inválidos, 1 para errores de datos/I/O o límite de memoria del índice.
- `SequentialEngine.index(Path)` devuelve el número de entradas publicadas.
- `BinaryIndexSearch.search(Path, long, consumer)` devuelve el número de resultados
  y los entrega uno por uno; no acumula los payloads de todas las coincidencias.
- Archivos fuente: `F_<id>.dat` directamente en el directorio, id entero positivo
  de 32 bits, sin ceros iniciales. Directorio sin archivos: error; archivos vacíos:
  válidos. No se siguen enlaces simbólicos de archivos fuente.
- Índice: entradas de 20 bytes Big-Endian, sin cabecera; offset de 64 bits al inicio
  del registro. Publicación con temporal en el mismo directorio, cierre y movimiento
  atómico; si no está soportado, falla sin reemplazo no atómico.

## Límites del incremento

El dataset debe permanecer inmutable y un único proceso debe ejecutar las operaciones
sin solaparlas. No se implementan aún bloqueos, PACK, recuperación de mantenimiento,
paralelismo ni ordenación externa. El índice se ordena en memoria con arreglos
primitivos y heapsort in-place; el presupuesto de entradas se limita conservadoramente
según el heap máximo. Si no cabe, se falla sin sustituir el índice anterior.

Una consulta valida tamaño del índice (múltiplo de 20), referencias y cabeceras
de las coincidencias. No escanea todo el índice para certificar orden o detectar
cambios externos: hay que reconstruirlo después de modificar los `.dat`.
El offset de un índice externo no confiable no certifica por sí solo una frontera
de registro. Los índices consumidos deben proceder de este motor sobre datos inmutables.
Los payloads se entregan individualmente en memoria; la API binaria conserva bytes,
y la CLI los representa como UTF-8, sustituyendo secuencias inválidas.

## Cierre y evidencia

Rama de desarrollo: `feat/sprint-1-index-search`.

- Las 9 pruebas iniciales del sprint fallaron porque no existían los comandos.
- Tras implementar y ampliar casos límite, `make test` pasa **28 pruebas**
  (16 previas y 12 nuevas), compilando con `--release 17 -Xlint:all -Werror`.
- El oráculo Python compara todas las tuplas, no solo claves: 4 archivos con IDs
  no consecutivos, incluido `2147483647`, y registros aleatorios con duplicados.
- Probados tamaños pequeños del heapsort, extremos int64, vacíos/todos borrados,
  inexistencia de clave, escapes de payload, argumentos inválidos y referencias
  corruptas (archivo, offset, estado, clave y truncamientos).
- Un archivo disperso comprueba lectura e indexación con offset **4294967336**,
  superior a 32 bits, sin materializar los dos payloads borrados gigantes.
- Con `-Xmx32m` y 500.000 activos, INDEX rechaza el exceso de presupuesto sin
  `OutOfMemoryError` ni reemplazo del índice anterior.
- Los errores de lectura y publicación conservan el índice/destino existente
  y no dejan temporales de esta ejecución. Se rechazan enlaces simbólicos.
- `git diff --check` no detecta errores de espacios.

### Demo ejecutada con el generador del repositorio

Muestra creada en un directorio temporal nuevo y eliminada al finalizar. No se
alteraron datasets existentes. Comandos equivalentes para repetir en un destino vacío:

```sh
make build
python3 scripts/generate_cdr.py --output data/sprint1-demo --files 8 --records-per-file 1000 --seed 42
java -cp build/classes com.hpc.lsdfms.Main inspect data/sprint1-demo
java -cp build/classes com.hpc.lsdfms.Main index data/sprint1-demo
java -cp build/classes com.hpc.lsdfms.Main search data/sprint1-demo 51900000889
```

Resultados observados:

| Métrica | Valor |
| --- | --- |
| Archivos fuente | 8 |
| Bytes fuente | 2.736.795 |
| Registros | 8.000 |
| Activos / borrados | 6.365 / 1.635 |
| Entradas del índice | 6.365 |
| Bytes del índice | 127.300 = 6.365 × 20 |
| Comparación completa con oráculo Python | Todas las tuplas coinciden |
| Consulta de `51900000889` | 1 coincidencia, archivo 4, offset 302353 |
| Timestamp de esa coincidencia | 1737428006154 |

No son benchmarks de rendimiento ni validación del hito del 50%. No se simularon
caída del SO ni falta de soporte de `ATOMIC_MOVE`: el código propaga ese error sin
fallback; las pruebas de publicación cubren un destino no reemplazable.
