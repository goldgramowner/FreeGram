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
        Button invite = new Button(context);
        invite.setText("Ссылка");
        header.addView(invite);
        Button refresh = new Button(context);
        refresh.setText("Обновить");
        header.addView(refresh);
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
        return fragmentView;
    }

    private void loadMessages() {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET", "/v1/chats/" + chatId + "/messages", null, true);
                JSONArray rows = result.optJSONArray("messages");
                android.os.Handler main = new android.os.Handler(getParentActivity().getMainLooper());
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
                android.os.Handler main = new android.os.Handler(getParentActivity().getMainLooper());
                main.post(() -> { if (status != null) status.setText("Ошибка: " + e.getMessage()); });
            }
        });
    }

    private void createInvite() {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("POST", "/v1/chats/" + chatId + "/invite", new JSONObject(), true);
                String link = result.optString("invite_link", "");
                android.os.Handler main = new android.os.Handler(getParentActivity().getMainLooper());
                main.post(() -> {
                    if (getParentActivity() == null || link.isEmpty()) return;
                    ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("FreeGram invite", link));
                    status.setText("Ссылка-приглашение скопирована: " + link);
                });
            } catch (Exception e) {
                android.os.Handler main = new android.os.Handler(getParentActivity().getMainLooper());
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
                android.os.Handler main = new android.os.Handler(getParentActivity().getMainLooper());
                main.post(() -> {
                    if (composer == null) return;
                    composer.setText("");
                    composer.setEnabled(true);
                    loadMessages();
                });
            } catch (Exception e) {
                android.os.Handler main = new android.os.Handler(getParentActivity().getMainLooper());
                main.post(() -> {
                    if (composer != null) composer.setEnabled(true);
                    if (status != null) status.setText("Не отправлено: " + e.getMessage());
                });
            }
        });
    }

    @Override
    public void onFragmentDestroy() {
        executor.shutdownNow();
        messages = null;
        composer = null;
        super.onFragmentDestroy();
    }
}
