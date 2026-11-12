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
import org.json.JSONArray;
import org.telegram.ui.ActionBar.BaseFragment;

public class FreeGramStarsFragment extends BaseFragment {
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private TextView balance;
    private TextView info;
    private LinearLayout historyRows;

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

        TextView historyTitle = new TextView(context);
        historyTitle.setText("История операций");
        historyTitle.setTextColor(Color.rgb(30, 48, 75));
        historyTitle.setTextSize(18);
        historyTitle.setPadding(2, 22, 2, 8);
        root.addView(historyTitle);
        ScrollView historyScroll = new ScrollView(context);
        historyRows = new LinearLayout(context);
        historyRows.setOrientation(LinearLayout.VERTICAL);
        historyScroll.addView(historyRows);
        root.addView(historyScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView note = new TextView(context);
        note.setText("Stars можно использовать для подарков и коллекционных предметов FreeGram. Пополнение реальными деньгами пока не подключено.");
        note.setTextColor(Color.rgb(100, 116, 139));
        note.setTextSize(12);
        note.setPadding(4, 12, 4, 0);
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
                JSONObject history = FreeGramApi.request("GET", "/v1/stars/history", null, true);
                stars = history.optLong("balance", 0);
                JSONArray transactions = history.optJSONArray("transactions");
                final JSONArray historyData = transactions == null ? new JSONArray() : transactions;
                new Handler(Looper.getMainLooper()).post(() -> renderHistory(historyData));
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

    private void renderHistory(JSONArray transactions) {
        if (historyRows == null || getParentActivity() == null) return;
        historyRows.removeAllViews();
        if (transactions.length() == 0) {
            TextView empty = new TextView(getParentActivity());
            empty.setText("Операций пока нет");
            empty.setTextColor(Color.rgb(120, 132, 150));
            empty.setPadding(4, 14, 4, 14);
            historyRows.addView(empty);
            return;
        }
        for (int i = 0; i < transactions.length(); i++) {
            JSONObject item = transactions.optJSONObject(i);
            if (item == null) continue;
            long delta = item.optLong("delta");
            String reason = item.optString("reason", "Операция");
            String peer = item.optString("counterparty_username", "");
            String date = item.optString("created_at", "");
            if (date.length() > 16) date = date.substring(0, 16).replace('T', ' ');
            TextView row = new TextView(getParentActivity());
            row.setText((delta > 0 ? "+" : "") + delta + " ★    " + reason
                + (peer.isEmpty() ? "" : " · @" + peer) + (date.isEmpty() ? "" : "\n" + date));
            row.setTextColor(delta >= 0 ? Color.rgb(24, 135, 85) : Color.rgb(60, 75, 95));
            row.setTextSize(14);
            row.setPadding(10, 12, 10, 12);
            historyRows.addView(row);
            View divider = new View(getParentActivity());
            divider.setBackgroundColor(Color.rgb(225, 230, 238));
            historyRows.addView(divider, new LinearLayout.LayoutParams(-1, 1));
        }
    }

    @Override
    public void onFragmentDestroy() {
        executor.shutdownNow();
        balance = null;
        info = null;
        historyRows = null;
        super.onFragmentDestroy();
    }
}