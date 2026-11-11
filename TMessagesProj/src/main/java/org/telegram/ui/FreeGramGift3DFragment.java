package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;

public class FreeGramGift3DFragment extends BaseFragment {
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private WebView webView;
    private LinearLayout root;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(9, 13, 25));
        TextView heading = new TextView(context);
        heading.setText("FREEGRAM · COLLECTIBLE GIFTS");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(19);
        heading.setPadding(18, 16, 18, 10);
        root.addView(heading);
        ProgressBar progress = new ProgressBar(context);
        root.addView(progress);
        webView = new WebView(context);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        fragmentView = root;
        loadCatalog(progress);
        return fragmentView;
    }

    private void loadCatalog(ProgressBar progress) {
        executor.execute(() -> {
            String payload = "[]";
            String error = "";
            try {
                JSONObject response = FreeGramApi.request("GET", "/v1/shop/gifts", null, true);
                JSONArray gifts = response.optJSONArray("gifts");
                if (gifts != null) payload = gifts.toString();
            } catch (Exception e) { error = e.getMessage() == null ? "Не удалось загрузить каталог" : e.getMessage(); }
            final String json = payload;
            final String message = error;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (webView == null) return;
                if (progress != null && progress.getParent() == root) root.removeView(progress);
                webView.loadDataWithBaseURL("https://freegram.app/", page(json, message),
                        "text/html", "UTF-8", null);
            });
        });
    }

    private String page(String gifts, String error) {
        String safe = gifts.replace("</", "<\\/");
        String err = error.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
        + "<script type='module' src='https://unpkg.com/@google/model-viewer/dist/model-viewer.min.js'></script>"
        + "<style>*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 50% 0,#253b67,#090d19 62%);color:#f5f7ff;font:14px Arial}"
        + ".intro{padding:14px 16px;color:#b9c9e8}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:11px;padding:10px}"
        + ".card{background:linear-gradient(150deg,#202c45,#111827);border:1px solid #354766;border-radius:17px;padding:10px;min-width:0;box-shadow:0 8px 22px #0006}"
        + ".stage{height:155px;border-radius:12px;background:radial-gradient(circle,#344d78,#111827 70%);display:flex;align-items:center;justify-content:center;overflow:hidden}"
        + "model-viewer{width:100%;height:100%;--poster-color:transparent;background:transparent}.fallback{font-size:68px;filter:drop-shadow(0 12px 10px #0009);animation:turn 5s linear infinite}"
        + "@keyframes turn{0%{transform:perspective(350px) rotateY(0) rotateX(-8deg)}100%{transform:perspective(350px) rotateY(360deg) rotateX(-8deg)}}"
        + ".name{font-weight:700;font-size:15px;margin-top:10px}.meta{color:#9db0d2;font-size:12px;margin:5px 0}.price{color:#ffd778;font-weight:700}.pill{display:inline-block;border:1px solid #647ba4;border-radius:20px;padding:3px 7px;font-size:10px;color:#c8d7f3;margin-top:6px}"
        + ".hint{padding:10px 16px;color:#91a6c8;font-size:12px}.empty{padding:25px;text-align:center;color:#b8c6df}</style></head><body>"
        + "<div class='intro'>Вращайте модель пальцем. Если для подарка подключён GLB/GLTF, откроется настоящая 3D-модель; иначе показан объёмный предпросмотр.</div>"
        + "<div id='grid' class='grid'></div><div class='hint'>3D-модели подключаются через model_url (GLB/GLTF). Анимация — через animation_url.</div>"
        + "<script>const gifts=" + safe + ";const grid=document.getElementById('grid');const icons={rose:'🌹',heart:'💖',crown:'👑',dragon:'🐉',golden_rose:'🌹',ruby_heart:'❤️',neon_crown:'👑',cosmic_dragon:'🐲',crystal_ball:'🔮',golden_key:'🗝️',moon_cat:'🐈‍⬛',star_ship:'🚀',diamond_ring:'💍',phoenix:'🦅',sapphire:'💎',emerald:'🟢',ruby:'🔴',magic_lamp:'🪔',black_hole:'🌌',golden_trophy:'🏆',butterfly:'🦋',ice_crystal:'❄️',samurai_mask:'🎭',royal_car:'🏎️'};"
        + "if(!gifts.length){grid.innerHTML='<div class=empty>Каталог подарков пока пуст или недоступен.</div>'}else gifts.forEach(g=>{const card=document.createElement('section');card.className='card';const stage=document.createElement('div');stage.className='stage';if(g.model_url){const m=document.createElement('model-viewer');m.setAttribute('src',g.model_url);m.setAttribute('camera-controls','');m.setAttribute('auto-rotate','');m.setAttribute('shadow-intensity','1');if(g.animation_url)m.setAttribute('ios-src',g.animation_url);stage.appendChild(m)}else{const fallback=document.createElement('div');fallback.className='fallback';fallback.textContent=icons[g.code]||'🎁';stage.appendChild(fallback)}"
        + "const name=document.createElement('div');name.className='name';name.textContent=g.title||g.code;const meta=document.createElement('div');meta.className='meta';meta.textContent=(g.collection_name||'FreeGram Gifts')+' · '+(g.rarity||'common');const price=document.createElement('div');price.className='price';price.textContent=(g.price||0)+' Stars';const pill=document.createElement('div');pill.className='pill';pill.textContent=g.is_collectible?'COLLECTIBLE NFT':'GIFT';card.append(stage,name,meta,price,pill);grid.appendChild(card)});</script></body></html>";
    }

    @Override
    public void onFragmentDestroy() {
        if (webView != null) { webView.stopLoading(); webView.destroy(); webView = null; }
        executor.shutdownNow();
        super.onFragmentDestroy();
    }
}