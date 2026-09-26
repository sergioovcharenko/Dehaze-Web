package ua.dehaze.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-stage offline diagnostics. Synthetic self-tests never claim to validate
 * camera hardware, GPU quality, or dehazing quality on unseen real footage.
 */
final class LabRuntime {
    static final int CAMERA=0, GPU=1, DCP=2, GUIDED=3, CLAHE=4,
                     RETINEX=5, TEMPORAL=6, FUSION=7;
    static final String[] NAMES={
        "Камера / Camera2", "GPU / OpenGL", "Multi-scale DCP",
        "Guided Filter", "Адаптивний CLAHE", "Retinex",
        "Temporal Filter", "Image Fusion"
    };
    static final int ALL=63;
    static final String VERSION="3.0.0-LAB3 • CLASSIC";
    private static final String[] status=new String[NAMES.length],
                                  detail=new String[NAMES.length],
                                  synthetic=new String[NAMES.length];
    private static final long[] latency=new long[NAMES.length];
    private static final ArrayDeque<String> logs=new ArrayDeque<>();
    private static final AtomicBoolean testing=new AtomicBoolean(false);
    private static Context app;
    private static volatile int enabledMask=ALL;
    private static volatile long testCompleted=0;
    private static volatile String testState="Ще не запускався";
    private static volatile String frameStats="Очікування кадрів";
    private static final SimpleDateFormat CLOCK=new SimpleDateFormat("HH:mm:ss",Locale.US);
    static {
        for(int i=0;i<NAMES.length;i++){
            status[i]="НЕ ПЕРЕВІРЕНО";
            detail[i]=i<2?"Потрібна камера пристрою":"Очікує на обробку відео";
            synthetic[i]=i<2?"ПЕРЕВІРЯЄТЬСЯ НА ПРИСТРОЇ":"ОЧІКУЄ";
        }
    }
    private LabRuntime(){}
    static synchronized void init(Context context){
        if(app!=null)return;
        app=context.getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences("lab-features",Context.MODE_PRIVATE);
        enabledMask=prefs.getInt("flags",ALL)&ALL;
        log("LAB запущено • "+Build.MANUFACTURER+" "+Build.MODEL);
        runSelfTest();
    }
    static boolean enabled(int stage){
        if(stage<2||stage>=NAMES.length)return true;
        return (enabledMask&(1<<(stage-2)))!=0;
    }
    static int flags(){return enabledMask;}
    static synchronized void setEnabled(int stage,boolean value){
        if(stage<2||stage>=NAMES.length)return;
        int bit=1<<(stage-2);
        enabledMask=value?(enabledMask|bit):(enabledMask&~bit);
        if(app!=null)app.getSharedPreferences("lab-features",Context.MODE_PRIVATE)
            .edit().putInt("flags",enabledMask).apply();
        status[stage]=value?"ОЧІКУЄ":"ВИМКНЕНО";
        detail[stage]=value?"Очікує на нові відеокадри":"Вимкнено вручну";
        log(NAMES[stage]+": "+(value?"увімкнено":"вимкнено"));
    }
    static synchronized void autoDisable(int stage,String cause){
        if(stage<2||stage>=NAMES.length)return;
        enabledMask&=~(1<<(stage-2));
        status[stage]="АВАРІЙНО ВИМКНЕНО";
        detail[stage]=trim(cause,170);
        // Runtime-only fallback: a new app launch restores the user's preferences.
        log("АВАРІЙНИЙ ОБХІД "+NAMES[stage]+": "+cause);
    }
    static synchronized void state(int stage,String value,String info){
        if(stage<0||stage>=NAMES.length)return;
        status[stage]=value;
        detail[stage]=trim(info,185);
        if("ПОМИЛКА".equals(value)||"РЕЗЕРВНИЙ".equals(value))log(NAMES[stage]+": "+info);
    }
    static synchronized void live(int stage,long ms,String info){
        if(stage<0||stage>=NAMES.length)return;
        if(stage>=2&&!enabled(stage))return;
        status[stage]="ПРАЦЮЄ";
        latency[stage]=Math.max(0,ms);
        detail[stage]=trim(info,160);
    }
    static void frames(String value){
        frameStats=value;
        live(CAMERA,0,"Відеокадри надходять");
    }
    static void error(int stage,Throwable ex){
        state(stage,"ПОМИЛКА",ex.getClass().getSimpleName()+": "+String.valueOf(ex.getMessage()));
    }
    static synchronized void log(String line){
        logs.addLast(CLOCK.format(new Date())+"  "+trim(line,250));
        while(logs.size()>65)logs.removeFirst();
    }
    private static String trim(String s,int len){
        if(s==null)return "";
        return s.length()<=len?s:s.substring(0,len)+"…";
    }
    static boolean testing(){return testing.get();}
    static String testState(){return testState;}
    static long testedAt(){return testCompleted;}
    static synchronized String snapshot(){
        StringBuilder b=new StringBuilder();
        b.append("САМОТЕСТ: ").append(testState).append("\n")
         .append("FPS: ").append(frameStats).append("\n\n");
        for(int i=0;i<NAMES.length;i++){
            b.append(NAMES[i]).append(": ").append(status[i]);
            if(i>=2&&!enabled(i))b.append(" [LIVE ВИМКНЕНО]");
            if(latency[i]>0)b.append(" • ").append(latency[i]).append(" мс");
            b.append("\n   ").append(detail[i])
             .append("\n   Самотест: ").append(synthetic[i]).append("\n\n");
        }
        b.append("\n").append(DeviceTest.summary()).append("\n");
        return b.toString();
    }
    static synchronized String report(){
        StringBuilder b=new StringBuilder();
        b.append("МЕТІ ТУМАН LAB — ТЕХНІЧНИЙ ЗВІТ\n")
         .append("Версія: ").append(VERSION)
         .append("\nДата: ").append(new Date())
         .append("\nПристрій: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
         .append("\nAndroid: ").append(Build.VERSION.RELEASE)
         .append(" API ").append(Build.VERSION.SDK_INT)
         .append("\nІнтернет не використовувався. Фото й кадри не додаються до звіту.\n\n")
         .append(snapshot()).append("\nЖУРНАЛ ПОДІЙ\n");
        for(String item:logs)b.append(item).append('\n');
        return b.toString();
    }
    private static void syntheticState(int index,String message){
        synchronized(LabRuntime.class){synthetic[index]=message;}
    }
    private static void validate(boolean success,String msg){
        if(!success)throw new IllegalStateException(msg);
    }
    private static byte[] syntheticFrame(int frame){
        byte[] pixels=new byte[VideoDehazeProcessor.N*4];
        for(int y=0;y<VideoDehazeProcessor.H;y++)
            for(int x=0;x<VideoDehazeProcessor.W;x++){
                int i=(y*VideoDehazeProcessor.W+x)*4;
                boolean sky=y>VideoDehazeProcessor.H*.64;
                float haze=sky?.70f:.46f;
                int structure=sky?170:
                    ((x/16+y/13)%2==0?60:139);
                int raw=structure+(x*5/VideoDehazeProcessor.W)
                    +((x*23+y*17+frame*3)%19-9);
                int value=Math.min(244,Math.max(22,Math.round(raw*(1-haze)+205*haze)));
                pixels[i]=(byte)Math.min(255,value+5);
                pixels[i+1]=(byte)Math.min(255,value+8);
                pixels[i+2]=(byte)Math.min(255,value+12);
                pixels[i+3]=(byte)255;
            }
        return pixels;
    }
    private static float retinexReference(float lum,float local,float sky){
        float lowlight=Math.max(0f,Math.min(1f,(.58f-local)*1.7f));
        float adjusted=(float)Math.pow(Math.max(0f,Math.min(1f,lum)),
             1f-.28f*lowlight*(1f-sky));
        return Math.min(1f,Math.max(0f,adjusted));
    }
    private static float fusionReference(float dcp,float fast,float detail,float sky){
        float blend=.30f*(1f-sky)*detail;
        return Math.max(0f,Math.min(1f,dcp*(1f-blend)+fast*blend));
    }
    static void runSelfTest(){
        if(!testing.compareAndSet(false,true))return;
        testState="ВИКОНУЄТЬСЯ";
        new Thread(()->{
            long start=SystemClock.elapsedRealtime();
            final byte[] frame=syntheticFrame(0),frame2=syntheticFrame(1);
            VideoDehazeProcessor.Result reference=null;
            try{
                // Use the same real pipeline as live video, with all modules
                // enabled, independently of the user's live toggles.
                reference=VideoDehazeProcessor.process(frame,null,ALL);
                validate(reference.map.length==VideoDehazeProcessor.N*4,"Неправильний розмір карти");
                validate(reference.lut.length==VideoDehazeProcessor.LUT_W*
                    VideoDehazeProcessor.LUT_H*4,"Неправильна CLAHE LUT");
                int varying=0,min=255,max=0;
                for(int k=0;k<reference.map.length;k+=4){
                    int t=reference.map[k]&255;
                    if(t<245)varying++;
                    min=Math.min(min,t);max=Math.max(max,t);
                }
                validate(varying>VideoDehazeProcessor.N/15&&max-min>3,
                    "Карта передачі однорідна або порожня");
                syntheticState(DCP,"OK • багатомасштабна карта "+min+".."+max);
            }catch(Throwable error){
                syntheticState(DCP,"ПОМИЛКА • "+trim(error.toString(),105));
                log("Самотест DCP: "+error);
            }
            try{
                VideoDehazeProcessor.Result noGuide=
                    VideoDehazeProcessor.process(frame,null,ALL&~(1<<(GUIDED-2)));
                validate(noGuide.map.length==VideoDehazeProcessor.N*4,"Guided fallback карта");
                validate(reference!=null,"DCP попередньо не пройшов тест");
                long delta=0;
                for(int i=0;i<noGuide.map.length;i+=16)
                    delta+=Math.abs((noGuide.map[i]&255)-(reference.map[i]&255));
                validate(delta>30,"Guided Filter не впливає на карту");
                syntheticState(GUIDED,"OK • guided та raw карти відрізняються");
            }catch(Throwable error){syntheticState(GUIDED,"ПОМИЛКА • "+trim(error.toString(),100));log("Самотест Guided: "+error);}
            try{
                VideoDehazeProcessor.Result noClahe=
                    VideoDehazeProcessor.process(frame,null,ALL&~(1<<(CLAHE-2)));
                validate(noClahe.lut.length==VideoDehazeProcessor.LUT_W*VideoDehazeProcessor.LUT_H*4,"LUT");
                validate((noClahe.lut[120*4]&255)==120,"CLAHE OFF не створив identity LUT");
                validate(reference!=null,"Немає базової CLAHE LUT");
                int difference=0;
                for(int i=0;i<reference.lut.length;i+=64)
                    if(reference.lut[i]!=noClahe.lut[i])difference++;
                validate(difference>8,"CLAHE LUT не змінює контраст");
                syntheticState(CLAHE,"OK • адаптивна LUT та її вимкнення");
            }catch(Throwable error){syntheticState(CLAHE,"ПОМИЛКА • "+trim(error.toString(),100));log("Самотест CLAHE: "+error);}
            try{
                float input=.18f,output=retinexReference(input,.20f,0f);
                validate(Float.isFinite(output)&&output>input&&output<1f,
                    "Retinex недопустимий рівень");
                syntheticState(RETINEX,"OK • CPU-модель; GPU перевіряється камерою");
            }catch(Throwable error){syntheticState(RETINEX,"ПОМИЛКА • "+trim(error.toString(),95));log("Самотест Retinex: "+error);}
            try{
                validate(reference!=null,"Немає базової карти для порівняння");
                VideoDehazeProcessor.Result tempor=
                    VideoDehazeProcessor.process(frame2,reference,ALL);
                VideoDehazeProcessor.Result unsmoothed=
                    VideoDehazeProcessor.process(frame2,null,ALL&~(1<<(TEMPORAL-2)));
                int diff=0;
                for(int i=0;i<tempor.map.length;i+=8)
                    diff+=Math.abs((tempor.map[i]&255)-(unsmoothed.map[i]&255));
                validate(diff>0,"Temporal Filter не згладжує сусідні кадри");
                syntheticState(TEMPORAL,"OK • згладжування послідовних кадрів");
            }catch(Throwable error){syntheticState(TEMPORAL,"ПОМИЛКА • "+trim(error.toString(),95));log("Самотест Temporal: "+error);}
            try{
                float result=fusionReference(.42f,.65f,.87f,.05f);
                validate(Float.isFinite(result)&&result>.42f&&result<.65f,
                    "Fusion некоректна суміш");
                syntheticState(FUSION,"OK • CPU-модель; GPU перевіряється камерою");
            }catch(Throwable error){syntheticState(FUSION,"ПОМИЛКА • "+trim(error.toString(),95));log("Самотест Fusion: "+error);}
            testCompleted=System.currentTimeMillis();
            testState="ЗАВЕРШЕНО • "+(SystemClock.elapsedRealtime()-start)+" мс";
            log("Автоматичний самотест завершений • "+(SystemClock.elapsedRealtime()-start)+" мс");
            testing.set(false);
        },"meti-lab-selftest").start();
    }
}
