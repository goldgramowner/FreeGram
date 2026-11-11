package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FreeGramAuthFragment extends BaseFragment {
    private static final String API_LOGIN = "/v1/auth/verify";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText virtualId, code;
    private TextView status;
    private Button loginButton;

    private int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable shape(int color, int radius, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        return drawable;
    }

    private TextView label(Context context, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(Color.rgb(174, 190, 211));
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(10, 15, 24));

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(context, 22), dp(context, 28), dp(context, 22), dp(context, 28));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 24), dp(context, 28), dp(context, 24), dp(context, 24));
        card.setBackground(shape(Color.rgb(21, 30, 45), 22, context));
        page.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView logo = new TextView(context);
        logo.setText("FreeGram");
        logo.setTextColor(Color.rgb(83, 166, 255));
        logo.setTextSize(32);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        card.addView(logo, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(context);
        subtitle.setText("Вход в аккаунт");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(19);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(context, 8), 0, dp(context, 8));
        card.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(context);
        hint.setText("Открой @FreeGramNelpBot и отправь /register. Введи выданные ID и код.");
        hint.setTextColor(Color.rgb(151, 167, 188));
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(context, 24));
        card.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        card.addView(label(context, "FREEGRAM ID"));
        virtualId = new EditText(context);
        virtualId.setSingleLine(true);
        virtualId.setHint("FG-XXXXXXXXXX");
        virtualId.setTextColor(Color.WHITE);
        virtualId.setHintTextColor(Color.rgb(112, 130, 153));
        virtualId.setTextSize(16);
        virtualId.setInputType( android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        virtualId.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        virtualId.setBackground(shape(Color.rgb(12, 20, 33), 12, context));
        LinearLayout.LayoutParams idParams = new LinearLayout.LayoutParams(-1, dp(context, 54));
        idParams.topMargin = dp(context, 8);
        idParams.bottomMargin = dp(context, 18);
        card.addView(virtualId, idParams);

        card.addView(label(context, "ОДНОРАЗОВЫЙ КОД"));
        code = new EditText(context);
        code.setSingleLine(true);
        code.setHint("123456");
        code.setTextColor(Color.WHITE);
        code.setHintTextColor(Color.rgb(112, 130, 153));
        code.setTextSize(18);
        code.setLetterSpacing(0.18f);
        code.setGravity(Gravity.CENTER_VERTICAL);
        code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        code.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        code.setBackground(shape(Color.rgb(12, 20, 33), 12, context));
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(-1, dp(context, 54));
        codeParams.topMargin = dp(context, 8);
        codeParams.bottomMargin = dp(context, 20);
        card.addView(code, codeParams);

        loginButton = new Button(context);
        loginButton.setText("ВОЙТИ");
        loginButton.setTextColor(Color.WHITE);
        loginButton.setTextSize(15);
        loginButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        loginButton.setAllCaps(false);
        loginButton.setBackground(shape(Color.rgb(43, 137, 232), 13, context));
        card.addView(loginButton, new LinearLayout.LayoutParams(-1, dp(context, 52)));

        status = new TextView(context);
        status.setTextColor(Color.rgb(174, 190, 211));
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(context, 16), 0, 0);
        card.addView(status, new LinearLayout.LayoutParams(-1, -2));

        loginButton.setOnClickListener(v -> verifyCode(context));
        fragmentView = scroll;
        return fragmentView;
    }

    private void verifyCode(Context context) {
        String id = virtualId.getText().toString().trim().toUpperCase(java.util.Locale.US);
        String value = code.getText().toString().trim();
        if (!id.matches("FG-[A-Z0-9]{6,32}") || !value.matches("[0-9]{6}")) {
            status.setText("Проверь FreeGram ID и шестизначный код.");
            return;
        }

        loginButton.setEnabled(false);
        status.setText("Проверяем данные…");
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("virtual_id", id);
                body.put("code", value);
                JSONObject result = FreeGramApi.request("POST", API_LOGIN, body, false);
                String token = result.optString("access_token", "");
                if (token.isEmpty()) throw new Exception("Сервер не вернул токен авторизации.");
                FreeGramApi.saveToken(token);
                FreeGramApi.request("GET", "/v1/me", null, true);
                android.os.Handler main = new android.os.Handler(context.getMainLooper());
                main.post(() -> {
                    if (getParentActivity() == null) return;
                    presentFragment(new FreeGramHomeFragment(), true);
                });
            } catch (Exception e) {
                android.os.Handler main = new android.os.Handler(context.getMainLooper());
                main.post(() -> {
                    status.setText(e.getMessage() == null ? "Не удалось войти. Проверь ID и код." : e.getMessage());
                    loginButton.setEnabled(true);
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
