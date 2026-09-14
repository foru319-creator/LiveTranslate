package com.etilevi.livetranslate;

import android.Manifest;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.ArrayList;

public class OverlayService extends Service {
    private WindowManager windowManager;
    private View floatingButton;
    private TextView closeButton;
    private TextView statusView;
    private TextView subtitleView;
    private boolean receiverRegistered = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean speechListening = false;
    private boolean captureActive = false;
    private String detectedLanguage = "";

    private final BroadcastReceiver captureStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!AudioCaptureService.ACTION_STATUS.equals(intent.getAction())) return;
            String status = intent.getStringExtra("status");
            int level = intent.getIntExtra("level", 0);
            updateCaptureStatus(status, level);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        registerCaptureReceiver();
        showFloatingButton();
        showCloseButton();
        showSubtitleView();
        showStatusView();
        setupSpeechRecognizer();
    }

    private void registerCaptureReceiver() {
        IntentFilter filter = new IntentFilter(AudioCaptureService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(captureStatusReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void setupSpeechRecognizer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            if (subtitleView != null) {
                subtitleView.setText("זיהוי הדיבור של Android אינו זמין במכשיר");
                subtitleView.setVisibility(View.VISIBLE);
            }
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                speechListening = true;
            }

            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }

            @Override
            public void onError(int error) {
                speechListening = false;
                if (!captureActive) return;

                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    scheduleSpeechRestart(500L);
                } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    scheduleSpeechRestart(900L);
                } else if (error == SpeechRecognizer.ERROR_AUDIO) {
                    showRecognizedText("זיהוי דיבור: לא התקבל קול מהמיקרופון");
                    scheduleSpeechRestart(1200L);
                } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    showRecognizedText("נדרש אישור מיקרופון לזיהוי הדיבור");
                } else {
                    scheduleSpeechRestart(1000L);
                }
            }

            @Override
            public void onResults(Bundle results) {
                speechListening = false;
                showBestResult(results);
                if (captureActive) scheduleSpeechRestart(350L);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                showBestResult(partialResults);
            }

            @Override public void onEvent(int eventType, Bundle params) { }

            @Override
            public void onLanguageDetection(Bundle results) {
                if (Build.VERSION.SDK_INT >= 34 && results != null) {
                    String language = results.getString(SpeechRecognizer.DETECTED_LANGUAGE);
                    if (language != null && !language.isEmpty()) {
                        detectedLanguage = language;
                    }
                }
            }
        });

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        speechIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

        if (Build.VERSION.SDK_INT >= 34) {
            speechIntent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
            speechIntent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true);
        }
    }

    private void startSpeechRecognitionIfNeeded() {
        if (!captureActive || speechRecognizer == null || speechIntent == null || speechListening) return;
        try {
            speechListening = true;
            speechRecognizer.startListening(speechIntent);
        } catch (Exception e) {
            speechListening = false;
            showRecognizedText("לא הצלחנו להפעיל את זיהוי הדיבור");
        }
    }

    private void scheduleSpeechRestart(long delayMs) {
        handler.postDelayed(() -> {
            if (captureActive) startSpeechRecognitionIfNeeded();
        }, delayMs);
    }

    private void stopSpeechRecognition() {
        speechListening = false;
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) {}
        }
    }

    private void showBestResult(Bundle results) {
        if (results == null) return;
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return;
        String text = matches.get(0);
        if (text == null || text.trim().isEmpty()) return;
        showRecognizedText(text.trim());
    }

    private void showRecognizedText(String text) {
        if (subtitleView == null) return;
        if (detectedLanguage != null && !detectedLanguage.isEmpty()) {
            subtitleView.setText("[" + detectedLanguage + "]  " + text);
        } else {
            subtitleView.setText(text);
        }
        subtitleView.setVisibility(View.VISIBLE);
    }

    private void showFloatingButton() {
        TextView button = new TextView(this);
        button.setText("🌐");
        button.setTextSize(24f);
        button.setGravity(Gravity.CENTER);
        button.setElevation(12f);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF6D5DFB);
        background.setShape(GradientDrawable.OVAL);
        button.setBackground(background);

        int size = dp(64);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(18);
        params.y = dp(180);

        button.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean moved;
            private boolean longPressed;
            private Runnable longPressRunnable;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        moved = false;
                        longPressed = false;
                        longPressRunnable = () -> {
                            if (!moved) {
                                longPressed = true;
                                showCloseControl();
                            }
                        };
                        handler.postDelayed(longPressRunnable, 650L);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                            moved = true;
                            if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
                        }
                        params.x = initialX - (int) dx;
                        params.y = initialY + (int) dy;
                        windowManager.updateViewLayout(floatingButton, params);
                        updateCloseButtonPosition(params);
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
                        if (event.getAction() == MotionEvent.ACTION_UP && !moved && !longPressed) {
                            hideCloseControl();
                            Intent toggleIntent = new Intent(OverlayService.this, AudioCaptureService.class);
                            toggleIntent.setAction(AudioCaptureService.ACTION_TOGGLE);
                            startService(toggleIntent);
                        }
                        return true;
                }
                return false;
            }
        });

        floatingButton = button;
        windowManager.addView(floatingButton, params);
    }

    private void showCloseButton() {
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(22f);
        close.setGravity(Gravity.CENTER);
        close.setElevation(14f);
        close.setVisibility(View.GONE);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFE53935);
        background.setShape(GradientDrawable.OVAL);
        close.setBackground(background);

        int size = dp(48);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(26);
        params.y = dp(252);

        close.setOnClickListener(v -> closeOverlayCompletely());

        closeButton = close;
        windowManager.addView(closeButton, params);
    }

    private void showCloseControl() {
        if (closeButton == null) return;
        closeButton.setVisibility(View.VISIBLE);
        if (statusView != null) statusView.setText("לחצי על ✕ כדי לסגור את Live Translate");
    }

    private void hideCloseControl() {
        if (closeButton != null) closeButton.setVisibility(View.GONE);
    }

    private void updateCloseButtonPosition(WindowManager.LayoutParams floatingParams) {
        if (closeButton == null) return;
        try {
            WindowManager.LayoutParams closeParams = (WindowManager.LayoutParams) closeButton.getLayoutParams();
            closeParams.x = floatingParams.x + dp(8);
            closeParams.y = floatingParams.y + dp(72);
            windowManager.updateViewLayout(closeButton, closeParams);
        } catch (Exception ignored) {}
    }

    private void closeOverlayCompletely() {
        captureActive = false;
        stopSpeechRecognition();
        Intent stopCapture = new Intent(this, AudioCaptureService.class);
        stopCapture.setAction(AudioCaptureService.ACTION_STOP);
        try { startService(stopCapture); } catch (Exception ignored) {}
        stopSelf();
    }

    private void showSubtitleView() {
        TextView subtitle = new TextView(this);
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(20f);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(dp(18), dp(12), dp(18), dp(12));
        subtitle.setMaxLines(3);
        subtitle.setVisibility(View.GONE);
        subtitle.setElevation(11f);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC000000);
        background.setCornerRadius(dp(14));
        subtitle.setBackground(background);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(145);
        params.horizontalMargin = 0.05f;

        subtitleView = subtitle;
        windowManager.addView(subtitleView, params);
    }

    private void showStatusView() {
        TextView status = new TextView(this);
        status.setText("Live Translate • ממתין לאישור קליטת שמע");
        status.setTextColor(Color.WHITE);
        status.setTextSize(14f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(10), dp(16), dp(10));
        status.setElevation(10f);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xD9212230);
        background.setCornerRadius(dp(18));
        status.setBackground(background);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(80);

        statusView = status;
        windowManager.addView(statusView, params);
    }

    private void updateCaptureStatus(String status, int level) {
        if (statusView == null) return;

        if ("capturing".equals(status)) {
            captureActive = true;
            StringBuilder meter = new StringBuilder();
            for (int i = 0; i < 5; i++) meter.append(i < level ? "●" : "○");
            statusView.setText("🎧 קולט שמע • 🎤 מזהה מילים  " + meter);
            if (floatingButton instanceof TextView) ((TextView) floatingButton).setText("■");
            startSpeechRecognitionIfNeeded();
        } else if ("paused".equals(status)) {
            captureActive = false;
            stopSpeechRecognition();
            statusView.setText("⏸ התרגום מושהה • לחצי 🌐 להמשך");
            hideSubtitle();
            if (floatingButton instanceof TextView) ((TextView) floatingButton).setText("🌐");
        } else if ("stopped".equals(status)) {
            captureActive = false;
            stopSpeechRecognition();
            statusView.setText("Live Translate • הקליטה נעצרה");
            hideSubtitle();
            if (floatingButton instanceof TextView) ((TextView) floatingButton).setText("🌐");
        } else if ("unsupported".equals(status)) {
            captureActive = false;
            stopSpeechRecognition();
            statusView.setText("המכשיר לא תומך בקליטת שמע פנימי");
            hideSubtitle();
        } else if ("capture_error".equals(status)) {
            captureActive = false;
            stopSpeechRecognition();
            statusView.setText("לא הצלחנו לקלוט את השמע מהסרטון");
            hideSubtitle();
        } else if ("permission_error".equals(status)) {
            captureActive = false;
            stopSpeechRecognition();
            statusView.setText("נדרש אישור Android לקליטת המדיה");
            hideSubtitle();
        }
    }

    private void hideSubtitle() {
        if (subtitleView != null) subtitleView.setVisibility(View.GONE);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onDestroy() {
        captureActive = false;
        handler.removeCallbacksAndMessages(null);
        stopSpeechRecognition();
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) {}
            speechRecognizer = null;
        }
        if (floatingButton != null && windowManager != null) {
            windowManager.removeView(floatingButton);
            floatingButton = null;
        }
        if (closeButton != null && windowManager != null) {
            windowManager.removeView(closeButton);
            closeButton = null;
        }
        if (subtitleView != null && windowManager != null) {
            windowManager.removeView(subtitleView);
            subtitleView = null;
        }
        if (statusView != null && windowManager != null) {
            windowManager.removeView(statusView);
            statusView = null;
        }
        if (receiverRegistered) {
            try { unregisterReceiver(captureStatusReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
