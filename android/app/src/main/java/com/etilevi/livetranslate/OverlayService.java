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
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class OverlayService extends Service {
    private WindowManager windowManager;
    private View floatingButton;
    private TextView statusView;
    private boolean receiverRegistered = false;

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
        showStatusView();
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

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true;
                        params.x = initialX - (int) dx;
                        params.y = initialY + (int) dy;
                        windowManager.updateViewLayout(floatingButton, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
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
            StringBuilder meter = new StringBuilder();
            for (int i = 0; i < 5; i++) meter.append(i < level ? "●" : "○");
            statusView.setText("🎧 קולט שמע מהסרטון  " + meter);
            if (floatingButton instanceof TextView) ((TextView) floatingButton).setText("■");
        } else if ("paused".equals(status)) {
            statusView.setText("⏸ התרגום מושהה • לחצי 🌐 להמשך");
            if (floatingButton instanceof TextView) ((TextView) floatingButton).setText("🌐");
        } else if ("stopped".equals(status)) {
            statusView.setText("Live Translate • הקליטה נעצרה");
            if (floatingButton instanceof TextView) ((TextView) floatingButton).setText("🌐");
        } else if ("unsupported".equals(status)) {
            statusView.setText("המכשיר לא תומך בקליטת שמע פנימי");
        } else if ("capture_error".equals(status)) {
            statusView.setText("לא הצלחנו לקלוט את השמע מהסרטון");
        } else if ("permission_error".equals(status)) {
            statusView.setText("נדרש אישור Android לקליטת המדיה");
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onDestroy() {
        if (floatingButton != null && windowManager != null) {
            windowManager.removeView(floatingButton);
            floatingButton = null;
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
