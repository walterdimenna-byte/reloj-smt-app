package ar.com.smtsolutions.reloj;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import java.util.Locale;

/**
 * Reloj SMT / Panel SMT: abre la app web del reloj (Google Apps Script) en pantalla completa,
 * con permiso de ubicación, memoria propia (no se pierde el registro del celular al abrir desde WhatsApp)
 * y voz en español usando el lector del teléfono.
 */
public class MainActivity extends Activity {

    private static final int PEDIR_UBICACION = 10;
    private static final int ELEGIR_ARCHIVO = 11;

    private WebView web;
    private ProgressBar barra;
    private GeolocationPermissions.Callback ubicacionCallback;
    private String ubicacionOrigen;
    private ValueCallback<Uri[]> archivoCallback;
    private TextToSpeech voz;
    private boolean vozLista = false;

    // Terminal de la entrada: sensor para despertar la pantalla (proximidad y, de respaldo, luz).
    private SensorManager sensores;
    private final Handler mano = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock despierta;   // mantiene el sensor escuchando aunque apaguen la pantalla
    private void prenderSiApagada() {
        try { if (!((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) mano.post(this::prenderPantalla); } catch (Exception e) { }
    }
    // Si alguien apaga la pantalla con el botón, la terminal se vuelve a prender sola a los 2 segundos.
    private final BroadcastReceiver pantallaApagada = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) mano.postDelayed(MainActivity.this::prenderPantalla, 2000);
        }
    };
    private volatile long ultimoSensor = 0;
    private boolean cerca = false;
    private float luzMedia = -1;
    private final SensorEventListener oyente = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent ev) {
            if (ev.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                boolean ahora = ev.values[0] < Math.min(5f, ev.sensor.getMaximumRange());
                if (ahora && !cerca) { ultimoSensor = System.currentTimeMillis(); prenderSiApagada(); }
                cerca = ahora;
            } else if (ev.sensor.getType() == Sensor.TYPE_LIGHT) {
                float lux = ev.values[0];
                if (luzMedia < 0) { luzMedia = lux; return; }
                // Una mano que tapa el celular hace caer mucho la luz de golpe.
                if (luzMedia > 8 && lux < luzMedia * 0.35f && System.currentTimeMillis() - ultimoSensor > 1500) { ultimoSensor = System.currentTimeMillis(); prenderSiApagada(); }
                luzMedia = luzMedia * 0.9f + lux * 0.1f;
            }
        }
        @Override
        public void onAccuracyChanged(Sensor s, int a) { }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout raiz = new FrameLayout(this);
        web = new WebView(this);
        raiz.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        barra = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        barra.setMax(100);
        FrameLayout.LayoutParams pb = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8);
        raiz.addView(barra, pb);
        setContentView(raiz);

        if (BuildConfig.ES_TERMINAL) {
            // Siempre prendida y por encima del bloqueo: la página decide cuándo ponerse en negro con brillo mínimo.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
            else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            sensores = (SensorManager) getSystemService(SENSOR_SERVICE);
            registerReceiver(pantallaApagada, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            try {
                despierta = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smt:sensor");
                despierta.acquire();
            } catch (Exception e) { despierta = null; }
            Sensor p0 = sensores.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            Sensor l0 = sensores.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (p0 != null) sensores.registerListener(oyente, p0, SensorManager.SENSOR_DELAY_NORMAL);
            if (l0 != null) sensores.registerListener(oyente, l0, SensorManager.SENSOR_DELAY_NORMAL);
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString() + " RelojSMT-App/" + BuildConfig.VERSION_NAME);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        // Voz: la app web llama a RelojSMT.hablar("...") si existe (el navegador interno no trae voz propia).
        voz = new TextToSpeech(this, estado -> {
            if (estado == TextToSpeech.SUCCESS) {
                int r = voz.setLanguage(new Locale("es", "AR"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) voz.setLanguage(new Locale("es"));
                vozLista = true;
            }
        });
        web.addJavascriptInterface(new Puente(), "RelojSMT");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (esInterno(u)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* sin app para abrirlo */ }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icono) { barra.setVisibility(View.VISIBLE); }

            @Override
            public void onPageCommitVisible(WebView v, String url) { ocultarCartel(); }

            @Override
            public void onPageFinished(WebView v, String url) { barra.setVisibility(View.GONE); ocultarCartel(); }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) mostrarSinConexion();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                barra.setProgress(p);
                barra.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origen, GeolocationPermissions.Callback cb) {
                if (tieneUbicacion()) {
                    cb.invoke(origen, true, true);
                } else {
                    ubicacionOrigen = origen;
                    ubicacionCallback = cb;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, PEDIR_UBICACION);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (archivoCallback != null) archivoCallback.onReceiveValue(null);
                archivoCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), ELEGIR_ARCHIVO);
                } catch (Exception e) {
                    archivoCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Pedir la ubicación de entrada (solo la app de fichar), así al tocar "Fichar" ya está concedida.
        if (!BuildConfig.ES_PANEL && !BuildConfig.ES_TERMINAL && !tieneUbicacion()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, PEDIR_UBICACION);
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else cargar();
    }

    @SuppressWarnings("deprecation")
    private void prenderPantalla() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isInteractive()) return;
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "smt:terminal");
            wl.acquire(5000);
            Intent v = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(v);
        } catch (Exception e) { /* nada */ }
    }

    private void cargar() {
        if (!hayInternet()) { mostrarSinConexion(); return; }
        web.loadUrl(BuildConfig.URL_INICIO);
    }

    // Oculta el aviso azul "Un usuario de Google Apps Script creó esta aplicación" dentro de nuestra app.
    private static final String SIN_CARTEL = "(function(){try{if(document.getElementById('smt-sin-cartel'))return;"
            + "var s=document.createElement('style');s.id='smt-sin-cartel';"
            + "s.textContent='#warning,.warning-bar{display:none!important}#warning-bar-table>tbody>tr:first-child{display:none!important}';"
            + "(document.head||document.documentElement).appendChild(s);}catch(e){}})();";

    private void ocultarCartel() {
        try { web.evaluateJavascript(SIN_CARTEL, null); } catch (Exception e) { /* nada */ }
    }

    private boolean esInterno(Uri u) {
        String h = u.getHost() == null ? "" : u.getHost();
        return h.equals("script.google.com") || h.endsWith("script.googleusercontent.com")
                || h.equals("accounts.google.com") || "about".equals(u.getScheme()) || "data".equals(u.getScheme());
    }

    private boolean tieneUbicacion() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private boolean hayInternet() {
        try {
            ConnectivityManager c = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo n = c.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Exception e) { return true; }
    }

    private void mostrarSinConexion() {
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{font-family:sans-serif;background:#f3f5f8;margin:0;display:flex;align-items:center;justify-content:center;height:100vh;color:#1c1f24}"
                + ".c{background:#fff;border-radius:16px;padding:28px 22px;margin:16px;text-align:center;box-shadow:0 1px 3px rgba(0,0,0,.1)}"
                + "h2{margin:0 0 8px}p{color:#5f6670;margin:0 0 20px}a{display:block;background:#ffc72c;color:#1c1f24;font-weight:700;"
                + "text-decoration:none;padding:16px;border-radius:12px;font-size:20px}</style></head><body><div class='c'>"
                + "<h2>Sin conexión</h2><p>Revisá el wifi o los datos del celular y probá de nuevo.</p>"
                + "<a href='" + BuildConfig.URL_INICIO + "'>Reintentar</a></div></body></html>";
        web.loadDataWithBaseURL("https://script.google.com/", html, "text/html", "utf-8", null);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == PEDIR_UBICACION && ubicacionCallback != null) {
            boolean ok = false;
            for (int r : res) if (r == PackageManager.PERMISSION_GRANTED) ok = true;
            ubicacionCallback.invoke(ubicacionOrigen, ok, ok);
            ubicacionCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (code == ELEGIR_ARCHIVO && archivoCallback != null) {
            archivoCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            archivoCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (BuildConfig.ES_TERMINAL) return;   // la terminal no se cierra con "atrás"
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensores != null && !BuildConfig.ES_TERMINAL) {
            Sensor p = sensores.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            Sensor l = sensores.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (p != null) sensores.registerListener(oyente, p, SensorManager.SENSOR_DELAY_NORMAL);
            if (l != null) sensores.registerListener(oyente, l, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean foco) {
        super.onWindowFocusChanged(foco);
        if (foco && BuildConfig.ES_TERMINAL) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensores != null && !BuildConfig.ES_TERMINAL) sensores.unregisterListener(oyente);
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (BuildConfig.ES_TERMINAL) {
            try { unregisterReceiver(pantallaApagada); } catch (Exception e) { }
            mano.removeCallbacksAndMessages(null);
            if (sensores != null) sensores.unregisterListener(oyente);
            try { if (despierta != null && despierta.isHeld()) despierta.release(); } catch (Exception e) { }
        }
        if (voz != null) voz.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Lo que la página puede pedirle al teléfono. */
    public class Puente {
        @JavascriptInterface
        public void hablar(String texto) {
            if (vozLista && texto != null) voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "reloj");
        }

        @JavascriptInterface
        public String version() { return BuildConfig.VERSION_NAME; }

        @JavascriptInterface
        public boolean esApp() { return true; }

        @JavascriptInterface
        public boolean esTerminal() { return BuildConfig.ES_TERMINAL; }

        /** Cuándo fue la última vez que alguien pasó la mano por el sensor (milisegundos; 0 = nunca). */
        @JavascriptInterface
        public String ultimoSensor() { return String.valueOf(ultimoSensor); }

        /** Reconocimiento de rostro (solo Terminal SMT): abre la cámara para registrar o verificar la cara de alguien. */
        @JavascriptInterface
        public boolean rostroDisponible() { return BuildConfig.ES_TERMINAL; }

        @JavascriptInterface
        public boolean rostroTiene(String nombre) {
            return getSharedPreferences("caras", MODE_PRIVATE).contains(String.valueOf(nombre));
        }

        @JavascriptInterface
        public void rostroBorrar(String nombre) {
            getSharedPreferences("caras", MODE_PRIVATE).edit().remove(String.valueOf(nombre)).apply();
        }

        @JavascriptInterface
        public void rostroIniciar(final String modo, final String nombre, final String id) {
            if (!BuildConfig.ES_TERMINAL) return;
            getSharedPreferences("rostro", MODE_PRIVATE).edit().remove("res").apply();
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent().setClassName(getPackageName(), "ar.com.smtsolutions.reloj.CaraActivity");
                    i.putExtra("modo", modo).putExtra("nombre", nombre).putExtra("id", id);
                    startActivity(i);
                } catch (Exception e) {
                    getSharedPreferences("rostro", MODE_PRIVATE).edit().putString("res", "{\"id\":\"" + id + "\",\"ok\":false,\"motivo\":\"No se pudo abrir la cámara\"}").apply();
                }
            });
        }

        /** Resultado del último reconocimiento (JSON) o "" si todavía no terminó. */
        @JavascriptInterface
        public String rostroResultado() {
            return getSharedPreferences("rostro", MODE_PRIVATE).getString("res", "");
        }

        /** Brillo de la pantalla: 0 = mínimo (dormida), -1 = el normal del celular. */
        @JavascriptInterface
        public void brillo(final float v) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = v < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.min(1f, v);
                getWindow().setAttributes(lp);
            });
        }
    }
}
