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
    private android.graphics.drawable.GradientDrawable shape(int color,int strokeColor,int radius) {
        android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();
        d.setColor(color); d.setCornerRadius(radius); if(strokeColor!=Color.TRANSPARENT)d.setStroke(2,strokeColor); return d;
    }
    private void styleField(EditText field,String hint) {
        field.setSingleLine(true); field.setHint(hint); field.setTextSize(16);
        field.setTextColor(Color.rgb(30,41,59)); field.setHintTextColor(Color.rgb(139,151,168));
        field.setPadding(18,0,18,0); field.setBackground(shape(Color.rgb(247,249,252),Color.rgb(226,232,240),18));
    }
    private void styleButton(Button button,boolean primary) {
        button.setAllCaps(false); button.setTextSize(16); button.setTextColor(primary?Color.WHITE:Color.rgb(35,111,220));
        button.setBackground(shape(primary?Color.rgb(35,111,220):Color.WHITE,primary?Color.TRANSPARENT:Color.rgb(215,227,245),18));
        button.setElevation(primary?3:0);
    }
    private void showAuth() {
        LinearLayout outer=root(); outer.setGravity(Gravity.CENTER); outer.setPadding(24,28,24,28);
        LinearLayout card=new LinearLayout(this); card.setOrientation(1); card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(24,28,24,24); card.setBackground(shape(Color.WHITE,Color.rgb(232,237,245),24)); card.setElevation(8);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.width=-1; outer.addView(card,cp);

        TextView mark=label("F",25,Color.WHITE); mark.setTypeface(null,1);
        mark.setBackground(shape(Color.rgb(35,111,220),Color.TRANSPARENT,22));
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(58,58); mp.gravity=Gravity.CENTER; mp.bottomMargin=14; card.addView(mark,mp);
        TextView brand=label("FreeGram",30,Color.rgb(27,43,67)); brand.setTypeface(null,1);
        card.addView(brand,new LinearLayout.LayoutParams(-1,-2));
        TextView subtitle=label("Твой мессенджер. Без номера телефона.",14,Color.rgb(112,126,147));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2); sp.topMargin=7; sp.bottomMargin=22; card.addView(subtitle,sp);

        username=new EditText(this); styleField(username,"Имя пользователя");
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,56); card.addView(username,p);
        password=new EditText(this); styleField(password,"Пароль"); password.setInputType(129);
        p=new LinearLayout.LayoutParams(-1,56); p.topMargin=12; card.addView(password,p);

        Button reg=new Button(this); reg.setText("Создать аккаунт"); styleButton(reg,true);
        p=new LinearLayout.LayoutParams(-1,54); p.topMargin=20; card.addView(reg,p);
        Button login=new Button(this); login.setText("Уже есть аккаунт? Войти"); styleButton(login,false);
        p=new LinearLayout.LayoutParams(-1,48); p.topMargin=6; card.addView(login,p);
        progress=new ProgressBar(this); progress.setVisibility(View.GONE);
        p=new LinearLayout.LayoutParams(-2,-2); p.topMargin=12; card.addView(progress,p);
        status=label("",13,Color.rgb(100,116,139)); p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=10; card.addView(status,p);

        reg.setOnClickListener(v->submit("/v1/auth/register"));
        login.setOnClickListener(v->submit("/v1/auth/login"));
        setContentView(outer);
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
        showChats(u,id);
    }
    private void showChats(String u,String id) {
        LinearLayout page=root(); page.setPadding(0,0,0,0); page.setBackgroundColor(Color.WHITE);
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(18,14,18,14);
        TextView brand=label("FreeGram",25,Color.rgb(35,111,220)); brand.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button compose=new Button(this); compose.setText("+"); compose.setOnClickListener(v->promptDirect(u,id)); header.addView(compose);
        page.addView(header,new LinearLayout.LayoutParams(-1,-2));
        View line=new View(this); line.setBackgroundColor(Color.rgb(225,230,238)); page.addView(line,new LinearLayout.LayoutParams(-1,1));
        LinearLayout content=new LinearLayout(this); content.setOrientation(1); content.setPadding(16,12,16,8);
        TextView heading=label("Все чаты",22,Color.BLACK); heading.setGravity(Gravity.LEFT); content.addView(heading);
        TextView account=label("@"+u+"  •  "+id,13,Color.GRAY); account.setGravity(Gravity.LEFT); content.addView(account);
        LinearLayout list=new LinearLayout(this); list.setOrientation(1);
        ScrollView scroll=new ScrollView(this); scroll.addView(list);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        page.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=new LinearLayout(this); nav.setGravity(Gravity.CENTER); nav.setBackgroundColor(Color.rgb(248,249,252));
        addNav(nav,"Чаты",()->showChats(u,id)); addNav(nav,"Контакты",()->showContacts(u,id));
        addNav(nav,"Профиль",()->showProfile(u,id)); addNav(nav,"Настройки",()->showSettings(u,id));
        page.addView(nav,new LinearLayout.LayoutParams(-1,58));
        setContentView(page); loadChats(list,u,id);
    }
    private void addNav(LinearLayout nav,String title,Runnable action) {
        Button b=new Button(this); b.setText(title); b.setTextSize(11); b.setAllCaps(false);
        b.setOnClickListener(v->action.run()); nav.addView(b,new LinearLayout.LayoutParams(0,-1,1));
    }
    private void showContacts(String u,String id) {
        LinearLayout page=root(); page.setPadding(0,0,0,0); page.setBackgroundColor(Color.WHITE);
        page.addView(label("Контакты",24,Color.BLACK));
        page.addView(label("Найти пользователя FreeGram можно по логину",14,Color.GRAY));
        Button search=new Button(this); search.setText("Найти по логину");
        search.setOnClickListener(v->promptDirect(u,id)); page.addView(search);
        page.addView(new View(this),new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=new LinearLayout(this); addNav(nav,"Чаты",()->showChats(u,id));
        addNav(nav,"Контакты",()->showContacts(u,id)); addNav(nav,"Профиль",()->showProfile(u,id));
        addNav(nav,"Настройки",()->showSettings(u,id)); page.addView(nav,new LinearLayout.LayoutParams(-1,58));
        setContentView(page);
    }
    private void showProfile(String u,String id) {
        LinearLayout page=root(); page.setPadding(0,0,0,0); page.setBackgroundColor(Color.WHITE);
        page.addView(label("Мой профиль",24,Color.BLACK));
        TextView avatar=label((u.isEmpty()?"F":u.substring(0,1).toUpperCase()),34,Color.WHITE);
        avatar.setBackgroundColor(Color.rgb(35,111,220));
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(84,84); ap.topMargin=22; ap.gravity=Gravity.CENTER;
        page.addView(avatar,ap);
        page.addView(label("@"+u,20,Color.BLACK));
        page.addView(label(id,14,Color.GRAY));
        page.addView(label("FreeGram ID",12,Color.GRAY));
        page.addView(new View(this),new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=new LinearLayout(this); addNav(nav,"Чаты",()->showChats(u,id));
        addNav(nav,"Контакты",()->showContacts(u,id)); addNav(nav,"Профиль",()->showProfile(u,id));
        addNav(nav,"Настройки",()->showSettings(u,id)); page.addView(nav,new LinearLayout.LayoutParams(-1,58));
        setContentView(page);
    }
    private void showSettings(String u,String id) {
        LinearLayout page=root(); page.setPadding(0,0,0,0); page.setBackgroundColor(Color.WHITE);
        page.addView(label("Настройки",24,Color.BLACK));
        Button logout=new Button(this); logout.setText("Выйти из аккаунта");
        logout.setOnClickListener(v->{getSharedPreferences("freegram_auth",0).edit().clear().apply();showAuth();});
        page.addView(logout);
        page.addView(new View(this),new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=new LinearLayout(this); addNav(nav,"Чаты",()->showChats(u,id));
        addNav(nav,"Контакты",()->showContacts(u,id)); addNav(nav,"Профиль",()->showProfile(u,id));
        addNav(nav,"Настройки",()->showSettings(u,id)); page.addView(nav,new LinearLayout.LayoutParams(-1,58));
        setContentView(page);
    }
    private String token(){return getSharedPreferences("freegram_auth",0).getString("token","");}
    private void loadChats(LinearLayout list,String u,String id) {
        list.removeAllViews();
        list.addView(label("Загружаю чаты...",14,Color.GRAY));
        io.execute(()->{
            try {
                JSONObject res=request("/v1/chats","GET",null,token());
                org.json.JSONArray chats=res.optJSONArray("chats");
                main.post(()->{
                    list.removeAllViews();
                    if(chats==null||chats.length()==0){list.addView(label("Пока нет чатов. Напишите пользователю по логину.",14,Color.GRAY));return;}
                    for(int i=0;i<chats.length();i++){
                        JSONObject ch=chats.optJSONObject(i); if(ch==null)continue;
                        final int chatId=ch.optInt("id");
                        String kind=ch.optString("kind","chat");
                        String title=ch.optString("title","");
                        if(title.isEmpty())title="@"+ch.optString("other_username","личный чат");
                        final String chatTitle=title;
                        String preview=ch.optString("last_message","Нет сообщений");
                        Button item=new Button(this);item.setText(chatTitle+"\n"+preview);
                        item.setOnClickListener(v->openChat(chatId,chatTitle,u,id));
                        list.addView(item,new LinearLayout.LayoutParams(-1,-2));
                    }
                });
            } catch(Exception e){main.post(()->{list.removeAllViews();list.addView(label("Ошибка загрузки: "+e.getMessage(),14,Color.RED));});}
        });
    }
    private void promptDirect(String u,String id) {
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Логин пользователя");
        new android.app.AlertDialog.Builder(this).setTitle("Новый чат").setView(input)
            .setNegativeButton("Отмена",null).setPositiveButton("Создать",(d,w)->{
                String name=input.getText().toString().trim();
                io.execute(()->{try{
                    JSONObject data=new JSONObject();data.put("kind","direct");data.put("username",name);
                    JSONObject res=request("/v1/chats","POST",data,token());
                    int chatId=res.getInt("chat_id");main.post(()->openChat(chatId,"@"+name,u,id));
                }catch(Exception e){main.post(()->android.widget.Toast.makeText(this,"Ошибка: "+e.getMessage(),android.widget.Toast.LENGTH_LONG).show());}});
            }).show();
    }
    private void promptRoom(String kind,String u,String id) {
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint(kind.equals("channel")?"Название канала":"Название группы");
        new android.app.AlertDialog.Builder(this).setTitle(kind.equals("channel")?"Новый канал":"Новая группа").setView(input)
            .setNegativeButton("Отмена",null).setPositiveButton("Создать",(d,w)->{
                String title=input.getText().toString().trim();
                io.execute(()->{try{
                    JSONObject data=new JSONObject();data.put("kind",kind);data.put("title",title);
                    JSONObject res=request("/v1/chats","POST",data,token());
                    int chatId=res.getInt("chat_id");main.post(()->openChat(chatId,title,u,id));
                }catch(Exception e){main.post(()->android.widget.Toast.makeText(this,"Ошибка: "+e.getMessage(),android.widget.Toast.LENGTH_LONG).show());}});
            }).show();
    }
    private void openChat(int chatId,String title,String u,String id) {
        LinearLayout l=root();
        Button back=new Button(this);back.setText("← Чаты");back.setOnClickListener(v->showChats(u,id));l.addView(back);
        l.addView(label(title,22,Color.rgb(35,111,220)));
        ScrollView scroll=new ScrollView(this);LinearLayout messages=new LinearLayout(this);messages.setOrientation(1);
        scroll.addView(messages);l.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        EditText input=new EditText(this);input.setHint("Сообщение");l.addView(input);
        Button send=new Button(this);send.setText("Отправить");l.addView(send);
        setContentView(l);loadMessages(chatId,messages);
        send.setOnClickListener(v->{
            String body=input.getText().toString().trim();if(body.isEmpty())return;
            input.setText("");io.execute(()->{try{
                JSONObject data=new JSONObject();data.put("text",body);
                request("/v1/chats/"+chatId+"/messages","POST",data,token());
                main.post(()->loadMessages(chatId,messages));
            }catch(Exception e){main.post(()->android.widget.Toast.makeText(this,"Не отправлено: "+e.getMessage(),android.widget.Toast.LENGTH_LONG).show());}});
        });
    }
    private void loadMessages(int chatId,LinearLayout target) {
        io.execute(()->{try{
            JSONObject res=request("/v1/chats/"+chatId+"/messages","GET",null,token());
            org.json.JSONArray arr=res.optJSONArray("messages");
            main.post(()->{
                target.removeAllViews();
                if(arr==null||arr.length()==0){target.addView(label("Сообщений пока нет",14,Color.GRAY));return;}
                for(int i=0;i<arr.length();i++){
                    JSONObject m=arr.optJSONObject(i);if(m==null)continue;
                    String line="@"+m.optString("username","user")+": "+m.optString("body","");
                    TextView t=label(line,15,Color.DKGRAY);t.setGravity(Gravity.LEFT);t.setPadding(8,12,8,12);
                    target.addView(t);
                }
            });
        }catch(Exception e){main.post(()->android.widget.Toast.makeText(this,"Ошибка сообщений: "+e.getMessage(),android.widget.Toast.LENGTH_LONG).show());}});
    }
    @Override protected void onDestroy(){super.onDestroy();io.shutdownNow();}
}
