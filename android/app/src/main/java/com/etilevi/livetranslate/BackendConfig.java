package com.etilevi.livetranslate;

public final class BackendConfig {
    private static final String TRANSCRIPTION_WEBSOCKET_BASE_URL =
            "wss://livetranslate-backend-lkze7kpqsq-uc.a.run.app/stream";

    // Translation target language sent to the backend. Hardcoded to Hebrew
    // for now; once the UI grows a language picker, pass its selection into
    // transcriptionWebSocketUrl() instead — the backend already honors this
    // per-connection (see backend/src/server.js's `targetLanguage` query
    // param), so no backend change will be needed then.
    public static final String TARGET_LANGUAGE = "he";

    public static String transcriptionWebSocketUrl(String targetLanguage) {
        return TRANSCRIPTION_WEBSOCKET_BASE_URL + "?targetLanguage=" + targetLanguage;
    }

    private BackendConfig() {
    }
}
