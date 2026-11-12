package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;

public class FreeGramGift3DFragment extends BaseFragment {
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private LinearLayout root;
    private ProgressBar progress;
    private String currentSection = "catalog";

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(9, 13, 25));
        TextView heading = new TextView(context);
        heading.setText("FreeGram · Подарки");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(20);
        heading.setPadding(18, 16, 18, 10);
        root.addView(heading);
        progress = new ProgressBar(context);
        root.addView(progress);
        webView = new WebView(context);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        webView.addJavascriptInterface(new GiftBridge(), "FreeGramBridge");
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        fragmentView = root;
        loadSection("catalog");
        return fragmentView;
    }

    private void loadSection(String section) {
        currentSection = section;
        executor.execute(() -> {
            String payload = "{}";
            String error = "";
            try {
                if (!FreeGramApi.ensureDeviceSession()) throw new Exception("Не удалось подключиться к FreeGram");
                JSONObject response;
                if ("market".equals(section)) {
                    response = FreeGramApi.request("GET", "/v1/shop/market", null, true);
                } else if ("mine".equals(section)) {
                    response = FreeGramApi.request("GET", "/v1/shop/my-gifts", null, true);
                } else {
                    response = FreeGramApi.request("GET", "/v1/shop/gifts", null, true);
                }
                payload = response.toString();
            } catch (Exception e) {
                error = e.getMessage() == null ? "Не удалось загрузить данные" : e.getMessage();
            }
            final String json = payload;
            final String message = error;
            main.post(() -> {
                if (webView == null) return;
                if (progress != null && progress.getParent() == root) root.removeView(progress);
                webView.loadDataWithBaseURL("https://freegram.app/", page(section, json, message), "text/html", "UTF-8", null);
            });
        });
    }

    private class GiftBridge {
        @JavascriptInterface public void buyGift(String code) {
            runAction(() -> {
                JSONObject body = new JSONObject();
                body.put("gift_code", code);
                JSONObject result = FreeGramApi.request("POST", "/v1/shop/gifts/buy", body, true);
                return "Подарок куплен · потрачено Stars: " + result.optLong("spent_stars")
                    + " · остаток: " + result.optLong("remaining_stars");
            }, "catalog");
        }
        @JavascriptInterface public void buyListing(long listingId) {
            runAction(() -> {
                JSONObject result = FreeGramApi.request("POST", "/v1/shop/market/" + listingId + "/buy", new JSONObject(), true);
                return "Подарок приобретён · остаток Stars: " + result.optLong("remaining_stars");
            }, "market");
        }
        @JavascriptInterface public void listGift(long giftId, int price) {
            runAction(() -> {
                JSONObject body = new JSONObject();
                body.put("gift_id", giftId);
                body.put("price_stars", price);
                FreeGramApi.request("POST", "/v1/shop/market/list", body, true);
                return "Подарок выставлен на маркет";
            }, "mine");
        }
        @JavascriptInterface public void cancelListing(long listingId) {
            runAction(() -> {
                FreeGramApi.request("DELETE", "/v1/shop/market/" + listingId, null, true);
                return "Объявление снято с маркета";
            }, "market");
        }
        @JavascriptInterface public void openSection(String section) {
            if ("catalog".equals(section) || "market".equals(section) || "mine".equals(section)) loadSection(section);
        }
    }

    private interface Action { String run() throws Exception; }
    private void runAction(Action action, String refreshSection) {
        executor.execute(() -> {
            try {
                String result = action.run();
                main.post(() -> {
                    if (getParentActivity() != null) Toast.makeText(getParentActivity(), result, Toast.LENGTH_LONG).show();
                    loadSection(refreshSection);
                });
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "Операция не выполнена" : e.getMessage();
                main.post(() -> {
                    if (getParentActivity() != null) Toast.makeText(getParentActivity(), message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String page(String section, String response, String error) {
        String safe = response.replace("</", "<\\/");
        String safeError = JSONObject.quote(error == null ? "" : error);
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
        + "<script type='module' src='https://unpkg.com/@google/model-viewer/dist/model-viewer.min.js'></script>"
        + "<style>*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 50% 0,#253b67,#090d19 62%);color:#f5f7ff;font:14px Arial}"
        + ".nav{display:flex;gap:7px;padding:10px;position:sticky;top:0;background:#101a2beF}.nav button,.buy{border:0;border-radius:10px;padding:10px;background:#29456d;color:white;font-weight:700}.nav button.active,.buy{background:#2878d0}"
        + ".intro{padding:8px 14px;color:#b9c9e8}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:11px;padding:10px}"
        + ".card{background:linear-gradient(150deg,#202c45,#111827);border:1px solid #354766;border-radius:17px;padding:10px;min-width:0;box-shadow:0 8px 22px #0006}"
        + ".stage{height:145px;border-radius:12px;background:radial-gradient(circle,#344d78,#111827 70%);display:flex;align-items:center;justify-content:center;overflow:hidden}"
        + "model-viewer{width:100%;height:100%;--poster-color:transparent;background:transparent}.fallback{font-size:68px;filter:drop-shadow(0 12px 10px #0009);animation:turn 5s linear infinite}"
        + "@keyframes turn{0%{transform:perspective(350px) rotateY(0) rotateX(-8deg)}100%{transform:perspective(350px) rotateY(360deg) rotateX(-8deg)}}"
        + ".name{font-weight:700;font-size:15px;margin-top:10px}.meta{color:#9db0d2;font-size:12px;margin:5px 0}.price{color:#ffd778;font-weight:700;margin:7px 0}.pill{display:inline-block;border:1px solid #647ba4;border-radius:20px;padding:3px 7px;font-size:10px;color:#c8d7f3;margin-top:6px}.buy{width:100%;margin-top:9px}.empty{padding:25px;text-align:center;color:#b8c6df;grid-column:1/-1}.error{padding:15px;color:#ffb4b4}</style></head><body>"
        + "<div class='nav'><button onclick=\"FreeGramBridge.openSection('catalog')\" class='" + ("catalog".equals(section) ? "active" : "") + "'>Подарки</button>"
        + "<button onclick=\"FreeGramBridge.openSection('market')\" class='" + ("market".equals(section) ? "active" : "") + "'>Маркет</button>"
        + "<button onclick=\"FreeGramBridge.openSection('mine')\" class='" + ("mine".equals(section) ? "active" : "") + "'>Мои подарки</button></div>"
        + "<div class='intro'>Покупка, коллекция и торговая площадка FreeGram за Stars.</div><div id='grid' class='grid'></div>"
        + "<script>const data=" + safe + ";const err=" + safeError + ";const section='" + section + "';const grid=document.getElementById('grid');"
        + "const icons={rose:'🌹',heart:'💖',crown:'👑',dragon:'🐉',golden_rose:'🌹',ruby_heart:'❤️',neon_crown:'👑',cosmic_dragon:'🐲',crystal_ball:'🔮',golden_key:'🗝️',moon_cat:'🐈‍⬛',star_ship:'🚀',diamond_ring:'💍',phoenix:'🦅',sapphire:'💎',emerald:'🟢',ruby:'🔴',magic_lamp:'🪔',black_hole:'🌌',golden_trophy:'🏆',butterfly:'🦋',ice_crystal:'❄️',samurai_mask:'🎭',royal_car:'🏎️'};"
        + "function empty(t){grid.innerHTML='<div class=empty>'+t+'</div>'}function card(g,mode){const c=document.createElement('section');c.className='card';const stage=document.createElement('div');stage.className='stage';if(g.model_url){const m=document.createElement('model-viewer');m.setAttribute('src',g.model_url);m.setAttribute('camera-controls','');m.setAttribute('auto-rotate','');stage.appendChild(m)}else{const f=document.createElement('div');f.className='fallback';f.textContent=icons[g.code]||'🎁';stage.appendChild(f)}const n=document.createElement('div');n.className='name';n.textContent=g.title||g.code||'Подарок';const meta=document.createElement('div');meta.className='meta';meta.textContent=(g.collection_name||'FreeGram Gifts')+' · '+(g.rarity||'common');const p=document.createElement('div');p.className='price';let btn=document.createElement('button');btn.className='buy';if(mode==='catalog'){p.textContent=(g.price||0)+' Stars';btn.textContent='Купить за Stars';btn.onclick=()=>FreeGramBridge.buyGift(g.code)}else if(mode==='market'){p.textContent=(g.price_stars||0)+' Stars · продавец @'+(g.seller_username||'');btn.textContent='Купить';btn.onclick=()=>FreeGramBridge.buyListing(g.listing_id)}else{p.textContent='Уровень '+(g.upgrade_level||0)+(g.serial_number?' · '+g.serial_number:'');btn.textContent='Выставить на маркет';btn.onclick=()=>{const v=prompt('Цена в Stars');if(v&&/^\\d+$/.test(v)&&Number(v)>0)FreeGramBridge.listGift(g.id,Number(v))}}c.append(stage,n,meta,p);if(g.is_collectible)c.insertAdjacentHTML('beforeend','<div class=pill>NFT · COLLECTIBLE</div>');c.appendChild(btn);grid.appendChild(c)}"
        + "if(err){grid.innerHTML='<div class=error>'+err+'</div>'}else{const arr=section==='catalog'?(data.gifts||[]):section==='market'?(data.listings||[]):(data.gifts||[]);if(!arr.length)empty(section==='catalog'?'Каталог подарков пока пуст':section==='market'?'На маркете пока нет предложений':'У вас пока нет подарков');else arr.forEach(g=>card(g,section))}</script></body></html>";
    }

    @Override
    public void onFragmentDestroy() {
        if (webView != null) { webView.stopLoading(); webView.removeJavascriptInterface("FreeGramBridge"); webView.destroy(); webView = null; }
        executor.shutdownNow();
        super.onFragmentDestroy();
    }
}