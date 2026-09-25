package ua.dehaze.live;

import android.os.SystemClock;
import java.util.Locale;

/**
 * Real source test, unlike the built-in synthetic CPU tests.
 * Measures actual decoded/captured frames and on-screen GPU readbacks.
 * No recording or network transmission occurs.
 */
final class DeviceTest {
    static final int NONE=0,CAMERA=1,VIDEO=2;
    static final int TEST_SECONDS=12;
    private static int active=NONE;
    private static String cameraResult="НЕ ПРОВОДИВСЯ",videoResult="НЕ ПРОВОДИВСЯ";
    private static long started,frames,lastNewFrame;
    private static int samples,gpuErrors,readbackErrors,blankOriginal,blankProcessed;
    private static double originalBrightness,processedBrightness,originalVariance,processedVariance;
    private static String lastEvent="Тести камери та відео ще не запускались";
    private DeviceTest(){}
    static synchronized boolean running(){return active!=NONE;}
    static synchronized int mode(){return active;}
    static synchronized void begin(int source){
        active=source;started=SystemClock.elapsedRealtime();frames=0;lastNewFrame=0;
        samples=0;gpuErrors=0;readbackErrors=0;blankOriginal=0;blankProcessed=0;
        originalBrightness=0;processedBrightness=0;originalVariance=0;processedVariance=0;
        lastEvent="ВИКОНУЄТЬСЯ: "+label(source);
        LabRuntime.log("Реальний тест розпочато: "+label(source));
    }
    static synchronized void newFrame(){
        if(active==NONE)return;
        frames++;lastNewFrame=SystemClock.elapsedRealtime();
    }
    // A completely dark image may be a genuine night scene. Call it suspect,
    // not a proven camera error.
    static boolean isSuspicious(double brightness,double variance){
        return (brightness<5.0||brightness>250.0)&&variance<4.0;
    }
    static synchronized void sample(double rawMean,double rawVariance,
                                    double outputMean,double outputVariance){
        if(active==NONE)return;
        if(!Double.isFinite(rawMean)||!Double.isFinite(outputMean)||
           !Double.isFinite(rawVariance)||!Double.isFinite(outputVariance)){
            readbackErrors++;return;
        }
        samples++;
        originalBrightness+=rawMean;processedBrightness+=outputMean;
        originalVariance+=rawVariance;processedVariance+=outputVariance;
        if(isSuspicious(rawMean,rawVariance))blankOriginal++;
        if(isSuspicious(outputMean,outputVariance))blankProcessed++;
    }
    static synchronized void gpuError(int code){
        if(active==NONE)return;
        gpuErrors++;
        LabRuntime.log(label(active)+" GL_ERROR="+code);
    }
    static synchronized void readbackError(int code){
        if(active==NONE)return;
        readbackErrors++;
        LabRuntime.log(label(active)+" glReadPixels error="+code);
    }
    static synchronized void failed(String cause){
        if(active==NONE){
            lastEvent="НЕ ВДАЛОСЯ ЗАПУСТИТИ: "+cause;
            LabRuntime.log(lastEvent);return;
        }
        String value="ПОМИЛКА: "+cause+" • кадрів: "+frames+" • GPU помилок: "+gpuErrors;
        if(active==CAMERA)cameraResult=value;else videoResult=value;
        lastEvent=value;LabRuntime.log(label(active)+": "+value);
        active=NONE;
    }
    static synchronized void cancel(){
        if(active!=NONE){
            lastEvent="Тест скасовано користувачем: "+label(active);
            LabRuntime.log(lastEvent);
            active=NONE;
        }
    }
    static synchronized void finish(){
        if(active==NONE)return;
        long elapsed=Math.max(1,SystemClock.elapsedRealtime()-started);
        double fps=frames*1000.0/elapsed;
        double raw=samples>0?originalBrightness/samples:0;
        double processed=samples>0?processedBrightness/samples:0;
        double rawVar=samples>0?originalVariance/samples:0;
        double procVar=samples>0?processedVariance/samples:0;
        boolean framesOk=frames>=10&&lastNewFrame>started;
        boolean screenOk=samples>=2;
        boolean blank=screenOk&&(blankOriginal>=samples*.80||blankProcessed>=samples*.80);
        boolean gpuOk=gpuErrors==0&&readbackErrors==0;
        String outcome=!framesOk?"ПОМИЛКА: відеокадри не надходять":
            !screenOk?"ПОМИЛКА: немає контрольних зчитувань GPU":
            !gpuOk?"ПОМИЛКА GPU: "+gpuErrors+" рендер / "+readbackErrors+" зчитування":
            blank?"УВАГА: можливий чорний екран або однотонна сцена":"ТЕХНІЧНО ПРАЦЮЄ";
        String result=String.format(Locale.US,
            "%s | %.1f FPS | %d кадрів | %d проб | %.0f/%.0f яскравість | дисперсія %.1f/%.1f | %.1f с\n"+
            "Зауваження: показники не доводять, що туман видалено правильно.",
            outcome,fps,frames,samples,raw,processed,rawVar,procVar,elapsed/1000.0);
        if(active==CAMERA)cameraResult=result;else videoResult=result;
        lastEvent=label(active)+": "+result;
        LabRuntime.log(lastEvent);
        active=NONE;
    }
    static synchronized String summary(){
        StringBuilder out=new StringBuilder("РЕАЛЬНІ ТЕСТИ (12 секунд кожен)\n");
        if(active!=NONE){
            long seconds=(SystemClock.elapsedRealtime()-started)/1000;
            out.append("▶ ").append(label(active)).append(": ").append(seconds)
               .append(" с • кадрів ").append(frames).append(" • зчитувань ").append(samples).append("\n");
        }
        out.append("КАМЕРА: ").append(cameraResult)
           .append("\n\nВІДЕОФАЙЛ: ").append(videoResult)
           .append("\n\nОстання подія: ").append(lastEvent)
           .append("\nЗначення 'технічно працює' не є оцінкою якості видалення туману.");
        return out.toString();
    }
    private static String label(int mode){
        return mode==CAMERA?"КАМЕРА":mode==VIDEO?"ЛОКАЛЬНЕ ВІДЕО":"НЕВІДОМИЙ";
    }
}
