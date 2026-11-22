package com.grigor.camlink;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone that films. Shows a preview, listens for a remote on the local network,
 * and records video when the remote says START / STOP.
 *
 * Protocol (one text line per message, TCP port {@link #PORT}):
 *   remote -> camera:  START | STOP | PING
 *   camera -> remote:  STATE IDLE | STATE RECORDING | SAVED <where> | ERR <message>
 */
public class CameraActivity extends Activity {

    static final int PORT = 8765;
    static final int HTTP_PORT = 8080;
    static final String SERVICE_TYPE = "_camlink._tcp.";
    private static final int REQ_PERMS = 1;

    // ---- UI ----
    private AutoFitTextureView textureView;
    private TextView statusView;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- camera ----
    private HandlerThread camThread;
    private Handler cam; // every Camera2 / MediaRecorder call runs here
    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private MediaRecorder recorder;
    private File outFile;
    private String cameraId;
    private Size videoSize;
    private int sensorOrientation = 90;
    private volatile boolean recording;
    private boolean opening;

    // ---- network ----
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private ServerSocket server;
    private Socket client;
    private PrintWriter clientOut;
    private volatile boolean serving;
    private ServerSocket httpServer;
    private volatile String lastMsg = "";
    private final String pin = String.format(Locale.US, "%04d", new java.util.Random().nextInt(10000));
    private NsdManager nsd;
    private NsdManager.RegistrationListener regListener;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        textureView = new AutoFitTextureView(this);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        root.addView(textureView, tlp);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(16);
        statusView.setBackgroundColor(0xAA000000);
        int p = dp(12);
        statusView.setPadding(p, p, p, p);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(statusView, slp);

        setContentView(root);
        setStatus("Starting…");

        if (!hasPerms()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasPerms()) return;
        camThread = new HandlerThread("camera");
        camThread.start();
        cam = new Handler(camThread.getLooper());
        startServer();
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
            });
        }
    }

    @Override
    protected void onPause() {
        stopServer();
        if (cam != null) {
            // Finish any recording cleanly (so the file is saved), then close the camera.
            final HandlerThread thread = camThread;
            cam.post(() -> {
                if (recording) stopRecording();
                closeCamera();
                thread.quitSafely();
            });
            cam = null;
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (hasPerms()) {
            onResume(); // onResume ran before permission was granted and returned early
        } else {
            setStatus("Camera and microphone permission are needed.");
        }
    }

    private boolean hasPerms() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // =====================================================================
    // Camera
    // =====================================================================

    private void openCamera() {
        final Handler h = cam;
        if (h == null) return;
        h.post(() -> {
            if (cameraDevice != null || opening) return;
            try {
                CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                cameraId = null;
                for (String id : mgr.getCameraIdList()) {
                    CameraCharacteristics c = mgr.getCameraCharacteristics(id);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        cameraId = id;
                        break;
                    }
                }
                if (cameraId == null) {
                    String[] ids = mgr.getCameraIdList();
                    if (ids.length == 0) { setStatus("No camera found on this phone."); return; }
                    cameraId = ids[0];
                }
                CameraCharacteristics ch = mgr.getCameraCharacteristics(cameraId);
                Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                sensorOrientation = so == null ? 90 : so;
                StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
                // Portrait screen: the sensor image is rotated, so swap width/height for the view.
                final boolean swap = sensorOrientation % 180 == 90;
                final int vw = swap ? videoSize.getHeight() : videoSize.getWidth();
                final int vh = swap ? videoSize.getWidth() : videoSize.getHeight();
                ui.post(() -> textureView.setAspect(vw, vh));

                opening = true;
                mgr.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice d) {
                        opening = false;
                        cameraDevice = d;
                        startPreview();
                    }
                    @Override public void onDisconnected(CameraDevice d) {
                        opening = false;
                        d.close();
                        cameraDevice = null;
                    }
                    @Override public void onError(CameraDevice d, int e) {
                        opening = false;
                        d.close();
                        cameraDevice = null;
                        setStatus("Camera error " + e);
                    }
                }, h);
            } catch (CameraAccessException | SecurityException e) {
                opening = false;
                setStatus("Cannot open camera: " + e.getMessage());
            }
        });
    }

    /** Biggest 16:9 size up to 1080p; falls back to the biggest size up to 1080p wide. */
    private static Size chooseVideoSize(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() <= 1920 && s.getHeight() <= 1080 && s.getWidth() * 9 == s.getHeight() * 16) {
                if (best == null || s.getWidth() > best.getWidth()) best = s;
            }
        }
        if (best != null) return best;
        for (Size s : sizes) {
            if (s.getWidth() <= 1920) {
                if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) best = s;
            }
        }
        return best != null ? best : sizes[0];
    }

    private void startPreview() {
        if (cameraDevice == null || !textureView.isAvailable()) return;
        try {
            closeSession();
            SurfaceTexture st = textureView.getSurfaceTexture();
            st.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            final Surface preview = new Surface(st);
            final CaptureRequest.Builder rb = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(preview);
            cameraDevice.createCaptureSession(Collections.singletonList(preview),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            if (cameraDevice == null) return;
                            session = s;
                            try {
                                rb.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                s.setRepeatingRequest(rb.build(), null, cam);
                            } catch (CameraAccessException | IllegalStateException e) {
                                setStatus("Preview failed: " + e.getMessage());
                            }
                            showIdleStatus();
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            setStatus("Preview configuration failed.");
                        }
                    }, cam);
        } catch (CameraAccessException | IllegalStateException e) {
            setStatus("Preview failed: " + e.getMessage());
        }
    }

    private void startRecording() {
        if (recording || cameraDevice == null || !textureView.isAvailable()) {
            if (!recording) send("ERR Camera not ready");
            return;
        }
        try {
            closeSession();
            setupRecorder();
            SurfaceTexture st = textureView.getSurfaceTexture();
            st.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            final Surface preview = new Surface(st);
            final Surface recSurface = recorder.getSurface();
            final CaptureRequest.Builder rb = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            rb.addTarget(preview);
            rb.addTarget(recSurface);
            cameraDevice.createCaptureSession(Arrays.asList(preview, recSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                rb.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                s.setRepeatingRequest(rb.build(), null, cam);
                                recorder.start();
                                recording = true;
                                setStatus("● RECORDING");
                                send("STATE RECORDING");
                            } catch (Exception e) {
                                failRecording("Could not start recording: " + e.getMessage());
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            failRecording("Recording configuration failed.");
                        }
                    }, cam);
        } catch (Exception e) {
            failRecording("Could not start recording: " + e.getMessage());
        }
    }

    private void failRecording(String msg) {
        recording = false;
        releaseRecorder();
        if (outFile != null) outFile.delete();
        send("ERR " + msg);
        send("STATE IDLE");
        setStatus(msg);
        startPreview();
    }

    private void stopRecording() {
        if (!recording) {
            send("STATE IDLE");
            return;
        }
        recording = false;
        closeSession();
        String result;
        try {
            recorder.stop();
            result = saveToGallery(outFile);
        } catch (RuntimeException e) {
            // stop() throws when almost nothing was recorded.
            if (outFile != null) outFile.delete();
            result = null;
            send("ERR Recording too short, nothing saved");
        }
        releaseRecorder();
        send("STATE IDLE");
        if (result != null) send("SAVED " + result);
        startPreview();
    }

    private void setupRecorder() throws IOException {
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        outFile = new File(dir, "CameraLink_" + stamp + ".mp4");

        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(outFile.getAbsolutePath());
        recorder.setVideoEncodingBitRate(10_000_000);
        recorder.setVideoFrameRate(30);
        recorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(128_000);
        recorder.setAudioSamplingRate(44_100);
        recorder.setOrientationHint(sensorOrientation); // app is locked to portrait
        recorder.prepare();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) { }
            recorder.release();
            recorder = null;
        }
    }

    /** Copies the clip into the phone's gallery (Movies/CameraLink). Returns a description of where it is. */
    private String saveToGallery(File f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return f.getAbsolutePath(); // older phones: keep it in the app's movie folder
        }
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, f.getName());
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CameraLink");
            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return f.getAbsolutePath();
            try (InputStream in = new FileInputStream(f);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return "Movies/CameraLink/" + f.getName();
        } catch (IOException | RuntimeException e) {
            return f.getAbsolutePath();
        }
    }

    private void closeSession() {
        if (session != null) {
            try { session.stopRepeating(); session.abortCaptures(); } catch (Exception ignored) { }
            session.close();
            session = null;
        }
    }

    private void closeCamera() {
        closeSession();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        releaseRecorder();
    }

    // =====================================================================
    // Network
    // =====================================================================

    private void startServer() {
        if (serving) return;
        serving = true;
        new Thread(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                registerNsd();
                startHttp();
                showIdleStatus();
                while (serving) {
                    final Socket s = server.accept();
                    s.setKeepAlive(true);
                    s.setTcpNoDelay(true);
                    synchronized (this) {
                        closeQuietly(client); // newest remote wins
                        client = s;
                        clientOut = new PrintWriter(s.getOutputStream(), true);
                    }
                    new Thread(() -> readClient(s), "client").start();
                }
            } catch (IOException e) {
                if (serving) setStatus("Network error: " + e.getMessage());
            }
        }, "server").start();
    }

    // ---- tiny web server so an iPhone (or any browser) can be the remote ----

    private void startHttp() {
        try {
            httpServer = new ServerSocket();
            httpServer.setReuseAddress(true);
            httpServer.bind(new InetSocketAddress(HTTP_PORT));
        } catch (IOException e) {
            return; // the Android remote still works
        }
        final ServerSocket hs = httpServer;
        new Thread(() -> {
            try {
                while (serving) {
                    final Socket s = hs.accept();
                    new Thread(() -> handleHttp(s), "http").start();
                }
            } catch (IOException ignored) { }
        }, "http-server").start();
    }

    private void handleHttp(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String req = r.readLine();
            if (req == null) return;
            String h;
            while ((h = r.readLine()) != null && !h.isEmpty()) { /* skip headers */ }
            String[] parts = req.split(" ");
            String target = parts.length > 1 ? parts[1] : "/";
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            boolean authed = ("&" + query + "&").contains("&k=" + pin + "&");

            int code = 200;
            String type = "text/plain; charset=utf-8";
            byte[] body;
            if (path.equals("/")) {
                type = "text/html; charset=utf-8";
                body = readAsset("remote.html");
            } else if (!authed) {
                code = 403;
                body = "Wrong or missing PIN".getBytes("UTF-8");
            } else if (path.equals("/start")) {
                Handler c = cam;
                if (c != null) c.post(this::startRecording);
                body = "ok".getBytes("UTF-8");
            } else if (path.equals("/stop")) {
                Handler c = cam;
                if (c != null) c.post(this::stopRecording);
                body = "ok".getBytes("UTF-8");
            } else if (path.equals("/state")) {
                body = ((recording ? "RECORDING" : "IDLE") + "\n" + lastMsg).getBytes("UTF-8");
            } else {
                code = 404;
                body = "Not found".getBytes("UTF-8");
            }
            String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream o = sock.getOutputStream();
            o.write(head.getBytes("UTF-8"));
            o.write(body);
            o.flush();
        } catch (IOException ignored) { }
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private void readClient(Socket s) {
        try {
            send("STATE " + (recording ? "RECORDING" : "IDLE"));
            showIdleStatus();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                final Handler h = cam;
                if (h == null) continue;
                if (line.equals("START")) h.post(this::startRecording);
                else if (line.equals("STOP")) h.post(this::stopRecording);
                else if (line.equals("PING")) send("STATE " + (recording ? "RECORDING" : "IDLE"));
            }
        } catch (IOException ignored) {
        }
        synchronized (this) {
            if (client == s) { client = null; clientOut = null; }
        }
        closeQuietly(s);
        // If the remote vanished mid-recording, keep recording: footage matters more.
        if (serving) showIdleStatus();
    }

    private void send(final String msg) {
        if (msg.startsWith("SAVED ")) lastMsg = "Saved on the camera phone: " + msg.substring(6);
        else if (msg.startsWith("ERR ")) lastMsg = msg.substring(4);
        else if (msg.equals("STATE RECORDING")) lastMsg = "";
        net.execute(() -> {
            PrintWriter w;
            synchronized (CameraActivity.this) { w = clientOut; }
            if (w != null) w.println(msg);
        });
    }

    private void stopServer() {
        serving = false;
        unregisterNsd();
        synchronized (this) {
            closeQuietly(client);
            client = null;
            clientOut = null;
        }
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
        try { if (httpServer != null) httpServer.close(); } catch (IOException ignored) { }
        httpServer = null;
    }

    private static void closeQuietly(Socket s) {
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    private void registerNsd() {
        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("CameraLink-" + Build.MODEL);
            info.setServiceType(SERVICE_TYPE);
            info.setPort(PORT);
            regListener = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo i, int e) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
                @Override public void onServiceRegistered(NsdServiceInfo i) { }
                @Override public void onServiceUnregistered(NsdServiceInfo i) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener);
        } catch (Exception ignored) {
            // Discovery is a convenience; the remote can always type the IP address.
        }
    }

    private void unregisterNsd() {
        try {
            if (nsd != null && regListener != null) nsd.unregisterService(regListener);
        } catch (Exception ignored) { }
        regListener = null;
    }

    private String localIp() {
        try {
            List<NetworkInterface> ifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            List<String> found = new ArrayList<>();
            for (NetworkInterface ni : ifs) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) found.add(a.getHostAddress());
                }
            }
            if (!found.isEmpty()) return found.get(0);
        } catch (Exception ignored) { }
        return null;
    }

    private void showIdleStatus() {
        if (recording) return;
        String ip = localIp();
        boolean connected;
        synchronized (this) { connected = client != null; }
        String where = ip == null ? "(connect to Wi-Fi or a hotspot first)" : ip + ":" + PORT;
        setStatus((connected ? "Remote connected ✔\n" : "Waiting for the remote…\n")
                + "Android remote address: " + where
                + (ip == null ? "" : "\n\niPhone / any browser:\nhttp://" + ip + ":" + HTTP_PORT + "/?k=" + pin));
    }

    private void setStatus(final String text) {
        ui.post(() -> statusView.setText(text));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** A TextureView that keeps the camera's aspect ratio. */
    static class AutoFitTextureView extends TextureView {
        private int ratioW, ratioH;

        AutoFitTextureView(Context c) { super(c); }

        void setAspect(int w, int h) {
            ratioW = w;
            ratioH = h;
            requestLayout();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = View.MeasureSpec.getSize(wSpec);
            int h = View.MeasureSpec.getSize(hSpec);
            if (ratioW == 0 || ratioH == 0) {
                setMeasuredDimension(w, h);
            } else if (w * ratioH < h * ratioW) {
                setMeasuredDimension(w, w * ratioH / ratioW);
            } else {
                setMeasuredDimension(h * ratioW / ratioH, h);
            }
        }
    }
}
