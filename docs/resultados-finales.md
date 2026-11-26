# Resultados finales · evidencia parcial, sprint 5 en curso

Este documento no declara todavía la entrega del 100%.

## Índice mayor que el heap: prueba de viabilidad completada

Dataset nuevo: 4.294.968.400 bytes en 16 archivos, payload fijo de 64 bytes,
semilla 42. Índice activo: **40.418.831 entradas / 808.376.620 bytes**.
Ambos motores finalizaron con `-Xmx512m` (536.870.912 bytes).

| Motor | Tiempo wall (s) | RSS máximo (KiB) | Pico temporal lógico muestreado (bytes) | Pico FD muestreado |
| --- | --- | --- | --- | --- |
| Secuencial | 115,902 | 572.856 | 1.616.753.240 | 24 |
| Paralelo, 4 trabajadores | 50,344 | 346.600 | 1.616.753.240 | 24 |

Los picos se muestrearon cada 100 ms: son observaciones, no cotas matemáticas
ni detección garantizada de eventos más breves. RSS incluye más que heap;
el RSS secuencial supera 512 MiB sin contradecir el límite de heap.
El pico temporal no incluye dataset ni índice final anterior.

Validación por motor: recorrido completo del orden y conteos del índice,
1.000 referencias reproducibles (misma muestra) y SHA-256 común:
`5047b0a4b92d79eaf0da896574c845792456d8905a2abcd523d4f61c71fcca04`.

Una ejecución por motor, sin calentamientos ni cinco repeticiones: **no es
evidencia estadística de speedup**. Los tiempos incluyen arranque JVM, bloqueo,
conteo, runs/merges, escritura forzada y cierre; la validación se realiza después.

Artefactos: [sprint5-large-index](benchmarks/sprint5-large-index/).

## Pendiente para cierre

- Matriz INDEX con 1/2/4/8/16 trabajadores y varios tamaños.
- PACK con fuente masiva independiente y reindexación externa bajo heap limitado.
- Escalabilidad PACK, instrumentación separada de fases y análisis CPU/I/O.
- Revisión y checklist de entrega basados en las evidencias completas.
