# Exposición LSDFMS · guía práctica de 5–7 minutos

## Antes de entrar al aula

- JDK 17+ (`java`, `javac`) y Python 3.10+, sin paquetes externos.
- Ejecutar `make test` y ensayar una vez `make demo-auto`.
- Abrir este guion y los dos SVG de `graficos/` en el navegador.
- Ampliar terminal a ~110 columnas, fuente grande; ocultar notificaciones.
- Conservar un ensayo exitoso: cada ejecución guarda transcripción, resumen y datos
  en un directorio único bajo `build/demos/`.
- No usar datasets grandes ni ejecutar benchmarks durante la presentación.
- Si el equipo es lento, saltar la recuperación opcional; no prometer tiempos.

## Comandos

```sh
# Guiada, con Enter entre etapas
make demo

# Ensayo completo, sin pausas, incluida recuperación
make demo-auto

# Guiada con recuperación
python3 scripts/demo.py --recovery

# Regenerar gráficos desde los JSON originales
make presentation
```

La demo compila las clases en su propio directorio; no depende de clases antiguas
en `build/classes`. Usa 4 archivos de 250 registros, semilla 42. Modifica explícitamente
tres teléfonos activos del dataset nuevo para que existan duplicados; deja constancia
en `fixture.json`. El manifiesto conserva conteos/tamaños del generador.

`--output <ruta>` exige un directorio inexistente. No hay `--force` ni limpieza
automática de datasets anteriores. Sin terminal interactiva se exige `--auto`.
No hace falta conexión a Internet.

## Guion del expositor

| Tiempo | Acción | Explicación sugerida |
| --- | --- | --- |
| 0:00–0:40 | Introducción | «Esto es un motor backend de archivos, no una aplicación de llamadas ni un facturador». |
| 0:40–1:15 | Etapa 1: datos/inspect | «Los CDR son variables: 21 bytes de cabecera más payload. Borrado lógico todavía ocupa disco». |
| 1:15–2:00 | Etapa 2: INDEX | «Cada activo genera una tupla de 20 bytes. Secuencial y paralelo deben producir los mismos bytes». |
| 2:00–2:40 | Etapa 3: consulta | «El teléfono no es único: buscamos un rango y verificamos las referencias físicas». |
| 2:40–3:20 | Etapa 4: externa | «Forzamos bloques pequeños para recorrer runs/merge. Esto NO demuestra por sí solo >RAM». |
| 3:20–4:30 | Etapa 5: PACK | «Mismos eventos, menos bytes. Los offsets cambian: por eso reindexamos. Repetir PACK libera cero bytes». |
| 4:30–5:30 | Recuperación opcional | «Un fallo tras un reemplazo deja el marcador y bloquea consultas. Recuperar reconstruye un estado consultable». |
| 5:30–6:30 | Gráficos | «Estos son benchmarks preparados, no tiempos medidos durante esta demo». |
| 6:30–7:00 | Cierre | «La integridad es obligatoria; el speedup se mide y no se garantiza». |

La recuperación usa un ejecutable **separado**, `demo/java/.../RecoveryDemo.java`.
No añade banderas de fallos a la CLI de producción. Inyecta una `IOException`
después de reemplazar un archivo de una copia desechable; NO simula corte eléctrico
ni afirma atomicidad de toda la colección. El motor y la recuperación son reales.

La copia de fallo ya contiene datos compactados: permite aislar el protocolo de
consistencia sin volver a depender del porcentaje de borrados.

## Qué mostrar en los gráficos

### `graficos/index-speedup.svg`

Serie de 1/2/4/8/16 trabajadores, para 64 MiB, 512 MiB y 2 GiB.
Speedup calculado respecto a la mediana secuencial de cada dataset.
Cinco medidas y un calentamiento por configuración; heap 512 MiB.
En 2 GiB, 8 trabajadores superaron a 16. En muestras pequeñas, los costes fijos
pueden dominar. Caché del SO no controlada y equipo no aislado.

### `graficos/index-vs-heap.svg`

Índice de 808.376.620 bytes (~770,9 MiB), superior a un heap máximo de 512 MiB.
Ambos motores terminaron con el mismo hash. Las barras de índice, heap y RSS son
magnitudes distintas. Una sola ejecución por motor: prueba de viabilidad, no
benchmark estadístico de velocidad.

Los SVG y `sources.json` se regeneran con biblioteca estándar desde
`docs/benchmarks/sprint5-index-*/summary.json` y
`docs/benchmarks/sprint5-large-index/summary.json`. No incluyen datos inventados.

## Respaldo si falla el directo

1. Abrir un ensayo exitoso en `build/demos/exposicion-*/transcript.txt`.
2. Mostrar su `summary.json`: `ok`, activos, bytes y offsets antes/después.
3. Abrir los SVG estáticos y explicar las etapas con el esquema inferior.
4. No borrar archivos ni modificar marcadores en vivo para «hacer funcionar» la demo.

Se incluye `ensayo.json` como resumen pequeño versionado de un ensayo real:
1.000 registros, 783 activos conservados, 74.579 bytes recuperados y offset
476 → 0. No reemplaza la transcripción de un ensayo en el equipo del expositor.

```text
CDR sintéticos → inspect → INDEX → consulta
                                  ↓
                        PACK → INDEX nuevo → consulta
                                  ↓ fallo
                          marcador → recover → consulta
```

### Grabación previa recomendada

Usar el grabador de pantalla del equipo u OBS si ya está disponible:
capturar terminal y gráficos, ejecutar `make demo-auto`, guardar el video fuera de
Git y probar su reproducción local sin Internet. No se incluye un video generado
ni se sustituye la ejecución real por una animación.

## Preguntas frecuentes

- **¿Por qué no una base de datos?** Para estudiar directamente almacenamiento,
  índices, algoritmos externos y concurrencia; no pretende reemplazar un DBMS general.
- **¿Búsqueda O(log n)?** Localizar el rango sí; devolver k resultados cuesta además
  O(k) y la lectura de sus payloads.
- **¿20% de borrados libera 20% del disco?** No necesariamente: las longitudes varían.
- **¿Más hilos siempre ayudan?** No; existen costes fijos, fases seriales y límites I/O.
- **¿Es seguro ante cualquier caída?** Hay exclusión y recuperación cooperativas;
  atomicidad por archivo, no transacción global ni garantía absoluta ante corte eléctrico.
- **¿La exposición certifica la entrega final?** No. Muestra capacidades concretas;
  el estado académico debe respaldarse con el checklist y las evidencias del proyecto.
