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
    private TextView videoLabelsLeft,videoLabelsRight,zoomBadge,modeBadge;
    private FrameLayout root,drawer,videoArea;
    private boolean fillMode=true,fullMode=false,frozen=false,drawerVisible=false;
    private boolean usingFile=false;
    private Uri fileUri;
    private MediaPlayer mediaPlayer;
    private static final int FILE_REQUEST=20;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float touchX,touchY;
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

    private void drawHeader(){
        LinearLayout bar=new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(9),dp(5),dp(9),dp(5));
        bar.setBackgroundColor(Color.rgb(37,40,44));
        TextView logo=text("◉",23,ACCENT);
        logo.setPadding(dp(5),0,dp(10),0);
        bar.addView(logo);
        TextView title=text("Dehaze LIVE",17,INK);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bar.addView(title);
        LinearLayout.LayoutParams stretch=new LinearLayout.LayoutParams(0,dp(2),1f);
        bar.addView(new View(this),stretch);
        statsView=text("FPS —  •  — мс  •  GPU  •  720p",11,MUTED);
        bar.addView(statsView);
        TextView menu=button("☰",()->setDrawer(!drawerVisible));
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(47),dp(40));
        mp.leftMargin=dp(8);
        bar.addView(menu,mp);
        FrameLayout.LayoutParams top=new FrameLayout.LayoutParams(-1,dp(52),Gravity.TOP);
        root.addView(bar,top);
    }

    private void drawLabels(){
        LinearLayout overlay=new LinearLayout(this);
        boolean portrait=getResources().getConfiguration().orientation==Configuration.ORIENTATION_PORTRAIT;
        overlay.setOrientation(portrait?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
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
        LinearLayout.LayoutParams share=portrait
            ?new LinearLayout.LayoutParams(-1,0,1f)
            :new LinearLayout.LayoutParams(0,-1,1f);
        overlay.addView(l,share);
        LinearLayout.LayoutParams share2=portrait
            ?new LinearLayout.LayoutParams(-1,0,1f)
            :new LinearLayout.LayoutParams(0,-1,1f);
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
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(45));
        lp.bottomMargin=dp(8);
        list.addView(t,lp);
    }

    private void menuTitle(LinearLayout list,String title){
        TextView t=text(title,12,MUTED);
        t.setPadding(0,dp(9),0,dp(6));
        list.addView(t);
    }

    private void makeDrawer(){
        drawer=new FrameLayout(this);
        drawer.setBackgroundColor(Color.rgb(42,45,49));
        ScrollView scroll=new ScrollView(this);
        LinearLayout list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(15),dp(12),dp(15),dp(16));
        scroll.addView(list);
        drawer.addView(scroll);
        menuTitle(list,"КЕРУВАННЯ");
        menuItem(list,"✕  Закрити меню",()->setDrawer(false));
        toggle=button("Антитуман: УВІМКНЕНО",()->{
            enhanced=!enhanced;
            renderer.setEnhanced(enhanced);
            toggle.setText(enhanced?"Антитуман: УВІМКНЕНО":"Антитуман: ВИМКНЕНО");
            renderer.refresh();
        });
        LinearLayout.LayoutParams basic=new LinearLayout.LayoutParams(-1,dp(45));
        list.addView(toggle,basic);
        menuTitle(list,"ДЖЕРЕЛО ВІДЕО");
        menuItem(list,"◉  Камера пристрою",this::useCamera);
        menuItem(list,"▣  Відкрити відеофайл",this::pickVideo);
        menuTitle(list,"ВІДОБРАЖЕННЯ");
        menuItem(list,"FIT / FILL",()->{
            fillMode=!fillMode;renderer.setFill(fillMode);
            setState(fillMode?"Режим FILL":"Режим FIT");
            renderer.refresh();
        });
        menuItem(list,"SPLIT / FULL",()->{
            fullMode=!fullMode;renderer.setFull(fullMode);
            videoLabelsLeft.setVisibility(fullMode?View.GONE:View.VISIBLE);
            videoLabelsRight.setText(fullMode?"АНТИТУМАН • FULL":"АНТИТУМАН");
            renderer.refresh();
        });
        menuItem(list,"↻  Повернути зображення на 90°",()->{
            renderer.rotate90();renderer.refresh();setState("Поворот зображення змінено");
        });
        menuItem(list,"Зум 1× / Скинути",()->{
            renderer.resetZoom();zoomBadge.setText("1.0×");renderer.refresh();
        });
        menuTitle(list,"СИЛА ОБРОБКИ");
        final TextView amount=text(strength+"%",13,INK);
        list.addView(amount);
        SeekBar seek=new SeekBar(this);
        seek.setMax(100);seek.setProgress(strength);
        if(Build.VERSION.SDK_INT>=21)seek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        list.addView(seek,new LinearLayout.LayoutParams(-1,dp(42)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int value,boolean fromUser){
                strength=value;renderer.setStrength(value/100f);amount.setText(value+"%");
                renderer.refresh();
            }
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
        });
        menuTitle(list,"ЗНІМКИ");
        menuItem(list,"▣  Зберегти знімок",this::takeSnapshot);
        menuItem(list,"Ⅱ  Стоп-кадр / Продовжити",()->{
            frozen=!frozen;renderer.setFrozen(frozen);
            if(!frozen)renderer.refresh();
            setState(frozen?"Стоп-кадр":"Відтворення відновлено");
        });
        stateView=text("Локальна обробка • офлайн",11,MUTED);
        stateView.setPadding(0,dp(13),0,dp(13));
        list.addView(stateView);
        FrameLayout.LayoutParams side=new FrameLayout.LayoutParams(dp(264),-1,Gravity.RIGHT);
        side.topMargin=dp(52);
        root.addView(drawer,side);
        setDrawer(false);
    }

    private void setDrawer(boolean show){
        drawerVisible=show;
        drawer.setVisibility(show?View.VISIBLE:View.GONE);
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
        renderer.setFill(true);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        videoArea.addView(glView,new FrameLayout.LayoutParams(-1,-1));
        drawLabels();
        FrameLayout.LayoutParams area=new FrameLayout.LayoutParams(-1,-1);
        area.topMargin=dp(52);
        root.addView(videoArea,area);
        drawHeader();
        makeDrawer();
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

    private void pickVideo(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("video/*");intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent,FILE_REQUEST);
        setDrawer(false);
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==FILE_REQUEST&&result==RESULT_OK&&data!=null&&data.getData()!=null){
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
            cameraTexture.setDefaultBufferSize(width,height);
            mediaPlayer=new MediaPlayer();
            mediaPlayer.setDataSource(this,fileUri);
            cameraSurface=new Surface(cameraTexture);
            mediaPlayer.setSurface(cameraSurface);
            mediaPlayer.setLooping(true);
            mediaPlayer.setOnPreparedListener(mp->{mp.start();setState("Локальне відео • працює без інтернету");});
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
        stopMedia();usingFile=false;fileUri=null;
        setDrawer(false);maybeOpenCamera();
    }

    private void takeSnapshot(){
        renderer.captureNext(bitmap->new Thread(()->{
            try{
                String name="Dehaze-"+System.currentTimeMillis()+".png";
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

    private void onFrameStats(final String stats) {
        runOnUiThread(()->{if(active)statsView.setText(stats);});
    }

    private void setState(final String info){
        runOnUiThread(()->stateView.setText(info));
    }

    private void onCameraTextureReady(SurfaceTexture texture) {
        cameraTexture=texture;
        if(active){if(usingFile)startVideoFile();else maybeOpenCamera();}
    }

    @Override protected void onResume() {
        super.onResume();active=true;glView.onResume();
        if(usingFile)startVideoFile();else maybeOpenCamera();
    }

    @Override protected void onPause() {
        active=false;stopMedia();closeCamera();glView.onPause();
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
        stopMedia();closeCamera();
        if(cameraThread!=null)cameraThread.quitSafely();
        super.onDestroy();
    }

    public interface TextureCallback { void onReady(SurfaceTexture texture); }
    public interface StatsCallback { void onStats(String stats); }
}
