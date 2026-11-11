package org.telegram.ui;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FreeGramChatFragment extends BaseFragment {
    private final long chatId;
    private final String chatTitle;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout messages;
    private EditText composer;
    private TextView status;
    private final android.os.Handler pollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (messages != null) {
                loadMessages();
                pollHandler.postDelayed(this, 5000);
            }
        }
    };

    public FreeGramChatFragment(long id, String title) {
        chatId = id;
        chatTitle = title == null || title.isEmpty() ? "Чат" : title;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(239, 244, 249));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(16, 10, 16, 10);
        TextView title = new TextView(context);
        title.setText(chatTitle);
        title.setTextColor(Color.rgb(25, 35, 48));
        title.setTextSize(19);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button info = new Button(context);
        info.setText("ⓘ");
        header.addView(info);
        Button gift = new Button(context);
        gift.setText("🎁");
        header.addView(gift);
        Button invite = new Button(context);
        invite.setText("Ссылка");
        header.addView(invite);
        Button refresh = new Button(context);
        refresh.setText("↻");
        header.addView(refresh);
        info.setOnClickListener(v -> showChatProfile());
        gift.setOnClickListener(v -> showChatGifts());
        invite.setOnClickListener(v -> createInvite());
        root.addView(header);

        status = new TextView(context);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(14, 4, 14, 4);
        root.addView(status);

        ScrollView scroll = new ScrollView(context);
        messages = new LinearLayout(context);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(10, 8, 10, 8);
        scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout sendBar = new LinearLayout(context);
        sendBar.setGravity(Gravity.CENTER_VERTICAL);
        sendBar.setPadding(8, 6, 8, 6);
        composer = new EditText(context);
        composer.setSingleLine(true);
        composer.setHint("Сообщение");
        sendBar.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        Button send = new Button(context);
        send.setText("Отправить");
        sendBar.addView(send);
        root.addView(sendBar);

        refresh.setOnClickListener(v -> loadMessages());
        send.setOnClickListener(v -> sendMessage());
        fragmentView = root;
        loadMessages();
        pollHandler.postDelayed(pollRunnable, 5000);
        return fragmentView;
    }

    private void loadMessages() {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET", "/v1/chats/" + chatId + "/messages", null, true);
                JSONArray rows = result.optJSONArray("messages");
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (messages == null) return;
                    messages.removeAllViews();
                    int count = rows == null ? 0 : rows.length();
                    status.setText(count == 0 ? "Сообщений пока нет" : "Сообщений: " + count);
                    for (int i = 0; i < count; i++) {
                        JSONObject item = rows.optJSONObject(i);
                        if (item == null) continue;
                        TextView bubble = new TextView(getParentActivity());
                        bubble.setText(item.optString("username", "Пользователь") + "\n" + item.optString("body", ""));
                        bubble.setTextColor(Color.rgb(30, 38, 48));
                        bubble.setTextSize(15);
                        bubble.setPadding(14, 10, 14, 10);
                        bubble.setBackgroundColor(Color.WHITE);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                        lp.bottomMargin = 6;
                        messages.addView(bubble, lp);
                    }
                });
            } catch (Exception e) {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> { if (status != null) status.setText("Ошибка: " + e.getMessage()); });
            }
        });
    }

    private void showChatProfile() {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET", "/v1/chats/" + chatId, null, true);
                JSONObject chat = result.optJSONObject("chat");
                JSONArray members = result.optJSONArray("members");
                boolean isOwner = result.optBoolean("is_owner", false);
                StringBuilder info = new StringBuilder(chatTitle);
                if (chat != null) {
                    String description = chat.optString("description", "");
                    if (!description.isEmpty()) info.append("\n\n").append(description);
                    info.append("\n\nТип: ").append(chat.optString("kind", "group"));
                    String cover = chat.optString("cover_url", "");
                    if (!cover.isEmpty()) info.append("\nОбложка: задана");
                }
                info.append("\nУчастников: ").append(members == null ? 0 : members.length());
                if (members != null) {
                    for (int i = 0; i < members.length(); i++) {
                        JSONObject member = members.optJSONObject(i);
                        if (member != null) info.append("\n@").append(member.optString("username", "user"));
                    }
                }
                String text = info.toString();
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (getParentActivity() == null) return;
                    if (!isOwner) {
                        new AlertDialog.Builder(getParentActivity()).setTitle("Профиль чата")
                            .setMessage(text).setPositiveButton("Закрыть", null).show();
                        return;
                    }
                    new AlertDialog.Builder(getParentActivity()).setTitle("Профиль чата")
                        .setMessage(text)
                        .setItems(new String[]{"Изменить название и обложку", "Добавить участника", "Закрыть"},
                            (dialog, which) -> {
                                if (which == 0) editChatProfile(chat);
                                else if (which == 1) addChatMember();
                            }).show();
                });
            } catch (Exception e) {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> { if (status != null) status.setText("Не удалось открыть профиль: " + e.getMessage()); });
            }
        });
    }

    private void editChatProfile(JSONObject chat) {
        if (getParentActivity() == null || chat == null) return;
        LinearLayout form = new LinearLayout(getParentActivity());
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(20, 8, 20, 4);
        EditText title = new EditText(getParentActivity());
        title.setSingleLine(true);
        title.setHint("Название");
        title.setText(chat.optString("title", chatTitle));
        EditText description = new EditText(getParentActivity());
        description.setHint("Описание");
        description.setText(chat.optString("description", ""));
        EditText cover = new EditText(getParentActivity());
        cover.setSingleLine(true);
        cover.setHint("URL обложки");
        cover.setText(chat.optString("cover_url", ""));
        form.addView(title);
        form.addView(description);
        form.addView(cover);
        new AlertDialog.Builder(getParentActivity()).setTitle("Редактировать чат")
            .setView(form).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", (d, w) -> executor.execute(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("title", title.getText().toString().trim());
                    body.put("description", description.getText().toString().trim());
                    body.put("cover_url", cover.getText().toString().trim());
                    FreeGramApi.request("PATCH", "/v1/chats/" + chatId, body, true);
                    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    main.post(() -> status.setText("Профиль чата обновлён"));
                } catch (Exception e) {
                    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    main.post(() -> { if (status != null) status.setText("Не удалось сохранить: " + e.getMessage()); });
                }
            })).show();
    }

    private void addChatMember() {
        EditText username = new EditText(getParentActivity());
        username.setSingleLine(true);
        username.setHint("@username");
        new AlertDialog.Builder(getParentActivity()).setTitle("Добавить участника")
            .setView(username).setNegativeButton("Отмена", null)
            .setPositiveButton("Добавить", (d, w) -> executor.execute(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("username", username.getText().toString().trim().replace("@", ""));
                    FreeGramApi.request("POST", "/v1/chats/" + chatId + "/members", body, true);
                    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    main.post(() -> status.setText("Участник добавлен"));
                } catch (Exception e) {
                    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    main.post(() -> { if (status != null) status.setText("Не удалось добавить участника: " + e.getMessage()); });
                }
            })).show();
    }

    private String giftIcon(String code) {
        if ("rose".equals(code)) return "🌹";
        if ("heart".equals(code)) return "❤️";
        if ("crown".equals(code)) return "👑";
        if ("dragon".equals(code)) return "🐉";
        return "🎁";
    }

    private void showChatGifts() {
        executor.execute(() -> {
            try {
                JSONObject me = FreeGramApi.request("GET", "/v1/me", null, true);
                JSONObject details = FreeGramApi.request("GET", "/v1/chats/" + chatId, null, true);
                JSONArray members = details.optJSONArray("members");
                String myName = me.optString("username", "");
                String recipient = "";
                if (members != null) {
                    for (int i = 0; i < members.length(); i++) {
                        JSONObject member = members.optJSONObject(i);
                        if (member != null && !member.optString("username", "").equalsIgnoreCase(myName)) {
                            recipient = member.optString("username", "");
                            break;
                        }
                    }
                }
                final String recipientName = recipient;
                JSONObject catalog = FreeGramApi.request("GET", "/v1/shop/gifts", null, true);
                JSONArray gifts = catalog.optJSONArray("gifts");
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (getParentActivity() == null) return;
                    if (recipientName.isEmpty() || gifts == null || gifts.length() == 0) {
                        status.setText("В этом чате пока нельзя отправить подарок.");
                        return;
                    }
                    String[] labels = new String[gifts.length()];
                    for (int i = 0; i < gifts.length(); i++) {
                        JSONObject item = gifts.optJSONObject(i);
                        labels[i] = giftIcon(item.optString("code")) + "  " + item.optString("title") + " · " + item.optLong("price") + " Stars · " + item.optString("rarity");
                    }
                    new AlertDialog.Builder(getParentActivity()).setTitle("Подарок для @" + recipientName)
                        .setItems(labels, (dialog, which) -> executor.execute(() -> {
                            try {
                                JSONObject item = gifts.optJSONObject(which);
                                JSONObject body = new JSONObject();
                                body.put("gift_code", item.optString("code"));
                                body.put("recipient_username", recipientName);
                                body.put("chat_id", chatId);
                                JSONObject sent = FreeGramApi.request("POST", "/v1/shop/gifts/send", body, true);
                                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                                h.post(() -> status.setText("Подарок отправлен · баланс: " + sent.optLong("remaining_stars") + " Stars"));
                            } catch (Exception e) {
                                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                                h.post(() -> { if (status != null) status.setText("Не удалось отправить подарок: " + e.getMessage()); });
                            }
                        })).setNegativeButton("Закрыть", null).show();
                });
            } catch (Exception e) {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> { if (status != null) status.setText("Ошибка магазина: " + e.getMessage()); });
            }
        });
    }

    private void createInvite() {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("POST", "/v1/chats/" + chatId + "/invite", new JSONObject(), true);
                String link = result.optString("invite_link", "");
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (getParentActivity() == null || link.isEmpty()) return;
                    ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("FreeGram invite", link));
                    status.setText("Ссылка-приглашение скопирована: " + link);
                });
            } catch (Exception e) {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> { if (status != null) status.setText("Ссылка доступна только владельцу чата: " + e.getMessage()); });
            }
        });
    }

    private void sendMessage() {
        String value = composer.getText().toString().trim();
        if (value.isEmpty()) return;
        composer.setEnabled(false);
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("text", value);
                FreeGramApi.request("POST", "/v1/chats/" + chatId + "/messages", body, true);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (composer == null) return;
                    composer.setText("");
                    composer.setEnabled(true);
                    loadMessages();
                });
            } catch (Exception e) {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    if (composer != null) composer.setEnabled(true);
                    if (status != null) status.setText("Не отправлено: " + e.getMessage());
                });
            }
        });
    }

    @Override
    public void onFragmentDestroy() {
        pollHandler.removeCallbacks(pollRunnable);
        executor.shutdownNow();
        messages = null;
        composer = null;
        super.onFragmentDestroy();
    }
}
