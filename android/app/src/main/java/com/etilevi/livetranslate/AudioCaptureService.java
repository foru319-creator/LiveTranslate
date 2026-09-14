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

import androidx.core.app.NotificationCompat;

public class AudioCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String ACTION_TOGGLE = "com.etilevi.livetranslate.TOGGLE_CAPTURE";
    public static final String ACTION_STOP = "com.etilevi.livetranslate.STOP_CAPTURE";
    public static final String ACTION_STATUS = "com.etilevi.livetranslate.CAPTURE_STATUS";

    private static final String CHANNEL_ID = "live_translate_capture";
    private static final int NOTIFICATION_ID = 4101;
    private static final int SAMPLE_RATE = 16000;

    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean capturing = false;
    private volatile boolean projectionReady = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
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

            captureThread = new Thread(() -> readAudioLoop(bufferSize / 2), "LiveTranslateAudioCapture");
            captureThread.start();
        } catch (Exception e) {
            capturing = false;
            broadcastStatus("capture_error", 0);
            releaseAudioRecord();
        }
    }

    private void readAudioLoop(int shortBufferSize) {
        short[] buffer = new short[Math.max(shortBufferSize, 2048)];
        long lastBroadcast = 0;

        while (capturing && audioRecord != null) {
            int read = audioRecord.read(buffer, 0, buffer.length);
            if (read <= 0) continue;

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

    private synchronized void pauseCapture() {
        if (!capturing) return;
        capturing = false;
        releaseAudioRecord();
        updateNotification("הקליטה מושהית");
        broadcastStatus("paused", 0);
    }

    private void stopCaptureAndSelf() {
        capturing = false;
        releaseAudioRecord();
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

    private void broadcastStatus(String status, int level) {
        Intent statusIntent = new Intent(ACTION_STATUS);
        statusIntent.setPackage(getPackageName());
        statusIntent.putExtra("status", status);
        statusIntent.putExtra("level", level);
        sendBroadcast(statusIntent);
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
