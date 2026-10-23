# Reloj SMT · apps de Android

Dos apps que abren el sistema de fichaje de SMT Solutions (Google Apps Script):

- **Reloj SMT** (`Reloj-SMT.apk`): para que los empleados fichen. Pide la ubicación al abrir.
- **Panel SMT** (`Panel-SMT.apk`): el panel de control del encargado (pide la clave del panel).

Los fichajes van a la misma planilla que la versión web: las dos formas conviven.

## Cómo se compilan
Cada vez que se sube un cambio a la rama `main`, GitHub Actions compila las dos APK y las publica en **Releases**
(arriba a la derecha del repositorio). También se puede compilar a mano: pestaña **Actions → Compilar APK → Run workflow**.

## Firma
`firma/smt-reloj.jks` es la firma de las apps (clave `smtreloj2026`). **No borrarla ni cambiarla**: si cambia, los celulares
no pueden actualizar la app instalada y hay que desinstalarla primero. El repositorio tiene que quedar **privado**.

## Cambiar la dirección
Las direcciones están en `app/build.gradle` (`URL_INICIO`). Si se crea una implementación nueva en Apps Script con otra
dirección, hay que cambiarla ahí.
