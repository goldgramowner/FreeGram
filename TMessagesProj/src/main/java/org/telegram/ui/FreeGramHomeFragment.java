package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FreeGramHomeFragment extends BaseFragment {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView status;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 248, 251));
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(18, 16, 18, 16);
        TextView title = new TextView(context);
        title.setText("FreeGram");
        title.setTextColor(Color.rgb(28, 100, 190));
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button logout = new Button(context);
        logout.setText("Выйти");
        header.addView(logout);
        root.addView(header);
        logout.setOnClickListener(v -> {
            FreeGramApi.clearToken();
            presentFragment(new FreeGramAuthFragment(), true);
        });
        status = new TextView(context);
        status.setText("Загружаем чаты…");
        status.setTextColor(Color.DKGRAY);
        status.setPadding(18, 8, 18, 8);
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
                android.os.Handler main = new android.os.Handler(getParentActivity().getMainLooper());
                main.post(() -> {
                    if (list == null) return;
                    list.removeAllViews();
                    int count = chats == null ? 0 : chats.length();
                    status.setText(count == 0 ? "Пока нет чатов. Найдите пользователя и начните общение." : "Ваши чаты");
                    for (int i = 0; i < count; i++) {
                        JSONObject chat = chats.optJSONObject(i);
                        if (chat == null) continue;
                        String name = chat.optString("title", chat.optString("other_username", "Чат"));
                        String last = chat.optString("last_message", "");
                        TextView row = new TextView(getParentActivity());
                        row.setText(name + (last.isEmpty() ? "" : "\n" + last));
                        row.setTextColor(Color.rgb(30, 35, 42));
                        row.setTextSize(16);
                        row.setPadding(20, 18, 20, 18);
                        row.setBackgroundColor(Color.WHITE);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                        lp.bottomMargin = 1;
                        final long chatId = chat.optLong("id", -1);
                        final String chatTitle = name;
                        row.setOnClickListener(v -> {
                            if (chatId > 0) presentFragment(new FreeGramChatFragment(chatId, chatTitle), true);
                        });
                        list.addView(row, lp);
                    }
                });
            } catch (Exception e) {
                android.os.Handler main = new android.os.Handler(getParentActivity().getMainLooper());
                main.post(() -> status.setText(e.getMessage() == null ? "Ошибка загрузки чатов" : e.getMessage()));
            }
        });
    }

    @Override
    public void onFragmentDestroy() {
        executor.shutdownNow();
        list = null;
        super.onFragmentDestroy();
    }
}
