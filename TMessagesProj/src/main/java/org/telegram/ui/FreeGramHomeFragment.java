package org.telegram.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** FreeGram-owned UI. It deliberately does not initialize a Telegram account/session. */
public class FreeGramHomeFragment extends BaseFragment {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler();
    private LinearLayout root, content, tabs;
    private TextView pageTitle;
    private long activeChatId = -1;
    private String activeChatTitle = "";

    private interface Result { void done(JSONObject data, Exception error); }

    private int dp(float v) {
        return (int) (v * getParentActivity().getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(getParentActivity());
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private TextView action(String value, Runnable run) {
        TextView t = text(value, 14, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(11), dp(14), dp(11));
        t.setBackground(bg(Color.rgb(38, 132, 224), 18));
        t.setOnClickListener(v -> run.run());
        return t;
    }

    private void api(String method, String path, JSONObject body, Result callback) {
        io.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request(method, path, body, true);
                main.post(() -> callback.done(result, null));
            } catch (Exception e) {
                main.post(() -> callback.done(null, e));
            }
        });
    }

    private JSONObject obj(String... pairs) {
        JSONObject j = new JSONObject();
        try { for (int i = 0; i + 1 < pairs.length; i += 2) j.put(pairs[i], pairs[i + 1]); }
        catch (Exception ignored) {}
        return j;
    }

    private void line() {
        View v = new View(getParentActivity());
        v.setBackgroundColor(Color.rgb(42, 53, 68));
        content.addView(v, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void item(String title, String subtitle, Runnable click) {
        LinearLayout row = new LinearLayout(getParentActivity());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView a = text(title, 16, Color.WHITE, true);
        TextView b = text(subtitle == null ? "" : subtitle, 13, Color.rgb(151, 168, 188), false);
        row.addView(a);
        if (subtitle != null && !subtitle.isEmpty()) row.addView(b);
        row.setOnClickListener(v -> click.run());
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        line();
    }

    private void message(String value) {
        Toast.makeText(getParentActivity(), value, Toast.LENGTH_SHORT).show();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(15, 22, 32));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(12), dp(18), dp(12));
        header.setBackgroundColor(Color.rgb(21, 31, 45));
        pageTitle = text("FreeGram", 21, Color.WHITE, true);
        header.addView(pageTitle, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(action("⌕", this::searchDialog));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(58)));

        ScrollView scroll = new ScrollView(context);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        tabs = new LinearLayout(context);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(dp(6), dp(8), dp(6), dp(8));
        tabs.setBackgroundColor(Color.rgb(21, 31, 45));
        addTab("Чаты", () -> showChats());
        addTab("Контакты", () -> showContacts());
        addTab("Подарки", () -> showGifts());
        addTab("Профиль", () -> showProfile());
        root.addView(tabs, new LinearLayout.LayoutParams(-1, dp(58)));

        fragmentView = root;
        showChats();
        return fragmentView;
    }

    private void addTab(String label, Runnable run) {
        TextView t = text(label, 12, Color.rgb(93, 173, 255), true);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> run.run());
        tabs.addView(t, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void clear(String title) {
        pageTitle.setText(title);
        content.removeAllViews();
    }

    private void showChats() {
        activeChatId = -1;
        clear("Чаты");
        LinearLayout tools = new LinearLayout(getParentActivity());
        tools.setPadding(dp(12), dp(12), dp(12), dp(12));
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(action("+ Группа", () -> createChat("group")));
        TextView gap = text(" ", 12, Color.WHITE, false);
        tools.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        tools.addView(action("+ Канал", () -> createChat("channel")));
        content.addView(tools);
        api("GET", "/v1/chats", null, (data, error) -> {
            if (error != null) { item("Не удалось загрузить чаты", error.getMessage(), () -> showChats()); return; }
            JSONArray chats = data.optJSONArray("chats");
            if (chats == null || chats.length() == 0) {
                item("Пока нет чатов", "Найди пользователя во вкладке «Контакты» или создай группу/канал.", () -> showContacts());
                return;
            }
            for (int i = 0; i < chats.length(); i++) {
                JSONObject c = chats.optJSONObject(i);
                if (c == null) continue;
                long id = c.optLong("id");
                String kind = c.optString("kind");
                String rawTitle = c.optString("title");
                final String chatTitle = "direct".equals(kind)
                    ? "@" + c.optString("other_username", "пользователь")
                    : (rawTitle.isEmpty() ? "FreeGram " + kind : rawTitle);
                String last = c.optString("last_message", "Нет сообщений");
                item(("channel".equals(kind) ? "▣ " : "● ") + chatTitle, last, () -> openChat(id, chatTitle));
            }
        });
    }

    private void createChat(String kind) {
        EditText title = new EditText(getParentActivity());
        title.setHint("Название");
        EditText username = new EditText(getParentActivity());
        username.setHint("Username (необязательно)");
        LinearLayout box = new LinearLayout(getParentActivity());
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.setOrientation(LinearLayout.VERTICAL);
        if ("group".equals(kind) || "channel".equals(kind)) box.addView(title);
        box.addView(username);
        new AlertDialog.Builder(getParentActivity()).setTitle("Создать " + ("group".equals(kind) ? "группу" : "канал"))
            .setView(box).setNegativeButton("Отмена", null).setPositiveButton("Создать", (d, w) -> {
                JSONObject body = obj("kind", kind, "title", title.getText().toString());
                try { if (!username.getText().toString().trim().isEmpty()) body.put("username", username.getText().toString().trim()); }
                catch (Exception ignored) {}
                api("POST", "/v1/chats", body, (data, error) -> {
                    if (error != null) message(error.getMessage());
                    else {
                        String code = data.optString("invite_code", "");
                        if (!code.isEmpty()) message("Создано. Код приглашения: " + code);
                        showChats();
                    }
                });
            }).show();
    }

    private void openChat(long id, String title) {
        activeChatId = id;
        activeChatTitle = title;
        clear(title);
        content.addView(action("‹ Назад к чатам", this::showChats));
        LinearLayout composer = new LinearLayout(getParentActivity());
        EditText input = new EditText(getParentActivity());
        input.setHint("Сообщение");
        input.setTextColor(Color.WHITE);
        composer.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1));
        composer.addView(action("➤", () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) return;
            api("POST", "/v1/chats/" + id + "/messages", obj("text", value), (data, error) -> {
                if (error != null) message(error.getMessage());
                else { input.setText(""); openChat(id, title); }
            });
        }));
        api("GET", "/v1/chats/" + id + "/messages", null, (data, error) -> {
            if (error != null) { item("Ошибка загрузки", error.getMessage(), () -> openChat(id, title)); return; }
            JSONArray messages = data.optJSONArray("messages");
            if (messages != null) for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i);
                if (m != null) item("@" + m.optString("username", "user"), m.optString("body"), () -> {});
            }
            content.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        });
    }

    private void showContacts() {
        clear("Контакты");
        EditText query = new EditText(getParentActivity());
        query.setHint("Поиск по @username");
        query.setSingleLine(true);
        content.addView(query, new LinearLayout.LayoutParams(-1, dp(52)));
        content.addView(action("Найти", () -> {
            String q = query.getText().toString().trim();
            if (q.length() < 2) { message("Введи минимум 2 символа"); return; }
            content.removeViews(1, Math.max(0, content.getChildCount() - 1));
            api("GET", "/v1/search/users?q=" + android.net.Uri.encode(q), null, (data, error) -> {
                if (error != null) { item("Ошибка поиска", error.getMessage(), () -> showContacts()); return; }
                JSONArray users = data.optJSONArray("users");
                if (users != null) for (int i = 0; i < users.length(); i++) {
                    JSONObject u = users.optJSONObject(i);
                    if (u == null) continue;
                    String username = u.optString("username");
                    String name = u.optString("display_name", "");
                    String badge = u.optString("label", "");
                    String sub = (name.isEmpty() ? "" : name + " · ") + u.optString("virtual_number") + (badge.isEmpty() ? "" : " · " + badge);
                    item("@" + username + (u.optInt("verified") == 1 ? "  ✓" : ""), sub, () ->
                        api("POST", "/v1/chats", obj("kind", "direct", "username", username), (created, err) -> {
                            if (err != null) message(err.getMessage());
                            else openChat(created.optLong("chat_id"), "@" + username);
                        }));
                }
            });
        }));
    }

    private void showGifts() {
        clear("Подарки");
        content.addView(action("Мои подарки", () -> loadMyGifts()));
        api("GET", "/v1/gifts", null, (data, error) -> {
            if (error != null) { item("Магазин недоступен", error.getMessage(), () -> showGifts()); return; }
            JSONArray gifts = data.optJSONArray("gifts");
            if (gifts != null) for (int i = 0; i < gifts.length(); i++) {
                JSONObject g = gifts.optJSONObject(i);
                if (g == null) continue;
                String giftId = g.optString("gift_id");
                String title = g.optString("emoji", "🎁") + "  " + g.optString("title");
                String subtitle = g.optString("rarity") + " · " + g.optLong("price") + " Stars";
                item(title, subtitle, () -> giftRecipientDialog(giftId, g.optString("title"), g.optLong("price")));
            }
        });
    }

    private void giftRecipientDialog(String giftId, String title, long price) {
        EditText to = new EditText(getParentActivity());
        to.setHint("@username (пусто — себе)");
        new AlertDialog.Builder(getParentActivity()).setTitle(title + " · " + price + " Stars")
            .setMessage("Кому отправить подарок?")
            .setView(to).setNegativeButton("Отмена", null).setPositiveButton("Отправить", (d, w) -> {
                JSONObject body = obj("gift_id", giftId);
                try {
                    String recipient = to.getText().toString().trim().replace("@", "");
                    if (!recipient.isEmpty()) body.put("to_username", recipient);
                } catch (Exception ignored) {}
                api("POST", "/v1/gifts/buy", body, (data, error) -> {
                    if (error != null) message(error.getMessage());
                    else message("Подарок отправлен. Остаток: " + data.optLong("remaining_stars") + " Stars");
                });
            }).show();
    }

    private void loadMyGifts() {
        clear("Мои подарки");
        content.addView(action("‹ Магазин", this::showGifts));
        api("GET", "/v1/me/gifts", null, (data, error) -> {
            if (error != null) { item("Не удалось загрузить подарки", error.getMessage(), this::loadMyGifts); return; }
            JSONArray gifts = data.optJSONArray("gifts");
            if (gifts == null || gifts.length() == 0) { item("Подарков пока нет", "Они появятся здесь после получения.", () -> {}); return; }
            for (int i = 0; i < gifts.length(); i++) {
                JSONObject g = gifts.optJSONObject(i);
                if (g != null) item(g.optString("emoji", "🎁") + " " + g.optString("title"),
                    "От @" + g.optString("sender_username") + (g.optString("message").isEmpty() ? "" : " · " + g.optString("message")), () -> {});
            }
        });
    }

    private void showProfile() {
        clear("Профиль");
        api("GET", "/v1/me", null, (data, error) -> {
            if (error != null) { item("Профиль недоступен", error.getMessage(), this::showProfile); return; }
            String username = data.optString("username", "");
            String display = data.optString("display_name", "");
            item("● " + (display.isEmpty() ? username : display), "@" + username, () -> {});
            item("Номер FreeGram", data.optString("virtual_number"), () -> {});
            item("NFT username", data.optString("nft_username", "Не назначен"), () -> {});
            item("Статус", data.optString("label", data.optInt("verified") == 1 ? "Проверен ✓" : "Обычный"), () -> {});
            item("Баланс", data.optLong("stars") + " FreeGram Stars", () -> {});
            item("О себе", data.optString("bio", ""), () -> {});
            content.addView(action("Изменить профиль", () -> editProfile(data)));
            content.addView(action("Выйти", () -> new AlertDialog.Builder(getParentActivity())
                .setMessage("Выйти из FreeGram?").setNegativeButton("Отмена", null)
                .setPositiveButton("Выйти", (d, w) -> { FreeGramApi.clearToken(); presentFragment(new FreeGramAuthFragment(), true); }).show()));
        });
    }

    private void editProfile(JSONObject old) {
        EditText name = new EditText(getParentActivity());
        name.setHint("Имя");
        name.setText(old.optString("display_name", ""));
        EditText bio = new EditText(getParentActivity());
        bio.setHint("О себе");
        bio.setText(old.optString("bio", ""));
        EditText avatar = new EditText(getParentActivity());
        avatar.setHint("URL аватара (необязательно)");
        avatar.setText(old.optString("avatar_url", ""));
        LinearLayout box = new LinearLayout(getParentActivity());
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(name); box.addView(bio); box.addView(avatar);
        new AlertDialog.Builder(getParentActivity()).setTitle("Профиль").setView(box)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", (d, w) -> {
                JSONObject body = obj("display_name", name.getText().toString(),
                    "bio", bio.getText().toString(), "avatar_url", avatar.getText().toString());
                api("PUT", "/v1/me/profile", body, (result, error) -> {
                    if (error != null) message(error.getMessage()); else showProfile();
                });
            }).show();
    }

    private void searchDialog() {
        EditText input = new EditText(getParentActivity());
        input.setHint("@username");
        new AlertDialog.Builder(getParentActivity()).setTitle("Поиск FreeGram")
            .setView(input).setNegativeButton("Отмена", null).setPositiveButton("Найти", (d, w) -> {
                showContacts();
                message("Введи запрос в поиске контактов");
            }).show();
    }

    @Override
    public void onFragmentDestroy() {
        io.shutdownNow();
        super.onFragmentDestroy();
    }
}
