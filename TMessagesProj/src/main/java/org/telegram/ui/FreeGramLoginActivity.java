package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.telegram.messenger.FreeGramApi;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The only custom account screen: FreeGram ID + one-time code from the auth bot. */
public final class FreeGramLoginActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText idInput;
    private EditText codeInput;
    private TextView status;
    private Button submit;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FreeGramApi.init(this);
        if (FreeGramApi.hasSession(this)) {
            openApp();
            return;
        }

        getWindow().setStatusBarColor(Color.rgb(15, 20, 29));
        getWindow().setNavigationBarColor(Color.rgb(15, 20, 29));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(36), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(15, 20, 29));

        TextView logo = new TextView(this);
        logo.setText("FREEGRAM");
        logo.setTextColor(Color.rgb(86, 166, 255));
        logo.setTextSize(30);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo, matchWrap());

        TextView title = new TextView(this);
        title.setText("Вход в FreeGram");
        title.setTextColor(Color.WHITE);
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = matchWrap();
        titleLp.topMargin = dp(28);
        root.addView(title, titleLp);

        TextView hint = new TextView(this);
        hint.setText("Открой бота FreeGram Auth в Telegram и получи FreeGram ID и код подтверждения.");
        hint.setTextColor(Color.rgb(174, 185, 201));
        hint.setTextSize(15);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = matchWrap();
        hintLp.topMargin = dp(12);
        root.addView(hint, hintLp);

        idInput = field("FreeGram ID из бота", InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams fieldLp = matchWrap();
        fieldLp.topMargin = dp(30);
        root.addView(idInput, fieldLp);

        codeInput = field("Код из 6 цифр", InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams codeLp = matchWrap();
        codeLp.topMargin = dp(12);
        root.addView(codeInput, codeLp);

        submit = new Button(this);
        submit.setText("Продолжить");
        submit.setTextColor(Color.WHITE);
        submit.setTextSize(16);
        submit.setAllCaps(false);
        submit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(42, 133, 225)));
        LinearLayout.LayoutParams buttonLp = matchWrap();
        buttonLp.topMargin = dp(22);
        root.addView(submit, buttonLp);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        progressLp.gravity = Gravity.CENTER_HORIZONTAL;
        progressLp.topMargin = dp(14);
        root.addView(progress, progressLp);

        status = new TextView(this);
        status.setTextColor(Color.rgb(255, 116, 116));
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = matchWrap();
        statusLp.topMargin = dp(12);
        root.addView(status, statusLp);

        TextView footer = new TextView(this);
        footer.setText("FreeGram account • защищённая API-сессия");
        footer.setTextColor(Color.rgb(111, 125, 145));
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = matchWrap();
        footerLp.topMargin = dp(32);
        root.addView(footer, footerLp);

        setContentView(root);
        submit.setOnClickListener(v -> submitCode());
    }

    private EditText field(String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(16);
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(Color.rgb(133, 147, 166));
        edit.setHint(hint);
        edit.setInputType(inputType);
        edit.setPadding(dp(16), dp(12), dp(16), dp(12));
        edit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(78, 96, 119)));
        return edit;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void submitCode() {
        String virtualId = idInput.getText().toString().trim();
        String code = codeInput.getText().toString().trim();
        if (virtualId.isEmpty() || code.length() != 6) {
            status.setText("Введи FreeGram ID и шестизначный код из бота.");
            return;
        }
        submit.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setTextColor(Color.rgb(174, 185, 201));
        status.setText("Проверяем код…");
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(codeInput.getWindowToken(), 0);

        executor.execute(() -> {
            String error = null;
            try {
                FreeGramApi.verifyCode(this, virtualId, code);
            } catch (Exception e) {
                error = e.getMessage() == null ? "Не удалось войти. Попробуй ещё раз." : e.getMessage();
            }
            final String result = error;
            main.post(() -> {
                progress.setVisibility(View.GONE);
                if (isFinishing()) return;
                if (result == null) {
                    status.setTextColor(Color.rgb(111, 220, 155));
                    status.setText("Вход выполнен.");
                    openApp();
                } else {
                    submit.setEnabled(true);
                    status.setTextColor(Color.rgb(255, 116, 116));
                    status.setText(result);
                }
            });
        });
    }

    private void openApp() {
        Intent intent = new Intent(this, LaunchActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
