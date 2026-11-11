package org.telegram.ui;

import android.app.AlertDialog;
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
    private String searchMode = "users";

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 248, 251));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(14, 8, 10, 8);
        TextView title = new TextView(context);
        title.setText("FreeGram");
        title.setTextColor(Color.rgb(34, 115, 190));
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button profile = new Button(context);
        profile.setText("Профиль");
        header.addView(profile);
        Button logout = new Button(context);
        logout.setText("⋮");
        header.addView(logout);
        root.addView(header);
        profile.setOnClickListener(v -> showProfile());
        logout.setOnClickListener(v -> new AlertDialog.Builder(getParentActivity())
            .setItems(new String[]{"Выйти из аккаунта"}, (d, which) -> {
                FreeGramApi.clearToken();
                presentFragment(new FreeGramAuthFragment(), true);
            }).show());

        LinearLayout actions = new LinearLayout(context);
        actions.setPadding(8, 0, 8, 2);
        addAction(actions, "Группа +", () -> createNamedChat("group"));
        addAction(actions, "Канал +", () -> createNamedChat("channel"));
        addAction(actions, "Вступить", this::joinByInvite);
        root.addView(actions);

        LinearLayout shop = new LinearLayout(context);
        shop.setPadding(8, 0, 8, 2);
        addAction(shop, "Подарки", this::showGiftShop);
        addAction(shop, "Мои подарки", this::showMyGifts);
        root.addView(shop);

        LinearLayout searchBar = new LinearLayout(context);
        searchBar.setPadding(10, 0, 10, 4);
        search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setHint("Поиск @username или каналов");
        searchBar.addView(search, new LinearLayout.LayoutParams(0, 48, 1));
        Button findUsers = new Button(context);
        findUsers.setText("Люди");
        searchBar.addView(findUsers);
        Button findChats = new Button(context);
        findChats.setText("Чаты");
        searchBar.addView(findChats);
        root.addView(searchBar);
        findUsers.setOnClickListener(v -> { searchMode = "users"; searchUsers(); });
        findChats.setOnClickListener(v -> { searchMode = "chats"; searchChats(); });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if ("chats".equals(searchMode)) searchChats(); else searchUsers();
            return true;
        });

        status = new TextView(context);
        status.setText("Загружаем чаты…");
        status.setTextColor(Color.rgb(100, 110, 120));
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

    private void addAction(LinearLayout parent, String title, Runnable action) {
        Button button = new Button(getParentActivity());
        button.setText(title);
        parent.addView(button, new LinearLayout.LayoutParams(0, 44, 1));
        button.setOnClickListener(v -> action.run());
    }

    private TextView makeRow(String primary, String secondary) {
        TextView row = new TextView(getParentActivity());
        row.setText(secondary == null || secondary.isEmpty() ? primary : primary + "\n" + secondary);
        row.setTextColor(Color.rgb(35, 43, 52));
        row.setTextSize(16);
        row.setPadding(18, 15, 18, 15);
        row.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = 1;
        row.setLayoutParams(lp);
        return row;
    }

    private void loadChats() {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET", "/v1/chats", null, true);
                JSONArray chats = result.optJSONArray("chats");
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (list == null || status == null) return;
                    list.removeAllViews();
                    int count = chats == null ? 0 : chats.length();
                    status.setText(count == 0 ? "Пока нет чатов. Найдите пользователя или создайте группу." : "Ваши чаты");
                    for (int i = 0; i < count; i++) {
                        JSONObject chat = chats.optJSONObject(i);
                        if (chat == null) continue;
                        String kind = chat.optString("kind", "direct");
                        String name = chat.optString("title", "");
                        if (name.isEmpty()) name = chat.optString("other_username", "Личный чат");
                        String last = chat.optString("last_message", "");
                        String prefix = kind.equals("channel") ? "▣  " : kind.equals("group") ? "👥  " : "●  ";
                        String detail = last.isEmpty() ? kindLabel(kind) : last;
                        TextView row = makeRow(prefix + name, detail);
                        long id = chat.optLong("id", -1);
                        String chatTitle = name;
                        row.setOnClickListener(v -> {
                            if (id > 0) presentFragment(new FreeGramChatFragment(id, chatTitle), true);
                        });
                        list.addView(row);
                    }
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private String kindLabel(String kind) {
        if ("channel".equals(kind)) return "Канал";
        if ("group".equals(kind)) return "Группа";
        return "Личный чат";
    }

    private void searchUsers() {
        String q = search.getText().toString().trim().replace("@", "");
        if (q.length() < 2) { status.setText("Введите минимум 2 символа"); return; }
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET",
                    "/v1/users/search?q=" + URLEncoder.encode(q, "UTF-8"), null, true);
                JSONArray users = result.optJSONArray("users");
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (list == null || status == null) return;
                    list.removeAllViews();
                    int count = users == null ? 0 : users.length();
                    status.setText("Найдено пользователей: " + count);
                    for (int i = 0; i < count; i++) {
                        JSONObject user = users.optJSONObject(i);
                        if (user == null) continue;
                        String username = user.optString("username", "");
                        String display = user.optString("display_name", "");
                        String label = user.optString("label", "");
                        boolean verified = user.optInt("verified", 0) == 1;
                        String nft = user.optString("nft_username", "");
                        String line = (display.isEmpty() ? username : display + "  @" + username);
                        if (!nft.isEmpty()) line += "  ◆ @" + nft + " · " + user.optString("nft_rarity", "common");
                        if (verified) line += "  ✓";
                        if (!label.isEmpty()) line += " · " + label;
                        TextView row = makeRow(line, user.optString("bio", ""));
                        row.setOnClickListener(v -> openDirect(username));
                        list.addView(row);
                    }
                    TextView back = makeRow("← Вернуться к чатам", "");
                    back.setOnClickListener(v -> loadChats());
                    list.addView(back);
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private void searchChats() {
        String q = search.getText().toString().trim();
        if (q.length() < 2) { status.setText("Введите минимум 2 символа"); return; }
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET",
                    "/v1/chats/search?q=" + URLEncoder.encode(q, "UTF-8"), null, true);
                JSONArray chats = result.optJSONArray("chats");
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (list == null || status == null) return;
                    list.removeAllViews();
                    int count = chats == null ? 0 : chats.length();
                    status.setText("Найдено групп и каналов: " + count);
                    for (int i = 0; i < count; i++) {
                        JSONObject chat = chats.optJSONObject(i);
                        if (chat == null) continue;
                        long id = chat.optLong("id", -1);
                        String kind = chat.optString("kind", "group");
                        String name = chat.optString("title", "Сообщество");
                        String desc = kindLabel(kind) + " · " + chat.optInt("member_count") + " участников";
                        TextView row = makeRow((kind.equals("channel") ? "▣  " : "👥  ") + name, desc);
                        row.setOnClickListener(v -> joinPublicChat(id, name));
                        list.addView(row);
                    }
                    TextView back = makeRow("← Вернуться к чатам", "");
                    back.setOnClickListener(v -> loadChats());
                    list.addView(back);
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
                        main.post(() -> {
                            if (id > 0) presentFragment(new FreeGramChatFragment(id, name), true);
                            loadChats();
                        });
                    } catch (Exception e) { showError(e); }
                });
            }).show();
    }

    private void joinByInvite() {
        EditText input = new EditText(getParentActivity());
        input.setSingleLine(true);
        input.setHint("Код или ссылка-приглашение");
        new AlertDialog.Builder(getParentActivity())
            .setTitle("Вступить в группу или канал")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Вступить", (d, w) -> {
                String value = input.getText().toString().trim();
                int slash = value.lastIndexOf('/');
                String code = slash >= 0 ? value.substring(slash + 1) : value;
                if (code.isEmpty()) return;
                executor.execute(() -> {
                    try {
                        FreeGramApi.request("POST", "/v1/invites/" + URLEncoder.encode(code, "UTF-8") + "/join",
                            new JSONObject(), true);
                        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                        main.post(this::loadChats);
                    } catch (Exception e) { showError(e); }
                });
            }).show();
    }

    private void joinPublicChat(long id, String title) {
        executor.execute(() -> {
            try {
                FreeGramApi.request("POST", "/v1/chats/" + id + "/join", new JSONObject(), true);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    loadChats();
                    presentFragment(new FreeGramChatFragment(id, title), true);
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private void showProfile() {
        executor.execute(() -> {
            try {
                JSONObject me = FreeGramApi.request("GET", "/v1/me", null, true);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (getParentActivity() == null) return;
                    LinearLayout form = new LinearLayout(getParentActivity());
                    form.setPadding(22, 8, 22, 4);
                    form.setOrientation(LinearLayout.VERTICAL);
                    String number = me.optString("virtual_number", "");
                    String nft = me.optString("nft_username", "");
                    String rarity = me.optString("nft_rarity", "common");
                    String label = me.optString("label", "");
                    String badge = me.optInt("verified", 0) == 1 ? "✓ Проверен" : "Не подтверждён";
                    TextView details = new TextView(getParentActivity());
                    details.setText("Номер: " + (number.isEmpty() ? "не назначен" : number)
                        + "\n" + badge
                        + (label.isEmpty() ? "" : "\nМетка: " + label)
                        + (nft.isEmpty() ? "" : "\nNFT username: @" + nft + " · " + rarity)
                        + "\nStars: " + me.optLong("stars", 0));
                    details.setTextColor(Color.DKGRAY);
                    details.setPadding(0, 0, 0, 12);
                    form.addView(details);
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
                    avatar.setHint("Ссылка на аватар (URL)");
                    avatar.setText(me.optString("avatar_url", ""));
                    form.addView(username);
                    form.addView(display);
                    form.addView(bio);
                    form.addView(avatar);
                    new AlertDialog.Builder(getParentActivity())
                        .setTitle("Профиль · " + me.optString("virtual_id", ""))
                        .setView(form)
                        .setNegativeButton("Закрыть", null)
                        .setPositiveButton("Сохранить", (d, w) -> executor.execute(() -> {
                            try {
                                JSONObject update = new JSONObject();
                                String userNameValue = username.getText().toString().trim();
                                if (!userNameValue.isEmpty()) update.put("username", userNameValue);
                                update.put("display_name", display.getText().toString().trim());
                                update.put("bio", bio.getText().toString().trim());
                                update.put("avatar_url", avatar.getText().toString().trim());
                                FreeGramApi.request("PATCH", "/v1/me", update, true);
                                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                                h.post(() -> { if (status != null) status.setText("Профиль сохранён"); });
                            } catch (Exception e) { showError(e); }
                        })).show();
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private void showGiftShop() {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET", "/v1/shop/gifts", null, true);
                JSONArray gifts = result.optJSONArray("gifts");
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (getParentActivity() == null) return;
                    if (gifts == null || gifts.length() == 0) {
                        status.setText("Магазин подарков пока пуст");
                        return;
                    }
                    String[] labels = new String[gifts.length()];
                    for (int i = 0; i < gifts.length(); i++) {
                        JSONObject gift = gifts.optJSONObject(i);
                        labels[i] = gift.optString("title") + " · " + gift.optLong("price") + " Stars · " + gift.optString("rarity");
                    }
                    new AlertDialog.Builder(getParentActivity())
                        .setTitle("Подарки FreeGram")
                        .setItems(labels, (d, which) -> {
                            JSONObject gift = gifts.optJSONObject(which);
                            if (gift != null) sendGiftDialog(gift.optString("code"), gift.optString("title"));
                        }).setNegativeButton("Закрыть", null).show();
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private void sendGiftDialog(String giftCode, String giftTitle) {
        EditText recipient = new EditText(getParentActivity());
        recipient.setSingleLine(true);
        recipient.setHint("@username получателя");
        new AlertDialog.Builder(getParentActivity())
            .setTitle("Отправить: " + giftTitle)
            .setView(recipient)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Отправить", (d, w) -> executor.execute(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("gift_code", giftCode);
                    body.put("recipient_username", recipient.getText().toString().trim().replace("@", ""));
                    JSONObject result = FreeGramApi.request("POST", "/v1/shop/gifts/send", body, true);
                    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    main.post(() -> status.setText("Подарок отправлен · осталось Stars: " + result.optLong("remaining_stars")));
                } catch (Exception e) { showError(e); }
            })).show();
    }

    private void showMyGifts() {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET", "/v1/shop/my-gifts", null, true);
                JSONArray gifts = result.optJSONArray("gifts");
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (getParentActivity() == null) return;
                    if (gifts == null || gifts.length() == 0) {
                        new AlertDialog.Builder(getParentActivity()).setMessage("У вас пока нет подарков.")
                            .setPositiveButton("ОК", null).show();
                        return;
                    }
                    String[] labels = new String[gifts.length()];
                    for (int i = 0; i < gifts.length(); i++) {
                        JSONObject gift = gifts.optJSONObject(i);
                        labels[i] = gift.optString("title") + " · " + gift.optString("rarity")
                            + " · от @" + gift.optString("sender_username", "FreeGram");
                    }
                    new AlertDialog.Builder(getParentActivity()).setTitle("Мои подарки")
                        .setItems(labels, (d, which) -> {}).setPositiveButton("Закрыть", null).show();
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
