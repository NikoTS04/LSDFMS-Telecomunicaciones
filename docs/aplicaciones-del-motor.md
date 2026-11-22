# ¿En qué software se utilizaría este motor?

LSDFMS es un **componente backend de almacenamiento y procesamiento por lotes**:
no es una aplicación de llamadas, un servidor de telefonía ni un sistema completo
de facturación. Su función es organizar archivos CDR, localizar registros por
teléfono y recuperar espacio mediante compactación.

## Piezas de software donde encaja

| Pieza | Uso del motor | Lo que necesita fuera de LSDFMS |
| --- | --- | --- |
| Mediación de telecomunicaciones | Preparar archivos CDR normalizados para consulta y mantenimiento | Ingesta, validación de origen, deduplicación y conversión al formato propio |
| Backend de facturación/conciliación | Recuperar eventos de una línea y compactar los ya procesados cuando corresponda | Tarifas, impuestos, pagos y reglas de retención |
| Atención de reclamos | Consultar llamadas/SMS/datos asociados a un número | API, autenticación, autorización y presentación |
| Análisis antifraude por lotes | Obtener eventos de números investigados | Reglas/modelos, agregación y alertas; no garantiza detección en tiempo real |
| Archivo operativo o ETL local | Preparar un subconjunto o recuperar espacio antes de otra etapa | Exportación, orquestación y políticas de conservación |

El formato del repositorio es propio: un CDR de un proveedor no se puede leer
directamente sin un adaptador que respete los 21 bytes de cabecera y Big-Endian.

## Ejemplo: backend de reclamos de una operadora

Una persona cuestiona el consumo de la línea sintética `51987654321`.
La arquitectura podría ser:

```text
Equipos de red / archivos de proveedor
                 |
          Adaptador de CDR
                 |
       F_1.dat ... F_8.dat
                 |
          LSDFMS: INDEX
                 |
          index_phone.idx
                 |
Servicio de reclamos -> búsqueda por teléfono -> lectura de CDR
                 |
     Filtro por fecha y reglas de negocio
                 |
       Respuesta al agente de atención
```

1. El adaptador transforma eventos de red al contrato de este proyecto.
2. INDEX incorpora solo registros activos y guarda `(teléfono, archivo, offset)`.
3. La búsqueda localiza el rango de entradas de `51987654321`; un mismo teléfono
   puede tener muchos CDR, por lo que debe devolver todas las coincidencias.
4. El servicio lee los registros referidos y filtra el periodo reclamado.
   El índice principal no ordena por fecha; un índice temporal sería una mejora opcional.
5. Tras conciliación y cumplimiento de la política de conservación, **otro
   componente** podría marcar registros como borrados. La CLI actual no ofrece
   esa operación y el generador solo simula ese estado.
6. Durante una ventana de mantenimiento exclusiva, PACK elimina físicamente los
   borrados y reconstruye INDEX: los offsets anteriores dejan de ser fiables.

Ejemplo conceptual de una entrada (no es un resultado medido):

```text
phoneNumber = 51987654321
fileId      = 3
byteOffset  = 4096

=> abrir F_3.dat, situarse en byte 4096 y verificar cabecera/clave/estado
```

La posición es ilustrativa: solo sería válida si allí comenzara realmente un registro.
La búsqueda del rango cuesta `O(log n)` y recuperar `k` coincidencias añade al menos
`O(k)`, además de la lectura de sus payloads.

## Estado y límites

Actualmente funcionan generación, inspección, INDEX secuencial/paralelo, PACK
secuencial, búsqueda y recuperación de mantenimiento; PACK paralelo sigue
previsto en el [plan de sprints](plan-sprints.md).
La integración anterior es una propuesta, no una API implementada.

Para producción con datos reales harían falta controles adicionales: privacidad,
cifrado, autorización, auditoría, retención legal, backups y recuperación probada.
No sustituye a una base de datos transaccional, a un sistema distribuido ni a una
plataforma analítica completa. El mantenimiento propuesto bloquea consultas;
alta disponibilidad requeriría un diseño adicional.
