package ar.com.smtsolutions.reloj;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reconocimiento de rostro de la Terminal SMT. Todo queda en el celular: no se guardan ni se mandan fotos,
 * solo una "huella" numérica de la cara (192 números) por persona, en la memoria de la app.
 * modo "registrar": toma 5 muestras de la cara. modo "verificar": pide parpadear y compara con lo registrado.
 * El resultado queda en SharedPreferences "rostro" (clave "res") y la página lo lee con RelojSMT.rostroResultado().
 */
public class CaraActivity extends ComponentActivity {

    static final String PREFS_CARAS = "caras";
    static final String PREFS_RES = "rostro";
    private static final int PEDIR_CAMARA = 21;
    private static final int TAM = 112;          // entrada del modelo MobileFaceNet
    private static final int MUESTRAS = 5;
    private static final float UMBRAL = 0.62f;   // similitud mínima (coseno) para decir "sos vos"
    private static final long LIMITE_MS = 15000;

    private String modo, nombre, id;
    private PreviewView vista;
    private TextView aviso;
    private FaceDetector detector;
    private Interpreter modelo;
    private ExecutorService hilo;
    private boolean terminado = false, procesando = false;
    private long inicio, ultimaMuestra = 0;
    private final List<float[]> muestras = new ArrayList<>();
    private List<float[]> registradas;
    // Parpadeo: ojos abiertos -> cerrados -> abiertos
    private int etapaOjos = 0;
    private int coincidencias = 0;
    private float mejor = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        modo = getIntent().getStringExtra("modo");
        nombre = getIntent().getStringExtra("nombre");
        id = getIntent().getStringExtra("id");
        if (modo == null || nombre == null) { terminar(false, "Faltan datos"); return; }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1f;
        getWindow().setAttributes(lp);

        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.BLACK);
        vista = new PreviewView(this);
        vista.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        raiz.addView(vista, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout abajo = new LinearLayout(this);
        abajo.setOrientation(LinearLayout.VERTICAL);
        abajo.setPadding(40, 40, 40, 60);
        abajo.setBackgroundColor(0xCC000000);
        aviso = new TextView(this);
        aviso.setTextColor(Color.WHITE);
        aviso.setTextSize(24);
        aviso.setTypeface(Typeface.DEFAULT_BOLD);
        aviso.setGravity(Gravity.CENTER);
        abajo.addView(aviso);
        Button cancelar = new Button(this);
        cancelar.setText("Cancelar");
        cancelar.setOnClickListener(v -> terminar(false, "Cancelado"));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = 30;
        abajo.addView(cancelar, cp);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        raiz.addView(abajo, ap);

        TextView arriba = new TextView(this);
        arriba.setText(nombre);
        arriba.setTextColor(Color.WHITE);
        arriba.setTextSize(30);
        arriba.setTypeface(Typeface.DEFAULT_BOLD);
        arriba.setGravity(Gravity.CENTER);
        arriba.setPadding(20, 60, 20, 20);
        arriba.setBackgroundColor(0x88000000);
        raiz.addView(arriba, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        setContentView(raiz);

        if ("verificar".equals(modo)) {
            registradas = leerRegistradas(nombre);
            if (registradas.isEmpty()) { terminar(false, "sinRegistro"); return; }
            aviso.setText("Mirá a la cámara y parpadeá");
        } else {
            aviso.setText("Mirá a la cámara, sin anteojos de sol ni gorra");
        }

        try {
            modelo = new Interpreter(cargarModelo());
        } catch (Exception e) { terminar(false, "No se pudo cargar el reconocimiento"); return; }
        detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.25f)
                .build());
        hilo = Executors.newSingleThreadExecutor();
        inicio = System.currentTimeMillis();

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) arrancarCamara();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, PEDIR_CAMARA);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == PEDIR_CAMARA) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) arrancarCamara();
            else terminar(false, "Sin permiso de cámara");
        }
    }

    private void arrancarCamara() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                ProcessCameraProvider cp = f.get();
                Preview pv = new Preview.Builder().build();
                pv.setSurfaceProvider(vista.getSurfaceProvider());
                ImageAnalysis an = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(480, 640))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                an.setAnalyzer(hilo, this::analizar);
                cp.unbindAll();
                cp.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, pv, an);
            } catch (Exception e) { terminar(false, "No se pudo abrir la cámara"); }
        }, androidx.core.content.ContextCompat.getMainExecutor(this));
    }

    @SuppressWarnings("UnsafeOptInUsageError")
    private void analizar(ImageProxy img) {
        if (terminado || procesando) { img.close(); return; }
        if (System.currentTimeMillis() - inicio > LIMITE_MS) {
            img.close();
            terminar(false, "verificar".equals(modo) ? "noReconocido" : "Tiempo agotado");
            return;
        }
        procesando = true;
        final int rot = img.getImageInfo().getRotationDegrees();
        final Bitmap crudo;
        try { crudo = img.toBitmap(); } catch (Exception e) { img.close(); procesando = false; return; }
        img.close();
        Bitmap derecho = rotar(crudo, rot);
        InputImage entrada = InputImage.fromBitmap(derecho, 0);
        detector.process(entrada)
                .addOnSuccessListener(caras -> { try { conCaras(caras, derecho); } finally { procesando = false; } })
                .addOnFailureListener(e -> procesando = false);
    }

    private void conCaras(List<Face> caras, Bitmap foto) {
        if (terminado) return;
        if (caras.size() != 1) { avisar(caras.isEmpty() ? "Acercate y mirá a la cámara" : "Que haya una sola persona frente a la cámara"); return; }
        Face c = caras.get(0);
        Rect r = c.getBoundingBox();
        if (r.width() < foto.getWidth() * 0.28f) { avisar("Acercate un poco más"); return; }
        if (Math.abs(c.getHeadEulerAngleY()) > 18 || Math.abs(c.getHeadEulerAngleZ()) > 18) { avisar("Mirá derecho a la cámara"); return; }

        Float oi = c.getLeftEyeOpenProbability(), od = c.getRightEyeOpenProbability();
        boolean abiertos = oi != null && od != null && oi > 0.6f && od > 0.6f;
        boolean cerrados = oi != null && od != null && oi < 0.25f && od < 0.25f;

        if ("registrar".equals(modo)) {
            if (!abiertos) { avisar("Abrí bien los ojos"); return; }
            if (System.currentTimeMillis() - ultimaMuestra < 450) return;
            float[] h = huella(foto, r);
            if (h == null) return;
            muestras.add(h);
            ultimaMuestra = System.currentTimeMillis();
            avisar("Quedate quieto… " + muestras.size() + " de " + MUESTRAS);
            if (muestras.size() >= MUESTRAS) { guardarRegistradas(nombre, muestras); terminar(true, "registrado"); }
            return;
        }

        // Verificar: comparar la cara y exigir un parpadeo
        if (abiertos) {
            float[] h = huella(foto, r);
            if (h != null) {
                float s = similitud(h);
                if (s > mejor) mejor = s;
                if (s >= UMBRAL) coincidencias++;
            }
        }
        if (etapaOjos == 0 && abiertos) etapaOjos = 1;
        else if (etapaOjos == 1 && cerrados) etapaOjos = 2;
        else if (etapaOjos == 2 && abiertos) etapaOjos = 3;

        if (coincidencias >= 2 && etapaOjos >= 3) { terminar(true, "reconocido"); return; }
        if (coincidencias >= 2) avisar("Ahora parpadeá");
        else avisar("Mirá a la cámara y parpadeá");
    }

    private float similitud(float[] h) {
        float best = -1;
        for (float[] g : registradas) {
            float d = 0;
            for (int i = 0; i < h.length; i++) d += h[i] * g[i];
            if (d > best) best = d;
        }
        return best;
    }

    private float[] huella(Bitmap foto, Rect r) {
        try {
            int m = (int) (r.width() * 0.08f);
            int x = Math.max(0, r.left - m), y = Math.max(0, r.top - m);
            int w = Math.min(foto.getWidth() - x, r.width() + 2 * m), hgt = Math.min(foto.getHeight() - y, r.height() + 2 * m);
            if (w < 40 || hgt < 40) return null;
            Bitmap cara = Bitmap.createScaledBitmap(Bitmap.createBitmap(foto, x, y, w, hgt), TAM, TAM, true);
            ByteBuffer in = ByteBuffer.allocateDirect(TAM * TAM * 3 * 4).order(ByteOrder.nativeOrder());
            int[] px = new int[TAM * TAM];
            cara.getPixels(px, 0, TAM, 0, 0, TAM, TAM);
            for (int p : px) {
                in.putFloat((((p >> 16) & 0xFF) - 128f) / 128f);
                in.putFloat((((p >> 8) & 0xFF) - 128f) / 128f);
                in.putFloat(((p & 0xFF) - 128f) / 128f);
            }
            float[][] out = new float[1][192];
            modelo.run(in, out);
            float n = 0;
            for (float v : out[0]) n += v * v;
            n = (float) Math.sqrt(n);
            if (n == 0) return null;
            for (int i = 0; i < 192; i++) out[0][i] /= n;
            return out[0];
        } catch (Exception e) { return null; }
    }

    private Bitmap rotar(Bitmap b, int grados) {
        if (grados == 0) return b;
        Matrix mx = new Matrix();
        mx.postRotate(grados);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
    }

    private MappedByteBuffer cargarModelo() throws Exception {
        AssetFileDescriptor fd = getAssets().openFd("mobile_face_net.tflite");
        try (FileInputStream is = new FileInputStream(fd.getFileDescriptor())) {
            return is.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
    }

    private List<float[]> leerRegistradas(String n) {
        List<float[]> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(getSharedPreferences(PREFS_CARAS, MODE_PRIVATE).getString(n, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONArray v = a.getJSONArray(i);
                float[] f = new float[v.length()];
                for (int j = 0; j < f.length; j++) f[j] = (float) v.getDouble(j);
                l.add(f);
            }
        } catch (Exception e) { }
        return l;
    }

    private void guardarRegistradas(String n, List<float[]> l) {
        try {
            JSONArray a = new JSONArray();
            for (float[] f : l) {
                JSONArray v = new JSONArray();
                for (float x : f) v.put((double) x);
                a.put(v);
            }
            getSharedPreferences(PREFS_CARAS, MODE_PRIVATE).edit().putString(n, a.toString()).apply();
        } catch (Exception e) { }
    }

    private void avisar(String t) { runOnUiThread(() -> { if (!terminado) aviso.setText(t); }); }

    private void terminar(boolean ok, String motivo) {
        if (terminado) return;
        terminado = true;
        try {
            JSONObject o = new JSONObject();
            o.put("id", id == null ? "" : id);
            o.put("ok", ok);
            o.put("modo", modo);
            o.put("nombre", nombre);
            o.put("motivo", motivo);
            o.put("sim", Math.round(mejor * 100) / 100.0);
            getSharedPreferences(PREFS_RES, MODE_PRIVATE).edit().putString("res", o.toString()).apply();
        } catch (Exception e) { }
        runOnUiThread(this::finish);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (detector != null) detector.close(); } catch (Exception e) { }
        try { if (modelo != null) modelo.close(); } catch (Exception e) { }
        if (hilo != null) hilo.shutdown();
    }

    @Override
    public void onBackPressed() { terminar(false, "Cancelado"); }
}
