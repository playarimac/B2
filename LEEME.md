# v4.3 · Nueva interfaz para Android TV + guía de programación
- **Riel de categorías** a la izquierda, **vista previa** del canal arriba, **panel "Ahora / A continuación"** a su lado y **cuadrícula de canales** con logo, número, programa y barra de progreso (inspirada en apps de TV).
- **Dos vistas** (botones ▦ Cuadrícula / ☰ Guía): la guía muestra hora, programa actual y el siguiente por canal.
- **Control remoto**: ←→↑↓ recorren todo; OK abre el canal en la vista previa; OK otra vez = pantalla completa; ↑↓ en pantalla completa cambian de canal; Atrás sube al riel / sale de pantalla completa.
- **Guía de programación (EPG XMLTV)**: se toma de la dirección `url-tvg`/`x-tvg-url` que traen las listas y de las que agregues en "Mis canales y listas → Agregar guía XMLTV" (.xml o .xml.gz). Si un canal no tiene guía muestra "En vivo". Para canales de Perú no hay una guía pública ya alojada: ver abajo.
- **Corregido**: en pantalla completa el video quedaba tapado por el riel y la lista (el video de la TV va detrás de la página); y la verificación de canales podía detenerse al abrir un canal durante la revisión.
- Horas en formato 24 h.

## Guía para canales de Perú
`iptv-org/epg` es una herramienta para *generar* guías, no un archivo publicado. Opciones: (1) pegar en "Agregar guía XMLTV" cualquier guía XMLTV que tengas, o (2) generarla en GitHub Actions con `iptv-org/epg` y pegar la dirección del archivo resultante.

# v4.2 · Selección estricta de canales (Perú, deportes, cine)
- **Solo canales verificados**: en las pestañas Deportes Latino, Perú TV abierta, Películas, Pluto TV y Samsung TV Plus/Plex un canal aparece únicamente después de comprobar, desde TU conexión, que responde y entrega video. Los que caen se ocultan y se revisan de nuevo cada 6 h.
- **Mínimo 720p**: si se conoce que un canal no llega a 720p, no se muestra (excepto en Favoritos y Mis canales). El botón HD sigue mostrando solo 1080p.
- **Sonda más exigente**: una lista sin fragmentos ya no cuenta como "vivo"; los directos .ts se prueban pidiendo solo 1 KB; se mide la velocidad de respuesta y, entre fuentes equivalentes, se prueba primero la más rápida.
- **Fuentes corregidas**: Pluto TV en español existe solo en México, Argentina y Chile (más los canales en español de Pluto EE.UU.); Samsung TV Plus no tiene regiones latinoamericanas (se usa su feed en español de España); Plex: México y España. Las listas de i.mjh.nz fueron retiradas por DMCA en 2024: ahora se usan generadores activos y i.mjh.nz queda solo como último recurso.
- **Palabras clave más precisas**: "gol", "fox", "win", "sky" ya no atrapan Gold, Fox News, Sky Mix, etc. Perú TV abierta suma también la lista curada Free-TV.
- Canales de Perú siempre primero; luego deportes (ESPN, DAZN, DSports, Win, TUDN, beIN, FIFA+, Red Bull TV…) y cine.

# v4.1 · Corrección de imagen en Android TV 14
El video de ExoPlayer ahora se dibuja en una **TextureView** (antes SurfaceView detrás de la WebView, que en Android 14 daba audio sin imagen). Además: selector de pistas tolerante con el decodificador de la TV y detección de "suena pero no hay imagen" con reintento automático.

# Mi TV Deportes v4.0 · reproductor nativo ExoPlayer

**Novedad v4.0:** el video ya no lo reproduce el navegador interno (hls.js) sino **ExoPlayer**, el reproductor nativo de Android
(decodificador por hardware, reintentos propios, cambio automático de fuente, vigilante anticongelamiento).
Esto solo funciona en las **opciones B y C** (las que compilan el proyecto `android/`). La opción A (convertidores online)
sigue usando el reproductor antiguo.

Si la app se cierra sola, al abrirla de nuevo aparece en rojo, arriba, el motivo anotado.

# Mi TV Deportes (interfaz: www/index.html)

## Opción A · La más rápida (sin PC, ~5 min): ZIP → APK online
1. Crea un ZIP que contenga SOLO `index.html` (en la raíz del ZIP, no dentro de una carpeta).
2. Sube el ZIP a un convertidor "HTML ZIP → APK" (AppsGeyser, APKBuild.org u otro similar), pon nombre "Mi TV Deportes" y genera el APK.
3. Pasa el APK a la TV (USB o app "Send Files to TV") e instálalo (permite "orígenes desconocidos").
Limitación: estos convertidores no incluyen el proxy antibloqueo nativo, y algunos canales pueden no abrir.

## Opción B · La mejor (Android Studio, con antibloqueo nativo)
1. Instala Android Studio. 2. File → Open → carpeta `android/`. 3. Espera a que sincronice.
4. Build → Build Bundle(s)/APK(s) → Build APK(s). 5. "locate" → `app-release.apk`.
(`www/index.html` ya está dentro del proyecto; si lo editas, vuelve a compilar.)

## Opción C · GitHub (automático): sube esta carpeta (con .github) → Actions → Releases.

Control remoto: flechas/OK navegan · Atrás sale de pantalla completa · Menú/Info/Rojo = calidad · Canal +/- cambia de canal (estas teclas solo en opciones B y C).
