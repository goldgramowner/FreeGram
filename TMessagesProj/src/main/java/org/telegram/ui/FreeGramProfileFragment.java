package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FreeGramProfileFragment extends BaseFragment {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private EditText username, displayName, bio;
    private TextView details, status;
    private Button save;

    private int dp(Context c, int n) { return AndroidUtilities.dp(n); }

    private TextView label(Context c, String value, int size, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setTextSize(15);
        e.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        e.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bg.setCornerRadius(dp(c, 14));
        bg.setStroke(dp(c, 1), Theme.getColor(Theme.key_divider));
        e.setBackground(bg);
        e.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        return e;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(context, 18), dp(context, 22), dp(context, 18), dp(context, 30));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        page.addView(label(context, "Профиль FreeGram", 25, true));
        details = label(context, "Загружаем аккаунт…", 14, false);
        details.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        LinearLayout.LayoutParams d = new LinearLayout.LayoutParams(-1, -2);
        d.topMargin = dp(context, 10);
        page.addView(details, d);

        username = field(context, "username (латиница, 5–24)");
        displayName = field(context, "Имя профиля");
        bio = field(context, "О себе");
        addField(page, context, "Имя пользователя", username);
        addField(page, context, "Отображаемое имя", displayName);
        addField(page, context, "О себе", bio);

        save = new Button(context);
        save.setText("Сохранить профиль");
        save.setAllCaps(false);
        save.setTextColor(0xFFFFFFFF);
        save.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF3390EC));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(context, 50));
        bp.topMargin = dp(context, 18);
        page.addView(save, bp);
        status = label(context, "", 13, false);
        LinearLayout.LayoutParams st = new LinearLayout.LayoutParams(-1, -2);
        st.topMargin = dp(context, 10);
        page.addView(status, st);
        save.setOnClickListener(v -> saveProfile());
        loadProfile();
        fragmentView = scroll;
        return fragmentView;
    }

    private void addField(LinearLayout page, Context c, String title, EditText field) {
        TextView t = label(c, title, 13, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(c, 16);
        page.addView(t, tp);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(c, 52));
        fp.topMargin = dp(c, 6);
        page.addView(field, fp);
    }

    private void loadProfile() {
        executor.execute(() -> {
            try {
                JSONObject me = FreeGramApi.request("GET", "/v1/me", null, true);
                main.post(() -> {
                    if (username == null) return;
                    username.setText(me.optString("username", ""));
                    displayName.setText(me.optString("display_name", ""));
                    bio.setText(me.optString("bio", ""));
                    String verified = me.optBoolean("verified") ? "✓ Подтверждён FreeGram" : "Не подтверждён";
                    String nft = me.optString("nft_username", "");
                    String label = me.optString("label", "");
                    details.setText("ID: " + me.optString("virtual_id") + "\nНомер: "
                            + me.optString("virtual_number") + "\nStars: " + me.optLong("stars")
                            + "\n" + verified + (label.isEmpty() ? "" : "\nМетка: " + label)
                            + (nft.isEmpty() ? "" : "\nNFT username: @" + nft));
                });
            } catch (Exception e) {
                main.post(() -> { if (status != null) status.setText("Не удалось загрузить профиль: " + e.getMessage()); });
            }
        });
    }

    private void saveProfile() {
        final String name = username.getText().toString().trim();
        final String display = displayName.getText().toString().trim();
        final String about = bio.getText().toString().trim();
        if (save != null) save.setEnabled(false);
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("username", name);
                body.put("display_name", display);
                body.put("bio", about);
                FreeGramApi.request("PUT", "/v1/me/profile", body, true);
                main.post(() -> {
                    if (status != null) status.setText("Профиль сохранён.");
                    if (save != null) save.setEnabled(true);
                    loadProfile();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (status != null) status.setText("Не удалось сохранить: " + e.getMessage());
                    if (save != null) save.setEnabled(true);
                });
            }
        });
    }

    @Override
    public void onFragmentDestroy() {
        username = null;
        displayName = null;
        bio = null;
        details = null;
        status = null;
        save = null;
        executor.shutdownNow();
        super.onFragmentDestroy();
    }
}
