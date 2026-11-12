package org.telegram.ui;

import android.app.AlertDialog;
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
import android.webkit.WebView;
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
                    String virtualId = me.optString("virtual_id", "");
                    Button copyId = new Button(getParentActivity());
                    copyId.setText("Скопировать FreeGram ID");
                    copyId.setOnClickListener(v -> {
                        ClipboardManager clipboard = (ClipboardManager) getParentActivity()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("FreeGram ID", virtualId));
                            android.widget.Toast.makeText(getParentActivity(), "ID скопирован", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                    form.addView(copyId);
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
                        .setTitle("Профиль · " + virtualId)
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

    private String giftIcon(String code) {
        if ("rose".equals(code) || "red_rose_nft".equals(code)) return "🌹";
        if ("heart".equals(code) || "crystal_heart".equals(code)) return "❤️";
        if ("crown".equals(code) || "royal_ring".equals(code)) return "👑";
        if ("dragon".equals(code)) return "🐉";
        if (code.contains("pepe")) return "🐸";
        if (code.contains("redo")) return "🪐";
        return "✦";
    }

    private void loadGiftImage(WebView target, String path) {
        if (path == null || path.isEmpty()) return;
        target.getSettings().setJavaScriptEnabled(false);
        target.setVerticalScrollBarEnabled(false);
        target.setHorizontalScrollBarEnabled(false);
        target.setBackgroundColor(Color.TRANSPARENT);
        String url = path.startsWith("http") ? path : FreeGramApi.BASE + path;
        target.loadUrl(url);
    }

    private LinearLayout giftCard(JSONObject gift, boolean owned) {
        LinearLayout card = new LinearLayout(getParentActivity());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(10, 9, 10, 9);
        card.setBackgroundColor(Color.WHITE);
        WebView art = new WebView(getParentActivity());
        art.setBackgroundColor(Color.rgb(224, 230, 245));
        card.addView(art, new LinearLayout.LayoutParams(88, 88));
        loadGiftImage(art, gift.optString("image_url", ""));
        LinearLayout info = new LinearLayout(getParentActivity());
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(12, 0, 4, 0);
        TextView name = new TextView(getParentActivity());
        name.setText(giftIcon(gift.optString("code")) + "  " + gift.optString("title"));
        name.setTextColor(Color.rgb(30, 40, 55));
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        info.addView(name);
        TextView meta = new TextView(getParentActivity());
        String line = gift.optString("rarity", "common").toUpperCase()
            + " · " + gift.optString("collection", "FreeGram");
        if (owned) line += " · уровень " + gift.optInt("upgrade_level", 0);
        else line += " · " + gift.optLong("price") + " Stars";
        meta.setText(line);
        meta.setTextColor(Color.rgb(100, 110, 125));
        meta.setTextSize(12);
        info.addView(meta);
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        return card;
    }

    private void showGiftDetails(JSONObject gift) {
        if (getParentActivity() == null) return;
        LinearLayout content = new LinearLayout(getParentActivity());
        content.setPadding(18, 8, 18, 8);
        content.setOrientation(LinearLayout.VERTICAL);
        WebView art = new WebView(getParentActivity());
        art.setBackgroundColor(Color.rgb(224, 230, 245));
        content.addView(art, new LinearLayout.LayoutParams(-1, 250));
        loadGiftImage(art, gift.optString("image_url", ""));
        TextView details = new TextView(getParentActivity());
        details.setPadding(0, 12, 0, 4);
        details.setText(gift.optString("description", "")
            + "\nКоллекция: " + gift.optString("collection", "FreeGram")
            + "\nРедкость: " + gift.optString("rarity", "common")
            + ((gift.optInt("is_nft", 0) == 1) ? "\nКоллекционный предмет FreeGram" : "")
            + "\nЦена: " + gift.optLong("price") + " Stars");
        details.setTextColor(Color.DKGRAY);
        content.addView(details);
        new AlertDialog.Builder(getParentActivity()).setTitle(gift.optString("title"))
            .setView(content)
            .setNegativeButton("Назад", null)
            .setNeutralButton("Купить себе", (d,w) -> buyGiftForSelf(gift.optString("code"), gift.optString("title")))
            .setPositiveButton("Отправить", (d,w) -> sendGiftDialog(gift.optString("code"),gift.optString("title")))
            .show();
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
                    LinearLayout gallery = new LinearLayout(getParentActivity());
                    gallery.setPadding(10, 8, 10, 8);
                    gallery.setOrientation(LinearLayout.VERTICAL);
                    ScrollView scroll = new ScrollView(getParentActivity());
                    scroll.addView(gallery);
                    for (int i = 0; i < gifts.length(); i++) {
                        JSONObject gift = gifts.optJSONObject(i);
                        if (gift == null) continue;
                        LinearLayout card = giftCard(gift, false);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                        lp.bottomMargin = 7;
                        gallery.addView(card, lp);
                        card.setOnClickListener(v -> showGiftDetails(gift));
                    }
                    new AlertDialog.Builder(getParentActivity()).setTitle("NFT и подарки · " + gifts.length())
                        .setView(scroll).setNegativeButton("Закрыть", null).show();
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private void buyGiftForSelf(String giftCode, String giftTitle) {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("POST",
                    "/v1/shop/gifts/" + URLEncoder.encode(giftCode, "UTF-8") + "/buy",
                    new JSONObject(), true);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (status != null) status.setText("Куплено: " + giftTitle
                        + " · осталось Stars: " + result.optLong("remaining_stars"));
                    showMyGifts();
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
                    LinearLayout gallery = new LinearLayout(getParentActivity());
                    gallery.setPadding(10, 8, 10, 8);
                    gallery.setOrientation(LinearLayout.VERTICAL);
                    ScrollView scroll = new ScrollView(getParentActivity());
                    scroll.addView(gallery);
                    for (int i = 0; i < gifts.length(); i++) {
                        JSONObject gift = gifts.optJSONObject(i);
                        if (gift == null) continue;
                        LinearLayout card = giftCard(gift, true);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                        lp.bottomMargin = 7;
                        gallery.addView(card, lp);
                        card.setOnClickListener(v -> {
                            int level = gift.optInt("upgrade_level", 0);
                            String message = gift.optString("description", "")
                                + "\nКоллекция: " + gift.optString("collection", "FreeGram")
                                + "\nУровень улучшения: " + level + "/3"
                                + "\nСерийный номер: " + gift.optLong("serial_no", gift.optLong("id"));
                            new AlertDialog.Builder(getParentActivity()).setTitle(gift.optString("title"))
                                .setMessage(message)
                                .setNegativeButton("Закрыть", null)
                                .setPositiveButton(level >= 3 ? "Максимум" : "Улучшить", (d,w) -> {
                                    if (level < 3) upgradeMyGift(gift.optLong("id"));
                                }).show();
                        });
                    }
                    new AlertDialog.Builder(getParentActivity()).setTitle("Моя коллекция · " + gifts.length())
                        .setView(scroll).setNegativeButton("Закрыть", null).show();
                });
            } catch (Exception e) { showError(e); }
        });
    }

    private void upgradeMyGift(long id) {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("POST", "/v1/shop/my-gifts/" + id + "/upgrade",
                    new JSONObject(), true);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (status != null) status.setText("Подарок улучшен · уровень "
                        + result.optInt("upgrade_level") + " · осталось Stars: " + result.optLong("remaining_stars"));
                    showMyGifts();
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
