package com.etilevi.livetranslate;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

// Signs in anonymously with Firebase, opens an authenticated WebSocket to the
// Cloud Run backend (backend/src/server.js), streams raw PCM16LE audio to
// it, and delivers back partial/final transcripts already translated
// server-side (Chirp 3 -> Cloud Translation). Owns its own
// reconnect-with-backoff so callers can just start()/stop() it alongside
// audio capture and keep pushing audio.
public class CloudTranscriptionClient {

    public interface Listener {
        // `text` is the backend's Cloud Translation result when
        // translated=true; when translated=false (translation failed
        // server-side), it's the original, untranslated source-language
        // transcript and the caller should fall back to its own local
        // translation rather than displaying it as-is. `receivedAtElapsedMs`
        // is SystemClock.elapsedRealtime() at the moment this WebSocket
        // message arrived — latency-diagnostic only, so the caller can log
        // how long it takes from there to actually rendering the subtitle.
        // `speechBeginAtMs`/`speechEndAtMs` are wall-clock (epoch) ms from
        // the backend's voice-activity events (0 if not available for this
        // transcript) — also diagnostic-only, for an *approximate*
        // cross-device "person started talking -> displayed" latency; approximate
        // because it depends on the phone's clock being reasonably in sync,
        // unlike the exact on-device elapsedRealtime-based measurement above.
        void onTranscript(String text, String languageCode, boolean isFinal, boolean translated, long receivedAtElapsedMs, long speechBeginAtMs, long speechEndAtMs);
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
    // Bounds how much audio gets queued while the very first connection
    // (Firebase auth + WebSocket handshake + Chirp 3 stream setup) is still
    // in flight, so a slow/stuck connect can't grow this without limit.
    // ~10s of audio at the current 500ms chunk size.
    private static final int MAX_PRECONNECT_BUFFER_CHUNKS = 20;

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived streaming connection, no read timeout
            .build();

    // Audio captured before the very first successful connect is queued
    // here instead of dropped, so speech from the start of the video isn't
    // lost while auth/handshake/STT-stream setup is still in progress.
    // Guarded by its own lock: written from the audio capture thread
    // (sendAudio()), drained from OkHttp's callback thread (onOpen()).
    private final Object pendingAudioLock = new Object();
    private final ArrayDeque<ByteString> pendingAudio = new ArrayDeque<>();

    // volatile: written from OkHttp's callback thread, read from the audio
    // capture thread in sendAudio(). All other fields below are touched only
    // from the main thread (Service lifecycle calls in/out, plus every
    // WebSocketListener callback body that isn't sendAudio's fast path is
    // funneled through mainHandler.post()).
    private volatile WebSocket webSocket;
    private volatile boolean connected = false;
    // Once true (first successful connect of this start()/stop() cycle),
    // later disconnects/reconnects go back to dropping audio silently — see
    // sendAudio(). Reset in stop() so the next start() buffers again.
    private volatile boolean hasEverConnected = false;
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
        synchronized (pendingAudioLock) {
            hasEverConnected = false;
            pendingAudio.clear();
        }
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            try { socket.close(1000, "client stopping"); } catch (Exception ignored) {}
        }
    }

    // Safe to call from the audio capture thread; OkHttp WebSocket.send() is
    // thread-safe. While the very first connect for this start()/stop()
    // cycle is still in flight, audio is queued (bounded) instead of
    // dropped, and flushed in order once onOpen() fires — see
    // flushPendingAudio(). Once connected for the first time, any *later*
    // disconnect/reconnect gap goes back to silently dropping audio: a
    // short gap there is preferable to force-feeding a stale backlog into a
    // brand-new Chirp 3 stream on the backend.
    public void sendAudio(byte[] pcmData, int length) {
        WebSocket socket = webSocket;
        if (connected && socket != null) {
            // Sent as-is, one WebSocket frame per call — no buffering/
            // aggregation here, so the frame size on the wire always matches
            // the caller's chunk size exactly (must stay under the backend's
            // 25600-byte limit).
            Log.d(TAG, "socket.send: " + length + " bytes");
            socket.send(ByteString.of(pcmData, 0, length));
            return;
        }
        // hasEverConnected is checked and pendingAudio is enqueued under the
        // same lock flushPendingAudio() uses, so there's no window where a
        // chunk queued here could be missed by (or double-sent after) a
        // concurrent flush on OkHttp's callback thread.
        synchronized (pendingAudioLock) {
            if (hasEverConnected) return; // later reconnect gap — drop, as before
            if (pendingAudio.size() >= MAX_PRECONNECT_BUFFER_CHUNKS) {
                pendingAudio.pollFirst(); // buffer full — drop the oldest, keep it recent
            }
            pendingAudio.addLast(ByteString.of(pcmData, 0, length));
        }
    }

    // Sends everything buffered by sendAudio() while the first connect was
    // still in flight, in the order it was captured. No-op on any later
    // reconnect (hasEverConnected already true by then).
    private void flushPendingAudio(WebSocket socket) {
        List<ByteString> queued;
        synchronized (pendingAudioLock) {
            if (hasEverConnected) return;
            hasEverConnected = true;
            if (pendingAudio.isEmpty()) return;
            queued = new ArrayList<>(pendingAudio);
            pendingAudio.clear();
        }
        Log.d(TAG, "flushing " + queued.size() + " buffered pre-connect audio chunk(s)");
        for (ByteString chunk : queued) {
            socket.send(chunk);
        }
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
                .url(BackendConfig.transcriptionWebSocketUrl(BackendConfig.TARGET_LANGUAGE))
                .addHeader("Authorization", "Bearer " + idToken)
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
                Log.d(TAG, "onOpen");
                connected = true;
                flushPendingAudio(ws);
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
                // Echo the close per OkHttp's recommended shutdown pattern —
                // but 1005/1006/1015 are reserved for local use only (RFC
                // 6455 §7.4.1: "no status code was actually present") and
                // must never be sent in an actual close frame; OkHttp's own
                // close() throws IllegalArgumentException if asked to. Fall
                // back to a normal-closure code instead of propagating that
                // crash (e.g. when the server closes without a close frame
                // at all, which OkHttp reports here as code 1005).
                ws.close(isSendableCloseCode(code) ? code : 1000, reason);
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
        long receivedAtElapsedMs = SystemClock.elapsedRealtime();
        try {
            JSONObject json = new JSONObject(rawMessage);
            String type = json.optString("type", "");
            if ("partial".equals(type) || "final".equals(type)) {
                String text = json.optString("text", "");
                String languageCode = json.optString("languageCode", "");
                boolean isFinal = "final".equals(type);
                // Defaults to false (not translated) if the field is somehow
                // missing, so a stale/mismatched server never gets treated
                // as if `text` were already in the target language.
                boolean translated = json.optBoolean("translated", false);
                // 0 when the backend had no voice-activity timestamp for
                // this transcript (see server.js) — treated as "not
                // available" throughout, never as a real epoch-0 timestamp.
                long speechBeginAtMs = json.optLong("speechBeginAtMs", 0L);
                long speechEndAtMs = json.optLong("speechEndAtMs", 0L);
                mainHandler.post(() -> listener.onTranscript(text, languageCode, isFinal, translated, receivedAtElapsedMs, speechBeginAtMs, speechEndAtMs));
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

    // RFC 6455 §7.4.1: 1005 ("No Status Rcvd") and 1015 ("TLS handshake")
    // are reserved for local use only; 1006 ("Abnormal Closure") likewise
    // per §7.4.1. None may appear in an actual close frame on the wire.
    private static boolean isSendableCloseCode(int code) {
        return code != 1005 && code != 1006 && code != 1015;
    }
}
