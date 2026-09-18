package com.etilevi.livetranslate;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
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
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean captureActive = false;
    private boolean cloudConnected = true;

    private boolean closeArmed = false;
    private GradientDrawable normalButtonBackground;
    private GradientDrawable closeButtonBackground;

    // How long a partial result must stay unchanged before it gets
    // translated. Every new partial from the same utterance resets this, so
    // translation only fires once the text has settled for a moment instead
    // of on every tiny interim update — but short enough to still feel live.
    private static final long PARTIAL_TRANSLATE_DEBOUNCE_MS = 400L;

    // A lone word is rarely a coherent phrase on its own and translates
    // poorly in isolation — wait for a partial to grow at least this many
    // words before spending a translation call on it. Finals are exempt:
    // a one-word final (e.g. "כן") is still the ground truth and must show.
    private static final int MIN_PARTIAL_WORD_COUNT = 2;

    // A partial's language has to repeat this many times in a row before it
    // can override the source language already established for this
    // utterance/session — one noisy reading shouldn't flip the language.
    private static final int LANGUAGE_SWITCH_CONFIRM_COUNT = 2;

    private LanguageIdentifier languageIdentifier;
    private final Map<String, Translator> translators = new HashMap<>();
    private String translationStatus = "";
    private String lastTranslationSourceText;

    // Monotonic ordering for transcript events (partial or final), used to
    // make sure an async translation that resolves late (e.g. a debounced
    // partial's translation finishing after a newer final already rendered)
    // can never clobber something newer already on screen.
    private long transcriptSeq = 0L;
    private long lastRenderedSeq = -1L;
    private Runnable pendingPartialTranslate;

    // Rolling "confident source language" context for the current session:
    // once Chirp 3 gives us a real language code (always trusted on a
    // final; only after repeating on partials), it sticks and is reused for
    // any later partial that arrives without its own language code, instead
    // of guessing a different one per fragment.
    private String stableLanguageCode;
    private String pendingLanguageCode;
    private int pendingLanguageStreak;

    // Replaces the old captureStatusReceiver/transcriptReceiver/
    // cloudStatusReceiver BroadcastReceivers — same three event types, now
    // delivered as a direct in-process call via CaptureEventBus instead of
    // a full Android system broadcast. Each handler hops to the main thread
    // only when not already on it (runOnMain), since AudioCaptureService's
    // audio-capture thread calls onCaptureStatus directly while the other
    // two already arrive on the main thread.
    private final CaptureEventBus.Listener captureEventListener = new CaptureEventBus.Listener() {
        @Override
        public void onCaptureStatus(String status, int level) {
            runOnMain(() -> updateCaptureStatus(status, level));
        }

        @Override
        public void onTranscript(String text, String languageCode, boolean isFinal, boolean translated, long receivedAtElapsedMs, long speechBeginAtMs, long speechEndAtMs) {
            runOnMain(() -> handleCloudTranscript(text, languageCode, isFinal, translated, receivedAtElapsedMs, speechBeginAtMs, speechEndAtMs));
        }

        @Override
        public void onCloudStatus(boolean connected) {
            runOnMain(() -> {
                cloudConnected = connected;
                refreshCapturingStatus();
            });
        }
    };

    // Every CaptureEventBus callback (and nothing else) goes through this,
    // so UI-touching code below always runs on the main thread regardless
    // of which thread AudioCaptureService called notify*() from — without
    // paying for a redundant post when already on the main thread.
    private void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            handler.post(r);
        }
    }

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
        CaptureEventBus.setListener(captureEventListener);
        showFloatingButton();
        showSubtitleView();
        showStatusView();
    }

    // Translation now normally happens server-side (Chirp 3 -> Cloud
    // Translation, see backend/src/translationPipeline.js) — this just
    // displays what the backend already translated. The on-device ML Kit
    // pipeline below (translateToHebrew/translateWithLanguage) only runs as
    // a *fallback* when the backend reports translated=false, i.e. Cloud
    // Translation failed for that particular transcript server-side.
    private void handleCloudTranscript(String text, String languageCode, boolean isFinal, boolean translated, long receivedAtElapsedMs, long speechBeginAtMs, long speechEndAtMs) {
        Log.d(TAG, "handleCloudTranscript text=\"" + text + "\" languageCode=" + languageCode
                + " isFinal=" + isFinal + " translated=" + translated);
        if (text == null || text.trim().isEmpty()) return;
        String trimmed = text.trim();
        long seq = ++transcriptSeq;

        if (translated) {
            cancelPendingPartialTranslate();
            displayServerTranslated(trimmed, seq, receivedAtElapsedMs, speechBeginAtMs, speechEndAtMs);
            return;
        }

        // Fallback path: server-side translation failed for this transcript
        // — translate on-device instead, exactly like before Cloud
        // Translation was added.
        // Update the session's stable source language from this reading
        // regardless of whether we end up translating it — see
        // resolveSourceLanguage() for why a final is trusted immediately
        // while a partial needs to repeat before it can change anything.
        String resolvedLanguageCode = resolveSourceLanguage(languageCode, isFinal);

        if (isFinal) {
            cancelPendingPartialTranslate();
            translateToHebrew(trimmed, resolvedLanguageCode, seq, true);
            return;
        }

        if (countWords(trimmed) < MIN_PARTIAL_WORD_COUNT) {
            Log.d(TAG, "handleCloudTranscript: partial too short to translate yet, waiting seq=" + seq);
            cancelPendingPartialTranslate();
            return;
        }

        schedulePartialTranslate(trimmed, resolvedLanguageCode, seq);
    }

    // Fast path for the normal case: the backend already ran Cloud
    // Translation, so this only needs the same seq-ordering guard every
    // other rendering path uses — no ML Kit call at all.
    private void displayServerTranslated(String text, long seq, long receivedAtElapsedMs, long speechBeginAtMs, long speechEndAtMs) {
        if (subtitleView == null) return;
        if (seq < lastRenderedSeq) {
            Log.d(TAG, "SKIP: displayServerTranslated seq=" + seq + " superseded by lastRenderedSeq=" + lastRenderedSeq);
            return;
        }
        lastRenderedSeq = seq;
        translationStatus = "🌐 עברית";
        showSubtitleMessage(text);
        refreshCapturingStatus();
        if (receivedAtElapsedMs > 0) {
            long renderMs = SystemClock.elapsedRealtime() - receivedAtElapsedMs;
            Log.i(TAG, "TIMING seq=" + seq + " androidRenderMs=" + renderMs);
        }
        // Cross-device latency from the *person's own speech* to this text
        // actually being displayed — approximate, since it compares the
        // backend's wall clock (Date.now()) to System.currentTimeMillis()
        // here, which depends on the phone's clock being reasonably in
        // sync (unlike androidRenderMs above, which is exact because it's
        // entirely on-device via elapsedRealtime).
        if (speechBeginAtMs > 0 || speechEndAtMs > 0) {
            long displayedAtWallClockMs = System.currentTimeMillis();
            String timing = "TIMING seq=" + seq;
            if (speechBeginAtMs > 0) timing += " speechBeginToDisplayedMs=" + (displayedAtWallClockMs - speechBeginAtMs);
            if (speechEndAtMs > 0) timing += " speechEndToDisplayedMs=" + (displayedAtWallClockMs - speechEndAtMs);
            Log.i(TAG, timing + " (approximate, cross-device clock)");
        }
    }

    private void schedulePartialTranslate(String text, String resolvedLanguageCode, long seq) {
        cancelPendingPartialTranslate();
        if (resolvedLanguageCode == null) {
            // No source language established for this session/utterance at
            // all yet — wait for a partial/final that actually carries one
            // rather than guessing (ML Kit's text-based fallback only ever
            // runs for finals, in translateToHebrew()).
            Log.d(TAG, "schedulePartialTranslate: no source language known yet, skipping partial seq=" + seq);
            return;
        }
        pendingPartialTranslate = () -> translateToHebrew(text, resolvedLanguageCode, seq, false);
        handler.postDelayed(pendingPartialTranslate, PARTIAL_TRANSLATE_DEBOUNCE_MS);
    }

    private void cancelPendingPartialTranslate() {
        if (pendingPartialTranslate != null) {
            handler.removeCallbacks(pendingPartialTranslate);
            pendingPartialTranslate = null;
        }
    }

    // Chirp 3 doesn't attach a language code to every partial, and can waver
    // between two close languages for a word or two before settling. This
    // keeps one stable source language for the whole utterance/session
    // instead of flipping on every reading: a final's language is trusted
    // immediately (real STT endpointing already backs it), but a partial
    // has to repeat LANGUAGE_SWITCH_CONFIRM_COUNT times before it can
    // override the language already in use.
    private String resolveSourceLanguage(String cloudLanguageCode, boolean isFinal) {
        String normalized = normalizeLanguageTag(cloudLanguageCode);
        if (normalized == null) {
            return stableLanguageCode; // no signal this time; keep trusting what we already have
        }
        if (isFinal || stableLanguageCode == null) {
            commitStableLanguage(normalized);
            return stableLanguageCode;
        }
        if (normalized.equals(stableLanguageCode)) {
            pendingLanguageCode = null;
            pendingLanguageStreak = 0;
            return stableLanguageCode;
        }
        if (normalized.equals(pendingLanguageCode)) {
            pendingLanguageStreak++;
        } else {
            pendingLanguageCode = normalized;
            pendingLanguageStreak = 1;
        }
        if (pendingLanguageStreak >= LANGUAGE_SWITCH_CONFIRM_COUNT) {
            commitStableLanguage(pendingLanguageCode);
        }
        return stableLanguageCode;
    }

    private void commitStableLanguage(String normalizedTag) {
        if (!normalizedTag.equals(stableLanguageCode)) {
            Log.d(TAG, "source language changed: " + stableLanguageCode + " -> " + normalizedTag);
        }
        stableLanguageCode = normalizedTag;
        pendingLanguageCode = null;
        pendingLanguageStreak = 0;
    }

    private static int countWords(String text) {
        if (text == null) return 0;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return 0;
        return trimmed.split("\\s+").length;
    }

    private void translateToHebrew(String text, String resolvedLanguageCode, long seq, boolean isFinal) {
        Log.d(TAG, "ENTRY translateToHebrew seq=" + seq + " text=\"" + text + "\" resolvedLanguageCode=" + resolvedLanguageCode + " isFinal=" + isFinal);
        if (subtitleView == null || text == null || text.isEmpty() || languageIdentifier == null) {
            Log.e(TAG, "EARLY RETURN: translateToHebrew - subtitleView=" + (subtitleView != null)
                    + " text=" + text + " languageIdentifier=" + (languageIdentifier != null));
            return;
        }
        // A strictly newer transcript already owns the subtitle — this one
        // is stale (e.g. a debounced partial's translation resolving after
        // the final already rendered) and must not overwrite it.
        if (seq < lastRenderedSeq) {
            Log.d(TAG, "SKIP: translateToHebrew seq=" + seq + " superseded by lastRenderedSeq=" + lastRenderedSeq);
            return;
        }
        if (text.equals(lastTranslationSourceText)) {
            Log.d(TAG, "SKIP: translateToHebrew - duplicate of last translated text (\"" + text + "\") seq=" + seq);
            lastRenderedSeq = Math.max(lastRenderedSeq, seq);
            return;
        }
        lastTranslationSourceText = text;

        Log.d(TAG, "translateToHebrew: proceeding, seq=" + seq + " text=\"" + text + "\"");

        String sourceLanguage = resolvedLanguageCode == null ? null : TranslateLanguage.fromLanguageTag(resolvedLanguageCode);

        if (sourceLanguage != null) {
            Log.d(TAG, "translateToHebrew: using source language=" + sourceLanguage + " seq=" + seq);
            translateWithLanguage(text, sourceLanguage, seq, isFinal, resolvedLanguageCode);
            return;
        }

        if (!isFinal) {
            // schedulePartialTranslate() already filters out language-less
            // partials before scheduling; nothing to fall back to here.
            return;
        }

        // Fallback for a final only: the cloud never supplied a usable
        // language this whole utterance/session — try ML Kit's text-based
        // identification before giving up.
        Log.d(TAG, "translateToHebrew: no usable cloud language, falling back to ML Kit identifyLanguage seq=" + seq);
        if (seq < lastRenderedSeq) return;
        lastRenderedSeq = seq;
        translationStatus = "🌐 מזהה שפה";
        showSubtitleMessage("מזהה שפה…");
        refreshCapturingStatus();

        languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener(languageCode -> {
                    if (seq < lastRenderedSeq) return;
                    String resolved = (languageCode != null && !"und".equals(languageCode))
                            ? TranslateLanguage.fromLanguageTag(languageCode)
                            : null;
                    if (resolved == null) {
                        lastRenderedSeq = seq;
                        translationStatus = "🌐 שפה לא זוהתה";
                        showSubtitleMessage("לא הצלחתי לזהות את שפת הדיבור: " + text);
                        refreshCapturingStatus();
                        return;
                    }
                    commitStableLanguage(languageCode);
                    translateWithLanguage(text, resolved, seq, true, languageCode);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "identifyLanguage FAILURE seq=" + seq + " text=\"" + text + "\"", e);
                    if (seq < lastRenderedSeq) return;
                    lastRenderedSeq = seq;
                    translationStatus = "🌐 שגיאת זיהוי שפה";
                    showSubtitleMessage("לא הצלחתי לזהות את שפת הדיבור: " + text);
                    refreshCapturingStatus();
                });
    }

    private void translateWithLanguage(String text, String sourceLanguage, long seq, boolean isFinal, String rawLanguageCode) {
        Log.d(TAG, "ENTRY translateWithLanguage seq=" + seq + " sourceLanguage=" + sourceLanguage + " text=\"" + text + "\"");
        if (seq < lastRenderedSeq) {
            Log.d(TAG, "SKIP: translateWithLanguage seq=" + seq + " superseded by lastRenderedSeq=" + lastRenderedSeq);
            return;
        }

        if (TranslateLanguage.HEBREW.equals(sourceLanguage)) {
            Log.d(TAG, "translateWithLanguage: source already Hebrew, passthrough (no ML Kit translate call) seq=" + seq);
            lastRenderedSeq = seq;
            translationStatus = "🌐 עברית";
            showSubtitleMessage(text);
            refreshCapturingStatus();
            logTranslationPair(text, rawLanguageCode, text, isFinal);
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
            Log.d(TAG, "translateWithLanguage: created NEW Translator " + sourceLanguage + "->he seq=" + seq);
        }

        final Translator finalTranslator = translator;
        lastRenderedSeq = seq;
        translationStatus = "🌐 מכין תרגום";
        showSubtitleMessage("מכין תרגום לעברית…");
        refreshCapturingStatus();

        DownloadConditions conditions = new DownloadConditions.Builder().build();
        finalTranslator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    if (seq < lastRenderedSeq) return;
                    lastRenderedSeq = seq;
                    translationStatus = "🌐 מתרגם";
                    showSubtitleMessage("מתרגם לעברית…");
                    refreshCapturingStatus();

                    finalTranslator.translate(text)
                            .addOnSuccessListener(translatedText -> {
                                if (seq < lastRenderedSeq) return;
                                lastRenderedSeq = seq;
                                translationStatus = "🌐 עברית";
                                showSubtitleMessage(translatedText);
                                refreshCapturingStatus();
                                logTranslationPair(text, rawLanguageCode, translatedText, isFinal);
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "translate() FAILURE seq=" + seq + " text=\"" + text + "\"", e);
                                if (seq < lastRenderedSeq) return;
                                lastRenderedSeq = seq;
                                translationStatus = "🌐 שגיאת תרגום";
                                showSubtitleMessage("שגיאה בתרגום. הטקסט שזוהה: " + text);
                                refreshCapturingStatus();
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "downloadModelIfNeeded FAILURE seq=" + seq + " sourceLanguage=" + sourceLanguage, e);
                    if (seq < lastRenderedSeq) return;
                    lastRenderedSeq = seq;
                    translationStatus = "🌐 הורדת מודל נכשלה";
                    showSubtitleMessage("לא הצלחתי להוריד את מודל התרגום. ודאי שיש אינטרנט ונסי שוב.");
                    refreshCapturingStatus();
                });
    }

    // Diagnostic-only: lets us tell apart, from Logcat alone, whether a bad
    // result came from Chirp 3 (SOURCE_TRANSCRIPT/SOURCE_LANGUAGE already
    // wrong or mixed) or from ML Kit's on-device translation (SOURCE_* look
    // right but HEBREW_TRANSLATION doesn't).
    private void logTranslationPair(String sourceText, String sourceLanguage, String hebrewTranslation, boolean isFinal) {
        Log.i(TAG, "SOURCE_TRANSCRIPT: " + sourceText
                + "\nSOURCE_LANGUAGE: " + sourceLanguage
                + "\nHEBREW_TRANSLATION: " + hebrewTranslation
                + "\nIS_FINAL: " + isFinal);
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
        cancelPendingPartialTranslate();
        lastTranslationSourceText = null;
        lastRenderedSeq = transcriptSeq; // invalidate any translation still in flight
        translationStatus = "";
        cloudConnected = true;
        stableLanguageCode = null;
        pendingLanguageCode = null;
        pendingLanguageStreak = 0;
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
        // Must happen so AudioCaptureService's notify*() calls after this
        // point safely no-op instead of reaching a torn-down OverlayService
        // (its views/windowManager are already released above).
        CaptureEventBus.clearListener(captureEventListener);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
