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

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class OverlayService extends Service {
    private WindowManager windowManager;
    private TextView floatingButton;
    private TextView statusView;
    private TextView subtitleView;
    private boolean receiverRegistered = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean speechListening = false;
    private boolean captureActive = false;
    private String detectedLanguage = "";

    private boolean closeArmed = false;
    private GradientDrawable normalButtonBackground;
    private GradientDrawable closeButtonBackground;

    private LanguageIdentifier languageIdentifier;
    private final Map<String, Translator> translators = new HashMap<>();
    private long translationRequestId = 0L;
    private String translationStatus = "";

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
        languageIdentifier = LanguageIdentification.getClient();
        registerCaptureReceiver();
        showFloatingButton();
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
            showSubtitleMessage("זיהוי הדיבור של Android אינו זמין במכשיר");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { speechListening = true; }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }

            @Override
            public void onError(int error) {
                speechListening = false;
                if (!captureActive) return;

                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    scheduleSpeechRestart(500L);
                } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    scheduleSpeechRestart(900L);
                } else if (error == SpeechRecognizer.ERROR_AUDIO) {
                    showSubtitleMessage("זיהוי דיבור: לא התקבל קול מהמיקרופון");
                    scheduleSpeechRestart(1200L);
                } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    showSubtitleMessage("נדרש אישור מיקרופון לזיהוי הדיבור");
                } else {
                    scheduleSpeechRestart(1000L);
                }
            }

            @Override
            public void onResults(Bundle results) {
                speechListening = false;
                translateFinalResult(results);
                if (captureActive) scheduleSpeechRestart(350L);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                showPartialResult(partialResults);
            }

            @Override public void onEvent(int eventType, Bundle params) { }

            @Override
            public void onLanguageDetection(Bundle results) {
                if (Build.VERSION.SDK_INT >= 34 && results != null) {
                    String language = results.getString(SpeechRecognizer.DETECTED_LANGUAGE);
                    if (language != null && !language.isEmpty()) detectedLanguage = language;
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
            showSubtitleMessage("לא הצלחנו להפעיל את זיהוי הדיבור");
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

    private String bestText(Bundle results) {
        if (results == null) return null;
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return null;
        String text = matches.get(0);
        if (text == null || text.trim().isEmpty()) return null;
        return text.trim();
    }

    private void showPartialResult(Bundle results) {
        String text = bestText(results);
        if (text == null) return;
        if (translationStatus.isEmpty()) {
            showSubtitleMessage(text);
        }
    }

    private void translateFinalResult(Bundle results) {
        String text = bestText(results);
        if (text == null) return;
        translateToHebrew(text);
    }

    private void translateToHebrew(String text) {
        if (subtitleView == null || text == null || text.isEmpty() || languageIdentifier == null) return;

        final long requestId = ++translationRequestId;
        translationStatus = "🌐 מזהה שפה";
        showSubtitleMessage("מזהה שפה…");
        refreshCapturingStatus();

        languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener(languageCode -> {
                    if (requestId != translationRequestId) return;

                    String sourceLanguage = null;
                    if (languageCode != null && !"und".equals(languageCode)) {
                        sourceLanguage = TranslateLanguage.fromLanguageTag(languageCode);
                    }

                    if (sourceLanguage == null) {
                        String speechLanguage = normalizeLanguageTag(detectedLanguage);
                        if (speechLanguage != null) {
                            sourceLanguage = TranslateLanguage.fromLanguageTag(speechLanguage);
                        }
                    }

                    if (sourceLanguage == null) {
                        translationStatus = "🌐 שפה לא זוהתה";
                        showSubtitleMessage("לא הצלחתי לזהות את שפת הדיבור: " + text);
                        refreshCapturingStatus();
                        return;
                    }

                    translateWithLanguage(text, sourceLanguage, requestId);
                })
                .addOnFailureListener(e -> {
                    if (requestId != translationRequestId) return;
                    String speechLanguage = normalizeLanguageTag(detectedLanguage);
                    String sourceLanguage = speechLanguage == null ? null : TranslateLanguage.fromLanguageTag(speechLanguage);
                    if (sourceLanguage != null) {
                        translateWithLanguage(text, sourceLanguage, requestId);
                    } else {
                        translationStatus = "🌐 שגיאת זיהוי שפה";
                        showSubtitleMessage("לא הצלחתי לזהות את שפת הדיבור: " + text);
                        refreshCapturingStatus();
                    }
                });
    }

    private void translateWithLanguage(String text, String sourceLanguage, long requestId) {
        if (TranslateLanguage.HEBREW.equals(sourceLanguage)) {
            if (requestId == translationRequestId) {
                translationStatus = "🌐 עברית";
                showSubtitleMessage(text);
                refreshCapturingStatus();
            }
            return;
        }

        Translator translator = translators.get(sourceLanguage);
        if (translator == null) {
            TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(TranslateLanguage.HEBREW)
                    .build();
            translator = Translation.getClient(options);
            translators.put(sourceLanguage, translator);
        }

        final Translator finalTranslator = translator;
        translationStatus = "🌐 מכין תרגום";
        showSubtitleMessage("מכין תרגום לעברית…");
        refreshCapturingStatus();

        DownloadConditions conditions = new DownloadConditions.Builder().build();
        finalTranslator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    if (requestId != translationRequestId) return;
                    translationStatus = "🌐 מתרגם";
                    showSubtitleMessage("מתרגם לעברית…");
                    refreshCapturingStatus();

                    finalTranslator.translate(text)
                            .addOnSuccessListener(translatedText -> {
                                if (requestId != translationRequestId) return;
                                translationStatus = "🌐 עברית";
                                showSubtitleMessage(translatedText);
                                refreshCapturingStatus();
                            })
                            .addOnFailureListener(e -> {
                                if (requestId != translationRequestId) return;
                                translationStatus = "🌐 שגיאת תרגום";
                                showSubtitleMessage("שגיאה בתרגום. הטקסט שזוהה: " + text);
                                refreshCapturingStatus();
                            });
                })
                .addOnFailureListener(e -> {
                    if (requestId != translationRequestId) return;
                    translationStatus = "🌐 הורדת מודל נכשלה";
                    showSubtitleMessage("לא הצלחתי להוריד את מודל התרגום. ודאי שיש אינטרנט ונסי שוב.");
                    refreshCapturingStatus();
                });
    }

    private String normalizeLanguageTag(String language) {
        if (language == null) return null;
        String value = language.trim();
        if (value.isEmpty()) return null;
        return value.replace('_', '-');
    }

    private void showSubtitleMessage(String text) {
        if (subtitleView == null) return;
        subtitleView.setText(text);
        subtitleView.setVisibility(View.VISIBLE);
    }

    private void showFloatingButton() {
        TextView button = new TextView(this);
        button.setText("🌐");
        button.setTextSize(24f);
        button.setGravity(Gravity.CENTER);
        button.setElevation(50f);

        normalButtonBackground = new GradientDrawable();
        normalButtonBackground.setColor(0xFF6D5DFB);
        normalButtonBackground.setShape(GradientDrawable.OVAL);

        closeButtonBackground = new GradientDrawable();
        closeButtonBackground.setColor(0xFFE53935);
        closeButtonBackground.setShape(GradientDrawable.OVAL);

        button.setBackground(normalButtonBackground);

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
            private final int moveTolerance = dp(28);

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (closeArmed) return true;
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        moved = false;
                        longPressed = false;
                        longPressRunnable = () -> {
                            if (!moved && !closeArmed) {
                                longPressed = true;
                                armCloseButton();
                            }
                        };
                        handler.postDelayed(longPressRunnable, 500L);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (closeArmed) return true;
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (!longPressed && (Math.abs(dx) > moveTolerance || Math.abs(dy) > moveTolerance)) {
                            moved = true;
                            if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
                        }
                        if (!longPressed) {
                            params.x = initialX - (int) dx;
                            params.y = initialY + (int) dy;
                            windowManager.updateViewLayout(floatingButton, params);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
                        if (closeArmed) {
                            if (!longPressed) closeOverlayCompletely();
                            return true;
                        }
                        if (longPressed) return true;
                        if (!moved) toggleCapture();
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
                        return true;
                }
                return false;
            }
        });

        floatingButton = button;
        windowManager.addView(floatingButton, params);
    }

    private void armCloseButton() {
        closeArmed = true;
        if (floatingButton != null) {
            floatingButton.setText("✕");
            floatingButton.setTextSize(28f);
            floatingButton.setBackground(closeButtonBackground);
        }
        if (statusView != null) statusView.setText("לחצי על ✕ כדי לסגור את Live Translate");
    }

    private void disarmCloseButton() {
        closeArmed = false;
        if (floatingButton != null) {
            floatingButton.setText(captureActive ? "■" : "🌐");
            floatingButton.setTextSize(24f);
            floatingButton.setBackground(normalButtonBackground);
        }
    }

    private void toggleCapture() {
        disarmCloseButton();
        Intent toggleIntent = new Intent(OverlayService.this, AudioCaptureService.class);
        toggleIntent.setAction(AudioCaptureService.ACTION_TOGGLE);
        try { startService(toggleIntent); } catch (Exception ignored) {}
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

    private void refreshCapturingStatus() {
        if (statusView == null || !captureActive || closeArmed) return;
        String suffix = translationStatus.isEmpty() ? "" : " • " + translationStatus;
        statusView.setText("🎧 קולט שמע • 🎤 מזהה מילים" + suffix);
    }

    private void updateCaptureStatus(String status, int level) {
        if (statusView == null) return;

        if ("capturing".equals(status)) {
            captureActive = true;
            if (!closeArmed) refreshCapturingStatus();
            if (floatingButton != null && !closeArmed) floatingButton.setText("■");
            startSpeechRecognitionIfNeeded();
        } else if ("paused".equals(status)) {
            captureActive = false;
            translationStatus = "";
            stopSpeechRecognition();
            if (!closeArmed) statusView.setText("⏸ התרגום מושהה • לחצי 🌐 להמשך");
            hideSubtitle();
            if (floatingButton != null && !closeArmed) floatingButton.setText("🌐");
        } else if ("stopped".equals(status)) {
            captureActive = false;
            translationStatus = "";
            stopSpeechRecognition();
            if (!closeArmed) statusView.setText("Live Translate • הקליטה נעצרה");
            hideSubtitle();
            if (floatingButton != null && !closeArmed) floatingButton.setText("🌐");
        } else if ("unsupported".equals(status)) {
            captureActive = false;
            translationStatus = "";
            stopSpeechRecognition();
            statusView.setText("המכשיר לא תומך בקליטת שמע פנימי");
            hideSubtitle();
        } else if ("capture_error".equals(status)) {
            captureActive = false;
            translationStatus = "";
            stopSpeechRecognition();
            statusView.setText("לא הצלחנו לקלוט את השמע מהסרטון");
            hideSubtitle();
        } else if ("permission_error".equals(status)) {
            captureActive = false;
            translationStatus = "";
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
        translationRequestId++;
        handler.removeCallbacksAndMessages(null);
        stopSpeechRecognition();

        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) {}
            speechRecognizer = null;
        }
        if (languageIdentifier != null) {
            try { languageIdentifier.close(); } catch (Exception ignored) {}
            languageIdentifier = null;
        }
        for (Translator translator : translators.values()) {
            try { translator.close(); } catch (Exception ignored) {}
        }
        translators.clear();

        if (floatingButton != null && windowManager != null) {
            windowManager.removeView(floatingButton);
            floatingButton = null;
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
