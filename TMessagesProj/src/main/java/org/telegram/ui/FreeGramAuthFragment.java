package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FreeGramAuthFragment extends BaseFragment {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button retryButton;
    private volatile boolean creating;

    private int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    @Override
    public android.view.View createView(Context context) {
        actionBar.setAddToContainer(false);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(244, 247, 251));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(context, 24), dp(context, 36), dp(context, 24), dp(context, 36));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(context, 24), dp(context, 30), dp(context, 24), dp(context, 26));
        card.setBackground(rounded(context, Color.WHITE, 22));
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView logo = new TextView(context);
        logo.setText("FreeGram");
        logo.setTextColor(Color.rgb(35, 111, 220));
        logo.setTextSize(34);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        card.addView(logo, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(context);
        subtitle.setText("Мессенджер начинается здесь");
        subtitle.setTextColor(Color.rgb(45, 58, 75));
        subtitle.setTextSize(18);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(context, 10), 0, dp(context, 12));
        card.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        TextView explanation = new TextView(context);
        explanation.setText("Создадим один аккаунт FreeGram для этого устройства. Регистрация через бота и ввод кодов не нужны.");
        explanation.setTextColor(Color.rgb(105, 120, 140));
        explanation.setTextSize(14);
        explanation.setGravity(Gravity.CENTER);
        explanation.setPadding(0, 0, 0, dp(context, 22));
        card.addView(explanation, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(context);
        status.setText("Подключаемся к FreeGram…");
        status.setTextColor(Color.rgb(75, 91, 112));
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(context, 4), 0, dp(context, 18));
        card.addView(status, new LinearLayout.LayoutParams(-1, -2));

        retryButton = new Button(context);
        retryButton.setText("Повторить подключение");
        retryButton.setAllCaps(false);
        retryButton.setTextColor(Color.WHITE);
        retryButton.setTextSize(16);
        retryButton.setBackground(rounded(context, Color.rgb(35, 111, 220), 14));
        retryButton.setVisibility(android.view.View.GONE);
        card.addView(retryButton, new LinearLayout.LayoutParams(-1, dp(context, 50)));
        retryButton.setOnClickListener(v -> createOrOpenAccount(context));

        fragmentView = scroll;
        createOrOpenAccount(context);
        return fragmentView;
    }

    private void createOrOpenAccount(Context context) {
        if (creating || status == null) return;
        creating = true;
        retryButton.setEnabled(false);
        retryButton.setVisibility(android.view.View.GONE);
        status.setText("Создаём или восстанавливаем аккаунт устройства…");
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("device_id", FreeGramApi.deviceId());
                JSONObject result = FreeGramApi.request("POST", "/v1/auth/device", body, false);
                String token = result.optString("access_token", "");
                if (token.isEmpty()) throw new Exception("Сервер не выдал сессию.");
                FreeGramApi.saveToken(token);
                FreeGramApi.request("GET", "/v1/me", null, true);
                android.os.Handler main = new android.os.Handler(context.getMainLooper());
                main.post(() -> {
                    creating = false;
                    if (getParentActivity() != null) {
                        presentFragment(new FreeGramHomeFragment(), true);
                    }
                });
            } catch (Exception error) {
                android.os.Handler main = new android.os.Handler(context.getMainLooper());
                main.post(() -> {
                    creating = false;
                    if (status != null) {
                        String message = error.getMessage();
                        status.setText("Сервер FreeGram пока недоступен. " +
                                (message == null ? "Попробуй подключиться ещё раз." : message));
                        retryButton.setEnabled(true);
                        retryButton.setVisibility(android.view.View.VISIBLE);
                    }
                });
            }
        });
    }

    @Override
    public void onFragmentDestroy() {
        executor.shutdownNow();
        super.onFragmentDestroy();
    }
}
