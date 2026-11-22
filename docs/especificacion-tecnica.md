# Especificación Técnica: Sistema de Gestión de Archivos Masivos (LSDFMS) - Caso Telecomunicaciones (CDR)

## 1. Contexto y Objetivo del Proyecto

El objetivo es diseñar e implementar un motor de procesamiento de alto rendimiento (HPC) en **Java puro (JDK 17+)** utilizando exclusivamente su **biblioteca estándar (sin frameworks externos)**. El sistema gestiona grandes volúmenes de registros telefónicos (**Call Detail Records - CDR**) almacenados en múltiples archivos binarios de acceso directo ($F_1, F_2, \dots, F_N$).

El sistema debe implementar dos operaciones críticas paralelizadas:

1. **`INDEX()`**: Construcción paralela de archivos de índice ordenados para permitir búsquedas en tiempo logarítmico $O(\log n)$.

2. **`PACK()`**: Compactación paralela de archivos para eliminar físicamente registros marcados como borrados y recuperar espacio en disco.

## 2. Caso de Uso: Datos Telefónicos (CDR)

Una operadora de telecomunicaciones genera millones de registros diarios por llamadas de voz, SMS y consumo de datos móviles. Cada registro tiene **longitud variable** dependiendo de los metadatos asociados (ej. detalles de roaming, celdas de conexión, códigos de error de red, etc.).

### Dominio de Datos y Campos

* **Clave Principal de Indexación (`phoneNumber`):** Número telefónico de origen (ej. `51987654321`), almacenado como entero de 64 bits (`long`).

* **Claves Secundarias (Opcional para 100%):** `timestamp` (fecha/hora de inicio en epoch ms) o `duration` (duración en segundos).

* **Estado Lógico (`isDeleted`):** Bandera que indica si el registro ya fue facturado/conciliado y está listo para ser purgado en el próximo mantenimiento (`PACK()`).

* **Carga Útil Variable (`payload`):** Datos variables de la sesión (ID de antena, tipo de servicio: VOZ/SMS/DATA, país destino en roaming, JSON/texto plano con metadatos de red).

## 3. Arquitectura y Decisiones Técnicas

### 3.1. Restricciones Tecnológicas

* **Lenguaje Core:** Java (JDK 17 o superior).

* **Dependencias:** **CERO** librerías o frameworks externos (no Maven/Gradle dependencies, no Apache Commons, no Guava). Todo debe resolverse con `java.io`, `java.nio`, `java.util.concurrent`, `java.util.Arrays`.

* **Generación de Datos:** Script externo en **Python 3** (módulo `struct`) para generar datasets sintéticos masivos (2 GB a 10 GB) en formato binario Big-Endian.

### 3.2. Formato Binario de Archivos ($F_1 \dots F_N$)

Para evitar la sobrecarga del *parsing* de texto y la presión sobre el *Garbage Collector* (GC) de la JVM, los archivos se almacenan en formato binario estricto (**Big-Endian**, estándar nativo de Java `ByteBuffer`).

Cada registro tiene la siguiente estructura secuencial en bytes:

| Orden | Campo | Tipo Java | Tamaño (Bytes) | Descripción | 
| ----- | ----- | ----- | ----- | ----- | 
| 1 | `isDeleted` | `byte` | 1 | `0x00` = Activo, `0x01` = Marcado para eliminar. | 
| 2 | `recordLength` | `int` | 4 | Tamaño en bytes del campo `payload`. Permite saltar registros sin leer su contenido. | 
| 3 | `phoneNumber` | `long` | 8 | Campo clave indexable (ej. `51999888777`). Posición fija para acceso ultra rápido. | 
| 4 | `timestamp` | `long` | 8 | Marca de tiempo Unix (Epoch). | 
| 5 | `payload` | `byte[]` | Variable ($L$) | Datos variables de longitud `recordLength` (UTF-8). | 

*Tamaño de cabecera fija por registro:* $1 + 4 + 8 + 8 = 21 \text{ bytes}$.
*Tamaño total del registro:* $21 + L \text{ bytes}$.

### 3.3. Formato del Archivo de Índice (`index_phone.idx`)

Archivo binario generado por `INDEX()`, ordenado ascendentemente por `phoneNumber`. Cada entrada tiene un tamaño fijo de **20 bytes**, permitiendo cálculo directo de posición para búsqueda binaria en disco:

| Campo | Tipo Java | Tamaño (Bytes) | Descripción | 
| ----- | ----- | ----- | ----- | 
| `phoneNumber` | `long` | 8 | Clave ordenada. | 
| `fileId` | `int` | 4 | Identificador numérico del archivo $F_i$ donde reside el registro. | 
| `byteOffset` | `long` | 8 | Posición exacta (offset en bytes) dentro de $F_i$ donde inicia el registro. | 

### 3.4. Estrategia de Concurrencia e I/O en Java

* **Lectura/Escritura:** Uso de `FileChannel` y `ByteBuffer` (específicamente `ByteBuffer.allocateDirect()` o `MappedByteBuffer` vía `FileChannel.MapMode.READ_ONLY`) para minimizar copias entre el espacio de usuario y el kernel.

* **Gestión de Memoria (Anti-GC):** Evitar instanciar millones de objetos Java (`new Record()`). Utilizar arreglos primitivos paralelos (`long[] keys`, `int[] fileIds`, `long[] offsets`) o estructuras empaquetadas en `ByteBuffer`.

* **Paralelización de `INDEX()`:**

  1. Un `ExecutorService` (`FixedThreadPool` igual al número de núcleos físicos/lógicos) asigna archivos $F_i$ a cada hilo.

  2. Cada hilo escanea su archivo leyendo la cabecera de 21 bytes; si `isDeleted == 0`, extrae `phoneNumber` y `offset`, y salta el `payload` avanzando la posición del canal/buffer (`position + recordLength`).

  3. Se consolidan los resultados y se ordenan en paralelo usando `Arrays.parallelSort()` (o un *Parallel Merge Sort* personalizado sobre primitivos).

  4. Se escribe el archivo `.idx` resultante mediante `FileChannel`.

* **Paralelización de `PACK()`:**

  1. Paralelismo a nivel de archivo mediante `ExecutorService`.

  2. Cada hilo lee $F_i$ y escribe en un archivo temporal $F_i.\text{tmp}$.

  3. Para registros con `isDeleted == 0`, se utiliza `FileChannel.transferTo()` (Zero-Copy a nivel de SO) para transferir el bloque completo ($21 + L$ bytes) sin pasar los datos variables por la memoria de la JVM.

  4. Al finalizar el archivo, se reemplaza $F_i$ por $F_i.\text{tmp}$ usando `Files.move(..., StandardCopyOption.ATOMIC_MOVE)`.

  5. Al concluir todos los hilos, se invalida/regenera el índice ejecutando `INDEX()`.

## 4. Estructura de Directorios Sugerida

```
lsdfms-telecom/
├── scripts/
│   └── generate_cdr.py          # Generador sintético de archivos F_1.dat ... F_N.dat
├── data/                        # Directorio de almacenamiento (ignorado en git)
│   ├── F_1.dat
│   ├── F_2.dat
│   └── index_phone.idx
├── src/
│   └── com/hpc/lsdfms/
│       ├── Main.java            # CLI, orquestación y benchmarking (Secuencial vs Paralelo)
│       ├── model/
│       │   └── IndexEntry.java  # Constantes de offsets y utilidades de serialización
│       ├── io/
│       │   └── BinaryFileHandler.java # Manejo de FileChannel y ByteBuffer
│       ├── core/
│       │   ├── SequentialEngine.java  # Implementación baseline (1 hilo)
│       │   └── ParallelEngine.java    # Implementación concurrente (ExecutorService)
│       └── search/
│           └── BinaryIndexSearch.java # Consulta O(log n) sobre index_phone.idx
└── docs/especificacion-tecnica.md # Este documento

```

## 5. Criterios de Aceptación (Definition of Done)

### 5.1. Estado de Listo: Avance del 50%

El objetivo del 50% es demostrar la viabilidad de la arquitectura, el manejo correcto de archivos binarios de longitud variable y los primeros resultados comparativos de rendimiento.

* [x] **Generador de Datos Funcional:** Script `generate_cdr.py` capaz de generar al menos $N=8$ archivos binarios que sumen $\ge 2\text{ GB}$ de datos telefónicos con registros de longitud variable y un \~20% de registros marcados con `isDeleted = 1`.

* [x] **Motor Secuencial (Baseline) Completo:** Implementación funcional en 1 hilo de `INDEX()` y `PACK()` para establecer la línea base de tiempos de ejecución ($T_{\text{seq}}$).

* [x] **Implementación Paralela de `INDEX()`:** Versión multihilo funcional utilizando `ExecutorService` y `FileChannel`, generando el archivo `index_phone.idx` válido y ordenado.

* [x] **Verificador de Integridad:** Módulo de búsqueda binaria que lee `index_phone.idx`, localiza un `phoneNumber` aleatorio en $O(\log n)$, va al `fileId` y `byteOffset` en el archivo `.dat`, y verifica que los datos leídos coinciden y tienen `isDeleted == 0`.

* [x] **Métricas Iniciales:** Tabla comparativa en consola o documento mostrando $T_{\text{seq}}$ vs $T_{\text{par}}$ para `INDEX()`, calculando el **Speedup** ($S = T_{\text{seq}} / T_{\text{par}}$) y la **Eficiencia** ($E = S / \text{Cores}$).

Evidencia de validación en el equipo disponible: [resultados del hito del 50%](resultados-50.md).

### 5.2. Estado de Listo: Entrega Final del 100%

El objetivo del 100% es entregar un sistema robusto, completamente paralelizado, libre de fugas de memoria/descriptores de archivo y con análisis de escalabilidad completo.

* [x] **Implementación Paralela de `PACK()`:** Compactación multihilo funcional utilizando `FileChannel.transferTo()` y reemplazo atómico de archivos, reduciendo el tamaño en disco según los bytes de registros eliminados (los payloads tienen longitud variable).

* [x] **Flujo Integrado (`PACK` -> `INDEX`):** Al ejecutar `PACK()`, el sistema actualiza automáticamente los índices afectados para evitar punteros corruptos (*dangling offsets*).

Evidencia de estos dos criterios: [sprint 4](sprint-4.md). Los restantes requisitos del 100% siguen pendientes.

* \[ \] **Soporte Multi-Índice (Opcional/Plus):** Capacidad de generar índices secundarios en paralelo (ej. por `timestamp` además de `phoneNumber`).

* \[ \] **Manejo de Archivos Masivos (> RAM):** Pruebas exitosas con un dataset que supere la memoria RAM asignada a la JVM (ej. ejecutar Java con `-Xmx512m` procesando un dataset de `4 GB` o `8 GB` sin lanzar `OutOfMemoryError`).

* \[ \] **Benchmarking y Escalabilidad:** Pruebas ejecutadas variando el número de hilos ($1, 2, 4, 8, 16$) y el tamaño de los datos, documentando cuellos de botella (I/O bound vs CPU bound).

* \[ \] **Código Limpio y Documentado:** Cierre estricto de todos los `FileChannel` (uso de `try-with-resources`), sin warnings de compilación y con instrucciones claras de ejecución por línea de comandos.

## 6. Prompt Sugerido para Asistentes de Código (IA)

> *"Actúa como un Ingeniero Senior de Java especializado en Computación de Alto Rendimiento (HPC) y sistemas I/O de bajo nivel. Basándote estrictamente en el archivo `docs/especificacion-tecnica.md`, genera la implementación de \[nombre de la clase, ej. `ParallelEngine.java`\]. Restricciones obligatorias: usa únicamente la biblioteca estándar de Java 17 (sin librerías externas), prioriza `java.nio.channels.FileChannel` y arreglos primitivos para evitar presión sobre el Garbage Collector, y asegura el cierre de recursos con try-with-resources."*
