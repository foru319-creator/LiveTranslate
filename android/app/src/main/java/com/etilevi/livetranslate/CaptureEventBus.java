package com.etilevi.livetranslate;

// In-process replacement for the sendBroadcast()/BroadcastReceiver channel
// that used to carry capture status, transcripts, and cloud-connection
// status from AudioCaptureService to OverlayService. A real Android system
// broadcast (Intent -> ActivityManagerService -> dispatch) was measured
// adding 300-500ms of IPC overhead per transcript for no benefit, since
// both services always run in the same app process — a plain listener
// callback removes that overhead entirely.
//
// Thread safety: AudioCaptureService calls the notify*() methods from
// both its audio-capture thread (capture status/level, from readAudioLoop)
// and the main thread (transcript/cloud status, already funneled through
// CloudTranscriptionClient's own main-thread Handler) — `listener` is
// volatile so a registration from OverlayService's main thread is visible
// to whichever thread calls notify*() next. Each notify*() no-ops safely
// if no listener is registered, so AudioCaptureService can never crash
// from calling this while OverlayService isn't running.
final class CaptureEventBus {

    interface Listener {
        void onCaptureStatus(String status, int level);

        // `text` is the backend's Cloud Translation result when
        // translated=true; the original, untranslated source-language
        // transcript when false. receivedAtElapsedMs is
        // SystemClock.elapsedRealtime() at WebSocket-message receipt —
        // latency-diagnostic only, not used for any logic. speechBeginAtMs/
        // speechEndAtMs are wall-clock (epoch) ms from the backend's
        // voice-activity events, 0 if unavailable — also diagnostic-only.
        void onTranscript(String text, String languageCode, boolean isFinal, boolean translated, long receivedAtElapsedMs, long speechBeginAtMs, long speechEndAtMs);

        void onCloudStatus(boolean connected);
    }

    private static volatile Listener listener;

    // Call from OverlayService.onCreate().
    static void setListener(Listener l) {
        listener = l;
    }

    // Call from OverlayService.onDestroy(). Only clears if `l` is still the
    // currently-registered listener, so a fast destroy-then-recreate (a new
    // OverlayService instance registering before the old instance's
    // onDestroy runs) can't have the old instance's cleanup clobber the new
    // instance's registration.
    static void clearListener(Listener l) {
        if (listener == l) {
            listener = null;
        }
    }

    static void notifyCaptureStatus(String status, int level) {
        Listener l = listener;
        if (l != null) l.onCaptureStatus(status, level);
    }

    static void notifyTranscript(String text, String languageCode, boolean isFinal, boolean translated, long receivedAtElapsedMs, long speechBeginAtMs, long speechEndAtMs) {
        Listener l = listener;
        if (l != null) l.onTranscript(text, languageCode, isFinal, translated, receivedAtElapsedMs, speechBeginAtMs, speechEndAtMs);
    }

    static void notifyCloudStatus(boolean connected) {
        Listener l = listener;
        if (l != null) l.onCloudStatus(connected);
    }

    private CaptureEventBus() {
    }
}
