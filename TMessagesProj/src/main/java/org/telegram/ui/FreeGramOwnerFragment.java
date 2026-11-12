package org.telegram.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FreeGramOwnerFragment extends BaseFragment {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout rows;
    private TextView status;

    public FreeGramOwnerFragment() {}

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 247, 251));
        TextView title = new TextView(context);
        title.setText("FreeGram · Панель владельца");
        title.setTextSize(21);
        title.setTextColor(Color.rgb(25, 85, 160));
        title.setPadding(18, 18, 18, 10);
        root.addView(title);
        status = new TextView(context);
        status.setText("Загружаем данные…");
        status.setTextColor(Color.DKGRAY);
        status.setPadding(18, 8, 18, 8);
        root.addView(status);
        LinearLayout actions = new LinearLayout(context);
        Button refresh = new Button(context);
        refresh.setText("Обновить");
        actions.addView(refresh, new LinearLayout.LayoutParams(0, 48, 1));
        Button users = new Button(context);
        users.setText("Пользователи");
        actions.addView(users, new LinearLayout.LayoutParams(0, 48, 1));
        Button chats = new Button(context);
        chats.setText("Каналы/группы");
        actions.addView(chats, new LinearLayout.LayoutParams(0, 48, 1));
        root.addView(actions);
        ScrollView scroll = new ScrollView(context);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        refresh.setOnClickListener(v -> loadStatus());
        users.setOnClickListener(v -> loadUsers());
        chats.setOnClickListener(v -> loadChats());
        fragmentView = root;
        loadStatus();
        loadUsers();
        return fragmentView;
    }

    private void loadStatus() {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET", "/v1/owner/status", null, true);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (status != null) status.setText("Всего аккаунтов: " + result.optInt("total_users")
                        + " · подтверждено: " + result.optInt("verified_users")
                        + " · заблокировано: " + result.optInt("banned_users"));
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private void loadUsers() {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET", "/v1/owner/users", null, true);
                JSONArray users = result.optJSONArray("users");
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (rows == null) return;
                    rows.removeAllViews();
                    int count = users == null ? 0 : users.length();
                    if (status != null) status.setText("Пользователей: " + count + " · нажми на аккаунт для управления");
                    for (int i = 0; i < count; i++) {
                        JSONObject user = users.optJSONObject(i);
                        if (user == null) continue;
                        String id = user.optString("virtual_id", "");
                        String number = user.optString("virtual_number", "");
                        String nft = user.optString("nft_username", "");
                        String text = id + (number.isEmpty() ? "" : " · " + number)
                            + " · Stars: " + user.optLong("stars")
                            + (user.optInt("banned") == 1 ? " · БАН" : "")
                            + (nft.isEmpty() ? "" : " · @" + nft);
                        TextView row = new TextView(getParentActivity());
                        row.setText(text);
                        row.setTextSize(15);
                        row.setTextColor(Color.rgb(35, 45, 60));
                        row.setPadding(16, 15, 16, 15);
                        row.setBackgroundColor(Color.WHITE);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                        lp.bottomMargin = 1;
                        rows.addView(row, lp);
                        row.setOnClickListener(v -> showUserActions(id, user.optInt("banned") == 1));
                    }
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private void loadChats() {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET", "/v1/owner/chats", null, true);
                JSONArray chats = result.optJSONArray("chats");
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (rows == null) return;
                    rows.removeAllViews();
                    int count = chats == null ? 0 : chats.length();
                    if (status != null) status.setText("Групп и каналов: " + count);
                    for (int i = 0; i < count; i++) {
                        JSONObject chat = chats.optJSONObject(i);
                        if (chat == null) continue;
                        long id = chat.optLong("id");
                        String name = chat.optString("title", "Без названия");
                        TextView row = new TextView(getParentActivity());
                        row.setText((chat.optString("kind").equals("channel") ? "Канал · " : "Группа · ")
                            + name + (chat.optInt("verified") == 1 ? " ✓" : ""));
                        row.setTextColor(Color.DKGRAY);
                        row.setTextSize(16);
                        row.setPadding(16, 15, 16, 15);
                        row.setBackgroundColor(Color.WHITE);
                        rows.addView(row);
                        row.setOnClickListener(v -> showChatActions(id, name));
                    }
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private void showUserActions(String virtualId, boolean banned) {
        String[] options = new String[] {
            banned ? "Разблокировать" : "Заблокировать",
            "Подтвердить аккаунт",
            "Добавить Stars",
            "Снять Stars",
            "Установить баланс Stars",
            "Сгенерировать +888 номер",
            "Назначить +888 номер вручную",
            "Удалить виртуальный номер",
            "Назначить NFT username",
            "Снять NFT username",
            "Установить метку"
        };
        new AlertDialog.Builder(getParentActivity()).setTitle(virtualId)
            .setItems(options, (dialog, which) -> {
                if (which == 0) performUserAction(virtualId, banned ? "unban" : "ban", null);
                else if (which == 1) performUserAction(virtualId, "verify", null);
                else if (which == 2) askValue(virtualId, "stars_add", "Количество Stars", true);
                else if (which == 3) askValue(virtualId, "stars_remove", "Количество Stars", true);
                else if (which == 4) askValue(virtualId, "stars_set", "Новый баланс Stars", true);
                else if (which == 5) performUserAction(virtualId, "number_generate", null);
                else if (which == 6) askValue(virtualId, "number_set", "Номер в формате +888XXXXXXXX", false);
                else if (which == 7) performUserAction(virtualId, "number_remove", null);
                else if (which == 8) askValue(virtualId, "nft_set", "NFT username без @", false);
                else if (which == 9) performUserAction(virtualId, "nft_remove", null);
                else askValue(virtualId, "label", "Метка аккаунта", false);
            }).show();
    }

    private void askValue(String virtualId, String action, String hint, boolean numeric) {
        EditText input = new EditText(getParentActivity());
        input.setSingleLine(true);
        input.setHint(hint);
        if (numeric) input.setInputType(2);
        new AlertDialog.Builder(getParentActivity()).setTitle(hint).setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Применить", (d, w) -> performUserAction(virtualId, action, input.getText().toString().trim()))
            .show();
    }

    private void performUserAction(String virtualId, String action, String value) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("virtual_id", virtualId);
                body.put("action", action);
                if (value != null) body.put("value", value);
                FreeGramApi.request("POST", "/v1/owner/action", body, true);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (status != null) status.setText("Изменение выполнено: " + action);
                    loadUsers();
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private void showChatActions(long chatId, String title) {
        String[] options = {"Подтвердить", "Снять подтверждение", "Установить метку"};
        new AlertDialog.Builder(getParentActivity()).setTitle(title).setItems(options, (d, which) -> {
            if (which == 2) {
                EditText input = new EditText(getParentActivity());
                input.setHint("Метка");
                new AlertDialog.Builder(getParentActivity()).setTitle("Метка канала/группы").setView(input)
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Сохранить", (dd, ww) -> performChatAction(chatId, "label", input.getText().toString()))
                    .show();
            } else performChatAction(chatId, which == 0 ? "verify" : "unverify", null);
        }).show();
    }

    private void performChatAction(long chatId, String action, String value) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("chat_id", chatId);
                body.put("action", action);
                if (value != null) body.put("value", value);
                FreeGramApi.request("POST", "/v1/owner/chat-action", body, true);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(this::loadChats);
            } catch (Exception e) { showError(e); }
        });
    }

    private void showError(Exception error) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> {
            if (status != null) status.setText("Панель владельца: "
                + (error.getMessage() == null ? "ошибка запроса" : error.getMessage()));
        });
    }

    @Override
    public void onFragmentDestroy() {
        executor.shutdownNow();
        rows = null;
        super.onFragmentDestroy();
    }
}
