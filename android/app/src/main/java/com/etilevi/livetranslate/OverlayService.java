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

import java.util.ArrayList;
import java.util.Arrays;
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

    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean speechListening = false;
    private boolean captureActive = false;
    private String detectedLanguage = "";
    private Runnable pendingRestartRunnable;
    private int consecutiveErrorCount = 0;
    private int lastErrorCode = -1;
    private Runnable recognitionWatchdogRunnable;

    private boolean closeArmed = false;
    private GradientDrawable normalButtonBackground;
    private GradientDrawable closeButtonBackground;

    private LanguageIdentifier languageIdentifier;
    private final Map<String, Translator> translators = new HashMap<>();
    private long translationRequestId = 0L;
    private String translationStatus = "";
    private String lastTranslationSourceText;
    private String lastPartialDebounceText;
    private long pendingPartialStartTime = 0L;
    private Runnable partialTranslateRunnable;

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
        Log.d(TAG, "ENTRY onCreate");
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        // Recognized speech arrives in short snippets (a few words at a time), and ML Kit's
        // default confidence threshold (0.5) is tuned for longer text — it almost always
        // returns "und" (undetermined) for short phrases, which blocks translation entirely.
        // Lowering the threshold lets short snippets resolve to a language reliably.
        LanguageIdentificationOptions languageIdOptions = new LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.2f)
                .build();
        languageIdentifier = LanguageIdentification.getClient(languageIdOptions);
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
        Log.d(TAG, "ENTRY setupSpeechRecognizer");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "EARLY RETURN: setupSpeechRecognizer - RECORD_AUDIO permission not granted");
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "EARLY RETURN: setupSpeechRecognizer - SpeechRecognizer.isRecognitionAvailable() == false");
            showSubtitleMessage("זיהוי הדיבור של Android אינו זמין במכשיר");
            return;
        }

        Log.d(TAG, "setupSpeechRecognizer: Build.VERSION.SDK_INT=" + Build.VERSION.SDK_INT
                + " (language auto-detection via EXTRA_ENABLE_LANGUAGE_DETECTION requires >= 34)");
        Log.d(TAG, "setupSpeechRecognizer: creating SpeechRecognizer instance, thread=" + Thread.currentThread().getName());
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                Log.d(TAG, "ENTRY onReadyForSpeech thread=" + Thread.currentThread().getName());
                consecutiveErrorCount = 0;
                lastErrorCode = -1;
                speechListening = true;
            }
            @Override public void onBeginningOfSpeech() {
                Log.d(TAG, "ENTRY onBeginningOfSpeech");
            }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() {
                Log.d(TAG, "ENTRY onEndOfSpeech");
            }

            @Override
            public void onError(int error) {
                Log.d(TAG, "ENTRY onError code=" + error + " thread=" + Thread.currentThread().getName());
                speechListening = false;
                cancelRecognitionWatchdog();
                if (!captureActive) {
                    Log.d(TAG, "EARLY RETURN: onError - captureActive is false, ignoring error code=" + error);
                    return;
                }

                if (error == lastErrorCode) {
                    consecutiveErrorCount++;
                } else {
                    consecutiveErrorCount = 1;
                    lastErrorCode = error;
                }
                // Back off on repeated identical errors instead of hammering the recognition
                // service at a fixed short interval, which itself can provoke further errors.
                long backoffMultiplier = Math.min(consecutiveErrorCount, 5);
                Log.d(TAG, "onError: code=" + error + " consecutiveErrorCount=" + consecutiveErrorCount);

                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    scheduleSpeechRestart(500L * backoffMultiplier);
                } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    scheduleSpeechRestart(900L * backoffMultiplier);
                } else if (error == SpeechRecognizer.ERROR_CLIENT) {
                    // ERROR_CLIENT typically means the recognizer's native session state was
                    // left dirty by the previous session (e.g. a prior startListening() call
                    // whose result never fully settled). Force an explicit cancel() to reset
                    // it, on top of the cancel() startSpeechRecognitionIfNeeded() already does
                    // before every restart, and back off more aggressively on repeats.
                    Log.e(TAG, "onError: ERROR_CLIENT - forcing cancel() and backing off, consecutiveErrorCount=" + consecutiveErrorCount);
                    try { speechRecognizer.cancel(); } catch (Exception ignored) {}
                    scheduleSpeechRestart(Math.min(1000L * backoffMultiplier, 5000L));
                } else if (error == SpeechRecognizer.ERROR_AUDIO) {
                    showSubtitleMessage("זיהוי דיבור: לא התקבל קול מהמיקרופון");
                    scheduleSpeechRestart(1200L);
                } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    showSubtitleMessage("נדרש אישור מיקרופון לזיהוי הדיבור");
                } else {
                    scheduleSpeechRestart(1000L * backoffMultiplier);
                }
            }

            @Override
            public void onResults(Bundle results) {
                Log.d(TAG, "ENTRY onResults thread=" + Thread.currentThread().getName());
                speechListening = false;
                consecutiveErrorCount = 0;
                lastErrorCode = -1;
                cancelRecognitionWatchdog();
                cancelPendingPartialTranslation();
                translateFinalResult(results);
                if (captureActive) scheduleSpeechRestart(350L);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                Log.d(TAG, "ENTRY onPartialResults");
                handlePartialResult(partialResults);
            }

            @Override public void onEvent(int eventType, Bundle params) { }

            @Override
            public void onLanguageDetection(Bundle results) {
                Log.d(TAG, "ENTRY onLanguageDetection SDK_INT=" + Build.VERSION.SDK_INT + " results=" + (results != null));
                if (results == null) {
                    Log.d(TAG, "onLanguageDetection: results bundle is null");
                    return;
                }

                String language = results.getString(SpeechRecognizer.DETECTED_LANGUAGE);
                int confidenceLevel = results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL,
                        SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN);
                ArrayList<String> alternatives = results.getStringArrayList(SpeechRecognizer.TOP_LOCALE_ALTERNATIVES);

                Log.d(TAG, "onLanguageDetection: DETECTED_LANGUAGE=" + language
                        + " CONFIDENCE=" + confidenceLevel + " (" + languageDetectionConfidenceLabel(confidenceLevel) + ")"
                        + " TOP_LOCALE_ALTERNATIVES=" + alternatives);

                if (Build.VERSION.SDK_INT >= 34 && language != null && !language.isEmpty()) {
                    detectedLanguage = language;
                    Log.d(TAG, "onLanguageDetection: detectedLanguage field updated to \"" + detectedLanguage + "\"");
                }
            }
        });

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        speechIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

        if (Build.VERSION.SDK_INT >= 34) {
            // Source-language auto-detection: EXTRA_ENABLE_LANGUAGE_SWITCH is a String extra
            // (one of LANGUAGE_SWITCH_HIGH_PRECISION/BALANCED/QUICK_RESPONSE per AOSP's
            // RecognizerIntent), NOT a boolean — passing `true` (its previous value here) is a
            // type mismatch that the system silently ignores, which is the likely reason
            // onLanguageDetection never fired despite EXTRA_ENABLE_LANGUAGE_DETECTION being set.
            // Also provide an explicit candidate list: without EXTRA_..._ALLOWED_LANGUAGES the
            // detector has no declared set of languages to consider switching among.
            ArrayList<String> candidateLanguages = new ArrayList<>(Arrays.asList(
                    "tr-TR", "en-US", "he-IL", "ar-SA", "ru-RU", "es-ES", "fr-FR", "de-DE"
            ));
            speechIntent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
            speechIntent.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, candidateLanguages);
            speechIntent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
            speechIntent.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, candidateLanguages);
            Log.d(TAG, "setupSpeechRecognizer: language detection/switch configured, candidates=" + candidateLanguages);
        }
    }

    private void startSpeechRecognitionIfNeeded() {
        Log.d(TAG, "ENTRY startSpeechRecognitionIfNeeded thread=" + Thread.currentThread().getName()
                + " captureActive=" + captureActive
                + " speechRecognizer=" + (speechRecognizer != null)
                + " speechIntent=" + (speechIntent != null)
                + " speechListening=" + speechListening);
        if (!captureActive || speechRecognizer == null || speechIntent == null || speechListening) {
            // Expected/frequent, not an error: AudioCaptureService re-broadcasts "capturing"
            // roughly every 220ms while capture is active, and most of those calls land while
            // a recognition session is already in progress (speechListening=true).
            Log.d(TAG, "EARLY RETURN: startSpeechRecognitionIfNeeded - captureActive=" + captureActive
                    + " speechRecognizer=" + (speechRecognizer != null)
                    + " speechIntent=" + (speechIntent != null)
                    + " speechListening=" + speechListening);
            return;
        }
        try {
            // Some devices/recognition services leave residual native session state after the
            // previous session (onResults/onError) that startListening() alone doesn't clear,
            // which then surfaces as ERROR_CLIENT on the next startListening() call. Explicitly
            // cancel() first to force the recognizer into a clean idle state before every start.
            Log.d(TAG, "startSpeechRecognitionIfNeeded: calling cancel() to reset state before restart");
            speechRecognizer.cancel();
            speechListening = true;
            Log.d(TAG, "startSpeechRecognitionIfNeeded: calling speechRecognizer.startListening()");
            speechRecognizer.startListening(speechIntent);
            scheduleRecognitionWatchdog();
        } catch (Exception e) {
            speechListening = false;
            Log.e(TAG, "startSpeechRecognitionIfNeeded: EXCEPTION calling startListening()", e);
            showSubtitleMessage("לא הצלחנו להפעיל את זיהוי הדיבור");
            scheduleSpeechRestart(1500L);
        }
    }

    private void scheduleSpeechRestart(long delayMs) {
        // Never let more than one restart be pending: onError/onResults can each request a
        // restart in quick succession, and without this a pile of stacked postDelayed callbacks
        // could each independently call startListening() moments apart.
        cancelPendingRestart();
        Log.d(TAG, "scheduleSpeechRestart: scheduling in " + delayMs + "ms");
        pendingRestartRunnable = () -> {
            pendingRestartRunnable = null;
            if (captureActive) startSpeechRecognitionIfNeeded();
        };
        handler.postDelayed(pendingRestartRunnable, delayMs);
    }

    private void cancelPendingRestart() {
        if (pendingRestartRunnable != null) {
            handler.removeCallbacks(pendingRestartRunnable);
            pendingRestartRunnable = null;
        }
    }

    // Observed in practice: with continuous, non-pausing source audio the recognizer can go
    // fully silent for 40+ seconds — no onEndOfSpeech, no onPartialResults, no onResults, no
    // onError — until something external (capture stopping) eventually surfaces ERROR_CLIENT.
    // This watchdog forces a clean cancel()+restart if no terminal callback arrives in time,
    // instead of silently sitting stuck with no subtitles for tens of seconds.
    private void scheduleRecognitionWatchdog() {
        cancelRecognitionWatchdog();
        Log.d(TAG, "scheduleRecognitionWatchdog: armed, will force-restart in 10000ms if no callback arrives");
        recognitionWatchdogRunnable = () -> {
            recognitionWatchdogRunnable = null;
            if (speechListening && captureActive) {
                Log.e(TAG, "recognitionWatchdog: FIRED - no terminal callback for 10s, forcing cancel() and scheduling restart");
                speechListening = false;
                try { speechRecognizer.cancel(); } catch (Exception ignored) {}
                // Do NOT call startListening() synchronously right here: cancelling a session
                // that was still actively listening (mid-utterance) can trigger an async
                // ERROR_CLIENT that arrives a few ms later, after a new session has already
                // started, and stomps on its state (this was observed in practice). Route
                // through the same short-delay restart path the normal error handling uses
                // instead, so a late-arriving stale error just lands on an idle state.
                scheduleSpeechRestart(400L);
            }
        };
        handler.postDelayed(recognitionWatchdogRunnable, 10000L);
    }

    private void cancelRecognitionWatchdog() {
        if (recognitionWatchdogRunnable != null) {
            handler.removeCallbacks(recognitionWatchdogRunnable);
            recognitionWatchdogRunnable = null;
        }
    }

    private void stopSpeechRecognition() {
        speechListening = false;
        consecutiveErrorCount = 0;
        lastErrorCode = -1;
        cancelPendingRestart();
        cancelRecognitionWatchdog();
        cancelPendingPartialTranslation();
        lastTranslationSourceText = null;
        translationRequestId++;
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

    // A true final onResults can be rare or absent during continuous, non-pausing speech
    // (e.g. video audio) — the recognizer just keeps streaming partials and repeatedly hits
    // ERROR_NO_MATCH/ERROR_SPEECH_TIMEOUT instead of ever finalizing. If translation only ever
    // triggered from a true final, it could go permanently un-triggered. So a partial result
    // that stops changing for a short moment is treated as a de-facto final and translated too.
    // A hard cap forces a translation periodically even during very long uninterrupted speech.
    private void handlePartialResult(Bundle results) {
        String text = bestText(results);
        Log.d(TAG, "handlePartialResult: bestText() returned \"" + text + "\"");
        if (text == null) {
            Log.e(TAG, "EARLY RETURN: handlePartialResult - bestText() returned null/empty");
            return;
        }
        showSubtitleMessage(text);
        schedulePartialTranslation(text);
    }

    private void schedulePartialTranslation(String text) {
        Log.d(TAG, "ENTRY schedulePartialTranslation text=\"" + text + "\"");
        if (text.equals(lastPartialDebounceText)) {
            Log.d(TAG, "EARLY RETURN: schedulePartialTranslation - text unchanged since last partial (\"" + text + "\")");
            return;
        }
        lastPartialDebounceText = text;
        if (partialTranslateRunnable != null) handler.removeCallbacks(partialTranslateRunnable);

        long now = System.currentTimeMillis();
        if (pendingPartialStartTime == 0L) pendingPartialStartTime = now;
        long delay = (now - pendingPartialStartTime) > 4000L ? 0L : 700L;
        Log.d(TAG, "schedulePartialTranslation: scheduling in " + delay + "ms for text=\"" + text + "\"");

        partialTranslateRunnable = () -> {
            pendingPartialStartTime = 0L;
            Log.d(TAG, "schedulePartialTranslation: debounce fired, treating partial as final for text=\"" + text + "\"");
            if (captureActive) translateToHebrew(text);
        };
        handler.postDelayed(partialTranslateRunnable, delay);
    }

    private void cancelPendingPartialTranslation() {
        if (partialTranslateRunnable != null) {
            handler.removeCallbacks(partialTranslateRunnable);
            partialTranslateRunnable = null;
        }
        pendingPartialStartTime = 0L;
        lastPartialDebounceText = null;
    }

    private void translateFinalResult(Bundle results) {
        Log.d(TAG, "ENTRY translateFinalResult");
        String text = bestText(results);
        Log.d(TAG, "translateFinalResult: bestText() returned \"" + text + "\"");
        if (text == null) {
            Log.e(TAG, "EARLY RETURN: translateFinalResult - bestText() returned null/empty (empty RESULTS_RECOGNITION)");
            return;
        }
        translateToHebrew(text);
    }

    private void translateToHebrew(String text) {
        Log.d(TAG, "ENTRY translateToHebrew text=\"" + text + "\"");
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
        translationStatus = "🌐 מזהה שפה";
        showSubtitleMessage("מזהה שפה…");
        refreshCapturingStatus();

        languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener(languageCode -> {
                    Log.d(TAG, "identifyLanguage SUCCESS requestId=" + requestId + " languageCode=" + languageCode);
                    if (requestId != translationRequestId) {
                        Log.d(TAG, "EARLY RETURN: identifyLanguage(success) - stale requestId=" + requestId + " current=" + translationRequestId);
                        return;
                    }

                    String sourceLanguage = null;
                    if (languageCode != null && !"und".equals(languageCode)) {
                        sourceLanguage = TranslateLanguage.fromLanguageTag(languageCode);
                        Log.d(TAG, "identifyLanguage: languageCode=" + languageCode + " -> TranslateLanguage=" + sourceLanguage);
                    } else {
                        Log.d(TAG, "identifyLanguage: undetermined (\"und\") or null, will try speech-detected language fallback");
                    }

                    if (sourceLanguage == null) {
                        String speechLanguage = normalizeLanguageTag(detectedLanguage);
                        Log.d(TAG, "identifyLanguage: fallback detectedLanguage=" + detectedLanguage + " normalized=" + speechLanguage);
                        if (speechLanguage != null) {
                            sourceLanguage = TranslateLanguage.fromLanguageTag(speechLanguage);
                            Log.d(TAG, "identifyLanguage: fallback speechLanguage=" + speechLanguage + " -> TranslateLanguage=" + sourceLanguage);
                        }
                    }

                    if (sourceLanguage == null) {
                        Log.e(TAG, "EARLY RETURN: identifyLanguage(success) - could not resolve any source language for text=\"" + text + "\" requestId=" + requestId);
                        translationStatus = "🌐 שפה לא זוהתה";
                        showSubtitleMessage("לא הצלחתי לזהות את שפת הדיבור: " + text);
                        refreshCapturingStatus();
                        return;
                    }

                    Log.d(TAG, "identifyLanguage: resolved sourceLanguage=" + sourceLanguage + " requestId=" + requestId);
                    translateWithLanguage(text, sourceLanguage, requestId);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "identifyLanguage FAILURE requestId=" + requestId + " text=\"" + text + "\"", e);
                    if (requestId != translationRequestId) {
                        Log.d(TAG, "EARLY RETURN: identifyLanguage(failure) - stale requestId=" + requestId + " current=" + translationRequestId);
                        return;
                    }
                    String speechLanguage = normalizeLanguageTag(detectedLanguage);
                    String sourceLanguage = speechLanguage == null ? null : TranslateLanguage.fromLanguageTag(speechLanguage);
                    Log.d(TAG, "identifyLanguage failure: fallback speechLanguage=" + speechLanguage + " -> TranslateLanguage=" + sourceLanguage);
                    if (sourceLanguage != null) {
                        translateWithLanguage(text, sourceLanguage, requestId);
                    } else {
                        Log.e(TAG, "EARLY RETURN: identifyLanguage(failure) - no fallback language available for text=\"" + text + "\" requestId=" + requestId);
                        translationStatus = "🌐 שגיאת זיהוי שפה";
                        showSubtitleMessage("לא הצלחתי לזהות את שפת הדיבור: " + text);
                        refreshCapturingStatus();
                    }
                });
    }

    private void translateWithLanguage(String text, String sourceLanguage, long requestId) {
        Log.d(TAG, "ENTRY translateWithLanguage requestId=" + requestId + " sourceLanguage=" + sourceLanguage + " text=\"" + text + "\"");

        if (TranslateLanguage.HEBREW.equals(sourceLanguage)) {
            Log.d(TAG, "translateWithLanguage: source already Hebrew, passthrough (no ML Kit translate call) requestId=" + requestId);
            if (requestId == translationRequestId) {
                translationStatus = "🌐 עברית";
                showSubtitleMessage(text);
                Log.d(TAG, "translateWithLanguage: overlay updated (Hebrew passthrough) text=\"" + text + "\"");
                refreshCapturingStatus();
            } else {
                Log.d(TAG, "EARLY RETURN: translateWithLanguage(Hebrew passthrough) - stale requestId=" + requestId + " current=" + translationRequestId);
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
        } else {
            Log.d(TAG, "translateWithLanguage: reusing EXISTING Translator " + sourceLanguage + "->he requestId=" + requestId);
        }

        final Translator finalTranslator = translator;
        translationStatus = "🌐 מכין תרגום";
        showSubtitleMessage("מכין תרגום לעברית…");
        refreshCapturingStatus();

        DownloadConditions conditions = new DownloadConditions.Builder().build();
        Log.d(TAG, "downloadModelIfNeeded: checking/starting model " + sourceLanguage + "->he requestId=" + requestId);
        finalTranslator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "downloadModelIfNeeded SUCCESS (model ready) requestId=" + requestId);
                    if (requestId != translationRequestId) {
                        Log.d(TAG, "EARLY RETURN: downloadModelIfNeeded(success) - stale requestId=" + requestId + " current=" + translationRequestId);
                        return;
                    }
                    translationStatus = "🌐 מתרגם";
                    showSubtitleMessage("מתרגם לעברית…");
                    refreshCapturingStatus();

                    Log.d(TAG, "translate(): calling ML Kit translate() requestId=" + requestId + " text=\"" + text + "\"");
                    finalTranslator.translate(text)
                            .addOnSuccessListener(translatedText -> {
                                Log.d(TAG, "translate() SUCCESS requestId=" + requestId + " translatedText=\"" + translatedText + "\"");
                                if (requestId != translationRequestId) {
                                    Log.d(TAG, "EARLY RETURN: translate(success) - stale requestId=" + requestId + " current=" + translationRequestId + " (overlay NOT updated)");
                                    return;
                                }
                                translationStatus = "🌐 עברית";
                                showSubtitleMessage(translatedText);
                                Log.d(TAG, "translate(): overlay UPDATED with translatedText=\"" + translatedText + "\"");
                                refreshCapturingStatus();
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "translate() FAILURE requestId=" + requestId + " text=\"" + text + "\"", e);
                                if (requestId != translationRequestId) {
                                    Log.d(TAG, "EARLY RETURN: translate(failure) - stale requestId=" + requestId + " current=" + translationRequestId);
                                    return;
                                }
                                translationStatus = "🌐 שגיאת תרגום";
                                showSubtitleMessage("שגיאה בתרגום. הטקסט שזוהה: " + text);
                                refreshCapturingStatus();
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "downloadModelIfNeeded FAILURE requestId=" + requestId + " sourceLanguage=" + sourceLanguage, e);
                    if (requestId != translationRequestId) {
                        Log.d(TAG, "EARLY RETURN: downloadModelIfNeeded(failure) - stale requestId=" + requestId + " current=" + translationRequestId);
                        return;
                    }
                    translationStatus = "🌐 הורדת מודל נכשלה";
                    showSubtitleMessage("לא הצלחתי להוריד את מודל התרגום. ודאי שיש אינטרנט ונסי שוב.");
                    refreshCapturingStatus();
                });
    }

    private String languageDetectionConfidenceLabel(int level) {
        if (level == SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT) return "HIGHLY_CONFIDENT";
        if (level == SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT) return "CONFIDENT";
        if (level == SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_NOT_CONFIDENT) return "NOT_CONFIDENT";
        if (level == SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN) return "UNKNOWN";
        return "UNRECOGNIZED(" + level + ")";
    }

    private String normalizeLanguageTag(String language) {
        if (language == null) return null;
        String value = language.trim();
        if (value.isEmpty()) return null;
        return value.replace('_', '-');
    }

    private void showSubtitleMessage(String text) {
        if (subtitleView == null) {
            Log.e(TAG, "showSubtitleMessage: subtitleView is NULL, cannot update overlay. text=\"" + text + "\"");
            return;
        }
        Log.d(TAG, "showSubtitleMessage: overlay text set to \"" + text + "\"");
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
            // AudioCaptureService re-broadcasts "capturing" roughly every 220ms for the level
            // meter, for as long as capture is active. Only (re)start speech recognition on the
            // actual transition into capturing — ongoing keep-alive/restart after that is fully
            // owned by onResults/onError's own scheduleSpeechRestart chain, so calling this on
            // every single broadcast was redundant and just added noise/risk of races.
            boolean wasActive = captureActive;
            captureActive = true;
            if (!closeArmed) refreshCapturingStatus();
            if (floatingButton != null && !closeArmed) floatingButton.setText("■");
            if (!wasActive) {
                Log.d(TAG, "updateCaptureStatus: transition into capturing, starting speech recognition");
                startSpeechRecognitionIfNeeded();
            }
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
