package org.telegram.ui;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FreeGramProfileFragment extends BaseFragment {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText username, displayName, bio, avatar;
    private TextView status, identity;
    private Button ownerPanelButton;
    private Button numbersButton;
    private boolean loading;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 20), dp(context, 24), dp(context, 20), dp(context, 28));
        root.setBackgroundColor(Color.rgb(245, 248, 253));

        TextView title = new TextView(context);
        title.setText("Профиль FreeGram");
        title.setTextColor(Color.rgb(25, 43, 68));
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        identity = new TextView(context);
        identity.setText("Загрузка профиля…");
        identity.setTextColor(Color.rgb(80, 98, 124));
        identity.setTextSize(14);
        identity.setPadding(0, dp(context, 10), 0, dp(context, 20));
        root.addView(identity);

        username = field(context, root, "Username (5–24 символа, латиница/цифры/_)");
        displayName = field(context, root, "Имя профиля");
        bio = field(context, root, "О себе");
        bio.setMinLines(3);
        bio.setGravity(Gravity.TOP | Gravity.START);
        avatar = field(context, root, "Ссылка на аватар (https://...)");

        Button save = new Button(context);
        save.setText("Сохранить изменения");
        root.addView(save, new LinearLayout.LayoutParams(-1, dp(context, 52)));
        save.setOnClickListener(v -> saveProfile());

        numbersButton = new Button(context);
        numbersButton.setText("Мои виртуальные номера");
        root.addView(numbersButton, new LinearLayout.LayoutParams(-1, dp(context, 50)));
        numbersButton.setOnClickListener(v -> showNumbers());

        Button chatsButton = new Button(context);
        chatsButton.setText("Мои чаты, группы и каналы");
        root.addView(chatsButton, new LinearLayout.LayoutParams(-1, dp(context, 50)));
        chatsButton.setOnClickListener(v -> presentFragment(new FreeGramChatsFragment(), true));

        Button giftsButton = new Button(context);
        giftsButton.setText("Подарки и NFT-маркет");
        root.addView(giftsButton, new LinearLayout.LayoutParams(-1, dp(context, 50)));
        giftsButton.setOnClickListener(v -> presentFragment(new FreeGramGift3DFragment(), true));

        Button settingsButton = new Button(context);
        settingsButton.setText("Настройки Telegram");
        root.addView(settingsButton, new LinearLayout.LayoutParams(-1, dp(context, 50)));
        settingsButton.setOnClickListener(v -> presentFragment(new SettingsActivity(), true));

        ownerPanelButton = new Button(context);
        ownerPanelButton.setText("Панель владельца");
        ownerPanelButton.setVisibility(View.GONE);
        root.addView(ownerPanelButton, new LinearLayout.LayoutParams(-1, dp(context, 52)));
        ownerPanelButton.setOnClickListener(v -> presentFragment(new FreeGramOwnerFragment()));

        status = new TextView(context);
        status.setTextColor(Color.rgb(80, 98, 124));
        status.setPadding(0, dp(context, 12), 0, 0);
        root.addView(status);
        scroll.addView(root);
        fragmentView = scroll;
        loadProfile();
        checkOwnerAccess();
        return fragmentView;
    }

    private EditText field(Context c, LinearLayout root, String hint) {
        EditText input = new EditText(c);
        input.setSingleLine(false);
        input.setTextSize(16);
        input.setHint(hint);
        input.setTextColor(Color.rgb(25, 43, 68));
        input.setHintTextColor(Color.rgb(130, 145, 165));
        input.setPadding(dp(c, 13), dp(c, 10), dp(c, 13), dp(c, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, 54));
        lp.bottomMargin = dp(c, 9);
        root.addView(input, lp);
        return input;
    }

    private static int dp(Context c, float value) {
        return (int) (value * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showNumbers() {
        executor.execute(() -> {
            String[] labels;
            try {
                JSONObject result = FreeGramApi.request("GET", "/v1/me/numbers", null, true);
                org.json.JSONArray items = result.optJSONArray("numbers");
                int count = items == null ? 0 : items.length();
                labels = new String[count];
                for (int i = 0; i < count; i++) {
                    JSONObject item = items.optJSONObject(i);
                    labels[i] = item == null ? "Номер" : item.optString("number", "Номер");
                }
            } catch (Exception e) {
                final String error = e.getMessage() == null ? "Не удалось загрузить номера" : e.getMessage();
                main.post(() -> {
                    if (getParentActivity() != null) new android.app.AlertDialog.Builder(getParentActivity())
                        .setMessage(error).setPositiveButton("OK", null).show();
                });
                return;
            }
            final String[] numbers = labels;
            main.post(() -> {
                if (getParentActivity() == null) return;
                if (numbers.length == 0) {
                    new android.app.AlertDialog.Builder(getParentActivity()).setTitle("Мои номера")
                        .setMessage("Виртуальные номера пока не назначены.")
                        .setPositiveButton("OK", null).show();
                    return;
                }
                new android.app.AlertDialog.Builder(getParentActivity()).setTitle("Мои виртуальные номера")
                    .setItems(numbers, (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager) getParentActivity()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("FreeGram number", numbers[which]));
                        Toast.makeText(getParentActivity(), "Номер скопирован", Toast.LENGTH_SHORT).show();
                    }).setPositiveButton("Закрыть", null).show();
            });
        });
    }

    private void checkOwnerAccess() {
        executor.execute(() -> {
            boolean allowed = false;
            try {
                if (FreeGramApi.ensureDeviceSession()) {
                    JSONObject result = FreeGramApi.request("GET", "/v1/owner/me", null, true);
                    allowed = result.optBoolean("is_owner", false);
                }
            } catch (Exception ignored) { }
            final boolean show = allowed;
            main.post(() -> {
                if (ownerPanelButton != null) ownerPanelButton.setVisibility(show ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void loadProfile() {
        if (loading) return;
        loading = true;
        executor.execute(() -> {
            JSONObject result = null;
            String error = null;
            try {
                if (!FreeGramApi.ensureDeviceSession()) throw new Exception("Нет соединения с FreeGram");
                result = FreeGramApi.request("GET", "/v1/me", null, true);
            } catch (Exception e) {
                error = e.getMessage() == null ? "Не удалось загрузить профиль" : e.getMessage();
            }
            final JSONObject data = result;
            final String failure = error;
            main.post(() -> {
                loading = false;
                if (username == null || status == null) return;
                if (failure != null) { status.setText(failure); return; }
                username.setText(data.optString("username", ""));
                displayName.setText(data.optString("display_name", ""));
                bio.setText(data.optString("bio", ""));
                avatar.setText(data.optString("avatar_url", ""));
                identity.setText((data.optInt("premium", 0) == 1 ? "FreeGram Premium ✓\n" : "") + "FreeGram ID: " + data.optString("virtual_id", "—")
                    + "\nНомер: " + data.optString("virtual_number", "не назначен")
                    + "\nNFT username: " + (data.optString("nft_username", "").isEmpty() ? "нет" : "@" + data.optString("nft_username"))
                    + "\nСтатус: " + (data.optInt("verified") == 1 ? "✓ подтверждён" : "не подтверждён"));
                status.setText("Изменяй данные и нажми «Сохранить изменения».");
            });
        });
    }

    private void saveProfile() {
        if (loading) return;
        String name = username.getText().toString().trim();
        String shown = displayName.getText().toString().trim();
        String about = bio.getText().toString().trim();
        String photo = avatar.getText().toString().trim();
        if (!photo.isEmpty() && !photo.startsWith("https://") && !photo.startsWith("http://")) {
            status.setText("Ссылка на аватар должна начинаться с http:// или https://");
            return;
        }
        loading = true;
        status.setText("Сохраняем…");
        executor.execute(() -> {
            String error = null;
            try {
                JSONObject body = new JSONObject();
                body.put("username", name);
                body.put("display_name", shown);
                body.put("bio", about);
                body.put("avatar_url", photo);
                FreeGramApi.request("PATCH", "/v1/me", body, true);
            } catch (Exception e) {
                error = e.getMessage() == null ? "Не удалось сохранить профиль" : e.getMessage();
            }
            final String failure = error;
            main.post(() -> {
                loading = false;
                if (status == null) return;
                if (failure == null) {
                    status.setText("Профиль обновлён.");
                    Toast.makeText(getParentActivity(), "Изменения сохранены", Toast.LENGTH_SHORT).show();
                    loadProfile();
                } else status.setText("Ошибка: " + failure);
            });
        });
    }

    @Override
    public void onFragmentDestroy() {
        executor.shutdownNow();
        username = null;
        displayName = null;
        bio = null;
        avatar = null;
        status = null;
        identity = null;
        ownerPanelButton = null;
        numbersButton = null;
        super.onFragmentDestroy();
    }
}
