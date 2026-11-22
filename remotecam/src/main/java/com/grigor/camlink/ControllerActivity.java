package com.grigor.camlink;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The remote. Finds the filming phone on the local network (or takes its IP address),
 * then shows one big Start/Stop button.
 */
public class ControllerActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    // connection
    private Socket socket;
    private PrintWriter out;
    private volatile boolean connected;
    private boolean recording;
    private long recordStart;

    // discovery
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final Set<String> seen = new HashSet<>();

    // views
    private LinearLayout connectPanel, controlPanel, foundList;
    private TextView connectStatus, controlStatus, timerView;
    private EditText ipField;
    private Button bigButton;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (recording) {
                long s = (System.currentTimeMillis() - recordStart) / 1000;
                timerView.setText(String.format("%02d:%02d", s / 60, s % 60));
                ui.postDelayed(this, 500);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayoutHolder h = new FrameLayoutHolder();
        setContentView(h.root);
        showConnectPanel("Looking for the filming phone…");
        startDiscovery();
    }

    // ------------------------------------------------------------------ UI

    /** Builds both panels; only one is visible at a time. */
    private class FrameLayoutHolder {
        final android.widget.FrameLayout root = new android.widget.FrameLayout(ControllerActivity.this);

        FrameLayoutHolder() {
            root.setBackgroundColor(Color.parseColor("#101018"));
            root.addView(buildConnectPanel());
            root.addView(buildControlPanel());
        }
    }

    private View buildConnectPanel() {
        ScrollView sv = new ScrollView(this);
        connectPanel = new LinearLayout(this);
        connectPanel.setOrientation(LinearLayout.VERTICAL);
        int p = dp(24);
        connectPanel.setPadding(p, p * 2, p, p);

        connectPanel.addView(text("Connect to the camera", 26, Color.WHITE, true));
        connectStatus = text("", 16, Color.parseColor("#B0B0C0"), false);
        connectStatus.setPadding(0, dp(8), 0, dp(16));
        connectPanel.addView(connectStatus);

        foundList = new LinearLayout(this);
        foundList.setOrientation(LinearLayout.VERTICAL);
        connectPanel.addView(foundList);

        TextView or = text("Or type the address shown on the camera phone:", 14, Color.parseColor("#B0B0C0"), false);
        or.setPadding(0, dp(24), 0, dp(8));
        connectPanel.addView(or);

        ipField = new EditText(this);
        ipField.setHint("192.168.1.23");
        ipField.setTextColor(Color.WHITE);
        ipField.setHintTextColor(Color.GRAY);
        ipField.setInputType(InputType.TYPE_CLASS_PHONE);
        ipField.setSingleLine(true);
        SharedPreferences prefs = getSharedPreferences("camlink", MODE_PRIVATE);
        ipField.setText(prefs.getString("ip", ""));
        connectPanel.addView(ipField);

        Button go = button("Connect", "#1565C0");
        go.setOnClickListener(v -> {
            String ip = ipField.getText().toString().trim();
            // allow "ip:port" pasted from the camera screen
            if (ip.contains(":")) ip = ip.substring(0, ip.indexOf(':'));
            if (!ip.isEmpty()) connect(ip);
        });
        connectPanel.addView(go);

        sv.addView(connectPanel);
        return sv;
    }

    private View buildControlPanel() {
        controlPanel = new LinearLayout(this);
        controlPanel.setOrientation(LinearLayout.VERTICAL);
        controlPanel.setGravity(Gravity.CENTER);
        controlPanel.setVisibility(View.GONE);
        int p = dp(24);
        controlPanel.setPadding(p, p, p, p);

        controlStatus = text("", 18, Color.WHITE, true);
        controlStatus.setGravity(Gravity.CENTER);
        controlPanel.addView(controlStatus);

        timerView = text("00:00", 48, Color.WHITE, true);
        timerView.setGravity(Gravity.CENTER);
        timerView.setPadding(0, dp(16), 0, dp(32));
        controlPanel.addView(timerView);

        bigButton = new Button(this);
        bigButton.setTextColor(Color.WHITE);
        bigButton.setTextSize(24);
        bigButton.setTypeface(Typeface.DEFAULT_BOLD);
        bigButton.setAllCaps(false);
        int size = dp(220);
        controlPanel.addView(bigButton, new LinearLayout.LayoutParams(size, size));
        bigButton.setOnClickListener(v -> {
            bigButton.setEnabled(false); // re-enabled when the camera confirms
            sendLine(recording ? "STOP" : "START");
        });

        Button disc = button("Disconnect", "#444455");
        disc.setOnClickListener(v -> disconnect("Disconnected."));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) disc.getLayoutParams();
        lp.topMargin = dp(40);
        controlPanel.addView(disc);

        return controlPanel;
    }

    private void showConnectPanel(String status) {
        controlPanel.setVisibility(View.GONE);
        connectPanel.setVisibility(View.VISIBLE);
        connectStatus.setText(status);
    }

    private void showControlPanel() {
        connectPanel.setVisibility(View.GONE);
        ((View) connectPanel.getParent()).setVisibility(View.GONE); // the ScrollView
        controlPanel.setVisibility(View.VISIBLE);
    }

    private void applyState(boolean rec) {
        boolean was = recording;
        recording = rec;
        bigButton.setEnabled(true);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        if (rec) {
            d.setColor(Color.parseColor("#C62828"));
            bigButton.setText("STOP");
            controlStatus.setText("● Recording");
            if (!was) {
                recordStart = System.currentTimeMillis();
                ui.removeCallbacks(tick);
                ui.post(tick);
            }
        } else {
            d.setColor(Color.parseColor("#2E7D32"));
            bigButton.setText("START");
            controlStatus.setText("Ready");
            ui.removeCallbacks(tick);
        }
        bigButton.setBackground(d);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, String color) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        btn.setBackgroundColor(Color.parseColor(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(12);
        btn.setLayoutParams(lp);
        return btn;
    }

    // ----------------------------------------------------------- discovery

    private void startDiscovery() {
        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            discovery = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String t, int e) { }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onServiceLost(NsdServiceInfo i) { }
                @Override public void onServiceFound(final NsdServiceInfo info) {
                    ui.post(() -> addFound(info));
                }
            };
            nsd.discoverServices(CameraActivity.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception ignored) {
            // Manual IP entry still works.
        }
    }

    private void addFound(final NsdServiceInfo info) {
        if (!seen.add(info.getServiceName())) return;
        connectStatus.setText("Found a camera — tap it to connect:");
        Button btn = button("🎥  " + info.getServiceName(), "#2E7D32");
        btn.setOnClickListener(v -> {
            connectStatus.setText("Connecting…");
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int e) {
                    ui.post(() -> connectStatus.setText("Could not resolve it. Type the IP address instead."));
                }
                @Override public void onServiceResolved(NsdServiceInfo i) {
                    final String host = i.getHost().getHostAddress();
                    ui.post(() -> connect(host));
                }
            });
        });
        foundList.addView(btn);
    }

    private void stopDiscovery() {
        try {
            if (nsd != null && discovery != null) nsd.stopServiceDiscovery(discovery);
        } catch (Exception ignored) { }
        discovery = null;
    }

    // ---------------------------------------------------------- connection

    private void connect(final String host) {
        connectStatus.setText("Connecting to " + host + "…");
        new Thread(() -> {
            boolean established = false;
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, CameraActivity.PORT), 4000);
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                socket = s;
                out = new PrintWriter(s.getOutputStream(), true);
                connected = true;
                established = true;
                getSharedPreferences("camlink", MODE_PRIVATE).edit().putString("ip", host).apply();
                ui.post(() -> {
                    showConnectPanelHidden();
                    applyState(false);
                });
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) handle(line.trim());
                if (connected) ui.post(() -> disconnect("Connection lost."));
            } catch (IOException e) {
                if (!established) {
                    ui.post(() -> connectStatus.setText("Could not connect to " + host
                            + ". Is the camera phone open on its camera screen, on the same Wi-Fi?"));
                } else if (connected) {
                    ui.post(() -> disconnect("Connection lost."));
                }
            }
        }, "connect").start();
    }

    private void showConnectPanelHidden() {
        showControlPanel();
    }

    private void handle(final String line) {
        ui.post(() -> {
            if (line.equals("STATE RECORDING")) applyState(true);
            else if (line.equals("STATE IDLE")) applyState(false);
            else if (line.startsWith("SAVED ")) controlStatus.setText("Saved on the camera phone:\n" + line.substring(6));
            else if (line.startsWith("ERR ")) {
                bigButton.setEnabled(true);
                controlStatus.setText(line.substring(4));
            }
        });
    }

    private void sendLine(final String msg) {
        net.execute(() -> {
            PrintWriter w = out;
            if (w != null) w.println(msg);
        });
    }

    private void disconnect(String message) {
        connected = false;
        recording = false;
        ui.removeCallbacks(tick);
        final Socket s = socket;
        socket = null;
        out = null;
        if (s != null) new Thread(() -> { try { s.close(); } catch (IOException ignored) { } }).start();
        ((View) connectPanel.getParent()).setVisibility(View.VISIBLE);
        showConnectPanel(message);
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        connected = false;
        ui.removeCallbacks(tick);
        final Socket s = socket;
        if (s != null) new Thread(() -> { try { s.close(); } catch (IOException ignored) { } }).start();
        net.shutdown();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
