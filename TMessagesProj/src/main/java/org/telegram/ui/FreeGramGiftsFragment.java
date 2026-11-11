package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FreeGramGiftsFragment extends BaseFragment {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private LinearLayout giftsList;
    private EditText recipient;
    private TextView status;

    private int dp(Context c, int value) {
        return AndroidUtilities.dp(value);
    }

    private GradientDrawable cardBackground(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        d.setCornerRadius(dp(c, 18));
        d.setStroke(dp(c, 1), Theme.getColor(Theme.key_divider));
        return d;
    }

    private TextView text(Context c, String value, int size, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(context, 16), dp(context, 18), dp(context, 16), dp(context, 28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        TextView title = text(context, "Подарки FreeGram", 25, true);
        page.addView(title);
        TextView subtitle = text(context, "Покупай подарки за FreeGram Stars и отправляй их другим пользователям.", 14, false);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        LinearLayout.LayoutParams sub = new LinearLayout.LayoutParams(-1, -2);
        sub.topMargin = dp(context, 6);
        page.addView(subtitle, sub);

        recipient = new EditText(context);
        recipient.setSingleLine(true);
        recipient.setHint("Получатель: username (необязательно)");
        recipient.setTextSize(15);
        recipient.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        recipient.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        recipient.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        recipient.setBackground(cardBackground(context));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(context, 52));
        rp.topMargin = dp(context, 18);
        page.addView(recipient, rp);

        status = text(context, "Загружаем подарки…", 13, false);
        status.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(context, 12);
        page.addView(status, sp);

        giftsList = new LinearLayout(context);
        giftsList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = dp(context, 8);
        page.addView(giftsList, gl);

        TextView ownedTitle = text(context, "Мои подарки", 20, true);
        LinearLayout.LayoutParams ot = new LinearLayout.LayoutParams(-1, -2);
        ot.topMargin = dp(context, 24);
        page.addView(ownedTitle, ot);
        loadGifts();
        fragmentView = scroll;
        return fragmentView;
    }

    private void loadGifts() {
        executor.execute(() -> {
            try {
                JSONObject catalog = FreeGramApi.request("GET", "/v1/gifts", null, true);
                JSONObject owned = FreeGramApi.request("GET", "/v1/me/gifts", null, true);
                main.post(() -> {
                    if (giftsList == null) return;
                    giftsList.removeAllViews();
                    JSONArray gifts = catalog.optJSONArray("gifts");
                    if (gifts != null) {
                        for (int i = 0; i < gifts.length(); i++) {
                            JSONObject gift = gifts.optJSONObject(i);
                            if (gift != null) addGiftCard(gift);
                        }
                    }
                    JSONArray mine = owned.optJSONArray("gifts");
                    if (mine != null && mine.length() > 0) {
                        for (int i = 0; i < mine.length(); i++) {
                            JSONObject gift = mine.optJSONObject(i);
                            if (gift != null) {
                                String line = gift.optString("emoji", "🎁") + "  " + gift.optString("title", "Подарок")
                                        + "  ·  от @" + gift.optString("sender_username", "FreeGram");
                                TextView item = text(getParentActivity(), line, 14, false);
                                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                                p.topMargin = dp(getParentActivity(), 8);
                                giftsList.addView(item, p);
                            }
                        }
                    }
                    status.setText("Выбери подарок. Покупка списывает Stars с твоего баланса.");
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (status != null) status.setText("Не удалось загрузить подарки: " + e.getMessage());
                });
            }
        });
    }

    private void addGiftCard(JSONObject gift) {
        Context c = getParentActivity();
        if (c == null) return;
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 14), dp(c, 12), dp(c, 12), dp(c, 12));
        row.setBackground(cardBackground(c));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(c, 8);
        giftsList.addView(row, p);

        TextView emoji = text(c, gift.optString("emoji", "🎁"), 30, false);
        row.addView(emoji, new LinearLayout.LayoutParams(dp(c, 48), -2));
        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        info.addView(text(c, gift.optString("title", "Подарок"), 16, true));
        info.addView(text(c, gift.optString("rarity", "common") + " · " + gift.optLong("price") + " Stars", 13, false));

        Button buy = new Button(c);
        buy.setText("Подарить");
        buy.setAllCaps(false);
        buy.setTextColor(Color.WHITE);
        buy.setBackgroundColor(0xFF3390EC);
        row.addView(buy, new LinearLayout.LayoutParams(-2, dp(c, 42)));
        String giftId = gift.optString("gift_id");
        buy.setOnClickListener(v -> buyGift(giftId, buy));
    }

    private void buyGift(String giftId, Button button) {
        String to = recipient == null ? "" : recipient.getText().toString().trim().replace("@", "");
        button.setEnabled(false);
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("gift_id", giftId);
                if (!to.isEmpty()) body.put("to_username", to);
                JSONObject result = FreeGramApi.request("POST", "/v1/gifts/buy", body, true);
                main.post(() -> {
                    if (status != null) status.setText("Подарок отправлен! Остаток: " + result.optLong("remaining_stars") + " Stars.");
                    button.setEnabled(true);
                    loadGifts();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (status != null) status.setText("Покупка не выполнена: " + e.getMessage());
                    button.setEnabled(true);
                });
            }
        });
    }

    @Override
    public void onFragmentDestroy() {
        giftsList = null;
        recipient = null;
        status = null;
        executor.shutdownNow();
        super.onFragmentDestroy();
    }
}
