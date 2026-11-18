package pe.mitv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * Mi TV Deportes v4
 *
 * La interfaz (listas, pestañas, favoritos) sigue siendo la página www/index.html dentro de una WebView,
 * pero el VIDEO ya NO lo reproduce el navegador interno: lo reproduce ExoPlayer (nativo, con decodificador
 * por hardware) sobre una TextureView colocada DETRÁS de la WebView. La página es transparente en el
 * rectángulo del video, así que se ve la imagen nativa con la interfaz encima.
 */
@UnstableApi
public class MainActivity extends Activity {
    private WebView web;
    private FrameLayout raiz, caja;
    private TextureView superficie;   // TextureView: forma parte de la jerarquía de vistas, así la WebView transparente no la tapa (Android 14)
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static Context appCtx;
    private static boolean diagListo = false;

    /* Identidades que se prueban en orden si un servidor bloquea (401/403/406/429/451) */
    private static final String[] UAS = {
        "VLC/3.0.20 LibVLC/3.0.20",
        "Mozilla/5.0 (Linux; Android 11; BRAVIA 4K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Lavf/60.3.100"
    };

    /* Más tolerante con transmisiones IPTV "sucias" (cuadros clave no IDR, sin delimitadores de acceso) */
    private static final int TS_FLAGS = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
            | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        appCtx = getApplicationContext();
        instalarDiagnostico();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        /* Capa 1 (fondo): caja negra con la TextureView del video. Capa 2 (encima): la WebView transparente */
        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(0xFF060A12);
        caja = new FrameLayout(this);
        caja.setBackgroundColor(Color.BLACK);
        caja.setVisibility(View.INVISIBLE);
        superficie = new TextureView(this);
        caja.addView(superficie, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        raiz.addView(caja, new FrameLayout.LayoutParams(1, 1));

        web = new WebView(this);
        raiz.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(raiz);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setAllowFileAccessFromFileURLs(true);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);   // transparencia fiable sobre el video en Android 14
        web.addJavascriptInterface(new Puente(), "Nativo");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                try { return proxy(r); } catch (Throwable t) { return null; }
            }
            /* Si el motor de la WebView se cierra por falta de memoria, la app se reinicia sola (y deja una nota de diagnóstico) */
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                guardarDiag(d.didCrash() ? "El motor web falló (crash)" : "El motor web se cerró por falta de memoria");
                recreate();
                return true;
            }
        });
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
    }

    /* ================================================================
       PUENTE JavaScript → Android (la página le pide cosas al reproductor)
       Estas funciones llegan en un hilo secundario: todo se pasa al hilo principal.
       ================================================================ */
    private class Puente {
        @JavascriptInterface public void reproducir(final String json, final String nom, final int alto) {
            ui.post(() -> {
                try {
                    JSONArray a = new JSONArray(json);
                    String[] u = new String[a.length()];
                    for (int i = 0; i < u.length; i++) u[i] = a.getString(i);
                    iniciar(u, nom, alto);
                } catch (Exception e) { js("window.nCaido&&nCaido()"); }
            });
        }
        @JavascriptInterface public void detener() { ui.post(() -> parar(true)); }
        @JavascriptInterface public void calidad(final int alto) { ui.post(() -> { altoMax = alto; aplicarCalidad(); }); }
        @JavascriptInterface public void rect(final int l, final int t, final int w, final int h) { ui.post(() -> ponerRect(l, t, w, h)); }
        @JavascriptInterface public void reanudar() { ui.post(() -> reanudarSiHaceFalta()); }
        /* Nota de por qué se cerró la vez anterior (si pasó) */
        @JavascriptInterface public String diag() {
            try {
                android.content.SharedPreferences sp = getSharedPreferences("diag", MODE_PRIVATE);
                String d = sp.getString("d", "");
                sp.edit().remove("d").apply();
                return d == null ? "" : d;
            } catch (Throwable t) { return ""; }
        }
    }

    /* ================================================================
       REPRODUCTOR NATIVO (ExoPlayer)
       ================================================================ */
    private ExoPlayer player;
    private String[] urls = new String[0];
    private String nombre = "";
    private String detalle = "";
    private String nivelesJson = "[]";
    private int altoMax = 0;                         // 0 = automática
    private int n = 0, uaIdx = 0, vuelta = 0, intento = 0;
    private int reintDec = 0, reintLive = 0, congelado = 0;
    private boolean primerCuadro = false, hayVideo = false;
    private long tListo = 0;
    private boolean forzarProg = false, conectado = false, caido = false, esperando = false, reanudarAlVolver = false;
    private long bufDesde = 0, ultPos = -1, ultPosT = 0;
    private int vw = 0, vh = 0, rw = 0, rh = 0, aw = -1, ah = -1;
    private float par = 1f;

    /* 6 reintentos por fragmento antes de rendirse con una fuente (el valor por defecto es 3) */
    private final DefaultLoadErrorHandlingPolicy politica = new DefaultLoadErrorHandlingPolicy() {
        @Override public int getMinimumLoadableRetryCount(int dataType) { return 6; }
    };

    private void js(String code) { if (web != null) web.evaluateJavascript(code, null); }
    private void estado(String t) { js("window.nEstado&&nEstado(" + JSONObject.quote(t) + ")"); }
    private String sufijo() { return detalle.isEmpty() ? "" : " · " + detalle; }

    /* Ejecuta algo en el hilo principal, solo si el intento sigue siendo el mismo (evita eventos viejos) */
    private void luego(final Runnable r) {
        final int g = intento;
        ui.post(() -> { if (g == intento) r.run(); });
    }

    private void iniciar(String[] u, String nom, int alto) {
        ui.removeCallbacks(reiniciarRonda);
        ui.removeCallbacks(guardia);
        urls = u; nombre = nom; altoMax = alto;
        n = 0; uaIdx = 0; vuelta = 0; detalle = ""; caido = false; esperando = false; forzarProg = false;
        reintDec = 0; reintLive = 0; nivelesJson = "[]";
        if (urls.length == 0) { caido = true; liberarPlayer(); js("window.nCaido&&nCaido()"); return; }
        ui.postDelayed(guardia, 2000);
        conectar();
    }

    /* Para todo. Si olvidar=true también se olvida el canal (si no, se podrá retomar al volver a la app) */
    private void parar(boolean olvidar) {
        ui.removeCallbacks(reiniciarRonda);
        ui.removeCallbacks(guardia);
        liberarPlayer();
        esperando = false;
        if (olvidar) { urls = new String[0]; caido = false; }
    }

    private void reanudarSiHaceFalta() {
        if (urls.length == 0) return;
        boolean muerto = caido || player == null
                || player.getPlaybackState() == Player.STATE_IDLE
                || player.getPlaybackState() == Player.STATE_ENDED;
        if (muerto) iniciar(urls, nombre, altoMax);
    }

    private final Runnable reiniciarRonda = () -> { n = 0; uaIdx = 0; forzarProg = false; conectar(); };

    /* Elige la fuente que toca; cuando se acaban todas, espera y vuelve a empezar (3 rondas) */
    private void conectar() {
        ui.removeCallbacks(reiniciarRonda);
        if (n >= urls.length) {
            vuelta++;
            liberarPlayer();
            if (vuelta >= 3) {   // tras 3 rondas completas se da por caído (la página lo revisa de nuevo en 12 h)
                caido = true; esperando = false;
                js("window.nCaido&&nCaido()");
                return;
            }
            esperando = true;
            estado("Reconectando… (ronda " + (vuelta + 1) + "/3)" + sufijo());
            ui.postDelayed(reiniciarRonda, vuelta * 6000L);
            return;
        }
        esperando = false;
        probar();
    }

    private void fallar() {
        n++; uaIdx = 0; forzarProg = false; reintDec = 0; reintLive = 0;
        conectar();
    }

    private static boolean esProgresivo(String u) {
        String p = Uri.parse(u).getPath();
        if (p == null) return false;
        p = p.toLowerCase();
        return p.endsWith(".ts") || p.endsWith(".mp4") || p.endsWith(".m4v") || p.endsWith(".mkv")
                || p.endsWith(".mp3") || p.endsWith(".aac") || p.endsWith(".flv");
    }

    /* Un intento: reproductor NUEVO y limpio con una fuente y una identidad concretas */
    private void probar() {
        crearPlayer();
        final String u = urls[n];
        conectado = false; congelado = 0; ultPos = -1; primerCuadro = false; hayVideo = false; tListo = 0;
        bufDesde = SystemClock.uptimeMillis(); ultPosT = bufDesde;
        estado("Conectando" + (urls.length > 1 ? " (opción " + (n + 1) + "/" + urls.length + ")" : "") + "…" + sufijo());

        DefaultHttpDataSource.Factory df = new DefaultHttpDataSource.Factory()
                .setUserAgent(UAS[uaIdx])
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(12000)
                .setAllowCrossProtocolRedirects(true);   // http ↔ https

        /* Para directos: se queda ~10 s detrás del borde en vivo para tener colchón ante tirones de red */
        MediaItem item = new MediaItem.Builder()
                .setUri(u)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(10000).setMinOffsetMs(6000).setMaxOffsetMs(30000)
                        .setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.03f).build())
                .build();

        MediaSource ms;
        if (forzarProg || esProgresivo(u)) {
            DefaultExtractorsFactory ef = new DefaultExtractorsFactory().setTsExtractorFlags(TS_FLAGS);
            ms = new ProgressiveMediaSource.Factory(df, ef)
                    .setLoadErrorHandlingPolicy(politica)
                    .createMediaSource(item);
        } else {
            ms = new HlsMediaSource.Factory(df)
                    .setExtractorFactory(new DefaultHlsExtractorFactory(TS_FLAGS, false))
                    .setAllowChunklessPreparation(true)
                    .setLoadErrorHandlingPolicy(politica)
                    .createMediaSource(item);
        }
        player.setMediaSource(ms);
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void crearPlayer() {
        liberarPlayer();
        intento++;
        vw = 0; vh = 0; par = 1f;
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)   // si el decodificador por hardware falla, prueba otro (software)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 2500, 5000)   // mín, máx, para arrancar, tras un corte
                .setTargetBufferBytes(32 * 1024 * 1024)           // tope de memoria para cajitas con poca RAM
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(0, false)                          // en directo no hace falta guardar lo ya visto
                .build();
        DefaultTrackSelector ts = new DefaultTrackSelector(this);
        DefaultTrackSelector.Parameters.Builder pb = ts.buildUponParameters();
        pb.setExceedRendererCapabilitiesIfNecessary(true);      // no descarta una pista que el decodificador de la TV sí puede mover
        pb.setAllowVideoMixedMimeTypeAdaptiveness(true);
        pb.setViewportSizeToPhysicalDisplaySize(this, true);
        ts.setParameters(pb);
        player = new ExoPlayer.Builder(this, rf)
                .setTrackSelector(ts)
                .setLoadControl(lc)
                .setHandleAudioBecomingNoisy(false)
                .build();
        player.setWakeMode(C.WAKE_MODE_NETWORK);   // mantiene CPU y WiFi despiertos mientras se reproduce
        /* Sin enfoque de audio: en una TV nada debe pausar el canal por un aviso del sistema */
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false);
        player.setVideoTextureView(superficie);
        player.addListener(oyente);
        aplicarCalidad();
    }

    private void liberarPlayer() {
        intento++;
        if (player != null) {
            try { player.removeListener(oyente); player.clearVideoSurface(); player.release(); } catch (Throwable t) { /* ya liberado */ }
            player = null;
        }
    }

    private void aplicarCalidad() {
        if (player == null) return;
        int h = altoMax > 0 ? altoMax : Integer.MAX_VALUE;
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters().buildUpon().setMaxVideoSize(Integer.MAX_VALUE, h).build());
    }

    private int alto() { return player == null ? 0 : player.getVideoSize().height; }

    private final Player.Listener oyente = new Player.Listener() {
        @Override public void onPlaybackStateChanged(int st) {
            if (player == null) return;
            if (st == Player.STATE_BUFFERING) {
                if (bufDesde == 0) bufDesde = SystemClock.uptimeMillis();
                if (conectado) estado("Cargando…");
            } else if (st == Player.STATE_READY) {
                bufDesde = 0; ultPosT = SystemClock.uptimeMillis();
                if (tListo == 0) tListo = ultPosT;
                if (!conectado) {
                    conectado = true; reintDec = 0; reintLive = 0; detalle = "";
                    js("window.nListo&&nListo(" + JSONObject.quote(urls[n]) + "," + alto() + "," + nivelesJson + ")");
                } else {
                    js("window.nAlto&&nAlto(" + alto() + ")");
                }
            } else if (st == Player.STATE_ENDED) {
                luego(() -> { detalle = "fin de transmisión"; fallar(); });
            }
        }

        @Override public void onPlayerError(final PlaybackException e) {
            luego(() -> manejarError(e));
        }

        @Override public void onRenderedFirstFrame() { primerCuadro = true; }

        @Override public void onVideoSizeChanged(VideoSize v) {
            vw = v.width; vh = v.height; par = v.pixelWidthHeightRatio > 0 ? v.pixelWidthHeightRatio : 1f;
            ajustarVideo();
            if (conectado) js("window.nAlto&&nAlto(" + v.height + ")");
        }

        @Override public void onTracksChanged(Tracks t) {
            TreeSet<Integer> hs = new TreeSet<>(Collections.reverseOrder());
            for (Tracks.Group g : t.getGroups()) {
                if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
                for (int i = 0; i < g.length; i++) {
                    if (g.isTrackSupported(i)) {
                        int h = g.getTrackFormat(i).height;
                        if (h > 0) hs.add(h);
                    }
                }
            }
            JSONArray a = new JSONArray();
            for (int h : hs) a.put(h);
            nivelesJson = a.toString();
            hayVideo = hs.size() > 0 || t.isTypeSelected(C.TRACK_TYPE_VIDEO);
            if (conectado) js("window.nNiveles&&nNiveles(" + nivelesJson + ")");
        }
    };

    /* Qué hacer según el tipo de error (en vez de rendirse a la primera) */
    private void manejarError(PlaybackException e) {
        if (player == null || caido || esperando) return;
        int code = e.errorCode;
        detalle = e.getErrorCodeName();
        Log.w("MiTV", "Error de reproducción: " + detalle + " en " + urls[n]);

        /* 1) Se quedó atrás de la ventana en vivo: saltar al borde en vivo (no es culpa de la fuente) */
        if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            if (++reintLive <= 4) { player.seekToDefaultPosition(); player.prepare(); return; }
            fallar(); return;
        }
        /* 2) El servidor rechaza nuestra identidad: probar con otra sobre la misma dirección */
        int http = codigoHttp(e);
        if ((http == 401 || http == 403 || http == 406 || http == 429 || http == 451) && uaIdx < UAS.length - 1) {
            uaIdx++; probar(); return;
        }
        /* 3) No se entendió como lista HLS: intentar como video directo (.ts, etc.) */
        if (code >= 3000 && code < 4000 && !forzarProg && !esProgresivo(urls[n])) {
            forzarProg = true; probar(); return;
        }
        /* 4) Falló el decodificador: una repetición con reproductor nuevo */
        if ((code == 4001 || code == 4003) && reintDec++ < 1) { probar(); return; }
        /* 5) Cualquier otra cosa: siguiente fuente */
        fallar();
    }

    private static int codigoHttp(Throwable t) {
        for (int i = 0; t != null && i < 6; i++, t = t.getCause()) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException)
                return ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
        }
        return 0;
    }

    /* Vigilante (cada 2 s): sin datos demasiado tiempo → cambia de fuente; imagen congelada → salta al directo */
    private final Runnable guardia = new Runnable() {
        @Override public void run() {
            if (player != null && !caido && !esperando) {
                long ahora = SystemClock.uptimeMillis();
                int st = player.getPlaybackState();
                if (st == Player.STATE_READY && player.getPlayWhenReady()) {
                    long pos = player.getCurrentPosition();
                    if (pos != ultPos) { ultPos = pos; ultPosT = ahora; congelado = 0; }
                    else if (ahora - ultPosT > 8000) {
                        ultPosT = ahora; congelado++;
                        if (congelado == 1) { player.seekToDefaultPosition(); }
                        else { detalle = "imagen congelada"; fallar(); }
                    }
                    bufDesde = 0;
                    /* Suena pero no hay imagen: se rehace la conexión (otro decodificador/fuente) en vez de quedarse en negro */
                    if (hayVideo && !primerCuadro && tListo > 0 && ahora - tListo > 8000) {
                        tListo = 0; detalle = "sin imagen";
                        if (reintDec++ < 1) probar(); else fallar();
                    }
                } else if (st == Player.STATE_BUFFERING || st == Player.STATE_IDLE) {
                    if (bufDesde == 0) bufDesde = ahora;
                    if (ahora - bufDesde > (conectado ? 20000 : 15000)) { detalle = "sin datos"; fallar(); }
                }
            }
            ui.postDelayed(this, 2000);
        }
    };

    /* ================================================================
       POSICIÓN DEL VIDEO: la página dice dónde está su rectángulo de video
       ================================================================ */
    private void ponerRect(int l, int t, int w, int h) {
        rw = w; rh = h;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(w, 1), Math.max(h, 1));
        lp.leftMargin = l; lp.topMargin = t; lp.gravity = Gravity.TOP | Gravity.LEFT;
        caja.setLayoutParams(lp);
        caja.setVisibility(w > 8 && h > 8 ? View.VISIBLE : View.INVISIBLE);
        aw = -1; ah = -1;
        ajustarVideo();
    }

    /* Mantiene la proporción de la imagen (barras negras en lugar de estirarla) */
    private void ajustarVideo() {
        int w, h;
        if (rw < 8 || rh < 8 || vw <= 0 || vh <= 0) { w = ViewGroup.LayoutParams.MATCH_PARENT; h = ViewGroup.LayoutParams.MATCH_PARENT; }
        else {
            float ar = vw * par / vh;
            w = rw; h = Math.round(rw / ar);
            if (h > rh) { h = rh; w = Math.round(rh * ar); }
        }
        if (w == aw && h == ah) return;
        aw = w; ah = h;
        superficie.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }

    /* ================================================================
       DIAGNÓSTICO: si la app se cierra por un error, queda anotado y se muestra al abrirla de nuevo
       ================================================================ */
    private static void guardarDiag(String m) {
        try { if (appCtx != null) appCtx.getSharedPreferences("diag", MODE_PRIVATE).edit().putString("d", m).commit(); } catch (Throwable t) { /* nada */ }
    }

    private static void instalarDiagnostico() {
        if (diagListo) return;
        diagListo = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            String st = Log.getStackTraceString(e);
            guardarDiag("Se cerró por un error: " + (st.length() > 300 ? st.substring(0, 300) : st));
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /* ===== Proxy nativo antibloqueo (para listas y sondeos de la página; el video ya no pasa por aquí) =====
       Todas las peticiones salen desde aquí: sin Referer ni Origin (los servidores que bloquean por dominio
       dejan de rechazarlas), con identidad de reproductor y reintento con otra identidad si hay bloqueo,
       siguiendo redirecciones http↔https y con cabeceras CORS. */
    private WebResourceResponse proxy(WebResourceRequest r) throws Exception {
        Uri u = r.getUrl();
        String sch = u.getScheme(), host = u.getHost() == null ? "" : u.getHost();
        if (!"http".equals(sch) && !"https".equals(sch)) return null;
        if (host.endsWith("googleapis.com") || host.endsWith("gstatic.com")) return null;   // tipografías: que las maneje la WebView
        String method = r.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) return resp(204, "No Content", "text/plain", new ByteArrayInputStream(new byte[0]), null);
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) return null;
        String range = r.getRequestHeaders() == null ? null : r.getRequestHeaders().get("Range");

        HttpURLConnection c = null;
        String origen = u.toString(), cur = origen;
        for (int ua = 0; ua < UAS.length; ua++) {
            cur = origen;
            c = null;
            for (int hop = 0; hop < 8; hop++) {
                try {
                    c = (HttpURLConnection) new URL(cur).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(15000);
                    c.setRequestMethod(method.toUpperCase());
                    c.setRequestProperty("User-Agent", UAS[ua]);
                    c.setRequestProperty("Accept", "*/*");
                    c.setRequestProperty("Connection", "keep-alive");
                    if (range != null) c.setRequestProperty("Range", range);
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                        cur = new URL(new URL(cur), c.getHeaderField("Location")).toString();
                        c.disconnect();
                        continue;
                    }
                    break;
                } catch (Exception e) {
                    if (c != null) c.disconnect();
                    c = null;
                    break;
                }
            }
            if (c == null) { if (ua == UAS.length - 1) break; else continue; }
            int code = c.getResponseCode();
            if ((code == 401 || code == 403 || code == 406 || code == 429 || code == 451) && ua < UAS.length - 1) { c.disconnect(); continue; }
            break;
        }
        if (c == null) return resp(502, "Bad Gateway", "text/plain", new ByteArrayInputStream(new byte[0]), null);

        final HttpURLConnection fc = c;
        int code = c.getResponseCode();
        String msg = c.getResponseMessage();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) in = new ByteArrayInputStream(new byte[0]);
        /* Si el servidor ignora "Range" y manda todo el archivo, se corta a lo pedido (los sondeos piden 1 KB) */
        if (range != null && code == 200) {
            long lim = limiteRango(range);
            if (lim > 0) in = new Corto(in, lim);
        }
        /* Al cerrar la lectura se cierra también la conexión: nada queda colgado consumiendo red ni memoria */
        in = new FilterInputStream(in) {
            @Override public void close() throws IOException {
                try { super.close(); } catch (IOException e) { /* ya cerrado */ } finally { fc.disconnect(); }
            }
        };
        String ct = c.getContentType();
        String mime = ct == null ? "application/octet-stream" : ct.split(";")[0].trim();
        String path = Uri.parse(cur).getPath() == null ? "" : Uri.parse(cur).getPath().toLowerCase();
        boolean lista = path.endsWith(".m3u8") || path.endsWith(".m3u") || mime.contains("mpegurl");
        if (lista && (mime.equals("application/octet-stream") || mime.startsWith("text/"))) mime = "application/vnd.apple.mpegurl";
        else if (path.endsWith(".ts") && mime.equals("application/octet-stream")) mime = "video/mp2t";

        Map<String, String> extra = new HashMap<>();
        if (c.getHeaderField("Content-Range") != null) extra.put("Content-Range", c.getHeaderField("Content-Range"));
        if (c.getHeaderField("Accept-Ranges") != null) extra.put("Accept-Ranges", c.getHeaderField("Accept-Ranges"));

        /* Si hubo redirección a otro host, las rutas relativas de la lista se vuelven absolutas */
        if (lista && !cur.equals(origen) && code < 400) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int k;
            while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
            in.close();
            String txt = reescribir(new String(bo.toByteArray(), StandardCharsets.UTF_8), cur);
            return resp(code, msg, mime, new ByteArrayInputStream(txt.getBytes(StandardCharsets.UTF_8)), extra);
        }
        return resp(code, msg, mime, in, extra);
    }

    private static final Pattern RANGO = Pattern.compile("bytes=0-(\\d+)");
    private static long limiteRango(String range) {
        Matcher m = RANGO.matcher(range);
        if (!m.find()) return 0;
        try { return Long.parseLong(m.group(1)) + 1; } catch (NumberFormatException e) { return 0; }
    }

    /* Lectura limitada a N bytes */
    private static class Corto extends FilterInputStream {
        private long resta;
        Corto(InputStream in, long max) { super(in); resta = max; }
        @Override public int read() throws IOException {
            if (resta <= 0) return -1;
            int b = super.read();
            if (b >= 0) resta--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (resta <= 0) return -1;
            int k = super.read(b, off, (int) Math.min(len, resta));
            if (k > 0) resta -= k;
            return k;
        }
    }

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    private String reescribir(String txt, String base) {
        StringBuilder sb = new StringBuilder();
        for (String l : txt.split("\\r?\\n", -1)) {
            String t = l.trim();
            if (t.isEmpty()) { sb.append(l); }
            else if (t.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(l);
                StringBuffer o = new StringBuffer();
                while (m.find()) m.appendReplacement(o, Matcher.quoteReplacement("URI=\"" + absoluta(base, m.group(1)) + "\""));
                m.appendTail(o);
                sb.append(o);
            } else sb.append(absoluta(base, t));
            sb.append('\n');
        }
        return sb.toString();
    }

    private String absoluta(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); } catch (Exception e) { return rel; }
    }

    private WebResourceResponse resp(int code, String msg, String mime, InputStream in, Map<String, String> extra) {
        if (code < 100 || code > 599 || (code >= 300 && code < 400)) code = 502;
        if (msg == null || msg.isEmpty()) msg = code < 400 ? "OK" : "Error";
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Access-Control-Allow-Headers", "*");
        h.put("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        h.put("Access-Control-Expose-Headers", "*");
        h.put("Cache-Control", "no-cache");
        if (extra != null) h.putAll(extra);
        return new WebResourceResponse(mime, "utf-8", code, msg, h, in);
    }

    /* ===== Control remoto: canal +/-, Menú/Info → calidad ===== */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            String js = null;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_PAGE_UP: js = "window.canalMenos&&canalMenos()"; break;
                case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN: js = "window.canalMas&&canalMas()"; break;
                case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_PROG_RED: js = "window.menuCalidad&&menuCalidad()"; break;
            }
            if (js != null) { web.evaluateJavascript(js, null); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("(window.atras&&window.atras())?'1':'0'", v -> {
            if (!"\"1\"".equals(v)) finish();
        });
    }

    /* ===== Ciclo de vida: al salir de la app se libera el decodificador; al volver, el canal se retoma solo ===== */
    @Override protected void onStart() {
        super.onStart();
        if (reanudarAlVolver) {
            reanudarAlVolver = false;
            if (urls.length > 0) iniciar(urls, nombre, altoMax);
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (urls.length > 0) { reanudarAlVolver = true; parar(false); }
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        ui.postDelayed(() -> web.evaluateJavascript("window.alVolver&&alVolver()", null), 1500);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        liberarPlayer();
        if (web != null) {
            raiz.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
