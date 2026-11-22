# Plan de sprints · LSDFMS Telecomunicaciones

## Objetivo y supuestos

Completar los criterios obligatorios de la [especificación técnica](especificacion-tecnica.md),
sin añadir frameworks ni dependencias al motor Java 17. Los índices secundarios son
un extra y no bloquean la entrega.

Propuesta: **5 sprints de una semana**, para una persona con dedicación suficiente.
Es una estimación, no una fecha comprometida: ajustar al conocer disponibilidad,
hardware y resultados del primer sprint. Los responsables se asignarán al iniciar
cada sprint. No se requiere desarrollar interfaz web, API ni facturación.

## Punto de partida verificado (antes del sprint 1)

- Generador Python reproducible y streaming, contrato binario e inspección Java.
- `IndexEntry` serializa entradas, pero todavía no construye índices.
- `core/` y `search/` contienen únicamente documentación de paquetes.
- `Main` admite `inspect` y `--help`.
- `make test`: **16 pruebas correctas**, compilación con `-Xlint:all -Werror`.
- No se ha validado en esta revisión un dataset de 2 GiB ni medido escalabilidad.

Esta base **no satisface aún el hito del 50%**.

## Calendario y dependencias

| Sprint | Resultado demostrable | Dependencia | Hito |
| --- | --- | --- | --- |
| 1 | INDEX secuencial y búsqueda verificable | Base actual | Base funcional |
| 2 | PACK secuencial y publicación segura de índices | Sprint 1 | Baseline completo |
| 3 | INDEX paralelo y comparativa sobre ≥2 GiB | Sprints 1–2 | 50% |
| 4 | PACK paralelo integrado con INDEX y recuperación | Sprints 2–3 | Funcionalidad final |
| 5 | Ordenación externa, escala y entrega documentada | Sprints 3–4 | 100% |

Las cifras 50% y 100% representan los hitos de aceptación de la especificación,
no el porcentaje de semanas transcurridas.

## Sprint 1 · Índice secuencial y consulta

**Objetivo:** demostrar que cada entrada apunta al registro activo correcto.

- [x] S1-01: definir contratos de motores y CLI; fijar nombres de comandos,
  códigos de salida y tratamiento de errores antes de implementarlos.
- [x] S1-02: reutilizar/extender el escaneo de `io/BinaryFileHandler.java` para
  emitir clave, identificador numérico de archivo y offset inicial del registro.
- [x] S1-03: implementar `core/SequentialEngine.java` para INDEX con arreglos
  primitivos, sin objetos por registro y omitiendo borrados.
- [x] S1-04: ordenar tuplas completas `(phoneNumber, fileId, byteOffset)`.
  Usar `fileId` y offset como desempate determinista; nunca ordenar solo `keys`.
- [x] S1-05: escribir `index_phone.idx` Big-Endian en temporal y publicarlo solo
  tras finalizar y cerrar correctamente la escritura.
- [x] S1-06: implementar `search/BinaryIndexSearch.java`: búsqueda por límites
  inferior/superior para devolver todas las coincidencias de claves repetidas.
- [x] S1-07: añadir lectura del registro localizado y verificación de clave,
  límites de offset y `isDeleted == 0`.

**Aceptación:** índice con tamaño `20 × activos`, ordenado, sin entradas de
borrados; comparar todas las tuplas con un oráculo independiente en fixtures
pequeños. Probar duplicados, ausencia de clave, archivos vacíos, todos borrados,
IDs no consecutivos y entradas/archivos truncados.

**Cierre:** contrato, pruebas y demo documentados en [sprint-1.md](sprint-1.md).
El cierre del sprint 1 incorporó `inspect`, `index`, `search` y `--help`.

## Sprint 2 · PACK secuencial y consistencia

**Objetivo:** disponer del baseline completo sin servir offsets obsoletos.

- [x] S2-01: implementar PACK secuencial copiando registros activos completos
  a temporales en el mismo sistema de archivos.
- [x] S2-02: manejar transferencias parciales y ausencia de progreso de
  `transferTo`; definir reintentos limitados o copia mediante buffer reutilizable.
- [x] S2-03: validar el temporal, forzar escritura según el contrato acordado,
  cerrar canales y reemplazar mediante `ATOMIC_MOVE`.
- [x] S2-04: diseñar exclusión entre búsqueda, INDEX y PACK para el dataset.
  Documentar un solo proceso propietario; si se permiten varios procesos,
  añadir bloqueo entre procesos y probarlo.
- [x] S2-05: invalidar el índice **antes del primer reemplazo**, bloquear consultas
  durante mantenimiento y regenerarlo al terminar PACK.
- [x] S2-06: registrar estado de mantenimiento de forma persistente: un reinicio
  tras un fallo no debe tratar un índice anterior como válido.
- [x] S2-07: documentar recuperación de temporales y fallo por disco lleno;
  nunca borrar originales o respaldos sin comprobar su estado.

**Aceptación:** conservar exactamente registros activos y sus bytes, mantener el
orden relativo por archivo y recuperar la suma exacta de `21 + recordLength` de
los borrados. Segunda ejecución sin borrados: mismos datos, cero bytes recuperados.
Tras PACK, todas las entradas del índice nuevo deben ser válidas.

**Fallos a probar:** error de copia, interrupción antes/después de un reemplazo,
índice ausente/obsoleto y `AtomicMoveNotSupportedException`. No hacer fallback
silencioso a un reemplazo no atómico.

**Límite explícito:** un movimiento atómico protege un archivo, no toda la
colección. Un fallo entre archivos puede dejar mezcla de archivos compactados
y originales; los CDR activos se conservan, pero las consultas deben permanecer
bloqueadas hasta recuperar el estado y reconstruir el índice.

**Cierre:** completado en `feat/sprint-2-pack-consistency`; contrato, recuperación,
pruebas y demo en [sprint-2.md](sprint-2.md). Disponibles `pack` y `recover`;
INDEX/búsqueda/inspección CLI ahora adquieren bloqueo de dataset.

## Sprint 3 · INDEX paralelo y cierre del 50%

**Objetivo:** comparar una implementación concurrente correcta contra el baseline.

- [x] S3-01: implementar INDEX en `core/ParallelEngine.java` con
  `ExecutorService`, tareas por archivo y buffers/canales propios.
- [x] S3-02: aceptar número de trabajadores configurable y limitar recursos;
  propagar fallos de tareas, cancelar pendientes y cerrar el executor.
- [x] S3-03: implementar ordenación paralela de tuplas sobre primitivos
  (por ejemplo, merge sort); preservar asociaciones y desempates.
- [x] S3-04: verificar igualdad byte a byte del índice secuencial y paralelo
  para la misma entrada y política de orden.
- [x] S3-05: generar ≥8 archivos que sumen ≥2 GiB con semilla conocida y
  aproximadamente 20% de borrados en el equipo de evaluación.
- [x] S3-06: medir INDEX secuencial/paralelo y verificar consultas contra el
  dataset; guardar configuración, resultados y metodología.

**Aceptación del 50%:** generador masivo ejecutado, INDEX/PACK secuenciales
funcionales, INDEX paralelo, búsqueda/verificación y tabla de tiempos con
`S = Tseq / Tpar` y `E = S / p`, indicando trabajadores `p` y núcleos disponibles.
Reportar también `S / Cores` para el criterio literal de la especificación si
`p` difiere de los núcleos. No exigir ni inventar un speedup mínimo.

**Cierre:** implementación y pruebas en [sprint-3.md](sprint-3.md), mediciones y
evidencia del hito en [resultados-50.md](resultados-50.md). Se guardan tiempos
crudos, configuración, entorno, hashes, conteos y muestreo reproducible de punteros.

## Sprint 4 · PACK paralelo y mantenimiento integrado

**Objetivo:** compactar concurrentemente sin publicar índices inconsistentes.

- [x] S4-01: distribuir archivos entre trabajadores usando la copia validada
  del sprint 2 y limitar canales abiertos y temporales simultáneos.
- [x] S4-02: integrar exclusión, marcador persistente, reemplazos y reconstrucción
  del índice; publicar el nuevo índice únicamente tras éxito completo.
- [x] S4-03: propagar fallos de cualquier trabajador y dejar un estado recuperable,
  sin comunicar éxito parcial como éxito global.
- [x] S4-04: comparar PACK secuencial/paralelo sobre copias idénticas del dataset;
  comprobar igualdad de archivos finales e índices reconstruidos.
- [x] S4-05: probar arranque tras fallo, cancelación, archivo corrupto y búsquedas
  rechazadas durante mantenimiento.

**Aceptación:** PACK → INDEX automático, cero offsets obsoletos aceptados y
recuperación documentada. Medir bytes recuperados, duración y recursos.
El porcentaje de registros borrados no implica el mismo porcentaje de bytes:
los payloads tienen longitud variable.

**Cierre:** contrato, recuperación, pruebas y mediciones en [sprint-4.md](sprint-4.md).
Rama `feat/sprint-4-parallel-pack`, basada en sprint 3 sin modificar `main`.

## Sprint 5 · Memoria acotada, escalabilidad y entrega

**Objetivo:** completar la entrega bajo límites de memoria demostrables.

- [ ] S5-01: añadir ordenación externa: runs ordenadas con presupuesto de memoria,
  merge de múltiples vías con buffers acotados y eliminación segura de temporales.
- [ ] S5-02: limitar el fan-in del merge y efectuar varias pasadas cuando sea
  necesario para no agotar descriptores de archivo.
- [ ] S5-03: hacer que ambos motores soporten la ruta externa conservando el
  formato final de 20 bytes y la política de orden de los sprints anteriores.
- [ ] S5-04: ejecutar con `-Xmx512m` sobre 4 u 8 GiB; incluir un caso donde el
  **índice activo estimado también exceda el presupuesto de memoria**.
- [ ] S5-05: medir con 1, 2, 4, 8 y 16 trabajadores y varios tamaños de datos;
  si exceden los núcleos, identificarlo como sobresuscripción.
- [ ] S5-06: verificar cierre de canales y terminación de hilos en éxito/error;
  registrar heap, memoria residente y disco temporal máximo.
- [ ] S5-07: actualizar README, ayuda CLI, pruebas, instrucciones de recuperación
  e informe final con limitaciones y cuellos de botella.

**Aceptación del 100%:** todos los requisitos obligatorios de §5.2 más los del
50%; procesamiento sin `OutOfMemoryError`, integridad tras compactación y
reconstrucción, compilación sin warnings y benchmarks reproducibles.
`-Xmx` limita heap, no memoria directa ni RSS: documentar ambos.

**Extra posterior:** índice por timestamp; no adelantarlo a costa de integridad,
ordenación externa o mediciones.

## Protocolo de pruebas y benchmark

1. Ejecutar `make test` en cada incremento; ampliar `tests/java/` y los wrappers
   Python existentes sin incorporar JUnit ni otras dependencias.
2. Fixtures pequeños: comprobar todas las entradas y bytes contra un oráculo;
   datasets grandes: escaneo completo de orden/conteos y muestreo reproducible
   de punteros, indicando semilla y tamaño de muestra.
3. Mantener dataset fuente inmutable. PACK usa una copia fresca por repetición;
   preparar/copiar datos fuera del intervalo cronometrado.
4. Registrar CPU, RAM, SO, JDK, almacenamiento/sistema de archivos, heap,
   buffers, trabajadores, semilla, tamaño real y cantidad de activos/borrados.
5. Separar generación, INDEX, consulta, PACK y reindexación; informar también
   duración completa PACK → INDEX.
6. Realizar al menos un calentamiento y cinco repeticiones medidas por caso;
   guardar tiempos crudos, mediana y dispersión. Declarar caché caliente/fría
   o no controlada, sin atribuir a concurrencia un efecto de caché.
7. Para búsqueda con duplicados, coste esperado `O(log n + k)` para devolver
   `k` resultados; la localización del rango es `O(log n)`.

Tabla mínima del informe:

| Dataset/activos | Operación | Trabajadores | Tseq | Tpar | S | E | Heap/RSS | Disco temporal | Integridad |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Por medir | INDEX/PACK | Por medir | — | — | — | — | — | — | Pendiente |

## Riesgos y decisiones previas

| Riesgo | Acción |
| --- | --- |
| Índice mayor que RAM | Estimar `20 × activos`, más arreglos auxiliares y buffers; runs externas |
| Ordenar claves sin referencias | Ordenar siempre tuplas; comparar con oráculo |
| Saturación de disco por concurrencia | Medir y limitar trabajadores según dispositivo |
| Disco insuficiente | Presupuestar fuente, copias, compactación, runs e índice antes de ejecutar |
| Reemplazo no soportado o fallo entre archivos | Abortar claramente y recuperar bajo exclusión |
| Cambios externos en los `.dat` | Dataset inmutable fuera del mantenimiento controlado |

## Definition of Done por sprint

- Criterios del sprint demostrados y tareas realmente terminadas marcadas.
- Pruebas nuevas para casos normales, límites y errores relevantes.
- `make test` correcto, sin dependencias externas ni warnings.
- Documentación/ayuda consistente con los comandos disponibles.
- Evidencias reales guardadas; no sustituir mediciones por estimaciones.
- Revisión de recursos, memoria, integridad y tratamiento de fallos.

Consultar [skills recomendadas](skills-recomendadas.md) para apoyo al desarrollo y
[aplicaciones del motor](aplicaciones-del-motor.md) para contexto de uso.
