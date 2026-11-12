package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;

public class FreeGramStarsFragment extends BaseFragment {
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private TextView balance;
    private TextView info;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(22, 28, 22, 20);
        root.setBackgroundColor(Color.rgb(246, 248, 252));

        TextView title = new TextView(context);
        title.setText("FreeGram Stars");
        title.setTextColor(Color.rgb(30, 48, 75));
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(context);
        subtitle.setText("Баланс вашего аккаунта");
        subtitle.setTextColor(Color.rgb(100, 116, 139));
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 8, 0, 22);
        root.addView(subtitle);

        balance = new TextView(context);
        balance.setText("★  —");
        balance.setTextColor(Color.rgb(211, 151, 25));
        balance.setTextSize(36);
        balance.setGravity(Gravity.CENTER);
        root.addView(balance, new LinearLayout.LayoutParams(-1, -2));

        info = new TextView(context);
        info.setText("Загружаем баланс…");
        info.setTextColor(Color.rgb(100, 116, 139));
        info.setTextSize(14);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, 14, 0, 20);
        root.addView(info);

        Button refresh = new Button(context);
        refresh.setText("Обновить баланс");
        root.addView(refresh, new LinearLayout.LayoutParams(-1, -2));
        refresh.setOnClickListener(v -> loadBalance());

        TextView note = new TextView(context);
        note.setText("Stars используются для покупки подарков и предметов на маркете FreeGram. Пополнение и история операций появятся после подключения соответствующих платёжных функций.");
        note.setTextColor(Color.rgb(100, 116, 139));
        note.setTextSize(13);
        note.setPadding(4, 24, 4, 0);
        root.addView(note);

        fragmentView = root;
        loadBalance();
        return fragmentView;
    }

    private void loadBalance() {
        executor.execute(() -> {
            long stars = -1;
            String error = null;
            try {
                if (!FreeGramApi.ensureDeviceSession()) throw new Exception("Нет соединения с FreeGram");
                JSONObject me = FreeGramApi.request("GET", "/v1/me", null, true);
                stars = me.optLong("stars", 0);
            } catch (Exception e) {
                error = e.getMessage() == null ? "Не удалось загрузить баланс" : e.getMessage();
            }
            final long value = stars;
            final String failure = error;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (balance == null || info == null) return;
                if (failure == null) {
                    balance.setText("★  " + value);
                    info.setText("Баланс FreeGram Stars");
                } else {
                    balance.setText("★  —");
                    info.setText(failure);
                }
            });
        });
    }

    @Override
    public void onFragmentDestroy() {
        executor.shutdownNow();
        balance = null;
        info = null;
        super.onFragmentDestroy();
    }
}