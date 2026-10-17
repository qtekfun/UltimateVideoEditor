# Privacidad

ultimateVE está hecha para que tus grabaciones y tus proyectos no salgan nunca de tu dispositivo y para que no se recoja nada
sobre cómo usas la app. Es una regla firme del proyecto, no un ajuste.

## Lo que la app no hace

- **Sin acceso a la red.** La app no declara ningún permiso de red (ni siquiera `INTERNET`), así que el propio Android le impide
  conectarse a nada. El tráfico sin cifrar también está desactivado.
- **Sin cuentas, sin inicio de sesión, sin nube.** Los proyectos viven en el almacenamiento privado de la app.
- **Sin analíticas, sin telemetría, sin servicio de informes de fallos, sin publicidad.** No hay ningún SDK de ese tipo en la
  app y no se envía nada a ninguna parte. Si la app falla, escribe un informe de texto breve **solo en tu dispositivo** (tipos de
excepción, mensajes acortados con cualquier ruta de archivo, nombre o dirección de contenido eliminados, marcos de la pila, versión
de la app, modelo de teléfono y versión de Android). Se conservan los cinco últimos informes (`last-crash.txt`, `last-crash.1.txt`
... `last-crash.4.txt`). Puedes leerlos, copiarlos, compartirlos o borrarlos en «Acerca de»; solo salen del teléfono si tocas
Compartir, mediante la hoja de compartir estándar.
- **Sin funciones de IA ni de aprendizaje automático** y sin modelos, ni descargados ni incluidos. Los subtítulos los escribes tú o
  los importas de un archivo de subtítulos; las funciones que necesitarían un modelo o un servidor quedan fuera del proyecto.
- **Sin servicios de terceros.** La app depende solo de bibliotecas de código abierto que se ejecutan dentro de ella (Android
  Jetpack, bibliotecas de Kotlin y kotlinx, Oboe). Consulta `THIRD_PARTY_NOTICES.md`.
- **Sin copias de seguridad en la nube.** La copia de seguridad de Android está desactivada para la app (`allowBackup="false"`).

## Qué se guarda y dónde

Todo está en tu dispositivo, en el almacenamiento privado de la app, salvo lo que exportes tú mismo:

| Datos | Dónde | Para qué |
|---|---|---|
| Proyectos (`project.json` y su `.bak`) | `files/projects/`, privado de la app | Tus líneas de tiempo |
| Cachés de formas de onda y miniaturas | Carpetas de caché privadas de la app | Rapidez; se pueden borrar sin problema |
| Biblioteca de LUT 3D | Almacenamiento privado de la app | Los archivos LUT que importaste |
| Preferencias (diseño, últimas elecciones, el idioma que elegiste, si ya se mostraron los consejos del primer uso) | Preferencias privadas de la app | Recordar tus ajustes |
| Traducciones (inglés, español y cualquier idioma posterior) | Dentro de la app (recursos de texto, incluidos al compilar) | No se descarga nada ni se usa ningún servicio de traducción; el ajuste de idioma es el propio idioma por app de Android, guardado en el teléfono |
| Últimos informes de fallos (`files/crash/last-crash.txt` y otros cuatro más antiguos, de 64 KB como máximo cada uno) | Almacenamiento privado de la app | Te permite compartir un informe de error si quieres (también resume un fallo nativo o un ANR que el sistema registró para la app: sin nombres de medios ni de proyectos); se puede borrar en «Acerca de» |
| Tus medios | **No se copian**: los proyectos guardan una referencia `content://` | Ahorra espacio; tú conservas el control |

Las exportaciones se escriben solo donde elijas con el selector de archivos del sistema.

## Permisos

La app declara exactamente cuatro permisos y ninguno tiene que ver con datos ni con la red. Existen para que una exportación pueda
seguir en marcha cuando sales de la app, cambias a otra o se apaga la pantalla:

| Permiso | Para qué sirve |
|---|---|
| `FOREGROUND_SERVICE` | Ejecutar la exportación como servicio en primer plano, que Android mantiene vivo mientras se muestra su notificación |
| `FOREGROUND_SERVICE_MEDIA_PROCESSING` | El tipo de servicio de Android 15 y posteriores: «este servicio está procesando medios» |
| `FOREGROUND_SERVICE_DATA_SYNC` | El tipo de servicio que Android 12 a 14 ofrece para una tarea larga. No se sincroniza nada ni se envía nada a ninguna parte; es solo la etiqueta más cercana que existe antes de Android 15 |
| `POST_NOTIFICATIONS` | Mostrar la notificación de progreso de la exportación (Android 13 y posteriores). Se pide al empezar una exportación y es opcional: si dices que no, la exportación sigue funcionando, solo que sin notificación |

La app sigue **sin** permisos de red, ubicación, contactos, cámara, micrófono ni almacenamiento y sin analíticas: la lectura de medios
pasa por el selector de archivos del sistema Android (Storage Access Framework), que solo le da acceso a los archivos que eliges. La
notificación de exportación muestra el nombre del proyecto y un porcentaje en tu propio dispositivo, y nada más. Cuando eliges un
archivo, Android puede conceder a la app un permiso de lectura persistente para ese archivo, de modo que un proyecto pueda volver a
abrirlo; la app libera los permisos que ya no usa ningún proyecto.

## Cómo comprobarlo tú mismo

1. Mira el manifiesto: `app/src/main/AndroidManifest.xml` declara solo las cuatro entradas `uses-permission` de arriba. En el APK
   compilado, `aapt2 dump permissions app-debug.apk` lista esas cuatro y `com.qtekfun.ultimatevideoeditor.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
   un permiso privado que AndroidX añade para que los receptores de difusión de la propia app no puedan ser llamados desde otras apps;
   no da acceso a nada.
2. En el teléfono: Ajustes > Apps > ultimateVE > Permisos muestra como mucho Notificaciones; en una app cortafuegos o con
   «Restringir datos móviles y Wi-Fi», la app no tiene tráfico porque nunca abre una conexión.
3. En el repositorio: `./gradlew :app:testDebugUnitTest --tests '*OfflineGuaranteeTest*'` falla si aparece cualquier permiso distinto de
   esos cuatro, un permiso de red, una API de red (HTTP, sockets, WebView, gestores de descargas), una dependencia de analíticas, de
   informes de fallos o de publicidad, o si alguna vez se introduce tráfico sin cifrar. La integración continua lo ejecuta en cada pull
   request.
4. El código es abierto (GPL-3.0): léelo.

## Cambios en este documento

Cualquier cambio que debilitara estas garantías necesita una decisión explícita registrada en `DECISIONS.md` y una actualización de la
prueba de arriba; la respuesta por defecto es no.
