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
import android.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;

import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FreeGramHomeFragment extends BaseFragment {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView status;
    private EditText search;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 248, 251));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(16, 10, 16, 8);
        TextView title = new TextView(context);
        title.setText("FreeGram");
        title.setTextColor(Color.rgb(28, 100, 190));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button profile = new Button(context);
        profile.setText("Профиль");
        header.addView(profile);
        Button logout = new Button(context);
        logout.setText("Выйти");
        header.addView(logout);
        root.addView(header);

        profile.setOnClickListener(v -> showProfile());
        logout.setOnClickListener(v -> {
            FreeGramApi.clearToken();
            presentFragment(new FreeGramAuthFragment(), true);
        });

        LinearLayout actions = new LinearLayout(context);
        actions.setPadding(10, 0, 10, 4);
        Button group = new Button(context);
        group.setText("Новая группа");
        actions.addView(group, new LinearLayout.LayoutParams(0, -2, 1));
        Button channel = new Button(context);
        channel.setText("Новый канал");
        actions.addView(channel, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);
        group.setOnClickListener(v -> createNamedChat("group"));
        channel.setOnClickListener(v -> createNamedChat("channel"));

        LinearLayout searchBar = new LinearLayout(context);
        searchBar.setPadding(12, 2, 12, 4);
        search = new EditText(context);
        search.setSingleLine(true);
        search.setHint("Поиск людей по @username");
        searchBar.addView(search, new LinearLayout.LayoutParams(0, 50, 1));
        Button find = new Button(context);
        find.setText("Найти");
        searchBar.addView(find);
        root.addView(searchBar);
        find.setOnClickListener(v -> searchUsers());
        search.setOnEditorActionListener((v, actionId, event) -> { searchUsers(); return true; });

        status = new TextView(context);
        status.setText("Загружаем чаты…");
        status.setTextColor(Color.DKGRAY);
        status.setPadding(16, 6, 16, 6);
        root.addView(status);
        ScrollView scroll = new ScrollView(context);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        fragmentView = root;
        loadChats();
        return fragmentView;
    }

    private void loadChats() {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET", "/v1/chats", null, true);
                JSONArray chats = result.optJSONArray("chats");
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (list == null) return;
                    list.removeAllViews();
                    int count = chats == null ? 0 : chats.length();
                    status.setText(count == 0 ? "Пока нет чатов. Найдите пользователя или создайте группу." : "Ваши чаты");
                    for (int i = 0; i < count; i++) {
                        JSONObject chat = chats.optJSONObject(i);
                        if (chat == null) continue;
                        String name = chat.optString("title", chat.optString("other_username", "Чат"));
                        if (name.isEmpty()) name = chat.optString("other_username", "Личный чат");
                        String kind = chat.optString("kind", "direct");
                        String last = chat.optString("last_message", "");
                        boolean verifiedChat = chat.optBoolean("verified", false);
                        String chatLabel = chat.optString("label", "");
                        String badge = verifiedChat ? "  ☑" + (chatLabel.isEmpty() ? "" : " " + chatLabel) : "";
                        TextView row = makeRow((kind.equals("channel") ? "▣  " : kind.equals("group") ? "👥  " : "●  ") + name + badge + (last.isEmpty() ? "" : "\n" + last));
                        long id = chat.optLong("id", -1);
                        String chatTitle = name;
                        row.setOnClickListener(v -> { if (id > 0) presentFragment(new FreeGramChatFragment(id, chatTitle), true); });
                        list.addView(row);
                    }
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private TextView makeRow(String value) {
        TextView row = new TextView(getParentActivity());
        row.setText(value);
        row.setTextColor(Color.rgb(30, 35, 42));
        row.setTextSize(16);
        row.setPadding(18, 16, 18, 16);
        row.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = 1;
        row.setTag(lp);
        return row;
    }

    private void searchUsers() {
        String q = search.getText().toString().trim().replace("@", "");
        if (q.length() < 2) { status.setText("Введите минимум 2 символа"); return; }
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET", "/v1/users/search?q=" + URLEncoder.encode(q, "UTF-8"), null, true);
                JSONArray users = result.optJSONArray("users");
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (list == null) return;
                    list.removeAllViews();
                    int count = users == null ? 0 : users.length();
                    status.setText("Найдено пользователей: " + count);
                    for (int i = 0; i < count; i++) {
                        JSONObject user = users.optJSONObject(i);
                        if (user == null) continue;
                        String username = user.optString("username", "");
                        String display = user.optString("display_name", "");
                        String label = user.optString("label", "");
                        String text = (display.isEmpty() ? username : display + "  @" + username) + (label.isEmpty() ? "" : "  ✓ " + label);
                        String nft = user.optString("nft_username", "");
                        if (!nft.isEmpty()) text += "\\nNFT · @" + nft;
                        TextView row = makeRow(text);
                        row.setOnClickListener(v -> openDirect(username));
                        row.setOnLongClickListener(v -> { showUserProfile(username); return true; });
                        list.addView(row);
                    }
                    TextView back = makeRow("← Вернуться к чатам");
                    back.setOnClickListener(v -> loadChats());
                    list.addView(back);
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private void showUserProfile(String username) {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET",
                        "/v1/users/" + URLEncoder.encode(username, "UTF-8"), null, true);
                JSONObject user = result.optJSONObject("profile");
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (getParentActivity() == null || user == null) return;
                    String display = user.optString("display_name", "");
                    StringBuilder info = new StringBuilder();
                    info.append(display.isEmpty() ? username : display + "  @" + username);
                    info.append("\\nFreeGram ID: ").append(user.optString("virtual_id", "—"));
                    if (!user.optString("bio", "").isEmpty()) info.append("\\n\\n").append(user.optString("bio", ""));
                    if (!user.optString("virtual_number", "").isEmpty()) info.append("\\n\\nНомер: ").append(user.optString("virtual_number", ""));
                    if (!user.optString("nft_username", "").isEmpty()) info.append("\\nNFT: @").append(user.optString("nft_username", ""));
                    if (!user.optString("label", "").isEmpty()) info.append("\\nМетка: ").append(user.optString("label", ""));
                    if (user.optBoolean("verified", false)) info.append("\\n✓ Подтверждён");
                    new AlertDialog.Builder(getParentActivity())
                            .setTitle("Профиль пользователя")
                            .setMessage(info.toString())
                            .setNegativeButton("Закрыть", null)
                            .setPositiveButton("Написать", (dialog, which) -> openDirect(username))
                            .show();
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private void openDirect(String username) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("kind", "direct");
                body.put("username", username);
                JSONObject result = FreeGramApi.request("POST", "/v1/chats", body, true);
                long id = result.optLong("chat_id", -1);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> { if (id > 0) presentFragment(new FreeGramChatFragment(id, username), true); });
            } catch (Exception e) { showError(e); }
        });
    }

    private void createNamedChat(String kind) {
        EditText input = new EditText(getParentActivity());
        input.setHint(kind.equals("channel") ? "Название канала" : "Название группы");
        new AlertDialog.Builder(getParentActivity())
            .setTitle(kind.equals("channel") ? "Создать канал" : "Создать группу")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Создать", (dialog, which) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) { status.setText("Укажите название"); return; }
                executor.execute(() -> {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("kind", kind);
                        body.put("title", name);
                        JSONObject result = FreeGramApi.request("POST", "/v1/chats", body, true);
                        long id = result.optLong("chat_id", -1);
                        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                        main.post(() -> { if (id > 0) presentFragment(new FreeGramChatFragment(id, name), true); loadChats(); });
                    } catch (Exception e) { showError(e); }
                });
            }).show();
    }

    private void showProfile() {
        executor.execute(() -> {
            try {
                JSONObject me = FreeGramApi.request("GET", "/v1/me", null, true);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (getParentActivity() == null) return;
                    LinearLayout form = new LinearLayout(getParentActivity());
                    form.setPadding(24, 8, 24, 4);
                    form.setOrientation(LinearLayout.VERTICAL);
                    EditText username = new EditText(getParentActivity());
                    username.setSingleLine(true);
                    username.setHint("username");
                    username.setText(me.optString("username", ""));
                    EditText display = new EditText(getParentActivity());
                    display.setSingleLine(true);
                    display.setHint("Имя");
                    display.setText(me.optString("display_name", ""));
                    EditText bio = new EditText(getParentActivity());
                    bio.setHint("О себе");
                    bio.setText(me.optString("bio", ""));
                    EditText avatar = new EditText(getParentActivity());
                    avatar.setSingleLine(true);
                    avatar.setHint("Ссылка на фото профиля");
                    avatar.setText(me.optString("avatar_url", ""));
                    TextView accountInfo = new TextView(getParentActivity());
                    StringBuilder details = new StringBuilder();
                    details.append("FreeGram ID: ").append(me.optString("virtual_id", "—"));
                    details.append("\\nНомер: ").append(me.optString("virtual_number", "не назначен"));
                    details.append("\\nДополнительные номера: ").append(me.optJSONArray("additional_numbers") == null ? "—" : me.optJSONArray("additional_numbers").toString());
                    details.append("\\nNFT-имена: ").append(me.optJSONArray("nft_usernames") == null ? "—" : me.optJSONArray("nft_usernames").toString());
                    if (me.optBoolean("verified", false)) {
                        details.append("\\n✓ Подтверждён");
                        if (!me.optString("label", "").isEmpty()) details.append(" · ").append(me.optString("label"));
                    }
                    accountInfo.setText(details.toString());
                    accountInfo.setTextSize(13);
                    accountInfo.setTextColor(Color.rgb(85, 100, 120));
                    accountInfo.setPadding(0, 8, 0, 12);
                    form.addView(accountInfo);
                    form.addView(username);
                    form.addView(display);
                    form.addView(bio);
                    form.addView(avatar);
                    new AlertDialog.Builder(getParentActivity())
                        .setTitle("Профиль FreeGram")
                        .setView(form)
                        .setNegativeButton("Закрыть", null)
                        .setPositiveButton("Сохранить", (d, w) -> executor.execute(() -> {
                            try {
                                JSONObject update = new JSONObject();
                                update.put("username", username.getText().toString().trim());
                                update.put("display_name", display.getText().toString().trim());
                                update.put("bio", bio.getText().toString().trim());
                                update.put("avatar_url", avatar.getText().toString().trim());
                                FreeGramApi.request("PATCH", "/v1/me", update, true);
                                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                                h.post(() -> status.setText("Профиль сохранён"));
                            } catch (Exception e) { showError(e); }
                        })).show();
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private void showError(Exception e) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> { if (status != null) status.setText(e.getMessage() == null ? "Ошибка FreeGram API" : e.getMessage()); });
    }

    @Override
    public void onFragmentDestroy() {
        executor.shutdownNow();
        list = null;
        search = null;
        super.onFragmentDestroy();
    }
}
