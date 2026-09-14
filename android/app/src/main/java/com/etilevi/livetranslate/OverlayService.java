package com.etilevi.livetranslate;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;

public class OverlayService extends Service {
    private WindowManager windowManager;
    private View floatingButton;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showFloatingButton();
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

        int size = (int) (64 * getResources().getDisplayMetrics().density);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 24;
        params.y = 220;

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
                            button.setText(button.getText().toString().equals("🌐") ? "■" : "🌐");
                        }
                        return true;
                }
                return false;
            }
        });

        floatingButton = button;
        windowManager.addView(floatingButton, params);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingButton != null && windowManager != null) {
            windowManager.removeView(floatingButton);
            floatingButton = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
