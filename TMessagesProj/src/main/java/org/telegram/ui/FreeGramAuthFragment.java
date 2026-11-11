package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
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
    private EditText virtualNumber, code;
    private TextView status;
    private Button requestButton, verifyButton;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(28, 48, 28, 32);
        root.setBackgroundColor(Color.rgb(246, 248, 251));
        scroll.addView(root);

        TextView title = new TextView(context);
        title.setText("FreeGram");
        title.setTextColor(Color.rgb(28, 100, 190));
        title.setTextSize(34);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setText("Вход по виртуальному номеру");
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 10, 0, 28);
        root.addView(subtitle);

        virtualNumber = new EditText(context);
        virtualNumber.setSingleLine(true);
        virtualNumber.setHint("+7XXXXXXXXXX");
        virtualNumber.setInputType(3);
        virtualNumber.setTextSize(18);
        root.addView(virtualNumber, new LinearLayout.LayoutParams(-1, 54));

        requestButton = new Button(context);
        requestButton.setText("Получить код в Telegram-боте");
        LinearLayout.LayoutParams requestParams = new LinearLayout.LayoutParams(-1, 54);
        requestParams.topMargin = 16;
        root.addView(requestButton, requestParams);

        code = new EditText(context);
        code.setSingleLine(true);
        code.setHint("Код из FreeGram Bot");
        code.setInputType(2);
        code.setTextSize(18);
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(-1, 54);
        codeParams.topMargin = 18;
        root.addView(code, codeParams);

        verifyButton = new Button(context);
        verifyButton.setText("Войти");
        LinearLayout.LayoutParams verifyParams = new LinearLayout.LayoutParams(-1, 54);
        verifyParams.topMargin = 12;
        root.addView(verifyButton, verifyParams);

        status = new TextView(context);
        status.setTextColor(Color.DKGRAY);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 18, 0, 0);
        root.addView(status);

        requestButton.setOnClickListener(v -> requestCode());
        verifyButton.setOnClickListener(v -> verifyCode(context));
        fragmentView = scroll;
        return fragmentView;
    }

    private String normalizedNumber() {
        return virtualNumber.getText().toString().replace(" ", "").replace("-", "").trim();
    }

    private void requestCode() {
        String number = normalizedNumber();
        if (!number.matches("\\+7[0-9]{10}")) {
            status.setText("Введите номер в формате +7 и ещё 10 цифр.");
            return;
        }
        requestButton.setEnabled(false);
        status.setText("Запрашиваем код…");
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("virtual_number", number);
                JSONObject result = FreeGramApi.request("POST", "/v1/auth/request-code", body, false);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    status.setText(result.optString("message",
                        "Если номер активен, код придёт в FreeGram Bot."));
                    requestButton.setEnabled(true);
                });
            } catch (Exception e) {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    status.setText(e.getMessage() == null ? "Не удалось запросить код." : e.getMessage());
                    requestButton.setEnabled(true);
                });
            }
        });
    }

    private void verifyCode(Context context) {
        String number = normalizedNumber();
        String value = code.getText().toString().trim();
        if (!number.matches("\\+7[0-9]{10}") || !value.matches("[0-9]{6}")) {
            status.setText("Проверьте номер и шестизначный код.");
            return;
        }
        verifyButton.setEnabled(false);
        status.setText("Проверяем код…");
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("virtual_number", number);
                body.put("code", value);
                JSONObject result = FreeGramApi.request("POST", "/v1/auth/verify", body, false);
                String token = result.optString("access_token", "");
                if (token.isEmpty()) throw new Exception("Сервер не вернул токен авторизации");
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
                    status.setText(e.getMessage() == null ? "Не удалось войти." : e.getMessage());
                    verifyButton.setEnabled(true);
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
