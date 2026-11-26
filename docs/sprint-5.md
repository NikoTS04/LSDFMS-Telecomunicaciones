# Sprint 5 · Ordenación externa y entrega final (en curso)

Rama: `feat/sprint-5-external-sort-final`, basada en sprint 4.

## Implementación

Se conserva el contrato de 20 bytes y el orden teléfono/fileId/offset.
Los motores eligen ruta en memoria o externa sin cambiar sus comandos.
PACK y recuperación usan esa misma selección al reconstruir INDEX.

Propiedades JVM, antes de `-cp`:

| Propiedad | Valor por defecto | Significado |
| --- | --- | --- |
| `lsdfms.index.mode` | `auto` | `auto`, `memory` (rechaza exceso) o `external` |
| `lsdfms.index.memoryMiB` | `128` | Presupuesto de trabajo, 4–4096 MiB; limitado además a un cuarto del heap |
| `lsdfms.index.fanIn` | `16` | Máximo solicitado de entradas por merge, 2–64; se reduce según memoria y concurrencia |
| `lsdfms.index.chunkEntries` | Automático | Cota adicional de entradas por bloque; útil para pruebas |

Se reserva la mitad del presupuesto para arreglos; el resto queda para buffers
y metadatos. La generación limita trabajos activos mediante permisos globales,
divide el presupuesto entre ellos y no usa arreglos auxiliares de ordenación.
Los bloques se ordenan in-place. Las runs se compactan por niveles para no guardar
una ruta por cada bloque del índice; metadatos crecen con archivos y niveles.
El merge reutiliza cursores/buffers por run, no objetos por entrada.
Los descriptores de merge se limitan por trabajos activos y fan-in efectivo.

La publicación conserva bloqueo, validación de conteo, `force` y movimiento atómico.
Un fallo espera el cierre de trabajadores antes de limpiar el directorio temporal
propio. Una caída abrupta puede dejar `.lsdfms-index-*`; no se borran huérfanos
de otras ejecuciones. El protocolo de recuperación de PACK no cambia.

No se promete que RSS sea igual al presupuesto: heap de JVM, pilas de hilos,
buffers directos y cachés del sistema se deben medir separadamente.

## Verificación

`make test`: **58 pruebas correctas**, compilación Java 17 sin warnings.
Esta evidencia corresponde a fixtures pequeños; no cierra los criterios masivos.

- Pruebas pequeñas con ruta forzada, bloques de 17 entradas y fan-in 2.
- Oráculo completo de tuplas, igualdad de archivos e índice después de PACK.
- Selección automática bajo heap de 32 MiB.
- Runs inválidas/truncadas/desordenadas, interrupción de merge.
- Cancelación de trabajadores y recuperación con ruta externa.
- Error de publicación conservando el destino y sin temporales propios pendientes.

## Prueba masiva prevista

```sh
python3 scripts/generate_cdr.py --output data/sprint5-dense-4gib --files 16 --total-size 4GiB --min-payload 64 --max-payload 64 --deleted-ratio 0.20 --seed 42
make build
python3 scripts/validate_large_index.py data/sprint5-dense-4gib --output docs/benchmarks/sprint5-large-index --workers 4
```

El script exige un índice activo >512 MiB, ejecuta ambos motores con `-Xmx512m`,
verifica orden/conteos, hash común y 1.000 referencias; registra RSS y picos
muestreados de disco temporal/descriptores. No es un benchmark estadístico:
una ejecución por motor demuestra viabilidad, no speedup estable.

## Pendiente para cerrar el sprint

- Completar y revisar la evidencia del caso masivo.
- Validar PACK → INDEX externo bajo límite de heap en una copia independiente.
- Campaña 1/2/4/8/16 trabajadores y varios tamaños, con calentamiento y cinco medidas.
- Separar fases de PACK y reindexación en la instrumentación.
- Informe final de recursos, limitaciones y recuperación.

No se marca la entrega del 100% hasta completar estas evidencias.

Actualización: el caso masivo INDEX de ambos motores terminó correctamente:
índice de **808.376.620 bytes** bajo `-Xmx512m`. Recursos, hash y límites en
[resultados-finales.md](resultados-finales.md). La matriz de escalabilidad INDEX
está en ejecución; PACK masivo y el resto del cierre siguen pendientes.
