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
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.SurfaceHolder;
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
    private SurfaceView directVideoView;
    private SurfaceHolder directHolder;
    private boolean directSurfaceReady=false;
    private SplitRenderer renderer;
    private SurfaceTexture cameraTexture;
    private CameraManager cameraManager;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private Surface cameraSurface;
    private TextView statsView,diagnosticView,headerSafeButton;
    private TextView autoModeButton,onModeButton,offModeButton,lowButton,mediumButton,highButton,panelButton;
    private boolean safeGpu=false;
    private TextView stateView;
    private TextView toggle;
    private TextView videoLabelsLeft,videoLabelsRight,zoomBadge,modeBadge;
    private FrameLayout root,drawer,videoArea;
    private boolean fillMode=true,frozen=false,drawerVisible=false;
    private int viewMode=2;  // 0 = 50/50 wipe, 1 = two identical FILL previews, 2 = processed fullscreen
    private View drawerScrim;
    private ImageView freezeOverlay;
    private TextView resumeOverlay;
    private TextView headerResumeButton;
    private TextView drawerFreezeButton;
    private Bitmap heldFrame;
    private boolean pausedFileForFreeze=false;
    private boolean usingFile=true;
    private Uri fileUri;
    private MediaPlayer mediaPlayer;
    private static final int FILE_REQUEST=20;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float touchX,touchY;
    private boolean active, opening;
    private boolean enhanced = true;
    private boolean liveMaxMode = true;
    private int dehazeMode=0; // 0=AUTO, 1=ON/manual, 2=OFF/direct video
    private int manualLevel=DigitalDehazePolicy.MEDIUM;
    private boolean panelProtection=true;
    private String autoProfile="DAY";
    private float autoHaze=0f;
    private long autoCadenceMs=420;
    private TextView maxModeButton;
    private int strength = 60;
    private volatile int autoStrength = 60;
    private boolean manualMode = false;
    private CheckBox manualCheck;
    private SeekBar strengthSeek;
    private TextView strengthLabel;
    private int cameraCorrectionDegrees; // saved hardware camera alignment; never rotates the UI

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        showImmersive();
        cameraCorrectionDegrees=getPreferences(MODE_PRIVATE).getInt(
            "camera_alignment_degrees",defaultCameraCorrection());
        cameraManager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        cameraThread = new HandlerThread("camera2-preview");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        makeUi();
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

    private void showImmersive(){
        // Landscape stays locked; hide both Android bars until an edge swipe.
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void drawHeader(){
        LinearLayout bar=new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10),dp(3),dp(8),dp(3));
        bar.setBackgroundColor(Color.rgb(40,43,47));
        TextView logo=text("◉",19,ACCENT);
        logo.setGravity(Gravity.CENTER_VERTICAL);
        logo.setPadding(dp(4),0,dp(10),0);
        bar.addView(logo);
        TextView title=text("Digital Dehazing VIDEO TEST",15,INK);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bar.addView(title);
        bar.addView(new View(this),new LinearLayout.LayoutParams(0,dp(1),1f));
        statsView=text("Очікування камери",11,MUTED);
        statsView.setSingleLine(true);
        bar.addView(statsView);
        TextView photoTop=button("Відкрити відео",this::pickVideo);
        photoTop.setBackgroundColor(Color.rgb(70,103,91));
        LinearLayout.LayoutParams photoTopParams=new LinearLayout.LayoutParams(dp(132),dp(37));
        photoTopParams.leftMargin=dp(8);
        bar.addView(photoTop,photoTopParams);
        headerSafeButton=button("GPU SAFE",()->{
            safeGpu=!safeGpu;
            renderer.setForceFast(safeGpu);
            headerSafeButton.setText(safeGpu?"GPU FAST":"GPU SAFE");
            setState(safeGpu?"Увімкнено стабільний GPU FAST":"Увімкнено VIDEO MAX (перевір зображення)");
        });
        headerSafeButton.setBackgroundColor(Color.rgb(58,77,82));
        LinearLayout.LayoutParams safeLp=new LinearLayout.LayoutParams(dp(90),dp(37));
        safeLp.leftMargin=dp(6);
        bar.addView(headerSafeButton,safeLp);
        headerResumeButton=button("▶ ПРОДОВЖИТИ",this::resumeFreeze);
        headerResumeButton.setTextColor(Color.WHITE);
        headerResumeButton.setBackgroundColor(Color.rgb(43,108,87));
        headerResumeButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        headerResumeButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams resumeHeaderSize=
            new LinearLayout.LayoutParams(dp(161),dp(38));
        resumeHeaderSize.leftMargin=dp(8);
        bar.addView(headerResumeButton,resumeHeaderSize);
        TextView menu=button("☰",()->setDrawer(!drawerVisible));
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(44),dp(36));
        mp.leftMargin=dp(9);
        bar.addView(menu,mp);
        root.addView(bar,new FrameLayout.LayoutParams(-1,dp(46),Gravity.TOP));
    }

    private void drawLabels(){
        LinearLayout overlay=new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.TOP);
        overlay.setPadding(dp(9),dp(9),dp(9),0);
        videoLabelsLeft=text("ОРИГІНАЛ",12,INK);
        videoLabelsRight=text("DIGITAL DEHAZING",12,INK);
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
        videoLabelsRight.setText("DIGITAL DEHAZING");
        renderer.refresh();
        setDrawer(false);
        setState(mode==0?"Порівняння 50/50 без деформації":
            mode==1?"Два повні кадри зі збереженням пропорцій":
                    "Оброблене відео на весь екран");
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

        menuTitle(list,"DIGITAL DEHAZING  •  VIDEO TEST");
        menuItem(list,"▣  Відкрити відео",this::pickVideo);
        menuItem(list,"✕  Сховати",()->setDrawer(false));

        menuTitle(list,"DIGITAL DEHAZING");
        LinearLayout modeRow=new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        autoModeButton=button("AUTO",()->setDehazeMode(0));
        onModeButton=button("ON",()->setDehazeMode(1));
        offModeButton=button("OFF",()->setDehazeMode(2));
        modeRow.addView(autoModeButton,new LinearLayout.LayoutParams(0,dp(44),1));
        LinearLayout.LayoutParams onLp=new LinearLayout.LayoutParams(0,dp(44),1);onLp.leftMargin=dp(6);
        modeRow.addView(onModeButton,onLp);
        LinearLayout.LayoutParams offLp=new LinearLayout.LayoutParams(0,dp(44),1);offLp.leftMargin=dp(6);
        modeRow.addView(offModeButton,offLp);
        list.addView(modeRow,new LinearLayout.LayoutParams(-1,dp(44)));

        menuTitle(list,"DEHAZING LEVEL");
        LinearLayout levelRow=new LinearLayout(this);
        levelRow.setOrientation(LinearLayout.HORIZONTAL);
        lowButton=button("LOW",()->setManualLevel(DigitalDehazePolicy.LOW));
        mediumButton=button("MEDIUM",()->setManualLevel(DigitalDehazePolicy.MEDIUM));
        highButton=button("HIGH",()->setManualLevel(DigitalDehazePolicy.HIGH));
        levelRow.addView(lowButton,new LinearLayout.LayoutParams(0,dp(44),1));
        LinearLayout.LayoutParams medLp=new LinearLayout.LayoutParams(0,dp(44),1);medLp.leftMargin=dp(6);
        levelRow.addView(mediumButton,medLp);
        LinearLayout.LayoutParams highLp=new LinearLayout.LayoutParams(0,dp(44),1);highLp.leftMargin=dp(6);
        levelRow.addView(highButton,highLp);
        list.addView(levelRow,new LinearLayout.LayoutParams(-1,dp(44)));
        strengthLabel=text("AUTO • аналіз сцени",12,INK);
        strengthLabel.setPadding(0,dp(7),0,dp(7));
        list.addView(strengthLabel);

        panelButton=button("Панелі: захист ON",()->{
            panelProtection=!panelProtection;
            renderer.setPanelProtection(panelProtection);
            panelButton.setText(panelProtection?"Панелі: захист ON":"Панелі: захист OFF");
        });
        LinearLayout.LayoutParams panelLp=new LinearLayout.LayoutParams(-1,dp(42));
        panelLp.topMargin=dp(6);list.addView(panelButton,panelLp);
        syncDehazeButtons();

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

        menuTitle(list,"ДІЇ");
        menuItem(list,"▣  Знімок",this::takeSnapshot);
        drawerFreezeButton=button("Ⅱ  Стоп-кадр",this::toggleFreeze);
        LinearLayout.LayoutParams freezeMenuParams=
            new LinearLayout.LayoutParams(-1,dp(44));
        freezeMenuParams.bottomMargin=dp(6);
        list.addView(drawerFreezeButton,freezeMenuParams);
        stateView=text("Локальне відео • офлайн • AUTO за замовчуванням",10,MUTED);
        stateView.setPadding(0,dp(10),0,dp(5));
        list.addView(stateView);

        FrameLayout.LayoutParams side=new FrameLayout.LayoutParams(dp(315),-1,Gravity.RIGHT);
        side.topMargin=dp(46);
        root.addView(drawer,side);
        setDrawer(false);
    }

    private void setDrawer(boolean show){
        drawerVisible=show;
        drawer.setVisibility(show?View.VISIBLE:View.GONE);
        drawerScrim.setVisibility(show?View.VISIBLE:View.GONE);
    }

    private void syncDehazeButtons(){
        if(autoModeButton==null)return;
        int selected=Color.rgb(45,109,89),normal=PANEL,disabled=Color.rgb(55,58,62);
        autoModeButton.setBackgroundColor(dehazeMode==0?selected:normal);
        onModeButton.setBackgroundColor(dehazeMode==1?selected:normal);
        offModeButton.setBackgroundColor(dehazeMode==2?Color.rgb(126,66,58):normal);
        boolean manual=dehazeMode==1;
        lowButton.setBackgroundColor(manual&&manualLevel==DigitalDehazePolicy.LOW?selected:disabled);
        mediumButton.setBackgroundColor(manual&&manualLevel==DigitalDehazePolicy.MEDIUM?selected:disabled);
        highButton.setBackgroundColor(manual&&manualLevel==DigitalDehazePolicy.HIGH?selected:disabled);
        lowButton.setAlpha(manual?1f:.55f);mediumButton.setAlpha(manual?1f:.55f);highButton.setAlpha(manual?1f:.55f);
    }

    private void setDehazeMode(int mode){
        dehazeMode=Math.max(0,Math.min(2,mode));
        if(dehazeMode==0){
            enhanced=true;manualMode=false;
            renderer.setEnhanced(true);renderer.setManualMode(false);
            setState("Digital Dehazing • AUTO • DAY/NIGHT визначається автоматично");
        }else if(dehazeMode==1){
            enhanced=true;manualMode=true;
            renderer.setEnhanced(true);renderer.setManualMode(true);
            renderer.setStrength(DigitalDehazePolicy.manualStrength(manualLevel));
            strength=manualLevel;
            if(strengthLabel!=null)strengthLabel.setText("MANUAL • "+manualLevel+"%");
            setState("Digital Dehazing • ON • ручний рівень "+manualLevel+"%");
        }else{
            enhanced=false;manualMode=true;
            renderer.setEnhanced(false);renderer.setManualMode(true);
            if(strengthLabel!=null)strengthLabel.setText("OFF • пряме оригінальне відео");
            setState("Digital Dehazing • OFF • GPU dehaze bypass");
        }
        syncDehazeButtons();
        routeFileOutput();
        renderer.refresh();
    }

    private void setManualLevel(int level){
        manualLevel=level;
        if(dehazeMode!=1)setDehazeMode(1);
        renderer.setStrength(DigitalDehazePolicy.manualStrength(level));
        strength=level;
        if(strengthLabel!=null)strengthLabel.setText("MANUAL • "+level+"%");
        syncDehazeButtons();renderer.refresh();
    }

    private void routeFileOutput(){
        if(!usingFile||mediaPlayer==null)return;
        try{
            if(!enhanced&&directSurfaceReady&&directHolder!=null&&directHolder.getSurface().isValid()){
                mediaPlayer.setSurface(directHolder.getSurface());
                directVideoView.setVisibility(View.VISIBLE);
                glView.setVisibility(View.GONE);
            }else{
                if(cameraSurface==null&&cameraTexture!=null)cameraSurface=new Surface(cameraTexture);
                if(cameraSurface!=null)mediaPlayer.setSurface(cameraSurface);
                directVideoView.setVisibility(View.GONE);
                glView.setVisibility(View.VISIBLE);
                renderer.refresh();
            }
        }catch(IllegalStateException e){setState("Не вдалося перемкнути відеовихід: "+e.getMessage());}
    }

    private void makeUi(){
        root=new FrameLayout(this);
        root.setBackgroundColor(BG);
        videoArea=new FrameLayout(this);
        directVideoView=new SurfaceView(this);
        directHolder=directVideoView.getHolder();
        directVideoView.setVisibility(View.GONE);
        directHolder.addCallback(new SurfaceHolder.Callback(){
            @Override public void surfaceCreated(SurfaceHolder holder){directSurfaceReady=true;routeFileOutput();}
            @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){directSurfaceReady=true;routeFileOutput();}
            @Override public void surfaceDestroyed(SurfaceHolder holder){directSurfaceReady=false;}
        });
        videoArea.addView(directVideoView,new FrameLayout.LayoutParams(-1,-1));
        glView=new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        renderer=new SplitRenderer(this,glView,this::onCameraTextureReady,this::onFrameStats);
        renderer.setStrength(strength/100f);
        renderer.setMaxMode(true);
        renderer.setFill(true);
        renderer.setViewMode(2);
        renderer.setPanelProtection(panelProtection);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        videoArea.addView(glView,new FrameLayout.LayoutParams(-1,-1));
        diagnosticView=text("Відкрий відео • Digital Dehazing AUTO",11,Color.WHITE);
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
        videoLabelsLeft.setVisibility(View.GONE);
        videoLabelsRight.setVisibility(View.GONE);
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
        if(!active||fileUri==null){
            setState("Спочатку відкрий відео");
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
        if(!enhanced){
            setDrawer(false);
            setState("Пауза • оригінальне відео • Digital Dehazing OFF");
            return;
        }
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
        if(request==FILE_REQUEST&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            clearFreeze();
            fileUri=data.getData();
            usingFile=true;
            closeCamera();
            startVideoFile();
        }
    }

    private void startVideoFile(){
        if(fileUri==null||cameraTexture==null||!active)return;
        stopMedia();
        try{
            MediaMetadataRetriever meta=new MediaMetadataRetriever();
            meta.setDataSource(this,fileUri);
            int width=Integer.parseInt(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height=Integer.parseInt(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rawRotation=meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rotation=rawRotation==null?0:Integer.parseInt(rawRotation);
            meta.release();
            renderer.setCameraInfo(width,height,rotation,false);
            // Media files carry their own orientation metadata; a camera-device
            // calibration must NEVER rotate an imported movie.
            renderer.setUserRotation(0);
            cameraTexture.setDefaultBufferSize(width,height);
            mediaPlayer=new MediaPlayer();
            mediaPlayer.setDataSource(this,fileUri);
            cameraSurface=new Surface(cameraTexture);
            routeFileOutput();
            mediaPlayer.setLooping(true);
            mediaPlayer.setOnPreparedListener(mp->{routeFileOutput();mp.start();setState("Локальне відео • Digital Dehazing "+(dehazeMode==0?"AUTO":dehazeMode==1?"ON":"OFF"));});
            mediaPlayer.setOnErrorListener((mp,what,extra)->{
                setState("Не вдалося відкрити відео: "+what);
                return false;
            });
            mediaPlayer.prepareAsync();
        }catch(Exception e){setState("Помилка відкриття відео: "+e.getMessage());}
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
        stopMedia();usingFile=false;fileUri=null;
        setDrawer(false);maybeOpenCamera();
    }

    private void takeSnapshot(){
        if(!enhanced){setState("Для знімка увімкни AUTO або ON");return;}
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
            if(!active||dehazeMode!=0||strengthLabel==null)return;
            strengthLabel.setText("AUTO • "+autoProfile+" • "+autoStrength+"% • haze "+Math.round(autoHaze*100f)+"%");
        });
    }

    public void onAutoProfile(boolean night,float haze,long cadenceMs){
        autoProfile=night?"NIGHT":"DAY";autoHaze=haze;autoCadenceMs=cadenceMs;
        runOnUiThread(()->{
            if(dehazeMode==0&&strengthLabel!=null)
                strengthLabel.setText("AUTO • "+autoProfile+" • "+autoStrength+"% • haze "+Math.round(autoHaze*100f)+"%");
        });
    }

    public void onRendererStatus(String info){setState(info);}

    public void onAutoUnavailable(){
        runOnUiThread(()->{
            if(strengthLabel!=null&&!manualMode)
                strengthLabel.setText("AUTO недоступний на цьому GPU • встанови Ручний");
        });
    }

    private void onFrameStats(final String stats) {
        runOnUiThread(()->{
            if(!active)return;
            String mode=dehazeMode==0?"AUTO "+autoProfile+" "+autoStrength+"%":dehazeMode==1?"MANUAL "+manualLevel+"%":"OFF DIRECT";
            statsView.setText(stats+" • "+mode);
            if(diagnosticView!=null)diagnosticView.setText("КАДРИ Є • "+mode+" • "+stats+
                (dehazeMode==0?" • аналіз "+autoCadenceMs+" мс":""));
        });
    }

    private void setState(final String info){
        runOnUiThread(()->{
            if(stateView!=null)stateView.setText(info);
            if(diagnosticView!=null)diagnosticView.setText(info);
        });
    }

    private void onCameraTextureReady(SurfaceTexture texture) {
        cameraTexture=texture;
        if(active&&usingFile)startVideoFile();
    }

    @Override protected void onResume() {
        super.onResume();showImmersive();active=true;glView.onResume();
        if(usingFile)startVideoFile();
    }

    @Override protected void onPause() {
        clearFreeze();active=false;stopMedia();closeCamera();glView.onPause();
        super.onPause();
    }

    private void maybeOpenCamera(){
        if(!active||usingFile||cameraTexture==null||cameraDevice!=null||opening)return;
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
        if(opening||!active||usingFile||cameraTexture==null)return;
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
                        opening=false;setState("Камера • корекція "+cameraCorrectionDegrees+
                            "° • GPU • офлайн");
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
        clearFreeze();stopMedia();closeCamera();
        if(cameraThread!=null)cameraThread.quitSafely();
        if(renderer!=null)renderer.shutdown();
        super.onDestroy();
    }

    public interface TextureCallback { void onReady(SurfaceTexture texture); }
    public interface StatsCallback { void onStats(String stats); }
}
