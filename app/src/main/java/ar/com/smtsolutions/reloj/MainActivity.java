package ar.com.smtsolutions.reloj;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.ViewGroup;
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
            public void onPageFinished(WebView v, String url) { barra.setVisibility(View.GONE); }

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
        if (!BuildConfig.ES_PANEL && !tieneUbicacion()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, PEDIR_UBICACION);
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else cargar();
    }

    private void cargar() {
        if (!hayInternet()) { mostrarSinConexion(); return; }
        web.loadUrl(BuildConfig.URL_INICIO);
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
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
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
    }
}
