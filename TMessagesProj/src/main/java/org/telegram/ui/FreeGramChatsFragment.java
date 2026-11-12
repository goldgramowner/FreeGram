package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;

public class FreeGramChatsFragment extends BaseFragment {
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private LinearLayout rows;
    private TextView status;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 248, 252));
        TextView title = new TextView(context);
        title.setText("Чаты FreeGram");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(28, 45, 70));
        title.setPadding(18, 18, 18, 8);
        root.addView(title);
        status = new TextView(context);
        status.setText("Загружаем чаты…");
        status.setTextColor(Color.GRAY);
        status.setPadding(18, 4, 18, 12);
        root.addView(status);
        ScrollView scroll = new ScrollView(context);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        fragmentView = root;
        loadChats();
        return fragmentView;
    }

    private void loadChats() {
        executor.execute(() -> {
            JSONArray chats = new JSONArray();
            String error = null;
            try {
                if (!FreeGramApi.ensureDeviceSession()) throw new Exception("Нет соединения с FreeGram");
                chats = FreeGramApi.request("GET", "/v1/chats", null, true).optJSONArray("chats");
                if (chats == null) chats = new JSONArray();
            } catch (Exception e) {
                error = e.getMessage() == null ? "Не удалось загрузить чаты" : e.getMessage();
            }
            final JSONArray result = chats;
            final String failure = error;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (rows == null || status == null) return;
                rows.removeAllViews();
                if (failure != null) {
                    status.setText(failure);
                    return;
                }
                status.setText("Чатов и каналов: " + result.length());
                if (result.length() == 0) {
                    TextView empty = new TextView(getParentActivity());
                    empty.setText("Пока нет чатов. Создай группу или канал через меню.");
                    empty.setTextColor(Color.GRAY);
                    empty.setPadding(18, 22, 18, 22);
                    rows.addView(empty);
                    return;
                }
                for (int i = 0; i < result.length(); i++) {
                    JSONObject chat = result.optJSONObject(i);
                    if (chat == null) continue;
                    final long id = chat.optLong("id");
                    final String kind = chat.optString("kind", "group");
                    final String name = chat.optString("title", "");
                    final String display = name.isEmpty() ? (kind.equals("channel") ? "Канал" : "Чат") : name;
                    TextView row = new TextView(getParentActivity());
                    String preview = chat.optString("last_message", "");
                    row.setText((kind.equals("channel") ? "◉  " : "●  ") + display
                        + (chat.optInt("verified") == 1 ? "  ✓" : "")
                        + (preview.isEmpty() ? "" : "\\n" + preview));
                    row.setTextSize(16);
                    row.setTextColor(Color.rgb(35, 48, 68));
                    row.setPadding(18, 16, 18, 16);
                    row.setBackgroundColor(Color.WHITE);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.bottomMargin = 1;
                    rows.addView(row, lp);
                    row.setOnClickListener(v -> presentFragment(new FreeGramChatFragment(id, display), true));
                }
            });
        });
    }

    @Override
    public void onFragmentDestroy() {
        executor.shutdownNow();
        rows = null;
        status = null;
        super.onFragmentDestroy();
    }
}