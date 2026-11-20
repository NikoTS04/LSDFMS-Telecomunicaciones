# Sprint 2 · PACK secuencial y consistencia

Rama: `feat/sprint-2-pack-consistency`, basada en el cierre del sprint 1.

## Contrato

- `pack <directorio>` compacta cada archivo conservando bytes y orden de todos
  los registros activos, publica mediante `ATOMIC_MOVE` y reconstruye INDEX.
- `recover <directorio>` requiere un mantenimiento pendiente: valida las fuentes,
  repite la compactación (idempotente) y reconstruye INDEX antes de habilitar consultas.
- PACK imprime `PACK<TAB>bytesAntes<TAB>bytesDespués<TAB>bytesRecuperados<TAB>activos`;
  recuperación imprime lo mismo con prefijo `RECOVER`. Los contadores de recuperación
  corresponden a lo pendiente, no a los reemplazos de una ejecución interrumpida.
- Todas las operaciones públicas INDEX, búsqueda, PACK, recuperación e inspección CLI
  adquieren un bloqueo exclusivo no bloqueante sobre `.lsdfms.lock`. Si está ocupado,
  fallan con código 1. El archivo de bloqueo **no se borra**: el SO libera el lock.
- Antes de modificar fuentes, PACK crea y fuerza a disco `.lsdfms-maintenance`
  e invalida `index_phone.idx`. INDEX y búsqueda rechazan ese marcador incluso después
  de reiniciar. Inspección sigue disponible bajo bloqueo para diagnóstico.
- Los temporales se crean en el mismo directorio, se validan, se fuerzan y se cierran
  antes del reemplazo. Transferencias parciales continúan; ante cero progreso se
  usa copia mediante buffer acotado. Sin progreso de lectura/escritura: error.
- Sin fallback a movimiento no atómico. Un error conserva los originales aún no
  reemplazados, los archivos ya compactados y el marcador; no se ofrece rollback global.

## Recuperación operativa

1. No modificar fuentes ni borrar `.lsdfms.lock` o el marcador manualmente.
2. Usar `inspect` para diagnóstico y corregir la causa (espacio, permisos, corrupción
   restaurada desde backup, soporte de movimiento atómico).
3. Ejecutar `recover`. Revalida las fuentes actuales y nunca reutiliza temporales
   de una ejecución anterior; únicamente purga temporales creados por la ejecución
   actual al terminar/fallar.
4. Temporales huérfanos de una caída abrupta (`.lsdfms-pack-*.tmp`, `.index-phone-*.tmp`)
   se conservan. Revisarlos/copiar fuera del dataset con el proceso detenido y sin
   otras operaciones; eliminarlos solo tras comprobar fuentes e índice recuperados.
   No borrar originales ni backups para liberar espacio sin verificar su estado.
5. El marcador solo desaparece después de completar PACK → INDEX correctamente.

Los bloqueos son cooperativos: otros programas no deben alterar los `.dat` ni los
archivos de control. Se admite más de un proceso del motor, pero solo una operación
por dataset a la vez, incluidas búsquedas. Las APIs de bajo nivel de I/O no son
operaciones de dataset y requieren que su llamador asegure exclusión.

`force(true)` fuerza archivos y marcador; no se promete durabilidad transaccional
ante corte eléctrico ni atomicidad de toda la colección. La recuperación cubre
errores de I/O e interrupción/terminación del proceso bajo las garantías del sistema
de archivos. Reservar espacio para al menos el mayor archivo activo compactado y
el índice nuevo; los huérfanos y backups ocupan espacio adicional.

## Evidencia de cierre

- `make test`: **36 pruebas correctas**, 28 previas y 8 nuevas de aceptación,
  más escenarios internos en ejecutables Java sin frameworks. Compilación Java 17
  con `-Xlint:all -Werror`.
- Oráculo Python: igualdad byte a byte y orden de activos por archivo, tuplas del
  índice reconstruido, idempotencia, vacíos/todos borrados, preflight corrupto
  conservando índice anterior y búsqueda posterior.
- Inyección Java: copia fallida por disco lleno simulado, interrupción, fallo antes
  y después del primer reemplazo, publicación fallida del índice y
  `AtomicMoveNotSupportedException`. Todos mantienen el marcador y se recuperan.
- Terminación real de una JVM antes/después del primer reemplazo: un proceso nuevo
  rechaza búsquedas e INDEX, adquiere el bloqueo liberado por el SO y completa
  `recover`. El temporal huérfano no se confunde con una fuente válida.
- Bloqueo contra otro proceso (`fcntl.lockf` en Linux), reentrada en la misma JVM
  y comprobación de que esa reentrada no libera el bloqueo nativo.
- Transferencias parciales, cero progreso con fallback, combinación de ambas,
  EOF inesperado, rango inválido e interrupción. Comparación de bytes exactos.

### Demo con datos generados

Ejecutada en un directorio temporal nuevo, sin tocar datasets existentes.
Comandos equivalentes para reproducir en un destino vacío:

```sh
make build
python3 scripts/generate_cdr.py --output data/sprint2-demo --files 8 --records-per-file 1000 --seed 42
java -cp build/classes com.hpc.lsdfms.Main index data/sprint2-demo
java -cp build/classes com.hpc.lsdfms.Main pack data/sprint2-demo
java -cp build/classes com.hpc.lsdfms.Main pack data/sprint2-demo
java -cp build/classes com.hpc.lsdfms.Main search data/sprint2-demo 51900000889
```

| Resultado | Valor observado |
| --- | --- |
| Fuente inicial | 8 archivos, 8.000 registros, 2.736.795 bytes |
| Activos / borrados iniciales | 6.365 / 1.635 |
| Tras PACK | 6.365 activos, 0 borrados, 2.172.045 bytes |
| Bytes recuperados | 564.750 |
| Segunda ejecución | 0 bytes recuperados; índice idéntico |
| Validación independiente | Bytes activos y todas las tuplas coinciden |
| Consulta de `51900000889` | Archivo 4, nuevo offset 239577 (antes 302353) |
| Estado al terminar | Sin marcador ni temporales de esa ejecución |

No son benchmarks ni validación del hito del 50%. El disco lleno se simuló por
inyección de `IOException`, sin llenar el disco real. Las pruebas no simulan un
corte eléctrico ni garantizan durabilidad frente a pérdida de cachés del SO.
