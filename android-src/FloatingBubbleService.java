package com.aventador.trade;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class FloatingBubbleService extends Service {

    private static final String CHANNEL_ID = "aventador_overlay";
    private static final int NOTIF_ID = 8801;
    private static final String LINK_TG = "https://t.me/tweakos";

    private static final int C_BG         = Color.parseColor("#0E0E12");
    private static final int C_BTN        = Color.parseColor("#16161C");
    private static final int C_BTN_STROKE = Color.parseColor("#26262E");
    private static final int C_ACCENT     = Color.parseColor("#E1A100");
    private static final int C_TEXT       = Color.parseColor("#DCDCDC");
    private static final int C_MUTED      = Color.parseColor("#6C6C6C");
    private static final int C_OK         = Color.parseColor("#2ECC71");
    private static final int C_ERR        = Color.parseColor("#FF5C5C");

    private final Handler ui = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;

    private int screen = 0; // 0 = main, 1 = settings
    private boolean holdTriggered = false;

    // Настройки
    private int delayMs = 50;
    private double maxPrice = 1.80;
    private boolean botRunning = false;
    private int currentFps = 0;

    // UI-ссылки для обновления
    private TextView fpsValue;
    private TextView delayValue;
    private TextView startStopBtn;
    private EditText priceField;

    private final Runnable holdRunnable = new Runnable() {
        @Override public void run() {
            holdTriggered = true;
            killAll();
        }
    };

    // FPS-счётчик — заглушка, обновляется каждую секунду
    private final Runnable fpsRunnable = new Runnable() {
        @Override public void run() {
            if (!botRunning) { currentFps = 0; }
            else {
                // TODO: сюда подключи свой реальный FPS-счётчик
                currentFps = 60;
            }
            updateFpsUi();
            ui.postDelayed(this, 1000);
        }
    };

    // ============================================================
    //  ЛОГИКА БОТА — ВСТАВЛЯЙ СВОЮ ЛОГИКУ ЗДЕСЬ
    // ============================================================

    private void startBot() {
        botRunning = true;
        toast("Бот запущен · цена ≤ " + String.format("%.2f", maxPrice));
        // TODO: тут запускай свою логику кликов
        // Например: вызов через Perfect Click, Accessibility Service, или нативный код
    }

    private void stopBot() {
        botRunning = false;
        toast("Бот остановлен");
        // TODO: тут останавливай логику кликов
    }

    // ============================================================
    //  Lifecycle
    // ============================================================

    @Override
    public void onCreate() {
        super.onCreate();
        try { wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE); }
        catch (Throwable ignored) {}
        ui.post(fpsRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSafe();
        try {
            if (intent != null) {
                String cfg = intent.getStringExtra("config");
                if (cfg != null) parseConfig(cfg);
            }
            if (!Settings.canDrawOverlays(this)) { killAll(); return START_NOT_STICKY; }
            if (bubble == null) createBubble();
        } catch (Throwable t) { killAll(); }
        return START_NOT_STICKY;
    }

    private void parseConfig(String json) {
        try {
            JSONObject o = new JSONObject(json);
            maxPrice = o.optDouble("price", 1.80);
            delayMs = o.optInt("delay", 50);
        } catch (Throwable ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        try { ui.removeCallbacksAndMessages(null); } catch (Throwable ignored) {}
        destroyAll();
        super.onDestroy();
    }

    private void startForegroundSafe() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, 0x40000000);
            else startForeground(NOTIF_ID, n);
        } catch (Throwable ignored) {}
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Aventador Trade", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL_ID);
        } else b = new Notification.Builder(this);
        b.setContentTitle("Aventador Trade").setContentText("Overlay active")
         .setSmallIcon(android.R.drawable.ic_menu_manage).setOngoing(true);
        return b.build();
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ============================================================
    //  Кружок
    // ============================================================

    private void createBubble() {
        if (wm == null || bubble != null) return;
        final int size = dp(60);

        TextView circle = new TextView(this);
        circle.setText("A");
        circle.setTextColor(C_ACCENT);
        circle.setTextSize(26);
        circle.setTypeface(Typeface.DEFAULT_BOLD);
        circle.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#000000"));
        bg.setStroke(dp(2), C_ACCENT);
        circle.setBackground(bg);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                size, size, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dm.widthPixels - size - dp(12);
        lp.y = dm.heightPixels / 3;

        circle.setOnTouchListener(new View.OnTouchListener() {
            private int sx, sy;
            private float tx, ty;
            private boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                try {
                    WindowManager.LayoutParams p = bubbleLp;
                    if (p == null || wm == null || bubble == null) return false;
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            sx = p.x; sy = p.y;
                            tx = e.getRawX(); ty = e.getRawY();
                            moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float dx = e.getRawX() - tx;
                            float dy = e.getRawY() - ty;
                            if (!moved && (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8))) moved = true;
                            if (moved) {
                                p.x = (int) (sx + dx);
                                p.y = (int) (sy + dy);
                                wm.updateViewLayout(bubble, p);
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                            if (!moved) openPanel();
                            return true;
                    }
                } catch (Throwable ignored) {}
                return true;
            }
        });

        try { wm.addView(circle, lp); } catch (Throwable t) { return; }
        bubble = circle;
        bubbleLp = lp;
    }

    // ============================================================
    //  Панель
    // ============================================================

    private void openPanel() {
        if (wm == null || panel != null || bubble == null || bubbleLp == null) return;
        try { bubble.setVisibility(View.GONE); } catch (Throwable ignored) {}

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(16));
        panel.setBackground(rounded(C_BG, 16, Color.parseColor("#2A2A32"), 1));

        buildScreen();

        int pw = dp(300);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                pw, ViewGroup.LayoutParams.WRAP_CONTENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = bubbleLp.x + (bubbleLp.width - pw) / 2;
        lp.y = bubbleLp.y;

        try { wm.addView(panel, lp); } catch (Throwable t) {
            try { bubble.setVisibility(View.VISIBLE); } catch (Throwable ignored) {}
            panel = null;
            return;
        }
        panelLp = lp;
    }

    private void buildScreen() {
        if (panel == null) return;
        try {
            panel.removeAllViews();
            switch (screen) {
                case 1: buildSettings(); break;
                default: buildMain(); break;
            }
        } catch (Throwable ignored) {}
    }

    private void buildMain() {
        addHeader("Aventador Trade", false);

        // --- FPS ---
        LinearLayout fpsCard = card("FPS в реальном времени");
        LinearLayout fpsRow = new LinearLayout(this);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        fpsRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView fpsLabel = text("Текущий", 13, C_MUTED, false);
        fpsRow.addView(fpsLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        fpsValue = text("0", 22, C_OK, true);
        fpsValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        fpsRow.addView(fpsValue);
        fpsCard.addView(fpsRow);
        panel.addView(fpsCard, lp(8, 0));

        // --- Start / Pause ---
        startStopBtn = new TextView(this);
        startStopBtn.setText(botRunning ? "⏸  Pause" : "▶  Start");
        startStopBtn.setTextColor(Color.WHITE);
        startStopBtn.setTextSize(15);
        startStopBtn.setGravity(Gravity.CENTER);
        startStopBtn.setPadding(dp(14), dp(14), dp(14), dp(14));
        startStopBtn.setBackground(rounded(botRunning ? C_ERR : C_ACCENT, 10, 0, 0));
        startStopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (botRunning) stopBot(); else startBot();
                buildScreen();
            }
        });
        panel.addView(startStopBtn, lp(10, 0));

        // --- Автонастройка ---
        TextView autoTune = iconButton("Автонастройка", ICON_GEAR, C_BTN, false);
        autoTune.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toast("Автонастройка: в разработке"); }
        });
        panel.addView(autoTune, lp(0, 8));

        // --- Настройки (кнопка перехода) ---
        TextView settings = iconButton("Настройки", ICON_GEAR, C_BTN, false);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { screen = 1; buildScreen(); }
        });
        panel.addView(settings, lp(0, 0));
    }

    private void buildSettings() {
        addHeader("Настройки", true);

        // --- Слайдер задержки ---
        LinearLayout delayCard = card("Задержка (10–100 мс)");
        delayValue = text(delayMs + " мс", 20, C_ACCENT, true);
        delayValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        delayValue.setGravity(Gravity.CENTER);
        delayValue.setPadding(0, 0, 0, dp(8));
        delayCard.addView(delayValue);

        SeekBar bar = new SeekBar(this);
        bar.setMax(90); // 10..100
        bar.setProgress(Math.max(0, Math.min(90, delayMs - 10)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                delayMs = p + 10;
                if (delayValue != null) delayValue.setText(delayMs + " мс");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        delayCard.addView(bar);
        panel.addView(delayCard, lp(8, 0));

        // --- Цена ---
        LinearLayout priceCard = card("Максимальная цена лота");
        priceField = new EditText(this);
        priceField.setText(String.format("%.2f", maxPrice));
        priceField.setTextColor(Color.WHITE);
        priceField.setTextSize(20);
        priceField.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        priceField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        priceField.setGravity(Gravity.CENTER);
        priceField.setBackground(rounded(Color.parseColor("#0B0B0F"), 10, C_BTN_STROKE, 1));
        priceField.setPadding(dp(12), dp(12), dp(12), dp(12));
        priceCard.addView(priceField);
        TextView hint = text("Покупать по этой цене и ниже", 11, C_MUTED, false);
        hint.setPadding(0, dp(8), 0, 0);
        priceCard.addView(hint);
        panel.addView(priceCard, lp(8, 0));

        // --- Применить ---
        TextView apply = iconButton("Сохранить", ICON_CHECK, C_ACCENT, true);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    String s = priceField.getText().toString().replace(',', '.');
                    maxPrice = Double.parseDouble(s);
                    toast("Сохранено · " + String.format("%.2f", maxPrice) + "G · " + delayMs + "мс");
                } catch (Throwable t) { toast("Неверная цена"); }
            }
        });
        panel.addView(apply, lp(10, 0));

        // --- Telegram ---
        TextView tg = iconButton("Telegram", ICON_SEND, C_BTN, false);
        tg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openUrl(LINK_TG); }
        });
        panel.addView(tg, lp(0, 8));

        // --- Назад ---
        TextView back = iconButton("Назад", ICON_BACK, C_BTN, false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { screen = 0; buildScreen(); }
        });
        panel.addView(back, lp(0, 0));
    }

    private void updateFpsUi() {
        ui.post(new Runnable() {
            @Override public void run() {
                if (fpsValue == null) return;
                try {
                    fpsValue.setText(String.valueOf(currentFps));
                    fpsValue.setTextColor(currentFps >= 50 ? C_OK
                            : currentFps >= 30 ? C_ACCENT : C_ERR);
                } catch (Throwable ignored) {}
            }
        });
    }

    // ============================================================
    //  Close / kill
    // ============================================================

    private void closePanel() {
        if (panel == null || panelLp == null) return;
        int px = panelLp.x;
        int py = panelLp.y;

        LinearLayout p = panel;
        panel = null; panelLp = null;
        fpsValue = null; delayValue = null; startStopBtn = null; priceField = null;
        if (p != null && wm != null) {
            try { wm.removeView(p); } catch (Throwable ignored) {}
        }

        if (bubble != null && bubbleLp != null && wm != null) {
            bubbleLp.x = px + (dp(300) - bubbleLp.width) / 2;
            bubbleLp.y = py;
            try {
                bubble.setVisibility(View.VISIBLE);
                wm.updateViewLayout(bubble, bubbleLp);
            } catch (Throwable ignored) {}
        }
    }

    private void attachDragToView(final View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private int sx, sy;
            private float tx, ty;
            @Override public boolean onTouch(View v, MotionEvent e) {
                try {
                    WindowManager.LayoutParams p = panelLp;
                    if (p == null || wm == null || panel == null) return false;
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            sx = p.x; sy = p.y;
                            tx = e.getRawX(); ty = e.getRawY();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            p.x = (int) (sx + e.getRawX() - tx);
                            p.y = (int) (sy + e.getRawY() - ty);
                            wm.updateViewLayout(panel, p);
                            return true;
                    }
                } catch (Throwable ignored) {}
                return false;
            }
        });
    }

    private void attachCloseHandler(final TextView close) {
        close.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                try {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            holdTriggered = false;
                            close.setTextColor(Color.parseColor("#FF5C5C"));
                            ui.removeCallbacks(holdRunnable);
                            ui.postDelayed(holdRunnable, 3000);
                            return true;
                        case MotionEvent.ACTION_UP:
                            ui.removeCallbacks(holdRunnable);
                            close.setTextColor(Color.parseColor("#9A9A9A"));
                            if (!holdTriggered) closePanel();
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            ui.removeCallbacks(holdRunnable);
                            close.setTextColor(Color.parseColor("#9A9A9A"));
                            return true;
                    }
                } catch (Throwable ignored) {}
                return true;
            }
        });
    }

    private void destroyAll() {
        LinearLayout p = panel;
        panel = null; panelLp = null;
        fpsValue = null; delayValue = null; startStopBtn = null; priceField = null;
        if (p != null && wm != null) {
            try { wm.removeView(p); } catch (Throwable ignored) {}
        }
        TextView b = bubble;
        bubble = null; bubbleLp = null;
        if (b != null && wm != null) {
            try { wm.removeView(b); } catch (Throwable ignored) {}
        }
    }

    private void killAll() {
        botRunning = false;
        try { ui.removeCallbacksAndMessages(null); } catch (Throwable ignored) {}
        destroyAll();
        try { stopForeground(Service.STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
        try { stopSelf(); } catch (Throwable ignored) {}
    }

    // ============================================================
    //  Icons
    // ============================================================

    private static final int ICON_GEAR  = 1;
    private static final int ICON_SEND  = 2;
    private static final int ICON_BACK  = 3;
    private static final int ICON_CHECK = 4;

    private Drawable iconDrawable(int type, int color) {
        return new VectorIcon(type, color, dp(18));
    }

    private static class VectorIcon extends Drawable {
        private final int type;
        private final int size;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        VectorIcon(int type, int color, int size) {
            this.type = type; this.size = size;
            p.setColor(color); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(size / 8f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(Canvas c) {
            float s = size; float cx = s / 2f; float cy = s / 2f;
            switch (type) {
                case ICON_GEAR:
                    c.drawCircle(cx, cy, s * 0.15f, p);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        float x1 = cx + (float)(Math.cos(a) * s * 0.22f);
                        float y1 = cy + (float)(Math.sin(a) * s * 0.22f);
                        float x2 = cx + (float)(Math.cos(a) * s * 0.40f);
                        float y2 = cy + (float)(Math.sin(a) * s * 0.40f);
                        c.drawLine(x1, y1, x2, y2, p);
                    }
                    break;
                case ICON_SEND:
                    Path t = new Path();
                    t.moveTo(s * 0.10f, s * 0.50f);
                    t.lineTo(s * 0.90f, s * 0.15f);
                    t.lineTo(s * 0.60f, s * 0.85f);
                    t.lineTo(s * 0.50f, s * 0.55f);
                    t.lineTo(s * 0.10f, s * 0.50f);
                    t.close();
                    c.drawPath(t, p);
                    break;
                case ICON_BACK:
                    c.drawLine(s * 0.20f, cy, s * 0.80f, cy, p);
                    Path a = new Path();
                    a.moveTo(s * 0.20f, cy);
                    a.lineTo(s * 0.42f, s * 0.28f);
                    a.moveTo(s * 0.20f, cy);
                    a.lineTo(s * 0.42f, s * 0.72f);
                    c.drawPath(a, p);
                    break;
                case ICON_CHECK:
                    Path ch = new Path();
                    ch.moveTo(s * 0.18f, s * 0.52f);
                    ch.lineTo(s * 0.42f, s * 0.76f);
                    ch.lineTo(s * 0.84f, s * 0.24f);
                    c.drawPath(ch, p);
                    break;
            }
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }

    // ============================================================
    //  UI Helpers
    // ============================================================

    private LinearLayout card(String label) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(rounded(C_BTN, 12, C_BTN_STROKE, 1));

        TextView t = text(label, 11, C_MUTED, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        c.addView(t, lp);
        return c;
    }

    private TextView iconButton(String label, int iconType, int bgColor, boolean primary) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(primary ? Color.WHITE : C_TEXT);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setBackground(rounded(bgColor, 10, primary ? bgColor : C_BTN_STROKE, 1));
        Drawable ic = iconDrawable(iconType, primary ? Color.WHITE : C_TEXT);
        ic.setBounds(0, 0, dp(18), dp(18));
        t.setCompoundDrawables(ic, null, null, null);
        t.setCompoundDrawablePadding(dp(8));
        t.setClickable(true);
        return t;
    }

    private void addHeader(String title, boolean backable) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        if (backable) {
            TextView back = new TextView(this);
            back.setText("←");
            back.setTextColor(C_TEXT);
            back.setTextSize(18);
            back.setPadding(dp(6), dp(4), dp(12), dp(4));
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { screen = 0; buildScreen(); }
            });
            row.addView(back);
        }

        TextView t = text(title, 16, Color.WHITE, true);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.parseColor("#9A9A9A"));
        close.setTextSize(18);
        close.setPadding(dp(10), dp(4), dp(4), dp(4));
        attachCloseHandler(close);
        row.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        attachDragToView(row);

        panel.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int topDp, int bottomDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        p.bottomMargin = dp(bottomDp);
        return p;
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) { toast("Cannot open link"); }
    }

    private void toast(final String msg) {
        ui.post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show(); }
                catch (Throwable ignored) {}
            }
        });
    }
}
