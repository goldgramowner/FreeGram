package org.telegram.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FreeGramEntryActivity extends Activity {
    private static final String API = "https://freegram-x.onrender.com";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText username, password;
    private TextView status;
    private ProgressBar progress;
    private boolean busy = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(245,247,250));
        getWindow().setNavigationBarColor(Color.rgb(245,247,250));
        String token = getSharedPreferences("freegram_auth", 0).getString("token", null);
        if (token == null || token.isEmpty()) showAuth();
        else restoreSession(token);
    }

    private LinearLayout root() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(1); l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(28,48,28,24); l.setBackgroundColor(Color.rgb(245,247,250));
        return l;
    }
    private TextView label(String s,int size,int color) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size);
        t.setTextColor(color); t.setGravity(Gravity.CENTER); return t;
    }
    private void showAuth() {
        LinearLayout l=root();
        l.addView(label("FreeGram",32,Color.rgb(35,111,220)),new LinearLayout.LayoutParams(-1,-2));
        l.addView(label("Вход без номера телефона",15,Color.DKGRAY));
        username=new EditText(this); username.setSingleLine(true); username.setHint("Логин (5–24 символа)");
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,58); p.topMargin=28; l.addView(username,p);
        password=new EditText(this); password.setSingleLine(true); password.setHint("Пароль (от 8 символов)"); password.setInputType(129);
        p=new LinearLayout.LayoutParams(-1,58); p.topMargin=10; l.addView(password,p);
        Button reg=new Button(this); reg.setText("Создать аккаунт");
        p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=18; l.addView(reg,p);
        Button login=new Button(this); login.setText("Войти"); l.addView(login,new LinearLayout.LayoutParams(-1,-2));
        progress=new ProgressBar(this); progress.setVisibility(View.GONE); l.addView(progress);
        status=label("",14,Color.DKGRAY); p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=16; l.addView(status,p);
        reg.setOnClickListener(v->submit("/v1/auth/register"));
        login.setOnClickListener(v->submit("/v1/auth/login"));
        setContentView(l);
    }
    private void restoreSession(String token) {
        busy=true; showLoading("Проверяю сохранённый вход...");
        io.execute(()->{
            try {
                JSONObject me=request("/v1/me","GET",null,token);
                String u=me.optString("username",getSharedPreferences("freegram_auth",0).getString("username","user"));
                String id=me.optString("virtual_id",getSharedPreferences("freegram_auth",0).getString("virtual_id",""));
                main.post(()->signedIn(u,id));
            } catch(Exception e) {
                getSharedPreferences("freegram_auth",0).edit().clear().apply();
                main.post(()->{busy=false;showAuth();});
            }
        });
    }
    private void showLoading(String message) {
        LinearLayout l=root(); l.setGravity(Gravity.CENTER);
        l.addView(label("FreeGram",32,Color.rgb(35,111,220)));
        ProgressBar p=new ProgressBar(this); l.addView(p);
        l.addView(label(message,15,Color.DKGRAY)); setContentView(l);
    }
    private JSONObject request(String route,String method,JSONObject data,String token) throws Exception {
        HttpURLConnection c=null;
        try {
            c=(HttpURLConnection)new URL(API+route).openConnection();
            c.setRequestMethod(method); c.setConnectTimeout(20000); c.setReadTimeout(20000);
            c.setRequestProperty("Accept","application/json");
            if(data!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
                try(OutputStream o=c.getOutputStream()){o.write(data.toString().getBytes(StandardCharsets.UTF_8));}}
            if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);
            int code=c.getResponseCode();
            InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
            StringBuilder b=new StringBuilder();
            try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String z;while((z=r.readLine())!=null)b.append(z);}
            JSONObject res=new JSONObject(b.toString());
            if(code<200||code>=300)throw new Exception(res.optString("detail","Ошибка API (HTTP "+code+")"));
            return res;
        } finally {if(c!=null)c.disconnect();}
    }
    private void submit(String route) {
        if(busy)return;
        String u=username.getText().toString().trim().toLowerCase();
        String pw=password.getText().toString();
        if(!u.matches("[a-z0-9_]{5,24}")||pw.length()<8){status.setText("Логин: 5–24 символа (a-z, 0-9, _), пароль: минимум 8");return;}
        busy=true; progress.setVisibility(View.VISIBLE); status.setText("Подключение к FreeGram...");
        io.execute(()->{
            try {
                JSONObject data=new JSONObject(); data.put("username",u); data.put("password",pw);
                JSONObject res=request(route,"POST",data,null);
                String token=res.getString("access_token"), id=res.optString("virtual_id","");
                getSharedPreferences("freegram_auth",0).edit().putString("token",token).putString("virtual_id",id).putString("username",u).apply();
                main.post(()->signedIn(u,id));
            } catch(Exception e) {
                String msg=e.getMessage()==null?"Не удалось подключиться":e.getMessage();
                main.post(()->{busy=false;progress.setVisibility(View.GONE);status.setText("Ошибка: "+msg);});
            }
        });
    }
    private void signedIn(String u,String id) {
        busy=false;
        LinearLayout l=root(); l.setGravity(Gravity.CENTER);
        l.addView(label("FreeGram",32,Color.rgb(35,111,220)));
        l.addView(label("Вы вошли как @"+u,20,Color.DKGRAY));
        l.addView(label(id,15,Color.GRAY));
        TextView note=label("Аккаунт FreeGram авторизован. Независимые чаты и сообщения ещё подключаются.",14,Color.DKGRAY);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=24; l.addView(note,p);
        Button out=new Button(this); out.setText("Выйти");
        out.setOnClickListener(v->{getSharedPreferences("freegram_auth",0).edit().clear().apply();showAuth();});
        l.addView(out); setContentView(l);
    }
    @Override protected void onDestroy(){super.onDestroy();io.shutdownNow();}
}
