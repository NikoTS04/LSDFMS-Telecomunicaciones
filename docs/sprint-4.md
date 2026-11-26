# Sprint 4 · PACK paralelo y mantenimiento integrado

Rama: `feat/sprint-4-parallel-pack`, basada en el cierre del sprint 3.

## Contrato

```sh
java -Xmx1g -cp build/classes com.hpc.lsdfms.Main pack-parallel <directorio> <trabajadores>
java -Xmx1g -cp build/classes com.hpc.lsdfms.Main recover-parallel <directorio> <trabajadores>
```

- 1–256 trabajadores solicitados; efectivos `min(trabajadores, archivos)`.
- Preflight, compactación por archivo e INDEX paralelos, dentro de **un único**
  bloqueo de dataset. Sin reabrir el bloqueo ni permitir consultas entre fases.
- PACK secuencial y paralelo comparten el protocolo de marcador, invalidación,
  temporales verificados, `force`, reemplazo atómico por archivo y publicación final.
- Como máximo un temporal de compactación por trabajador. Cada tarea utiliza
  canales propios y copia mediante `transferTo` con fallback acotado reutilizable.
- Antes de reemplazar un archivo se comprueba cancelación. Un fallo cancela las
  demás tareas y espera su terminación antes de liberar el dataset.
- No hay rollback de la colección: pueden quedar originales y compactados, pero
  nunca se comunica éxito parcial ni se habilita búsqueda con el marcador activo.
- El marcador solo se elimina tras éxito de todos los archivos y del índice nuevo.
  Tanto `recover` como `recover-parallel` pueden recuperar cualquiera de los modos.
- Salida: `PACK_PARALLEL` o `RECOVER_PARALLEL`, seguida de bytes antes, después,
  recuperados y activos, separados por tabulaciones. Códigos 0/1/2 sin cambios.

## Recuperación y límites

Aplican las [instrucciones del sprint 2](sprint-2.md): no borrar el lock ni el
marcador; corregir la causa y ejecutar recuperación. Temporales huérfanos de una
terminación abrupta se conservan y no se reutilizan. No se siguen symlinks fuente.
Se requiere cooperación de los procesos y datos no modificados externamente.

Reservar espacio para la suma de los mayores `p` archivos activos compactados
(cota conservadora: sus tamaños originales), más índice, huérfanos y respaldos.
Durante copia hay hasta tres canales por tarea (escaneo, fuente, temporal),
además del bloqueo; se cierran antes de reemplazar. Fallback de 64 KiB por tarea
y cabecera directa de 21 bytes por escaneo. La reindexación conserva el límite
de arreglos del sprint 3; ordenación externa pendiente del sprint 5.

No se promete atomicidad de dataset ni durabilidad transaccional ante corte eléctrico.
El manifiesto del generador no se actualiza tras PACK.

## Validación y medición

`make test`: **53 pruebas correctas** (45 previas y 8 nuevas), compilación con
`--release 17 -Xlint:all -Werror`, sin dependencias externas. También se comprobó
que las fuentes Java finales coinciden con las medidas y que las medianas del
resumen se derivan de las cinco observaciones por motor del CSV.

Las pruebas nuevas cubren:

- Comparación de todos los bytes activos e índices contra el baseline y un oráculo
  Python con 1, 2, 3 y 8 trabajadores; duplicados e IDs no consecutivos.
- Idempotencia, archivos vacíos, todos borrados (índice vacío) y activos sin borrados.
- Preflight corrupto sin invalidar el índice anterior; argumentos inválidos.
- Fallos inyectados de copia, antes/después del reemplazo, movimiento atómico no
  soportado, publicación del índice y escaneo de reindexación.
- Cancelación de tareas activas/en cola y del llamador, preservando su flag;
  no quedan trabajadores vivos ni temporales propios después de retornar.
- Límite de dos tareas y dos temporales simultáneos con cinco archivos.
- Terminación real de una JVM antes/después de reemplazos y antes de publicar el
  índice. Tras reinicio se rechazan búsquedas e INDEX hasta recuperar.
- Recuperación cruzada secuencial/paralela y rechazo de índices antiguos aun si
  reaparecen con el marcador. El bloqueo entre procesos incluye ambos comandos nuevos.
- Ejecución del benchmark con fixture pequeño: las copias se compactan, la fuente
  permanece idéntica y los informes existentes no se sobrescriben.

### Reproducción del benchmark

```sh
make build
python3 scripts/generate_cdr.py --output data/sprint4-pack-64mib --files 8 --total-size 64MiB --deleted-ratio 0.20 --seed 42
python3 scripts/benchmark_pack.py data/sprint4-pack-64mib --output docs/benchmarks/sprint4-pack-64mib --workers 4 --heap 1g --warmups 1 --repetitions 5 --samples 1000
```

Destinos nuevos. El dataset fuente no se compacta; se crea una copia lógica fresca
antes de cada ejecución y se elimina únicamente esa copia temporal propia al finalizar.
El dataset de ≥2 GiB del sprint 3 tampoco se modifica. La comparación del sprint 4
usa 64 MiB; no demuestra aún escalabilidad ni soporte de índices mayores que RAM.

Un calentamiento y cinco ejecuciones medidas por motor, alternando secuencial y
paralelo (4 trabajadores), JVM nueva con `-Xmx1g`. Tiempo wall de **PACK → INDEX
completo**, incluyendo preflight, copia/validación/force, reemplazos, reindexación
y cierre. No se atribuye ese tiempo a la compactación aislada. Preparación de copias,
oráculo y validaciones quedan fuera del reloj.

RSS/CPU por PID mediante `wait4`. Caché del SO no controlada; las copias previas
calientan datos y Btrfs puede optimizar la copia. No se presenta como medición de
disco frío ni se exige speedup positivo. Se comparan ambos motores con los mismos
archivos y política de publicación.

Validación por ejecución: SHA-256 **completo** de cada archivo compactado contra
los bytes activos concatenados por un oráculo Python streaming, igualdad del índice
con el baseline, orden/conteos completos y 1.000 referencias muestreadas con semilla 42.
La muestra se repite, no se contabiliza como 12.000 referencias únicas.
Los hashes de las fuentes antes/después deben coincidir.

### Resultados y reanudación

Serie completa en [`benchmarks/sprint4-pack-64mib/`](benchmarks/sprint4-pack-64mib/).
En cada ejecución se conservaron **157.503 activos**, se purgaron **39.469 borrados**
y se pasó de **67.110.791 a 53.670.560 bytes**: **13.440.231 bytes recuperados**.
Todos los archivos compactados coincidieron con el oráculo, todos los índices
tuvieron el mismo hash y las fuentes permanecieron intactas.

| Motor | Trabajadores | Mediana (s) | Mín–máx (s) | Desviación (s) | RSS máximo (KiB) |
| --- | --- | --- | --- | --- | --- |
| Secuencial | 1 | 16,932 | 15,187–32,956 | 7,332 | 85.744 |
| Paralelo | 4 | 8,747 | 5,698–16,121 | 4,369 | 107.332 |

La razón de medianas es **1,936×**, eficiencia descriptiva `S/4 = 48,39%`.
**Hay alta dispersión y la serie se reanudó tras reiniciar el servidor**: no es
evidencia de una mejora estable ni permite aislar concurrencia de caché/carga del
sistema. El análisis controlado de escalabilidad queda pendiente del sprint 5.
La medición corresponde al flujo completo PACK → INDEX, no a PACK aislado.

Se conservaron siete ejecuciones completas verificables (incluidos los dos
calentamientos) y se repitieron las cinco restantes. Una fila cuyo archivo de
recursos quedó vacío se descartó del cálculo y volvió a medirse. El CSV anterior
y artefactos incompletos quedan en `interrupted/`; **no forman parte del resumen**.
La primera tentativa, interrumpida por el límite de 120 s de la herramienta, se
conservó fuera del repositorio y tampoco se mezcla con esta serie.

El script permite continuar una serie incompleta:

```sh
python3 scripts/benchmark_pack.py data/sprint4-pack-64mib --output docs/benchmarks/sprint4-pack-64mib --workers 4 --heap 1g --warmups 1 --repetitions 5 --samples 1000 --resume
```

Rechaza cambios en fuentes, clases, configuración, plataforma, Python o JDK y
una serie ya completada. Solo conserva el prefijo de filas con salida y recursos
verificables; archiva y repite cualquier ejecución sin checkpoint completo.
Los comandos de las ejecuciones anteriores al reinicio no se habían persistido
individualmente; se conserva su configuración original y los hashes, y el script
recrea las mismas operaciones sobre copias nuevas. Las nuevas ejecuciones sí
guardan sus comandos exactos y las reanudaciones se registran en `metadata.json`.

El índice reconstruido tiene 3.150.060 bytes y SHA-256:
`420675c39eb86380a84b99292181e29f80a7df986817bbf414ba01d577fa38a8`.
Los metadatos identifican las fuentes/clases medidas antes del commit de cierre.

El sprint 4 completa PACK paralelo y el mantenimiento integrado. La entrega del
100% sigue pendiente de ordenación externa, validación >RAM y escalabilidad.
