package org.telegram.ui;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FreeGramChatFragment extends BaseFragment {
    private final long chatId;
    private final String chatTitle;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout messages;
    private ScrollView messageScroll;
    private EditText composer;
    private TextView status;
    private volatile boolean loadingMessages = false;
    private long lastMessageId = 0;

    private final Runnable pollMessages = new Runnable() {
        @Override
        public void run() {
            if (messages == null) return;
            loadMessages(false);
            mainHandler.postDelayed(this, 4000);
        }
    };

    public FreeGramChatFragment(long id, String title) {
        chatId = id;
        chatTitle = title == null || title.isEmpty() ? "Чат" : title;
    }

    private int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable bubbleBackground(Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.WHITE);
        drawable.setCornerRadius(dp(context, 16));
        return drawable;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(239, 244, 249));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        TextView title = new TextView(context);
        title.setText(chatTitle);
        title.setTextColor(Color.rgb(25, 35, 48));
        title.setTextSize(19);
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button invite = new Button(context);
        invite.setText("Ссылка");
        header.addView(invite);
        Button refresh = new Button(context);
        refresh.setText("↻");
        header.addView(refresh);
        invite.setOnClickListener(v -> createInvite());
        refresh.setOnClickListener(v -> loadMessages(true));
        root.addView(header);

        status = new TextView(context);
        status.setTextColor(Color.rgb(95, 110, 128));
        status.setTextSize(12);
        status.setPadding(dp(context, 14), dp(context, 4), dp(context, 14), dp(context, 4));
        root.addView(status);

        messageScroll = new ScrollView(context);
        messageScroll.setFillViewport(true);
        messages = new LinearLayout(context);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        messageScroll.addView(messages, new ScrollView.LayoutParams(-1, -2));
        root.addView(messageScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout sendBar = new LinearLayout(context);
        sendBar.setGravity(Gravity.CENTER_VERTICAL);
        sendBar.setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6));
        sendBar.setBackgroundColor(Color.WHITE);
        composer = new EditText(context);
        composer.setSingleLine(true);
        composer.setTextSize(16);
        composer.setHint("Сообщение");
        sendBar.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        Button send = new Button(context);
        send.setText("➤");
        send.setAllCaps(false);
        sendBar.addView(send);
        root.addView(sendBar);

        send.setOnClickListener(v -> sendMessage());
        composer.setOnEditorActionListener((v, actionId, event) -> {
            sendMessage();
            return true;
        });
        fragmentView = root;
        loadMessages(true);
        mainHandler.postDelayed(pollMessages, 4000);
        return fragmentView;
    }

    private void loadMessages(boolean reset) {
        if (loadingMessages || messages == null) return;
        loadingMessages = true;
        final long after = reset ? 0 : lastMessageId;
        if (reset) {
            lastMessageId = 0;
            messages.removeAllViews();
        }
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("GET",
                        "/v1/chats/" + chatId + "/messages?after_id=" + after, null, true);
                JSONArray rows = result.optJSONArray("messages");
                mainHandler.post(() -> {
                    loadingMessages = false;
                    if (messages == null) return;
                    int count = rows == null ? 0 : rows.length();
                    if (after == 0) {
                        status.setText(count == 0 ? "Повідомлень поки немає" : "FreeGram · " + count + " повідомлень");
                    } else if (count > 0) {
                        status.setText("Нові повідомлення: " + count);
                    }
                    for (int i = 0; i < count; i++) {
                        JSONObject item = rows.optJSONObject(i);
                        if (item == null) continue;
                        long id = item.optLong("id", 0);
                        if (id > lastMessageId) lastMessageId = id;
                        TextView bubble = new TextView(getParentActivity());
                        String username = item.optString("username", "Користувач");
                        String body = item.optString("body", "");
                        bubble.setText(username + "\n" + body);
                        bubble.setTextColor(Color.rgb(30, 38, 48));
                        bubble.setTextSize(15);
                        bubble.setPadding(dp(getParentActivity(), 14), dp(getParentActivity(), 10),
                                dp(getParentActivity(), 14), dp(getParentActivity(), 10));
                        bubble.setBackground(bubbleBackground(getParentActivity()));
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                        lp.bottomMargin = dp(getParentActivity(), 6);
                        messages.addView(bubble, lp);
                    }
                    if (count > 0 && messageScroll != null) {
                        messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    loadingMessages = false;
                    if (status != null) status.setText("Помилка з'єднання: " +
                            (e.getMessage() == null ? "FreeGram API" : e.getMessage()));
                });
            }
        });
    }

    private void createInvite() {
        executor.execute(() -> {
            try {
                JSONObject result = FreeGramApi.request("POST", "/v1/chats/" + chatId + "/invite",
                        new JSONObject(), true);
                String link = result.optString("invite_link", "");
                mainHandler.post(() -> {
                    if (getParentActivity() == null || link.isEmpty()) return;
                    ClipboardManager clipboard = (ClipboardManager) getParentActivity()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("FreeGram invite", link));
                    status.setText("Посилання-запрошення скопійовано");
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (status != null) status.setText("Не вдалося створити посилання: " + e.getMessage());
                });
            }
        });
    }

    private void sendMessage() {
        if (composer == null) return;
        String value = composer.getText().toString().trim();
        if (value.isEmpty()) return;
        composer.setEnabled(false);
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("text", value);
                FreeGramApi.request("POST", "/v1/chats/" + chatId + "/messages", body, true);
                mainHandler.post(() -> {
                    if (composer == null) return;
                    composer.setText("");
                    composer.setEnabled(true);
                    loadMessages(false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (composer != null) composer.setEnabled(true);
                    if (status != null) status.setText("Не відправлено: " + e.getMessage());
                });
            }
        });
    }

    @Override
    public void onFragmentDestroy() {
        mainHandler.removeCallbacks(pollMessages);
        executor.shutdownNow();
        messages = null;
        messageScroll = null;
        composer = null;
        status = null;
        super.onFragmentDestroy();
    }
}
