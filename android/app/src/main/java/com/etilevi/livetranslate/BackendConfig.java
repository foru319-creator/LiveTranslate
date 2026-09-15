package com.etilevi.livetranslate;

public final class BackendConfig {
    // TODO: replace with the real Cloud Run URL once backend/ is deployed
    // (see backend/README or the `gcloud run deploy` output for the service
    // URL), e.g. "wss://livetranslate-backend-xxxxx-uc.a.run.app/stream".
    public static final String TRANSCRIPTION_WEBSOCKET_URL =
            "wss://REPLACE_WITH_CLOUD_RUN_URL/stream";

    private BackendConfig() {
    }
}
