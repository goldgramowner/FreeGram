package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
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
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText idField;
    private EditText codeField;
    private TextView status;
    private Button loginButton;

    private int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    private EditText makeField(Context context, String hint, int inputType) {
        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setTextSize(16);
        field.setTextColor(Color.rgb(25, 39, 57));
        field.setHintTextColor(Color.rgb(130, 145, 164));
        field.setInputType(inputType);
        field.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        field.setBackground(rounded(context, Color.WHITE, 12));
        return field;
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
        card.setPadding(dp(context, 22), dp(context, 26), dp(context, 22), dp(context, 22));
        card.setBackground(rounded(context, Color.WHITE, 20));
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText("FreeGram");
        title.setTextColor(Color.rgb(35, 111, 220));
        title.setTextSize(32);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(context);
        subtitle.setText("Вход по FreeGram ID");
        subtitle.setTextColor(Color.rgb(45, 58, 75));
        subtitle.setTextSize(18);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(context, 8), 0, dp(context, 10));
        card.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        TextView instructions = new TextView(context);
        instructions.setText("Открой @FreeGramNelpBot и отправь /register. Для повторного кода используй /login FG-XXXXXXXXXX.");
        instructions.setTextColor(Color.rgb(105, 120, 140));
        instructions.setTextSize(13);
        instructions.setGravity(Gravity.CENTER);
        instructions.setPadding(0, 0, 0, dp(context, 22));
        card.addView(instructions, new LinearLayout.LayoutParams(-1, -2));

        TextView idLabel = new TextView(context);
        idLabel.setText("FREEGRAM ID");
        idLabel.setTextColor(Color.rgb(75, 91, 112));
        idLabel.setTextSize(12);
        idLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(idLabel);

        idField = makeField(context, "FG-XXXXXXXXXX",
                android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(-1, dp(context, 52));
        fieldParams.topMargin = dp(context, 7);
        fieldParams.bottomMargin = dp(context, 16);
        card.addView(idField, fieldParams);

        TextView codeLabel = new TextView(context);
        codeLabel.setText("ОДНОРАЗОВЫЙ КОД");
        codeLabel.setTextColor(Color.rgb(75, 91, 112));
        codeLabel.setTextSize(12);
        codeLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(codeLabel);

        codeField = makeField(context, "123456",
                android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        fieldParams = new LinearLayout.LayoutParams(-1, dp(context, 52));
        fieldParams.topMargin = dp(context, 7);
        fieldParams.bottomMargin = dp(context, 18);
        card.addView(codeField, fieldParams);

        loginButton = new Button(context);
        loginButton.setText("Войти");
        loginButton.setAllCaps(false);
        loginButton.setTextColor(Color.WHITE);
        loginButton.setTextSize(16);
        loginButton.setBackground(rounded(context, Color.rgb(35, 111, 220), 12));
        card.addView(loginButton, new LinearLayout.LayoutParams(-1, dp(context, 50)));

        status = new TextView(context);
        status.setTextColor(Color.rgb(92, 108, 128));
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(context, 14), 0, 0);
        card.addView(status, new LinearLayout.LayoutParams(-1, -2));

        loginButton.setOnClickListener(v -> verifyCode(context));
        fragmentView = scroll;
        return fragmentView;
    }

    private void verifyCode(Context context) {
        String virtualId = idField.getText().toString().trim().toUpperCase();
        String code = codeField.getText().toString().trim();
        if (!virtualId.matches("FG-[A-F0-9]{10}") || !code.matches("[0-9]{6}")) {
            status.setText("Введи FreeGram ID и шестизначный код из бота.");
            return;
        }
        loginButton.setEnabled(false);
        status.setText("Проверяем ID и код…");
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("virtual_id", virtualId);
                body.put("code", code);
                JSONObject result = FreeGramApi.request("POST", "/v1/auth/verify", body, false);
                String token = result.optString("access_token", "");
                if (token.isEmpty()) {
                    throw new Exception("Сервер не вернул токен авторизации.");
                }
                FreeGramApi.saveToken(token);
                FreeGramApi.request("GET", "/v1/me", null, true);
                android.os.Handler main = new android.os.Handler(context.getMainLooper());
                main.post(() -> {
                    if (getParentActivity() != null) {
                        presentFragment(new FreeGramHomeFragment(), true);
                    }
                });
            } catch (Exception error) {
                android.os.Handler main = new android.os.Handler(context.getMainLooper());
                main.post(() -> {
                    status.setText(error.getMessage() == null
                            ? "Не удалось войти. Проверь ID и код."
                            : error.getMessage());
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
