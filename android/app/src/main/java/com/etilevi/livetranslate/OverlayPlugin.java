package com.etilevi.livetranslate;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.provider.Settings;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
        name = "Overlay",
        permissions = {
                @Permission(alias = "microphone", strings = { Manifest.permission.RECORD_AUDIO })
        }
)
public class OverlayPlugin extends Plugin {

    @PluginMethod
    public void hasPermission(PluginCall call) {
        JSObject result = new JSObject();
        result.put("granted", Settings.canDrawOverlays(getContext()));
        call.resolve(result);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (Settings.canDrawOverlays(getContext())) {
            JSObject result = new JSObject();
            result.put("granted", true);
            call.resolve(result);
            return;
        }

        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getContext().getPackageName())
        );
        getActivity().startActivity(intent);
        JSObject result = new JSObject();
        result.put("openedSettings", true);
        call.resolve(result);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (!Settings.canDrawOverlays(getContext())) {
            call.reject("Overlay permission is required");
            return;
        }

        Intent intent = new Intent(getContext(), OverlayService.class);
        getContext().startService(intent);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent captureIntent = new Intent(getContext(), AudioCaptureService.class);
        captureIntent.setAction(AudioCaptureService.ACTION_STOP);
        try { getContext().startService(captureIntent); } catch (Exception ignored) {}

        Intent overlayIntent = new Intent(getContext(), OverlayService.class);
        getContext().stopService(overlayIntent);
        call.resolve();
    }

    @PluginMethod
    public void startAudioCapture(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "microphonePermissionCallback");
            return;
        }
        launchMediaProjectionPermission(call);
    }

    @PermissionCallback
    private void microphonePermissionCallback(PluginCall call) {
        if (call == null) return;
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            call.reject("Microphone permission was not granted");
            return;
        }
        launchMediaProjectionPermission(call);
    }

    private void launchMediaProjectionPermission(PluginCall call) {
        MediaProjectionManager manager = (MediaProjectionManager) getContext()
                .getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent permissionIntent = manager.createScreenCaptureIntent();
        startActivityForResult(call, permissionIntent, "capturePermissionResult");
    }

    @ActivityCallback
    private void capturePermissionResult(PluginCall call, ActivityResult result) {
        if (call == null) return;

        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("Media capture permission was not granted");
            return;
        }

        Intent serviceIntent = new Intent(getContext(), AudioCaptureService.class);
        serviceIntent.putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.getResultCode());
        serviceIntent.putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.getData());
        getContext().startForegroundService(serviceIntent);

        JSObject response = new JSObject();
        response.put("started", true);
        call.resolve(response);
    }
}
