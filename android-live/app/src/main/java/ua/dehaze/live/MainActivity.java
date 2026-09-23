package ua.dehaze.live;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.util.Size;

import java.util.Arrays;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION = 12;
    private GLSurfaceView glView;
    private SplitRenderer renderer;
    private SurfaceTexture cameraTexture;
    private CameraManager cameraManager;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private Surface cameraSurface;
    private TextView statsView;
    private TextView stateView;
    private TextView toggle;
    private boolean active, opening;
    private boolean enhanced = true;
    private int strength = 60;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cameraManager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        cameraThread = new HandlerThread("camera2-preview");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        makeUi();
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(sizeSp); t.setTextColor(color);
        return t;
    }
    private int dp(float x) { return (int)(getResources().getDisplayMetrics().density*x+.5f); }

    private void makeUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(6,13,25));
        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        renderer = new SplitRenderer(this,glView,this::onCameraTextureReady,this::onFrameStats);
        renderer.setEnhanced(enhanced);
        renderer.setStrength(strength/100f);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(glView,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackgroundColor(Color.argb(220,8,19,36));
        overlay.setPadding(dp(12),dp(8),dp(12),dp(8));

        TextView title=text("DEHAZE LIVE   •   ЛІВОРУЧ: КАМЕРА    |    ПРАВОРУЧ: АНТИТУМАН",14,Color.rgb(111,229,180));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        overlay.addView(title);
        statsView=text("Вік кадру: —   |   GPU-подача: —   |   FPS: —   |   Пінг: не потрібен",12,Color.WHITE);
        overlay.addView(statsView);
        stateView=text("Очікування дозволу на камеру. Обробка працює без інтернету.",11,Color.rgb(170,190,209));
        overlay.addView(stateView);
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
        root.addView(overlay,top);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(12),dp(7),dp(12),dp(7));
        bottom.setBackgroundColor(Color.argb(228,9,23,43));
        toggle=text("АНТИТУМАН: ON",12,Color.rgb(120,237,172));
        toggle.setPadding(dp(8),dp(8),dp(14),dp(8));
        toggle.setOnClickListener(v->{
            enhanced=!enhanced;renderer.setEnhanced(enhanced);
            toggle.setText(enhanced?"АНТИТУМАН: ON":"АНТИТУМАН: OFF");
            toggle.setTextColor(enhanced?Color.rgb(120,237,172):Color.LTGRAY);
            glView.requestRender();
        });
        bottom.addView(toggle);
        TextView label=text("Сила:",12,Color.WHITE);
        bottom.addView(label);
        SeekBar slider=new SeekBar(this);
        slider.setMax(100);slider.setProgress(strength);
        LinearLayout.LayoutParams barParams=new LinearLayout.LayoutParams(0,dp(45),1f);
        bottom.addView(slider,barParams);
        final TextView value=text(strength+"%",12,Color.WHITE);
        bottom.addView(value);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int val,boolean user){
                strength=val;renderer.setStrength(val/100f);value.setText(val+"%");glView.requestRender();
            }
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
        });
        FrameLayout.LayoutParams low=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        root.addView(bottom,low);
        setContentView(root);
    }

    private void onFrameStats(final String stats) {
        runOnUiThread(()->{if(active)statsView.setText(stats+"   |   Пінг: — (локально)");});
    }

    private void setState(final String info){
        runOnUiThread(()->stateView.setText(info));
    }

    private void onCameraTextureReady(SurfaceTexture texture) {
        cameraTexture=texture;
        if(active)maybeOpenCamera();
    }

    @Override protected void onResume() {
        super.onResume();active=true;glView.onResume();
        maybeOpenCamera();
    }

    @Override protected void onPause() {
        active=false;closeCamera();glView.onPause();
        super.onPause();
    }

    private void maybeOpenCamera(){
        if(!active||cameraTexture==null||cameraDevice!=null||opening)return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);
            return;
        }
        openCamera();
    }

    @Override public void onRequestPermissionsResult(int code,String[] perms,int[] grants){
        super.onRequestPermissionsResult(code,perms,grants);
        if(code==CAMERA_PERMISSION){
            if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)maybeOpenCamera();
            else setState("Немає доступу до камери. Дозволь доступ у налаштуваннях Android.");
        }
    }

    private void openCamera(){
        if(opening||!active||cameraTexture==null)return;
        try{
            String chosen=null;
            for(String id:cameraManager.getCameraIdList()){
                Integer facing=cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_BACK){chosen=id;break;}
                if(chosen==null)chosen=id;
            }
            if(chosen==null){setState("На пристрої немає доступної камери.");return;}
            CameraCharacteristics c=cameraManager.getCameraCharacteristics(chosen);
            StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(map==null)throw new IllegalStateException("Немає підтримуваних розмірів камери");
            Size size=pickSize(map.getOutputSizes(SurfaceTexture.class));
            if(size==null)throw new IllegalStateException("Немає SurfaceTexture preview для цієї камери");
            Integer sensor=c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer stamp=c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            int display=getWindowManager().getDefaultDisplay().getRotation();
            int displayDeg=display==Surface.ROTATION_90?90:display==Surface.ROTATION_180?180:display==Surface.ROTATION_270?270:0;
            int rotation=(((sensor==null?0:sensor)-displayDeg)%360+360)%360;
            renderer.setCameraInfo(size.getWidth(),size.getHeight(),rotation,
                stamp!=null&&stamp==CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
            cameraTexture.setDefaultBufferSize(size.getWidth(),size.getHeight());
            opening=true;
            cameraManager.openCamera(chosen,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice camera){
                    if(!active){camera.close();opening=false;return;}
                    cameraDevice=camera;
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice camera){
                    camera.close();cameraDevice=null;opening=false;setState("Камеру відключено.");
                }
                @Override public void onError(CameraDevice camera,int error){
                    camera.close();cameraDevice=null;opening=false;setState("Помилка Camera2: "+error);
                }
            },cameraHandler);
        }catch(Exception e){opening=false;setState("Не вдалося відкрити камеру: "+e.getMessage());}
    }

    private static Size pickSize(Size[] choices){
        if(choices==null||choices.length==0)return null;
        Size winner=choices[0];double best=Double.POSITIVE_INFINITY;
        for(Size s:choices){
            double a=(double)s.getWidth()/s.getHeight();
            double pixels=(double)s.getWidth()*s.getHeight();
            double cost=Math.abs(a-16.0/9)*3+
              Math.abs(Math.log(Math.max(1,pixels)/(1280.0*720)))*.4+
              (pixels>1920.0*1080?2:0);
            if(cost<best){best=cost;winner=s;}
        }
        return winner;
    }

    private void startPreview(){
        if(cameraDevice==null||cameraTexture==null)return;
        try{
            cameraSurface=new Surface(cameraTexture);
            CaptureRequest.Builder builder=cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(cameraSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            builder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
            cameraDevice.createCaptureSession(Arrays.asList(cameraSurface),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession cs){
                    if(!active||cameraDevice==null){cs.close();return;}
                    session=cs;
                    try{session.setRepeatingRequest(builder.build(),null,cameraHandler);
                        opening=false;setState("Камера працює • один відеопотік • обробка на GPU без мережі");
                    }catch(CameraAccessException e){opening=false;setState("Помилка відеопотоку: "+e.getMessage());}
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs){
                    opening=false;setState("Камера не підтримує обраний відеорежим.");
                }
            },cameraHandler);
        }catch(Exception e){opening=false;setState("Не вдалося запустити відео: "+e.getMessage());}
    }

    private synchronized void closeCamera(){
        opening=false;
        if(session!=null){try{session.close();}catch(Exception ignored){}session=null;}
        if(cameraDevice!=null){try{cameraDevice.close();}catch(Exception ignored){}cameraDevice=null;}
        if(cameraSurface!=null){cameraSurface.release();cameraSurface=null;}
    }

    @Override protected void onDestroy(){
        closeCamera();
        if(cameraThread!=null)cameraThread.quitSafely();
        super.onDestroy();
    }

    public interface TextureCallback { void onReady(SurfaceTexture texture); }
    public interface StatsCallback { void onStats(String stats); }
}
