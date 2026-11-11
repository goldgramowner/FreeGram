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
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FreeGramAuthFragment extends BaseFragment {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText username, password;
    private TextView status, switchMode;
    private boolean registerMode = true;

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
        subtitle.setText("Вход и регистрация без номера телефона");
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 10, 0, 28);
        root.addView(subtitle);

        username = new EditText(context);
        username.setSingleLine(true);
        username.setHint("Имя пользователя");
        username.setTextSize(16);
        root.addView(username, new LinearLayout.LayoutParams(-1, 54));

        password = new EditText(context);
        password.setSingleLine(true);
        password.setHint("Пароль (не менее 8 символов)");
        password.setInputType(129);
        password.setTextSize(16);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, 54);
        pp.topMargin = 12;
        root.addView(password, pp);

        Button submit = new Button(context);
        submit.setText("Создать аккаунт");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 54);
        bp.topMargin = 22;
        root.addView(submit, bp);

        switchMode = new TextView(context);
        switchMode.setText("Уже есть аккаунт? Войти");
        switchMode.setTextColor(Color.rgb(28, 100, 190));
        switchMode.setGravity(Gravity.CENTER);
        switchMode.setPadding(0, 20, 0, 16);
        root.addView(switchMode);

        status = new TextView(context);
        status.setTextColor(Color.DKGRAY);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        switchMode.setOnClickListener(v -> {
            registerMode = !registerMode;
            submit.setText(registerMode ? "Создать аккаунт" : "Войти");
            switchMode.setText(registerMode ? "Уже есть аккаунт? Войти" : "Нет аккаунта? Регистрация");
            status.setText("");
        });
        submit.setOnClickListener(v -> authenticate(context, submit));
        fragmentView = scroll;
        return fragmentView;
    }

    private void authenticate(Context context, Button submit) {
        String name = username.getText().toString().trim();
        String pass = password.getText().toString();
        if (name.length() < 5 || pass.length() < 8) {
            status.setText("Имя: 5–24 символа. Пароль: минимум 8 символов.");
            return;
        }
        submit.setEnabled(false);
        status.setText("Подключаемся к FreeGram…");
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("username", name);
                data.put("password", pass);
                JSONObject result = FreeGramApi.request("POST", registerMode ? "/v1/auth/register" : "/v1/auth/login", data, false);
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
                    status.setText(e.getMessage() == null ? "Не удалось подключиться" : e.getMessage());
                    submit.setEnabled(true);
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
