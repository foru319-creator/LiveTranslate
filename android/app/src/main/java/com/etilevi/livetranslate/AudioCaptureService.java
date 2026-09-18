package com.etilevi.livetranslate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class AudioCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String ACTION_TOGGLE = "com.etilevi.livetranslate.TOGGLE_CAPTURE";
    public static final String ACTION_STOP = "com.etilevi.livetranslate.STOP_CAPTURE";

    // Capture status, transcripts, and cloud-connection status are no
    // longer sent as Android system broadcasts (see CaptureEventBus for
    // why) — broadcastStatus()/broadcastTranscript()/broadcastCloudStatus()
    // below now just forward to CaptureEventBus's in-process listener.

    private static final String TAG = "AudioCaptureService";
    private static final String CHANNEL_ID = "live_translate_capture";
    private static final int NOTIFICATION_ID = 4101;
    private static final int SAMPLE_RATE = 16000;

    // Size of each audio chunk handed to the WebSocket, independent of
    // AudioRecord's internal capture buffer below. Google Speech-to-Text v2
    // streaming rejects any single chunk over 25600 bytes with
    // INVALID_ARGUMENT; 8000 samples * 2 bytes/sample (16-bit mono) = 16000
    // bytes (500ms @ 16kHz), well under that limit with margin, and cuts
    // latency in half versus the previous ~1s chunking.
    private static final int CHUNK_SAMPLES = 8000;

    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean capturing = false;
    private volatile boolean projectionReady = false;

    private CloudTranscriptionClient cloudClient;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        cloudClient = new CloudTranscriptionClient(new CloudTranscriptionClient.Listener() {
            @Override
            public void onTranscript(String text, String languageCode, boolean isFinal, boolean translated, long receivedAtElapsedMs, long speechBeginAtMs, long speechEndAtMs) {
                broadcastTranscript(text, languageCode, isFinal, translated, receivedAtElapsedMs, speechBeginAtMs, speechEndAtMs);
            }

            @Override
            public void onConnectionStateChanged(boolean connected) {
                broadcastCloudStatus(connected);
            }

            @Override
            public void onServerNotice(String message) {
                // Informational (e.g. server-side session cap) — the client
                // reconnects automatically, nothing else to do here.
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            if (capturing) pauseCapture(); else resumeCapture();
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopCaptureAndSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("מכין קליטת שמע…"));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            broadcastStatus("unsupported", 0);
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }

        if (!projectionReady && resultCode != 0 && resultData != null) {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = manager.getMediaProjection(resultCode, resultData);
            projectionReady = mediaProjection != null;
        }

        if (!projectionReady) {
            broadcastStatus("permission_error", 0);
            stopSelf();
            return START_NOT_STICKY;
        }

        resumeCapture();
        return START_STICKY;
    }

    private synchronized void resumeCapture() {
        if (!projectionReady || capturing || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;

        // Kick off Firebase auth + the WebSocket + Chirp 3 stream setup
        // first, before any AudioRecord/capture-config work below — every
        // millisecond of head start here matters, since sendAudio() now
        // buffers (rather than drops) audio captured before this finishes,
        // but a bounded buffer still fills faster the later this starts.
        cloudClient.start();

        try {
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();

            int minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int bufferSize = Math.max(minBuffer, SAMPLE_RATE * 2);

            audioRecord = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            audioRecord.startRecording();
            capturing = true;
            updateNotification("קולט שמע מהסרטון");
            broadcastStatus("capturing", 0);

            captureThread = new Thread(() -> readAudioLoop(CHUNK_SAMPLES), "LiveTranslateAudioCapture");
            captureThread.start();
        } catch (Exception e) {
            capturing = false;
            broadcastStatus("capture_error", 0);
            releaseAudioRecord();
        }
    }

    private void readAudioLoop(int chunkSamples) {
        short[] buffer = new short[chunkSamples];
        byte[] pcmBytes = new byte[buffer.length * 2];
        long lastBroadcast = 0;

        while (capturing && audioRecord != null) {
            int read = audioRecord.read(buffer, 0, buffer.length);
            if (read <= 0) continue;

            // Stream every chunk to the cloud backend for transcription,
            // independent of the throttled level-meter broadcast below.
            int chunkBytes = read * 2;
            Log.d(TAG, "sending audio chunk: " + chunkBytes + " bytes");
            cloudClient.sendAudio(shortsToPcm16Le(buffer, read, pcmBytes), chunkBytes);

            long now = System.currentTimeMillis();
            if (now - lastBroadcast < 220) continue;
            lastBroadcast = now;

            double sum = 0;
            for (int i = 0; i < read; i++) {
                double value = buffer[i] / 32768.0;
                sum += value * value;
            }
            double rms = Math.sqrt(sum / read);
            int level = (int) Math.round(Math.min(5.0, Math.max(0.0, rms * 25.0)));
            broadcastStatus("capturing", level);
        }
    }

    // AudioRecord's short[] samples are in the device's native byte order,
    // which is little-endian on all Android devices in practice — matching
    // exactly the LINEAR16 (PCM16LE) format the backend expects, so this is
    // a direct byte-level repack rather than a real endianness conversion.
    private static byte[] shortsToPcm16Le(short[] samples, int count, byte[] outBuffer) {
        for (int i = 0; i < count; i++) {
            short sample = samples[i];
            outBuffer[i * 2] = (byte) (sample & 0xFF);
            outBuffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return outBuffer;
    }

    private synchronized void pauseCapture() {
        if (!capturing) return;
        capturing = false;
        releaseAudioRecord();
        cloudClient.stop();
        updateNotification("הקליטה מושהית");
        broadcastStatus("paused", 0);
    }

    private void stopCaptureAndSelf() {
        capturing = false;
        releaseAudioRecord();
        cloudClient.stop();
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        projectionReady = false;
        broadcastStatus("stopped", 0);
        stopForeground(true);
        stopSelf();
    }

    private void releaseAudioRecord() {
        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) {}
            record.release();
        }
    }

    // Named broadcastX for historical reasons (this used to be a real
    // sendBroadcast()) — now a direct in-process call via CaptureEventBus.
    // May be called from the audio capture thread (see readAudioLoop) or
    // the main thread; CaptureEventBus itself is thread-safe either way.
    private void broadcastStatus(String status, int level) {
        CaptureEventBus.notifyCaptureStatus(status, level);
    }

    private void broadcastTranscript(String text, String languageCode, boolean isFinal, boolean translated, long receivedAtElapsedMs, long speechBeginAtMs, long speechEndAtMs) {
        CaptureEventBus.notifyTranscript(text, languageCode, isFinal, translated, receivedAtElapsedMs, speechBeginAtMs, speechEndAtMs);
    }

    private void broadcastCloudStatus(boolean connected) {
        CaptureEventBus.notifyCloudStatus(connected);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Live Translate audio capture",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("קליטת שמע לצורך תרגום חי");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Live Translate")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        capturing = false;
        releaseAudioRecord();
        cloudClient.stop();
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
