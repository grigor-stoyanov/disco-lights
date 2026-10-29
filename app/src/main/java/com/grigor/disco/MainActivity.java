package com.grigor.disco;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 1;

    private DiscoView view;
    private AudioEngine audio;
    private CameraManager cameraManager;
    private String torchId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable torchOff = () -> setTorch(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new DiscoView(this);
        setContentView(view);
        findTorch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAudio();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAudio();
        handler.removeCallbacks(torchOff);
        setTorch(false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startAudio();
        } else {
            view.setMessage("Microphone access is off.\nAllow it in Settings > Apps > Disco Lights.\nTap still changes modes.");
        }
    }

    private void startAudio() {
        if (audio != null) return;
        view.setMessage(null);
        audio = new AudioEngine(view);
        audio.start();
    }

    private void stopAudio() {
        if (audio == null) return;
        audio.shutdown();
        audio = null;
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        view.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void findTorch() {
        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) return;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchId = id;
                    return;
                }
            }
        } catch (Exception ignored) {
            torchId = null;
        }
    }

    boolean hasTorch() {
        return torchId != null;
    }

    /** Short flashlight blink, called on each beat when the torch strobe is on. */
    void flashTorch() {
        if (torchId == null) return;
        setTorch(true);
        handler.removeCallbacks(torchOff);
        handler.postDelayed(torchOff, 60);
    }

    void setTorch(boolean on) {
        if (torchId == null || cameraManager == null) return;
        try {
            cameraManager.setTorchMode(torchId, on);
        } catch (Exception ignored) {
            // Camera busy or unavailable; skip this flash.
        }
    }
}
