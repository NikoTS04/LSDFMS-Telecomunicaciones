# Skills adecuadas para LSDFMS

## Búsqueda y criterio

Se utilizó el flujo de `find-skills`: consulta del leaderboard de
[skills.sh](https://skills.sh/), búsqueda `npx --yes skills find "java performance"`
y revisión de las fichas, orientación técnica y estrellas de los repositorios.
Las cifras siguientes son aproximadas y corresponden a esta revisión; pueden cambiar.

Las skills son instrucciones para el asistente, **no dependencias del motor**.
No se instaló ninguna. Antes de instalar, revisar su contenido y permisos.
La popularidad no garantiza seguridad ni compatibilidad.

## Selección recomendada

| Skill y origen | Instalaciones | Estrellas del repositorio | Aplicación concreta |
| --- | --- | --- | --- |
| [test-driven-development](https://skills.sh/obra/superpowers/test-driven-development), `obra/superpowers` | 285,9 mil | ~322 mil | Pruebas primero para formatos, duplicados, offsets y compactación |
| [systematic-debugging](https://skills.sh/obra/superpowers/systematic-debugging), `obra/superpowers` | 340,3 mil | ~322 mil | Investigar corrupción, fallos concurrentes y consumo de recursos |
| [writing-plans](https://skills.sh/obra/superpowers/writing-plans), `obra/superpowers` | 309,6 mil | ~322 mil | Descomponer cada sprint en cambios pequeños con pruebas y archivos concretos |

`obra/superpowers` es una fuente comunitaria ampliamente utilizada, no documentación
oficial del JDK. Se verificó el repositorio mediante la API de GitHub; no está archivado.

Instalación opcional desde la raíz del proyecto:

```sh
npx skills add obra/superpowers@test-driven-development
npx skills add obra/superpowers@systematic-debugging
npx skills add obra/superpowers@writing-plans
```

No usar `-g` salvo que se desee instalación global. Revisar los cambios generados
antes de incorporarlos al repositorio. Las instrucciones de una skill no autorizan
a borrar trabajo existente ni a cambiar las restricciones del proyecto.

### Uso por sprint

- Sprints 1–2: TDD para INDEX/búsqueda/PACK; planificación por tarea.
- Sprints 3–4: TDD para equivalencia secuencial/paralela y fallos; depuración
  sistemática para carreras, cancelación y recuperación.
- Sprint 5: depuración basada en perfiles y mediciones, no optimización por
  intuición; usar herramientas del JDK como JFR y `jcmd` cuando estén disponibles.

## Opciones encontradas, pero no recomendadas como primera elección

- [`jeffallan/claude-skills@java-architect`](https://skills.sh/jeffallan/claude-skills/java-architect):
  ~6,4 mil instalaciones y ~12,5 mil estrellas. Su ficha se centra en Java 21,
  Spring Boot, JPA y Maven/Gradle; no encaja directamente con Java 17 puro.
- [`pluginagentmarketplace/custom-plugin-java@java-performance`](https://skills.sh/pluginagentmarketplace/custom-plugin-java/java-performance):
  491 instalaciones, pero solo 42 estrellas en el repositorio. Candidata a revisión
  manual, no recomendación principal por evidencia de adopción limitada.
- Las skills de JMeter/Gatling encontradas no son prioritarias: este proyecto
  requiere medir un motor local de archivos, no carga HTTP.

No se identificó en esta búsqueda una skill especializada en NIO/ordenación externa
que pudiera recomendarse con la misma confianza. No hace falta instalar una
skill de Spring, una base de datos ni un framework para completar el proyecto.
