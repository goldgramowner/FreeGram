package org.telegram.ui;

import android.content.Context;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.Button;
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
        LinearLayout actions = new LinearLayout(context);
        actions.setPadding(12, 4, 12, 8);
        Button group = new Button(context); group.setText("Создать группу");
        Button channel = new Button(context); channel.setText("Создать канал");
        Button direct = new Button(context); direct.setText("Написать");
        actions.addView(group, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(channel, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(direct, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);
        group.setOnClickListener(v -> promptCreate("group"));
        channel.setOnClickListener(v -> promptCreate("channel"));
        direct.setOnClickListener(v -> promptCreate("direct"));
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

    private void promptCreate(String kind) {
        if (getParentActivity() == null) return;
        EditText input = new EditText(getParentActivity());
        input.setSingleLine(true);
        input.setHint(kind.equals("direct") ? "Username без @" : "Название");
        String title = kind.equals("direct") ? "Новый личный чат" : (kind.equals("channel") ? "Новый канал" : "Новая группа");
        new AlertDialog.Builder(getParentActivity()).setTitle(title).setView(input)
            .setNegativeButton("Отмена", null).setPositiveButton("Создать", (d, w) -> createChat(kind, input.getText().toString().trim()))
            .show();
    }

    private void createChat(String kind, String value) {
        if (value.isEmpty()) { Toast.makeText(getParentActivity(), "Заполни поле", Toast.LENGTH_SHORT).show(); return; }
        executor.execute(() -> {
            String error = null;
            try {
                if (!FreeGramApi.ensureDeviceSession()) throw new Exception("Нет соединения с FreeGram");
                JSONObject body = new JSONObject(); body.put("kind", kind);
                if (kind.equals("direct")) body.put("username", value.replaceFirst("^@", "")); else body.put("title", value);
                JSONObject result = FreeGramApi.request("POST", "/v1/chats", body, true);
                if (!result.optBoolean("ok", false)) throw new Exception("Не удалось создать чат");
            } catch (Exception e) { error = e.getMessage() == null ? "Ошибка создания" : e.getMessage(); }
            final String failure = error;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (getParentActivity() == null) return;
                if (failure != null) Toast.makeText(getParentActivity(), failure, Toast.LENGTH_LONG).show();
                else { Toast.makeText(getParentActivity(), "Готово", Toast.LENGTH_SHORT).show(); loadChats(); }
            });
        });
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