package com.etilevi.livetranslate;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.HashMap;
import java.util.Map;

public class OverlayService extends Service {
    private static final String TAG = "LiveTranslateDebug";

    private WindowManager windowManager;
    private TextView floatingButton;
    private TextView statusView;
    private TextView subtitleView;
    private boolean receiverRegistered = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean captureActive = false;
    private boolean cloudConnected = true;

    private boolean closeArmed = false;
    private GradientDrawable normalButtonBackground;
    private GradientDrawable closeButtonBackground;

    private LanguageIdentifier languageIdentifier;
    private final Map<String, Translator> translators = new HashMap<>();
    private long translationRequestId = 0L;
    private String translationStatus = "";
    private String lastTranslationSourceText;

    private final BroadcastReceiver captureStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!AudioCaptureService.ACTION_STATUS.equals(intent.getAction())) return;
            String status = intent.getStringExtra("status");
            int level = intent.getIntExtra("level", 0);
            updateCaptureStatus(status, level);
        }
    };

    private final BroadcastReceiver transcriptReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!AudioCaptureService.ACTION_TRANSCRIPT.equals(intent.getAction())) return;
            String text = intent.getStringExtra(AudioCaptureService.EXTRA_TEXT);
            String languageCode = intent.getStringExtra(AudioCaptureService.EXTRA_LANGUAGE_CODE);
            boolean isFinal = intent.getBooleanExtra(AudioCaptureService.EXTRA_IS_FINAL, false);
            handleCloudTranscript(text, languageCode, isFinal);
        }
    };

    private final BroadcastReceiver cloudStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!AudioCaptureService.ACTION_CLOUD_STATUS.equals(intent.getAction())) return;
            cloudConnected = intent.getBooleanExtra(AudioCaptureService.EXTRA_CLOUD_CONNECTED, true);
            refreshCapturingStatus();
        }
    };

    @Override
    public void onCreate() {
        Log.d(TAG, "ENTRY onCreate");
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        // Fallback only: the cloud backend normally supplies a detected
        // source language with every transcript. ML Kit's default confidence
        // threshold (0.5) is tuned for longer text and returns "und" for
        // short phrases, so it's lowered for the rare case we fall back to
        // text-based identification.
        LanguageIdentificationOptions languageIdOptions = new LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.2f)
                .build();
        languageIdentifier = LanguageIdentification.getClient(languageIdOptions);
        registerReceivers();
        showFloatingButton();
        showSubtitleView();
        showStatusView();
    }

    private void registerReceivers() {
        IntentFilter statusFilter = new IntentFilter(AudioCaptureService.ACTION_STATUS);
        IntentFilter transcriptFilter = new IntentFilter(AudioCaptureService.ACTION_TRANSCRIPT);
        IntentFilter cloudStatusFilter = new IntentFilter(AudioCaptureService.ACTION_CLOUD_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureStatusReceiver, statusFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(transcriptReceiver, transcriptFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(cloudStatusReceiver, cloudStatusFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(captureStatusReceiver, statusFilter);
            registerReceiver(transcriptReceiver, transcriptFilter);
            registerReceiver(cloudStatusReceiver, cloudStatusFilter);
        }
        receiverRegistered = true;
    }

    // Partial results are shown as-is (source language) for live feedback;
    // only a final result triggers translation, since the cloud backend's
    // isFinal already reflects real endpointing from Speech-to-Text.
    private void handleCloudTranscript(String text, String languageCode, boolean isFinal) {
        Log.d(TAG, "handleCloudTranscript text=\"" + text + "\" languageCode=" + languageCode + " isFinal=" + isFinal);
        if (text == null || text.trim().isEmpty()) return;
        String trimmed = text.trim();

        if (!isFinal) {
            showSubtitleMessage(trimmed);
            return;
        }
        translateToHebrew(trimmed, languageCode);
    }

    private void translateToHebrew(String text, String cloudLanguageCode) {
        Log.d(TAG, "ENTRY translateToHebrew text=\"" + text + "\" cloudLanguageCode=" + cloudLanguageCode);
        if (subtitleView == null || text == null || text.isEmpty() || languageIdentifier == null) {
            Log.e(TAG, "EARLY RETURN: translateToHebrew - subtitleView=" + (subtitleView != null)
                    + " text=" + text + " languageIdentifier=" + (languageIdentifier != null));
            return;
        }
        if (text.equals(lastTranslationSourceText)) {
            Log.d(TAG, "EARLY RETURN: translateToHebrew - duplicate of last translated text (\"" + text + "\")");
            return;
        }
        lastTranslationSourceText = text;

        final long requestId = ++translationRequestId;
        Log.d(TAG, "translateToHebrew: proceeding, requestId=" + requestId + " text=\"" + text + "\"");

        String normalized = normalizeLanguageTag(cloudLanguageCode);
        String sourceLanguage = normalized == null ? null : TranslateLanguage.fromLanguageTag(normalized);

        if (sourceLanguage != null) {
            Log.d(TAG, "translateToHebrew: using cloud-detected language=" + sourceLanguage + " requestId=" + requestId);
            translateWithLanguage(text, sourceLanguage, requestId);
            return;
        }

        // Fallback: cloud didn't supply a usable language code — try ML
        // Kit's text-based identification before giving up.
        Log.d(TAG, "translateToHebrew: no usable cloud language, falling back to ML Kit identifyLanguage requestId=" + requestId);
        translationStatus = "🌐 מזהה שפה";
        showSubtitleMessage("מזהה שפה…");
        refreshCapturingStatus();

        languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener(languageCode -> {
                    if (requestId != translationRequestId) return;
                    String resolved = (languageCode != null && !"und".equals(languageCode))
                            ? TranslateLanguage.fromLanguageTag(languageCode)
                            : null;
                    if (resolved == null) {
                        translationStatus = "🌐 שפה לא זוהתה";
                        showSubtitleMessage("לא הצלחתי לזהות את שפת הדיבור: " + text);
                        refreshCapturingStatus();
                        return;
                    }
                    translateWithLanguage(text, resolved, requestId);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "identifyLanguage FAILURE requestId=" + requestId + " text=\"" + text + "\"", e);
                    if (requestId != translationRequestId) return;
                    translationStatus = "🌐 שגיאת זיהוי שפה";
                    showSubtitleMessage("לא הצלחתי לזהות את שפת הדיבור: " + text);
                    refreshCapturingStatus();
                });
    }

    private void translateWithLanguage(String text, String sourceLanguage, long requestId) {
        Log.d(TAG, "ENTRY translateWithLanguage requestId=" + requestId + " sourceLanguage=" + sourceLanguage + " text=\"" + text + "\"");

        if (TranslateLanguage.HEBREW.equals(sourceLanguage)) {
            Log.d(TAG, "translateWithLanguage: source already Hebrew, passthrough (no ML Kit translate call) requestId=" + requestId);
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
            Log.d(TAG, "translateWithLanguage: created NEW Translator " + sourceLanguage + "->he requestId=" + requestId);
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
                                Log.e(TAG, "translate() FAILURE requestId=" + requestId + " text=\"" + text + "\"", e);
                                if (requestId != translationRequestId) return;
                                translationStatus = "🌐 שגיאת תרגום";
                                showSubtitleMessage("שגיאה בתרגום. הטקסט שזוהה: " + text);
                                refreshCapturingStatus();
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "downloadModelIfNeeded FAILURE requestId=" + requestId + " sourceLanguage=" + sourceLanguage, e);
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
                        // Reset before the closeArmed early-return: otherwise a stale
                        // longPressed=true from the gesture that armed the X survives into
                        // the next tap and permanently blocks closeOverlayCompletely() below.
                        longPressed = false;
                        if (closeArmed) return true;
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        moved = false;
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
        resetSessionState();
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
        if (!cloudConnected) {
            statusView.setText("🎧 קולט שמע • ⚠️ אין חיבור לשרת התרגום, מנסה להתחבר…");
            return;
        }
        String suffix = translationStatus.isEmpty() ? "" : " • " + translationStatus;
        statusView.setText("🎧 קולט שמע • 🎤 מזהה מילים" + suffix);
    }

    private void resetSessionState() {
        lastTranslationSourceText = null;
        translationRequestId++;
        translationStatus = "";
        cloudConnected = true;
    }

    private void updateCaptureStatus(String status, int level) {
        if (statusView == null) return;

        if ("capturing".equals(status)) {
            captureActive = true;
            if (!closeArmed) refreshCapturingStatus();
            if (floatingButton != null && !closeArmed) floatingButton.setText("■");
        } else if ("paused".equals(status)) {
            captureActive = false;
            resetSessionState();
            if (!closeArmed) statusView.setText("⏸ התרגום מושהה • לחצי 🌐 להמשך");
            hideSubtitle();
            if (floatingButton != null && !closeArmed) floatingButton.setText("🌐");
        } else if ("stopped".equals(status)) {
            captureActive = false;
            resetSessionState();
            if (!closeArmed) statusView.setText("Live Translate • הקליטה נעצרה");
            hideSubtitle();
            if (floatingButton != null && !closeArmed) floatingButton.setText("🌐");
        } else if ("unsupported".equals(status)) {
            captureActive = false;
            resetSessionState();
            statusView.setText("המכשיר לא תומך בקליטת שמע פנימי");
            hideSubtitle();
        } else if ("capture_error".equals(status)) {
            captureActive = false;
            resetSessionState();
            statusView.setText("לא הצלחנו לקלוט את השמע מהסרטון");
            hideSubtitle();
        } else if ("permission_error".equals(status)) {
            captureActive = false;
            resetSessionState();
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
        resetSessionState();
        handler.removeCallbacksAndMessages(null);

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
            try { unregisterReceiver(transcriptReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
