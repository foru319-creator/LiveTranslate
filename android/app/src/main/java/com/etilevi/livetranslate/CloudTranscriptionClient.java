package com.etilevi.livetranslate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

// Signs in anonymously with Firebase, opens an authenticated WebSocket to the
// Cloud Run backend (backend/src/server.js), streams raw PCM16LE audio to
// it, and delivers back partial/final transcripts with their detected
// source language. Owns its own reconnect-with-backoff so callers can just
// start()/stop() it alongside audio capture and keep pushing audio.
public class CloudTranscriptionClient {

    public interface Listener {
        void onTranscript(String text, String languageCode, boolean isFinal);
        void onConnectionStateChanged(boolean connected);
        // Informational only (e.g. server-side session cap hit); the client
        // reconnects on its own via the normal onClosed/onFailure path.
        void onServerNotice(String message);
    }

    private static final String TAG = "CloudTranscriptionClient";
    private static final long[] RECONNECT_BACKOFF_MS = {500, 1000, 2000, 5000, 10000};
    // Don't surface a "disconnected" notice for a single quick blip (e.g. a
    // brief network handover) — only once a couple of reconnect attempts in a
    // row have failed does it start to look like a real, sustained problem.
    private static final int NOTIFY_DISCONNECTED_AFTER_ATTEMPTS = 2;

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived streaming connection, no read timeout
            .build();

    // volatile: written from OkHttp's callback thread, read from the audio
    // capture thread in sendAudio(). All other fields below are touched only
    // from the main thread (Service lifecycle calls in/out, plus every
    // WebSocketListener callback body that isn't sendAudio's fast path is
    // funneled through mainHandler.post()).
    private volatile WebSocket webSocket;
    private volatile boolean connected = false;
    private boolean active = false;
    private int reconnectAttempts = 0;
    private boolean notifiedDisconnected = false;
    private Runnable pendingReconnect;

    public CloudTranscriptionClient(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        if (active) return;
        active = true;
        reconnectAttempts = 0;
        notifiedDisconnected = false;
        connect();
    }

    public void stop() {
        active = false;
        cancelPendingReconnect();
        connected = false;
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            try { socket.close(1000, "client stopping"); } catch (Exception ignored) {}
        }
    }

    // Safe to call from the audio capture thread; OkHttp WebSocket.send() is
    // thread-safe. Silently drops audio while disconnected/reconnecting
    // rather than buffering — a short gap during reconnect is preferable to
    // building up an unbounded backlog of stale audio.
    public void sendAudio(byte[] pcmData, int length) {
        WebSocket socket = webSocket;
        if (!connected || socket == null) return;
        socket.send(ByteString.of(pcmData, 0, length));
    }

    private void connect() {
        if (!active) return;
        Log.d(TAG, "connect: signing in anonymously and fetching ID token");

        FirebaseAuth.getInstance().signInAnonymously()
                .continueWithTask(task -> {
                    FirebaseUser user = task.getResult() != null ? task.getResult().getUser() : null;
                    if (user == null) {
                        throw new IllegalStateException("Firebase anonymous sign-in returned no user");
                    }
                    return user.getIdToken(false);
                })
                .addOnSuccessListener(result -> openSocket(result.getToken()))
                .addOnFailureListener(this::handleAuthFailure);
    }

    private void openSocket(String idToken) {
        if (!active) return;

        Request request = new Request.Builder()
                .url(BackendConfig.TRANSCRIPTION_WEBSOCKET_URL)
                .addHeader("Authorization", "Bearer " + idToken)
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
                Log.d(TAG, "onOpen");
                connected = true;
                // reconnectAttempts/pendingReconnect/active are main-thread-only;
                // this callback runs on OkHttp's dispatcher thread.
                mainHandler.post(() -> {
                    reconnectAttempts = 0;
                    notifiedDisconnected = false;
                    listener.onConnectionStateChanged(true);
                });
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                handleServerMessage(text);
            }

            @Override
            public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
                Log.d(TAG, "onClosing code=" + code + " reason=" + reason);
                // Echo the close per OkHttp's recommended shutdown pattern.
                ws.close(code, reason);
            }

            @Override
            public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                Log.d(TAG, "onClosed code=" + code + " reason=" + reason);
                handleDisconnect();
            }

            @Override
            public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, @Nullable Response response) {
                Log.e(TAG, "onFailure", t);
                handleDisconnect();
            }
        });
    }

    private void handleServerMessage(String rawMessage) {
        try {
            JSONObject json = new JSONObject(rawMessage);
            String type = json.optString("type", "");
            if ("partial".equals(type) || "final".equals(type)) {
                String text = json.optString("text", "");
                String languageCode = json.optString("languageCode", "");
                boolean isFinal = "final".equals(type);
                mainHandler.post(() -> listener.onTranscript(text, languageCode, isFinal));
            } else if ("error".equals(type)) {
                String message = json.optString("message", "unknown_error");
                Log.w(TAG, "server notice: " + message);
                mainHandler.post(() -> listener.onServerNotice(message));
            }
        } catch (JSONException e) {
            Log.e(TAG, "failed to parse server message: " + rawMessage, e);
        }
    }

    private void handleDisconnect() {
        connected = false;
        webSocket = null;
        // scheduleReconnect() touches active/reconnectAttempts/pendingReconnect,
        // which are main-thread-only; this can run on OkHttp's dispatcher thread.
        mainHandler.post(this::scheduleReconnect);
    }

    private void handleAuthFailure(Exception e) {
        Log.e(TAG, "Firebase auth / token fetch failed", e);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!active) return;
        cancelPendingReconnect();
        int index = Math.min(reconnectAttempts, RECONNECT_BACKOFF_MS.length - 1);
        long delay = RECONNECT_BACKOFF_MS[index];
        reconnectAttempts++;
        Log.d(TAG, "scheduling reconnect in " + delay + "ms (attempt " + reconnectAttempts + ")");
        pendingReconnect = this::connect;
        mainHandler.postDelayed(pendingReconnect, delay);

        if (!notifiedDisconnected && reconnectAttempts >= NOTIFY_DISCONNECTED_AFTER_ATTEMPTS) {
            notifiedDisconnected = true;
            listener.onConnectionStateChanged(false);
        }
    }

    private void cancelPendingReconnect() {
        if (pendingReconnect != null) {
            mainHandler.removeCallbacks(pendingReconnect);
            pendingReconnect = null;
        }
    }
}
