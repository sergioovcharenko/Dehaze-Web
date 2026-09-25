package ua.dehaze.live;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Offline status, module switches, repeatable synthetic self-test and TXT export. */
public final class DiagnosticsActivity extends Activity {
    private static final int SAVE_REPORT=41;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status,summary;
    private LinearLayout switches;
    private boolean visible=false;
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private final int BG=Color.rgb(30,34,39),PANEL=Color.rgb(49,54,60),
        WHITE=Color.rgb(240,244,245),MUTED=Color.rgb(180,192,198),ACCENT=Color.rgb(128,214,181);
    private TextView label(String content,int sp,int color){
        TextView t=new TextView(this);
        t.setText(content);t.setTextSize(sp);t.setTextColor(color);
        return t;
    }
    private TextView action(String content,Runnable run){
        TextView t=label(content,14,WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12),dp(12),dp(12),dp(12));
        android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();
        shape.setColor(PANEL);shape.setCornerRadius(dp(12));
        shape.setStroke(dp(1),Color.rgb(102,125,120));
        t.setBackground(shape);t.setOnClickListener(v->run.run());
        return t;
    }
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        LabRuntime.init(this);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout page=new LinearLayout(this);
        page.setPadding(dp(16),dp(12),dp(16),dp(22));
        page.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(page);
        TextView heading=label("МЕТІ ТУМАН LAB · ДІАГНОСТИКА",21,ACCENT);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        page.addView(heading);
        TextView note=label("Окрема четверта APK • без інтернету. Самотест перевіряє алгоритми на створених програмою кадрах, а камера й GPU перевіряються тільки під час реального запуску.",12,MUTED);
        note.setPadding(0,dp(5),0,dp(14));
        page.addView(note);
        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=action("◀  НАЗАД",this::finish);
        actions.addView(back,new LinearLayout.LayoutParams(0,dp(47),1));
        TextView test=action("▶  ПОВНИЙ ТЕСТ",()->{
            LabRuntime.runSelfTest();
            refresh();
            Toast.makeText(this,"Самотест виконується у фоновому потоці",Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(47),1.2f);
        bp.leftMargin=dp(9);actions.addView(test,bp);
        TextView save=action("↓  TXT ЗВІТ",this::export);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(47),1);
        sp.leftMargin=dp(9);actions.addView(save,sp);
        page.addView(actions);
        summary=label("Отримання стану…",12,ACCENT);
        summary.setPadding(0,dp(13),0,dp(4));
        page.addView(summary);
        status=label("",12,WHITE);
        status.setTypeface(android.graphics.Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        status.setPadding(dp(12),dp(9),dp(12),dp(9));
        status.setBackgroundColor(PANEL);
        page.addView(status,new LinearLayout.LayoutParams(-1,-2));
        TextView switchesHeading=label("МОДУЛІ LIVE MAX · НЕЗАЛЕЖНІ ПЕРЕМИКАЧІ",15,ACCENT);
        switchesHeading.setPadding(0,dp(16),0,dp(5));
        switchesHeading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        page.addView(switchesHeading);
        switches=new LinearLayout(this);
        switches.setOrientation(LinearLayout.VERTICAL);
        page.addView(switches);
        for(int i=LabRuntime.DCP;i<LabRuntime.NAMES.length;i++){
            final int module=i;
            CheckBox checkbox=new CheckBox(this);
            checkbox.setText(LabRuntime.NAMES[module]);
            checkbox.setTextColor(WHITE);
            checkbox.setTextSize(14);
            checkbox.setChecked(LabRuntime.enabled(module));
            checkbox.setPadding(dp(4),dp(4),0,dp(4));
            checkbox.setOnCheckedChangeListener((button,checked)->{
                LabRuntime.setEnabled(module,checked);
                refresh();
            });
            switches.addView(checkbox);
        }
        TextView help=label("Якщо модуль завершується помилкою, він вимикається лише на поточний запуск. Оригінал камери завжди використовує незалежний швидкий GPU-шлях. Для чорного екрана натисни GPU SAFE на головному екрані.",12,MUTED);
        help.setPadding(0,dp(10),0,0);
        page.addView(help);
        setContentView(scroll);
        refresh();
    }
    private void refresh(){
        if(status!=null)status.setText(LabRuntime.snapshot());
        if(summary!=null)summary.setText(LabRuntime.testing()?
            "● Тест триває…":"● "+LabRuntime.testState());
    }
    private final Runnable periodic=new Runnable(){
        @Override public void run(){
            if(!visible)return;
            refresh();
            handler.postDelayed(this,900);
        }
    };
    @Override protected void onResume(){super.onResume();visible=true;handler.post(periodic);}
    @Override protected void onPause(){visible=false;handler.removeCallbacks(periodic);super.onPause();}
    private void export(){
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE,"Meti-Tuman-LAB-diagnostics.txt");
        startActivityForResult(intent,SAVE_REPORT);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=SAVE_REPORT||result!=RESULT_OK||data==null||data.getData()==null)return;
        try(OutputStream stream=getContentResolver().openOutputStream(data.getData())){
            if(stream==null)throw new IllegalStateException("Немає доступу до файлу");
            stream.write(LabRuntime.report().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this,"Звіт збережено — надішли TXT для аналізу",Toast.LENGTH_LONG).show();
        }catch(Exception error){
            Toast.makeText(this,"Помилка експорту: "+error.getMessage(),Toast.LENGTH_LONG).show();
            LabRuntime.log("Експорт TXT: "+error);
        }
    }
}
