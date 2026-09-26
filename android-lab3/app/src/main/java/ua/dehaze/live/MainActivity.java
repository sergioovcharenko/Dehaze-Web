package ua.dehaze.live;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.content.Intent;
import android.net.Uri;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.GestureDetector;
import android.widget.ScrollView;
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
import android.os.Looper;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.content.res.ColorStateList;
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
    private TextView statsView,diagnosticView,headerSafeButton;
    private boolean safeGpu=false;
    private TextView stateView;
    private TextView toggle,headerToggle;
    private int cameraEpoch,videoPosition;
    private TextView videoLabelsLeft,videoLabelsRight,zoomBadge,modeBadge;
    private FrameLayout root,drawer,videoArea;
    private boolean fillMode=true,frozen=false,drawerVisible=false;
    private int viewMode=1;  // 0 = 50/50 wipe, 1 = two identical FILL previews, 2 = processed fullscreen
    private View drawerScrim;
    private ImageView freezeOverlay;
    private TextView resumeOverlay;
    private TextView headerResumeButton;
    private TextView drawerFreezeButton;
    private Bitmap heldFrame;
    private boolean pausedFileForFreeze=false;
    private boolean usingFile=false;
    private Uri fileUri;
    private MediaPlayer mediaPlayer;
    private static final int FILE_REQUEST=20;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float touchX,touchY;
    private boolean active, opening;
    private boolean enhanced = true;
    private boolean liveMaxMode = true;
    private TextView maxModeButton;
    private int strength = 60;
    private volatile int autoStrength = 60;
    private boolean manualMode = false;
    private CheckBox manualCheck;
    private SeekBar strengthSeek;
    private TextView strengthLabel;
    private int cameraCorrectionDegrees; // saved hardware camera alignment; never rotates the UI
    private final Handler labTestHandler=new Handler(Looper.getMainLooper());
    private int requestedLabTest=DeviceTest.NONE;
    private boolean deviceTestStarted=false;
    private final Runnable statusPulse=new Runnable(){public void run(){if(!active)return;renderer.refresh();labTestHandler.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        showImmersive();
        cameraCorrectionDegrees=getPreferences(MODE_PRIVATE).getInt(
            "camera_alignment_degrees",defaultCameraCorrection());
        LabRuntime.init(this);
        requestedLabTest=getIntent().getIntExtra("lab_test_mode",DeviceTest.NONE);
        // For imported video do not open the physical camera while user chooses a file.
        if(requestedLabTest==DeviceTest.VIDEO)usingFile=true;
        cameraManager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        // Serialize source lifecycle and callbacks on the Activity thread.
        cameraHandler = new Handler(Looper.getMainLooper());
        makeUi();
        if(requestedLabTest!=DeviceTest.NONE){
            renderer.setViewMode(1);
            renderer.setFill(true);
            renderer.setMaxMode(true);
            if(requestedLabTest==DeviceTest.VIDEO){
                glView.postDelayed(()->{
                    if(!isFinishing()&&requestedLabTest==DeviceTest.VIDEO)pickVideo();
                },550);
            }else{
                setState("ТЕСТ КАМЕРИ • очікуємо доступу до камери");
            }
        }
    }

    private void startLabTest(int mode){
        if(requestedLabTest!=mode||deviceTestStarted||isFinishing())return;
        runOnUiThread(()->{
            if(deviceTestStarted||isFinishing())return;
            deviceTestStarted=true;
            renderer.setViewMode(1);
            renderer.setFill(true);
            DeviceTest.begin(mode);
            setState((mode==DeviceTest.CAMERA?"ТЕСТ КАМЕРИ":"ТЕСТ ВІДЕО")+
                " • 12 секунд • перевірка двох зображень");
            labTestHandler.postDelayed(()->{
                if(DeviceTest.running())DeviceTest.finish();
                if(!isFinishing())finish();
            },DeviceTest.TEST_SECONDS*1000L);
        });
    }

    private static int defaultCameraCorrection(){
        // The Active 10 Pro's rear-camera buffer is sideways with Android's
        // generic preview calculation in this fixed-landscape UI. It previously
        // required a manual quarter-turn. Make that correction automatic.
        String model=Build.MODEL==null?"":Build.MODEL.toLowerCase(Locale.ROOT)
            .replace(" ","").replace("-","");
        return model.contains("active10pro")?90:0;
    }

    private void calibrateCamera(){
        if(usingFile){
            android.widget.Toast.makeText(this,
                "Відкрий камеру для калібрування її положення",android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        cameraCorrectionDegrees=(cameraCorrectionDegrees+90)%360;
        getPreferences(MODE_PRIVATE).edit()
            .putInt("camera_alignment_degrees",cameraCorrectionDegrees).apply();
        renderer.setUserRotation(cameraCorrectionDegrees);
        renderer.resetZoom();
        zoomBadge.setText("1.0×");
        renderer.refresh();
        android.widget.Toast.makeText(this,
            "Корекція камери +"+cameraCorrectionDegrees+"° збережена",
            android.widget.Toast.LENGTH_SHORT).show();
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(sizeSp); t.setTextColor(color);
        return t;
    }
    private int dp(float x) { return (int)(getResources().getDisplayMetrics().density*x+.5f); }

    private final int BG=Color.rgb(33,35,39);
    private final int PANEL=Color.rgb(48,51,56);
    private final int INK=Color.rgb(241,243,245);
    private final int MUTED=Color.rgb(187,192,198);
    private final int ACCENT=Color.rgb(102,190,199);

    private TextView button(String value,Runnable action){
        TextView t=text(value,13,INK);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(11),dp(10),dp(11),dp(10));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(PANEL);bg.setCornerRadius(dp(9));
        bg.setStroke(dp(1),Color.rgb(87,91,96));
        t.setBackground(bg);
        t.setOnClickListener(v->action.run());
        return t;
    }

    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)showImmersive();}
    @Override public void onBackPressed(){if(drawerVisible)setDrawer(false);else if(frozen)resumeFreeze();else super.onBackPressed();}
    private void showImmersive(){
        // onCreate runs before setContentView: initialize DecorView before the
        // platform Window getter (Android 15 PhoneWindow dereferences mDecor).
        View decor=getWindow().getDecorView();
        if(Build.VERSION.SDK_INT>=30){
            android.view.WindowInsetsController controller=decor.getWindowInsetsController();
            if(controller!=null){controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);controller.hide(android.view.WindowInsets.Type.systemBars());return;}
        }
        // Landscape stays locked; hide both Android bars until an edge swipe.
        decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void drawHeader(){
        LinearLayout bar=new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10),dp(3),dp(8),dp(3));
        bar.setBackgroundColor(Color.rgb(40,43,47));
        TextView title=text("◉  Меті Туман LAB 4 LIVE",14,INK);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        bar.addView(title);
        statsView=text("Очікування",11,MUTED);
        statsView.setSingleLine(true);
        statsView.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        statsView.setPadding(dp(8),0,dp(8),0);
        bar.addView(statsView,new LinearLayout.LayoutParams(0,-1,1));
        TextView photo=button("Фото / 5",this::openComparison);
        bar.addView(photo,new LinearLayout.LayoutParams(dp(80),dp(38)));
        headerToggle=button("ANTI-FOG ON",this::toggleEnhanced);
        LinearLayout.LayoutParams anti=new LinearLayout.LayoutParams(dp(108),dp(38));
        anti.leftMargin=dp(6);bar.addView(headerToggle,anti);
        headerToggle.setBackgroundColor(Color.rgb(43,108,87));
        TextView menu=button("☰",()->setDrawer(!drawerVisible));
        menu.setContentDescription("Меню");
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(44),dp(38));
        mp.leftMargin=dp(6);bar.addView(menu,mp);
        root.addView(bar,new FrameLayout.LayoutParams(-1,dp(46),Gravity.TOP));
    }

    private void toggleEnhanced(){
        // A frozen processed bitmap must never cover the original after OFF.
        if(frozen)resumeFreeze();
        enhanced=!enhanced;renderer.setEnhanced(enhanced);
        headerToggle.setText(enhanced?"ANTI-FOG ON":"ANTI-FOG OFF");
        headerToggle.setBackgroundColor(enhanced?Color.rgb(43,108,87):PANEL);
        toggle.setText(enhanced?"Антитуман: ON":"Антитуман: OFF");
        videoLabelsRight.setText(enhanced?"АНТИТУМАН":"ОРИГІНАЛ • OFF");
        renderer.refresh();
    }

    private void openComparison(){
        clearFreeze();setDrawer(false);
        startActivity(new Intent(this,Lab3Activity.class));
    }

    private void drawLabels(){
        LinearLayout overlay=new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.TOP);
        overlay.setPadding(dp(9),dp(9),dp(9),0);
        videoLabelsLeft=text("ОРИГІНАЛ",12,INK);
        videoLabelsRight=text("АНТИТУМАН",12,INK);
        for(TextView v:new TextView[]{videoLabelsLeft,videoLabelsRight}){
            v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            v.setPadding(dp(9),dp(5),dp(9),dp(5));
            android.graphics.drawable.GradientDrawable b=new android.graphics.drawable.GradientDrawable();
            b.setColor(Color.argb(212,23,25,28));b.setCornerRadius(dp(8));
            v.setBackground(b);
        }
        FrameLayout l=new FrameLayout(this),right=new FrameLayout(this);
        l.addView(videoLabelsLeft,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT));
        right.addView(videoLabelsRight,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT));
        LinearLayout.LayoutParams share=new LinearLayout.LayoutParams(0,-1,1f);
        overlay.addView(l,share);
        LinearLayout.LayoutParams share2=new LinearLayout.LayoutParams(0,-1,1f);
        overlay.addView(right,share2);
        videoArea.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        zoomBadge=text("1.0×",11,INK);
        zoomBadge.setPadding(dp(10),dp(5),dp(10),dp(5));
        zoomBadge.setBackgroundColor(Color.argb(190,30,33,37));
        FrameLayout.LayoutParams zp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        zp.bottomMargin=dp(12);
        videoArea.addView(zoomBadge,zp);
    }

    private void menuItem(LinearLayout list,String title,Runnable callback){
        TextView t=button(title,callback);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(39));
        lp.bottomMargin=dp(6);
        list.addView(t,lp);
    }

    private void menuTitle(LinearLayout list,String title){
        TextView t=text(title,12,MUTED);
        t.setPadding(0,dp(10),0,dp(5));
        list.addView(t);
    }

    private void selectMode(int mode){
        viewMode=mode;
        renderer.setViewMode(mode);
        // Both 50/50 and full-screen sample exactly the same full-frame geometry.
        videoLabelsLeft.setVisibility(mode==2?View.GONE:View.VISIBLE);
        videoLabelsRight.setText(enhanced?"АНТИТУМАН":"ОРИГІНАЛ • OFF");
        renderer.refresh();
        setDrawer(false);
        setState(mode==0?"Порівняння 50/50 без деформації":
            mode==1?"Два повні кадри зі збереженням пропорцій":
                    "Оброблене відео на весь екран");
    }

    private void openDiagnostics(){
        setDrawer(false);
        startActivity(new Intent(this,DiagnosticsActivity.class));
    }

    private void openPhotoMax(){
        clearFreeze();
        setDrawer(false);
        startActivity(new Intent(this,PhotoActivity.class));
    }

    private void makeDrawer(){
        drawerScrim=new View(this);
        drawerScrim.setBackgroundColor(Color.argb(78,0,0,0));
        drawerScrim.setOnClickListener(v->setDrawer(false));
        FrameLayout.LayoutParams scrim=new FrameLayout.LayoutParams(-1,-1);
        scrim.topMargin=dp(46);
        root.addView(drawerScrim,scrim);

        drawer=new FrameLayout(this);
        drawer.setBackgroundColor(Color.rgb(47,50,54));
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(11),dp(5),dp(11),dp(13));
        scroll.addView(list);
        drawer.addView(scroll);

        menuTitle(list,"LAB 4 LIVE  •  ОФЛАЙН");
        menuItem(list,"Фото / порівняти 5 режимів",this::openComparison);
        menuItem(list,"🔧  ДІАГНОСТИКА / САМОТЕСТ",this::openDiagnostics);
        menuItem(list,"▣  ФОТО MAX — вибрати зображення",this::openPhotoMax);
        menuItem(list,"✕  Сховати",()->setDrawer(false));
        toggle=button("Антитуман: ON",this::toggleEnhanced);
        LinearLayout.LayoutParams toggleParams=new LinearLayout.LayoutParams(-1,dp(40));
        toggleParams.bottomMargin=dp(4);
        list.addView(toggle,toggleParams);
        maxModeButton=button("VIDEO MAX: УВІМКНЕНО",()->{
            liveMaxMode=!liveMaxMode;
            renderer.setMaxMode(liveMaxMode);
            maxModeButton.setText(liveMaxMode?"VIDEO MAX: УВІМКНЕНО":"LIVE FAST: УВІМКНЕНО");
            renderer.refresh();
        });
        LinearLayout.LayoutParams maxParams=new LinearLayout.LayoutParams(-1,dp(40));
        maxParams.bottomMargin=dp(5);
        list.addView(maxModeButton,maxParams);
        headerSafeButton=button("GPU SAFE: OFF",()->{
            safeGpu=!safeGpu;renderer.setForceFast(safeGpu);
            headerSafeButton.setText(safeGpu?"GPU SAFE: ON":"GPU SAFE: OFF");
            renderer.refresh();
        });
        list.addView(headerSafeButton,new LinearLayout.LayoutParams(-1,dp(40)));
        TextView liveNote=text("LIVE AUTO регулює силу CLASSIC. Вибір усіх 5 алгоритмів — у «Фото / порівняти 5».",11,MUTED);
        liveNote.setPadding(0,dp(8),0,dp(6));list.addView(liveNote);


        menuTitle(list,"ДЖЕРЕЛО");
        menuItem(list,"◉  Камера",this::useCamera);
        menuItem(list,"▣  Відкрити відео",this::pickVideo);
        menuItem(list,"↻  Калібрування камери (+90°)",this::calibrateCamera);

        menuTitle(list,"ПОРІВНЯННЯ");
        menuItem(list,"50/50  •  весь кадр",()->selectMode(0));
        menuItem(list,"Два кадри  •  FIT",()->{
            fillMode=false;renderer.setFill(false);selectMode(1);
        });
        menuItem(list,"Два кадри  •  FILL",()->{
            fillMode=true;renderer.setFill(true);selectMode(1);
        });
        menuItem(list,"Тільки оброблене",()->selectMode(2));
        menuItem(list,"Скинути зум  •  1×",()->{
            renderer.resetZoom();zoomBadge.setText("1.0×");renderer.refresh();setDrawer(false);
        });

        menuTitle(list,"СИЛА АНТИТУМАНУ");
        LinearLayout strengthRow=new LinearLayout(this);
        strengthRow.setOrientation(LinearLayout.HORIZONTAL);
        strengthRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView autoName=text("AUTO",13,ACCENT);
        autoName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        strengthRow.addView(autoName);
        strengthRow.addView(new View(this),new LinearLayout.LayoutParams(0,1,1f));
        manualCheck=new CheckBox(this);
        manualCheck.setText("Ручний");
        manualCheck.setTextColor(INK);
        manualCheck.setTextSize(13);
        manualCheck.setButtonTintList(ColorStateList.valueOf(ACCENT));
        manualCheck.setChecked(false);
        strengthRow.addView(manualCheck);
        list.addView(strengthRow,new LinearLayout.LayoutParams(-1,dp(38)));
        strengthLabel=text("AUTO • "+autoStrength+"%",13,INK);
        list.addView(strengthLabel);
        strengthSeek=new SeekBar(this);
        strengthSeek.setMax(100);
        strengthSeek.setProgress(autoStrength);
        strengthSeek.setProgressTintList(ColorStateList.valueOf(ACCENT));
        strengthSeek.setEnabled(false);
        strengthSeek.setAlpha(.40f);
        list.addView(strengthSeek,new LinearLayout.LayoutParams(-1,dp(35)));
        manualCheck.setOnCheckedChangeListener((box,checked)->{
            manualMode=checked;
            renderer.setManualMode(checked);
            strengthSeek.setEnabled(checked);
            strengthSeek.setAlpha(checked?1f:.40f);
            autoName.setTextColor(checked?MUTED:ACCENT);
            if(checked){
                strengthSeek.setProgress(strength);
                strengthLabel.setText("РУЧНИЙ • "+strength+"%");
                renderer.setStrength(strength/100f);
            }else{
                strengthSeek.setProgress(autoStrength);
                strengthLabel.setText("AUTO • "+autoStrength+"%");
            }
            renderer.refresh();
        });
        strengthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser){
                if(!manualMode)return;
                strength=value;
                renderer.setStrength(value/100f);
                strengthLabel.setText("РУЧНИЙ • "+value+"%");
                renderer.refresh();
            }
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
        });
        renderer.setManualMode(false);

        menuTitle(list,"ДІЇ");
        menuItem(list,"▣  Знімок",this::takeSnapshot);
        drawerFreezeButton=button("Ⅱ  Стоп-кадр",this::toggleFreeze);
        LinearLayout.LayoutParams freezeMenuParams=
            new LinearLayout.LayoutParams(-1,dp(44));
        freezeMenuParams.bottomMargin=dp(6);
        list.addView(drawerFreezeButton,freezeMenuParams);
        stateView=text("Камера та відеофайли • без інтернету",10,MUTED);
        stateView.setPadding(0,dp(10),0,dp(5));
        list.addView(stateView);

        FrameLayout.LayoutParams side=new FrameLayout.LayoutParams(dp(229),-1,Gravity.RIGHT);
        side.topMargin=dp(46);
        root.addView(drawer,side);
        setDrawer(false);
    }

    private void setDrawer(boolean show){
        drawerVisible=show;
        drawer.setVisibility(show?View.VISIBLE:View.GONE);
        drawerScrim.setVisibility(show?View.VISIBLE:View.GONE);
    }

    private void makeUi(){
        root=new FrameLayout(this);
        root.setBackgroundColor(BG);
        videoArea=new FrameLayout(this);
        glView=new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        renderer=new SplitRenderer(this,glView,this::onCameraTextureReady,this::onFrameStats);
        renderer.setStrength(strength/100f);
        renderer.setMaxMode(true);
        renderer.setFill(true);
        renderer.setViewMode(1);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        videoArea.addView(glView,new FrameLayout.LayoutParams(-1,-1));
        diagnosticView=text("Очікування камери • AUTO CLASSIC",11,Color.WHITE);
        diagnosticView.setBackgroundColor(Color.argb(184,12,18,23));
        diagnosticView.setPadding(dp(8),dp(5),dp(8),dp(5));
        diagnosticView.setMaxLines(2);
        FrameLayout.LayoutParams diagLp=new FrameLayout.LayoutParams(-2,-2,Gravity.LEFT|Gravity.BOTTOM);
        diagLp.leftMargin=dp(10);diagLp.bottomMargin=dp(9);
        videoArea.addView(diagnosticView,diagLp);
        // Freeze is an Android bitmap overlay, not a paused Camera2 consumer.
        // Camera frames continue to be drained behind it, so resume never
        // needs to restart a potentially blocked SurfaceTexture pipeline.
        freezeOverlay=new ImageView(this);
        freezeOverlay.setScaleType(ImageView.ScaleType.FIT_XY);
        freezeOverlay.setVisibility(View.GONE);
        freezeOverlay.setContentDescription("Зупинений кадр. Натисни, щоб продовжити.");
        freezeOverlay.setOnClickListener(v->resumeFreeze());
        videoArea.addView(freezeOverlay,new FrameLayout.LayoutParams(-1,-1));
        drawLabels();
        resumeOverlay=button("▶  ПРОДОВЖИТИ",this::resumeFreeze);
        resumeOverlay.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        resumeOverlay.setVisibility(View.GONE);
        resumeOverlay.setBackgroundColor(Color.rgb(56,98,106));
        FrameLayout.LayoutParams resumeLayout=
            new FrameLayout.LayoutParams(dp(180),dp(49),Gravity.CENTER_HORIZONTAL|Gravity.BOTTOM);
        resumeLayout.bottomMargin=dp(56);
        FrameLayout.LayoutParams area=new FrameLayout.LayoutParams(-1,-1);
        area.topMargin=dp(46);
        root.addView(videoArea,area);
        drawHeader();
        makeDrawer();
        // Root-level action is always above both the video and scrolling drawer.
        root.addView(resumeOverlay,resumeLayout);
        syncFreezeUi();
        setContentView(root);
        if(Build.VERSION.SDK_INT>=30){
            root.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
                root.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;
            });
            root.requestApplyInsets();
        }
        scaleDetector=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){
                renderer.setZoom(renderer.getZoom()*d.getScaleFactor());
                zoomBadge.setText(String.format(Locale.US,"%.1f×",renderer.getZoom()));
                renderer.refresh();
                return true;
            }
        });
        gestureDetector=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDoubleTap(MotionEvent e){
                renderer.setZoom(renderer.getZoom()<1.5f?2f:1f);
                zoomBadge.setText(String.format(Locale.US,"%.1f×",renderer.getZoom()));
                renderer.refresh();return true;
            }
        });
        glView.setOnTouchListener((v,e)->{
            scaleDetector.onTouchEvent(e);
            gestureDetector.onTouchEvent(e);
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&e.getPointerCount()==1&&!scaleDetector.isInProgress()){
                renderer.panBy(-(e.getX()-touchX)/Math.max(1,glView.getWidth()),
                    (e.getY()-touchY)/Math.max(1,glView.getHeight()));
                renderer.refresh();
            }
            touchX=e.getX();touchY=e.getY();
            return true;
        });
    }

    private void syncFreezeUi(){
        if(headerResumeButton!=null)
            headerResumeButton.setVisibility(frozen?View.VISIBLE:View.GONE);
        if(resumeOverlay!=null)
            resumeOverlay.setVisibility(frozen?View.VISIBLE:View.GONE);
        if(drawerFreezeButton!=null){
            drawerFreezeButton.setText(frozen
                ? "▶  ПРОДОВЖИТИ ВІДЕО" : "Ⅱ  Стоп-кадр");
            drawerFreezeButton.setBackgroundColor(frozen
                ? Color.rgb(43,108,87) : PANEL);
        }
    }

    private void toggleFreeze(){
        if(frozen){resumeFreeze();return;}
        if(!active||cameraTexture==null){
            setState("Спочатку запусти камеру або відкрий відео");
            setDrawer(false);return;
        }
        // Do not stop camera capture or let its SurfaceTexture queue accumulate.
        frozen=true;
        pausedFileForFreeze=false;
        if(usingFile&&mediaPlayer!=null){
            try{
                if(mediaPlayer.isPlaying()){
                    mediaPlayer.pause();
                    pausedFileForFreeze=true;
                }
            }catch(IllegalStateException ignored){}
        }
        renderer.setFrozen(true); // pauses only AUTO analysis, not input frame updates
        syncFreezeUi();
        setDrawer(false);
        setState("Стоп-кадр • ▶ Продовжити на екрані");
        renderer.captureNext(bitmap->runOnUiThread(()->{
            if(!frozen||freezeOverlay==null){
                bitmap.recycle();return;
            }
            Bitmap old=heldFrame;
            heldFrame=bitmap;
            freezeOverlay.setImageBitmap(bitmap);
            freezeOverlay.setVisibility(View.VISIBLE);
            if(old!=null&&old!=bitmap)old.recycle();
        }));
        renderer.refresh();
    }

    private void resumeFreeze(){
        if(!frozen)return;
        frozen=false;
        renderer.setFrozen(false);
        if(pausedFileForFreeze&&usingFile&&mediaPlayer!=null){
            try{mediaPlayer.start();}catch(IllegalStateException ignored){}
        }
        pausedFileForFreeze=false;
        if(freezeOverlay!=null){
            freezeOverlay.setVisibility(View.GONE);
            freezeOverlay.setImageDrawable(null);
        }
        syncFreezeUi();
        Bitmap old=heldFrame;heldFrame=null;
        if(old!=null)old.recycle();
        renderer.refresh();
        setState(usingFile?"Відтворення відеофайлу":"Камера працює • LIVE");
    }

    private void clearFreeze(){
        frozen=false;
        pausedFileForFreeze=false;
        renderer.setFrozen(false);
        if(freezeOverlay!=null){
            freezeOverlay.setVisibility(View.GONE);
            freezeOverlay.setImageDrawable(null);
        }
        syncFreezeUi();
        Bitmap old=heldFrame;heldFrame=null;
        if(old!=null)old.recycle();
    }

    private void pickVideo(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("video/*");intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent,FILE_REQUEST);
        setDrawer(false);
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==FILE_REQUEST&&requestedLabTest==DeviceTest.VIDEO&&
            (result!=RESULT_OK||data==null||data.getData()==null)){
            DeviceTest.failed("Файл не вибрано");
            finish();return;
        }
        if(request==FILE_REQUEST&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            clearFreeze();
            fileUri=data.getData();videoPosition=0;
            usingFile=true;
            closeCamera();
            startVideoFile();
        }
    }

    private void startVideoFile(){
        if(fileUri==null||cameraTexture==null||!active)return;
        stopMedia();renderer.invalidateSource();
        try{
            MediaMetadataRetriever meta=new MediaMetadataRetriever();
            int width,height,rotation;
            try{meta.setDataSource(this,fileUri);
            width=Integer.parseInt(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            height=Integer.parseInt(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rawRotation=meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            rotation=rawRotation==null?0:Integer.parseInt(rawRotation);
            }finally{meta.release();}
            renderer.setCameraInfo(width,height,rotation,false);
            // Media files carry their own orientation metadata; a camera-device
            // calibration must NEVER rotate an imported movie.
            renderer.setUserRotation(0);
            cameraTexture.setDefaultBufferSize(width,height);
            mediaPlayer=new MediaPlayer();
            mediaPlayer.setDataSource(this,fileUri);
            cameraSurface=new Surface(cameraTexture);
            mediaPlayer.setSurface(cameraSurface);
            mediaPlayer.setLooping(true);
            mediaPlayer.setOnPreparedListener(mp->{
                if(!active||mp!=mediaPlayer||!usingFile)return;
                if(videoPosition>0)mp.seekTo(videoPosition);
                mp.start();setState("Локальне відео • працює без інтернету");
                if(requestedLabTest==DeviceTest.VIDEO)
                    startLabTest(DeviceTest.VIDEO);
            });
            mediaPlayer.setOnErrorListener((mp,what,extra)->{
                if(mp!=mediaPlayer)return true;
                setState("Не вдалося відкрити відео: "+what);
                if(requestedLabTest==DeviceTest.VIDEO){
                    DeviceTest.failed("Помилка декодера відео "+what);
                    runOnUiThread(this::finish);
                }
                return true;
            });
            mediaPlayer.prepareAsync();
        }catch(Exception e){
            setState("Помилка відкриття відео: "+e.getMessage());
            if(requestedLabTest==DeviceTest.VIDEO){
                DeviceTest.failed("Файл: "+e.getMessage());
                finish();
            }
        }
    }

    private void stopMedia(){
        if(mediaPlayer!=null){
            try{mediaPlayer.stop();}catch(Exception ignored){}
            mediaPlayer.release();mediaPlayer=null;
        }
        if(usingFile&&cameraSurface!=null){cameraSurface.release();cameraSurface=null;}
    }

    private void useCamera(){
        clearFreeze();
        stopMedia();renderer.invalidateSource();usingFile=false;fileUri=null;
        setDrawer(false);maybeOpenCamera();
    }

    private void takeSnapshot(){
        renderer.captureNext(bitmap->new Thread(()->{
            try{
                String name="Meti-Tuman-MAX-"+System.currentTimeMillis()+".png";
                if(Build.VERSION.SDK_INT>=29){
                    ContentValues values=new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME,name);
                    values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Dehaze");
                    Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
                    if(uri==null)throw new IllegalStateException("Немає доступу до медіатеки");
                    try(java.io.OutputStream os=getContentResolver().openOutputStream(uri)){
                        if(os==null||!bitmap.compress(Bitmap.CompressFormat.PNG,100,os))
                            throw new IllegalStateException("Помилка збереження");
                    }
                }else{
                    java.io.File folder=getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
                    if(folder==null)throw new IllegalStateException("Пам’ять недоступна");
                    java.io.File file=new java.io.File(folder,name);
                    try(java.io.FileOutputStream os=new java.io.FileOutputStream(file)){
                        if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,os))
                            throw new IllegalStateException("Помилка збереження");
                    }
                }
                bitmap.recycle();
                setState("Знімок збережено • Pictures/Dehaze");
            }catch(Exception e){setState("Помилка знімка: "+e.getMessage());}
        }).start());
        renderer.refresh();
    }

    // AUTO is a local video contrast/texture estimate, not a calibrated
    // atmospheric fog sensor. Update UI only when the checkbox is unchecked.
    public void onAutoStrength(float value){
        autoStrength=Math.round(value*100f);
        runOnUiThread(()->{
            if(!active||manualMode||strengthLabel==null)return;
            strengthLabel.setText("AUTO • "+autoStrength+"%");
            if(strengthSeek!=null)strengthSeek.setProgress(autoStrength);
        });
    }

    public void onRendererStatus(String info){setState(info);LabRuntime.log("GPU: "+info);}

    public void onAutoUnavailable(){
        runOnUiThread(()->{
            if(strengthLabel!=null&&!manualMode)
                strengthLabel.setText("AUTO недоступний на цьому GPU • встанови Ручний");
        });
    }

    private void onFrameStats(final String stats) {
        LabRuntime.frames(stats);
        runOnUiThread(()->{
            if(!active)return;
            statsView.setText((manualMode?"РУЧНИЙ "+strength:"AUTO "+autoStrength)+"%"+(enhanced?"":" • OFF"));
            if(diagnosticView!=null)diagnosticView.setText(stats);
        });
    }

    private void setState(final String info){
        runOnUiThread(()->{
            if(stateView!=null)stateView.setText(info);
            if(diagnosticView!=null)diagnosticView.setText(info);
        });
    }

    private void onCameraTextureReady(SurfaceTexture texture) {
        LabRuntime.state(LabRuntime.CAMERA,"ГОТОВО","Поверхня камери створена");
        if(cameraTexture!=texture){stopMedia();closeCamera();}
        cameraTexture=texture;
        if(active){if(usingFile)startVideoFile();else maybeOpenCamera();}
    }

    @Override protected void onResume() {
        super.onResume();showImmersive();active=true;glView.onResume();
        labTestHandler.removeCallbacks(statusPulse);labTestHandler.postDelayed(statusPulse,1000);
        LabRuntime.state(LabRuntime.CAMERA,"ОЧІКУЄ","Відкриття Camera2 або відеофайлу");
        if(usingFile)startVideoFile();else maybeOpenCamera();
    }

    @Override protected void onPause() {
        if(deviceTestStarted&&DeviceTest.running()&&!isFinishing())
            DeviceTest.failed("Тест перервано до завершення");
        labTestHandler.removeCallbacksAndMessages(null);
        clearFreeze();active=false;
        if(mediaPlayer!=null){try{videoPosition=mediaPlayer.getCurrentPosition();}catch(IllegalStateException ignored){}}
        renderer.invalidateSource();stopMedia();closeCamera();glView.onPause();
        super.onPause();
    }

    private void maybeOpenCamera(){
        if(!active||usingFile||cameraTexture==null||cameraDevice!=null||opening)return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            LabRuntime.state(LabRuntime.CAMERA,"ДОЗВІЛ","Очікуємо дозвіл Android на камеру");
            requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);
            return;
        }
        openCamera();
    }

    @Override public void onRequestPermissionsResult(int code,String[] perms,int[] grants){
        super.onRequestPermissionsResult(code,perms,grants);
        if(code==CAMERA_PERMISSION){
            if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)maybeOpenCamera();
            else{
                if(requestedLabTest==DeviceTest.CAMERA){
                    DeviceTest.failed("Android відхилив дозвіл на камеру");
                    finish();
                }
                LabRuntime.state(LabRuntime.CAMERA,"ПОМИЛКА","Заборонено доступ CAMERA в Android");
                setState("Немає доступу до камери. Дозволь доступ у налаштуваннях Android.");
            }
        }
    }

    private void openCamera(){
        if(opening||!active||usingFile||cameraTexture==null)return;
        try{
            String chosen=null;
            for(String id:cameraManager.getCameraIdList()){
                Integer facing=cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_BACK){chosen=id;break;}
                if(chosen==null)chosen=id;
            }
            if(chosen==null){
                LabRuntime.state(LabRuntime.CAMERA,"ПОМИЛКА","Android не показав доступних камер");
                setState("На пристрої немає доступної камери.");return;
            }
            CameraCharacteristics c=cameraManager.getCameraCharacteristics(chosen);
            StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(map==null)throw new IllegalStateException("Немає підтримуваних розмірів камери");
            Integer sensor=c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer stamp=c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            int display=getWindowManager().getDefaultDisplay().getRotation();
            int displayDeg=display==Surface.ROTATION_90?90:display==Surface.ROTATION_180?180:display==Surface.ROTATION_270?270:0;
            Integer facing=c.get(CameraCharacteristics.LENS_FACING);
            int sensorDeg=(sensor==null?0:sensor);
            // Camera2 relative rotation: back = sensor-display;
            // front = sensor+display. The screen itself remains landscape.
            boolean front=facing!=null&&facing==CameraCharacteristics.LENS_FACING_FRONT;
            int rotation=((sensorDeg+(front?displayDeg:-displayDeg))%360+360)%360;
            Size size=pickSize(map.getOutputSizes(SurfaceTexture.class),rotation+cameraCorrectionDegrees);
            if(size==null)throw new IllegalStateException("Немає SurfaceTexture preview для цієї камери");
            renderer.setCameraInfo(size.getWidth(),size.getHeight(),rotation,
                stamp!=null&&stamp==CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
            renderer.setUserRotation(cameraCorrectionDegrees);
            cameraTexture.setDefaultBufferSize(size.getWidth(),size.getHeight());
            opening=true;
            final int request=++cameraEpoch;
            cameraManager.openCamera(chosen,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice camera){
                    if(!active||usingFile||request!=cameraEpoch){camera.close();return;}
                    cameraDevice=camera;
                    LabRuntime.state(LabRuntime.CAMERA,"ВІДКРИТО","Camera2 відкрито • очікуємо кадри");
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice camera){
                    camera.close();if(request!=cameraEpoch)return;cameraDevice=null;opening=false;
                    LabRuntime.state(LabRuntime.CAMERA,"ПОМИЛКА","Камеру відключено Android");
                    setState("Камеру відключено.");
                }
                @Override public void onError(CameraDevice camera,int error){
                    camera.close();if(request!=cameraEpoch)return;cameraDevice=null;opening=false;
                    LabRuntime.state(LabRuntime.CAMERA,"ПОМИЛКА","Помилка Camera2: "+error);
                    setState("Помилка Camera2: "+error);
                }
            },cameraHandler);
        }catch(Exception e){
            opening=false;LabRuntime.error(LabRuntime.CAMERA,e);
            setState("Не вдалося відкрити камеру: "+e.getMessage());
        }
    }

    private static Size pickSize(Size[] choices,int correctedRotation){
        if(choices==null||choices.length==0)return null;
        // Prefer a camera buffer that becomes landscape AFTER the sensor's
        // orientation correction. On some tablets the available buffers are
        // all landscape and become portrait when rotated; in that case we
        // crop the upright picture (FILL), rather than stretch it sideways.
        final boolean quarterTurn=((correctedRotation%180)+180)%180==90;
        Size chosen=choices[0];
        double best=Double.POSITIVE_INFINITY;
        for(Size size:choices){
            int w=quarterTurn?size.getHeight():size.getWidth();
            int h=quarterTurn?size.getWidth():size.getHeight();
            if(w<=0||h<=0)continue;
            double ratio=(double)w/h;
            double pixels=(double)size.getWidth()*size.getHeight();
            double targetAspect=16d/9d;
            double cost=Math.abs(Math.log(ratio/targetAspect))*3.0
                +Math.abs(Math.log(pixels/(1280d*720d)))*.30
                +(pixels>1920d*1080d?1.5:0.0);
            if(cost<best){best=cost;chosen=size;}
        }
        return chosen;
    }

    private void startPreview(){
        if(cameraDevice==null||cameraTexture==null)return;
        final int request=cameraEpoch;final CameraDevice device=cameraDevice;
        try{
            cameraSurface=new Surface(cameraTexture);
            CaptureRequest.Builder builder=cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(cameraSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            builder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
            cameraDevice.createCaptureSession(Arrays.asList(cameraSurface),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession cs){
                    if(!active||usingFile||request!=cameraEpoch||cameraDevice!=device){cs.close();return;}
                    session=cs;
                    try{session.setRepeatingRequest(builder.build(),null,cameraHandler);
                        opening=false;
                        LabRuntime.state(LabRuntime.CAMERA,"ПОТІК ЗАПУЩЕНО","Camera2 session готова • очікуємо кадри");
                        setState("Камера • корекція "+cameraCorrectionDegrees+
                            "° • GPU • офлайн");
                        if(requestedLabTest==DeviceTest.CAMERA)
                            startLabTest(DeviceTest.CAMERA);
                    }catch(CameraAccessException e){
                        opening=false;LabRuntime.error(LabRuntime.CAMERA,e);
                        setState("Помилка відеопотоку: "+e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs){
                    cs.close();if(request!=cameraEpoch)return;opening=false;
                    LabRuntime.state(LabRuntime.CAMERA,"ПОМИЛКА","Camera2 не підтримує цей SurfaceTexture відеорежим");
                    setState("Камера не підтримує обраний відеорежим.");
                    if(requestedLabTest==DeviceTest.CAMERA){
                        DeviceTest.failed("Camera2 не створив відеопотік");
                        runOnUiThread(MainActivity.this::finish);
                    }
                }
            },cameraHandler);
        }catch(Exception e){
            opening=false;LabRuntime.error(LabRuntime.CAMERA,e);
            setState("Не вдалося запустити відео: "+e.getMessage());
        }
    }

    private synchronized void closeCamera(){
        cameraEpoch++;opening=false;
        if(session!=null){try{session.close();}catch(Exception ignored){}session=null;}
        if(cameraDevice!=null){try{cameraDevice.close();}catch(Exception ignored){}cameraDevice=null;}
        if(cameraSurface!=null){cameraSurface.release();cameraSurface=null;}
    }

    @Override protected void onDestroy(){
        clearFreeze();stopMedia();closeCamera();
        if(cameraThread!=null)cameraThread.quitSafely();
        if(renderer!=null)renderer.shutdown();
        if(deviceTestStarted&&DeviceTest.running())
            DeviceTest.failed("Вікно тесту закрито до завершення");
        super.onDestroy();
    }

    public interface TextureCallback { void onReady(SurfaceTexture texture); }
    public interface StatsCallback { void onStats(String stats); }
}
