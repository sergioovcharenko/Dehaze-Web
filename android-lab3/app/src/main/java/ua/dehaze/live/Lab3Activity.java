package ua.dehaze.live;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.media.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import android.util.Size;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline comparison workbench. Matched snapshots are never labelled as live video. */
public final class Lab3Activity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final FrameGate gate=new FrameGate();
    private Lab3Processor processor;
    private static final int COUNT=Lab3Processor.NAMES.length, AUTO=COUNT;
    private static final String[] CHOICES={Lab3Processor.NAMES[0],Lab3Processor.NAMES[1],Lab3Processor.NAMES[2],Lab3Processor.NAMES[3],Lab3Processor.NAMES[4],"AUTO · самостійний вибір"};
    private final AutoPolicy autoPolicy=new AutoPolicy(COUNT);
    private Lab3Processor.Pair displayed;
    private boolean autoPhotoPending;
    private long lastAutoProbe;
    private float autoStrength=.7f;
    private TextureView preview;
    private ImageView before,after;
    private TextView info,pairLabel,sourceLabel;
    private Spinner algorithms;
    private CheckBox enabled;
    private SeekBar strength;
    private Bitmap photo,exportBitmap;
    private Lab3Processor.Pair[] cached=new Lab3Processor.Pair[COUNT];
    private long pairCaptured,lastSample,testElapsed,lastTick;
    private int mode=0,selected=AUTO,errors,sourceFrames,processed,skipped,sourceEpoch;
    private volatile boolean active,destroyed;
    private volatile int cameraEpoch;
    private boolean prepared,ready,testing,userPaused,holdComparison;
    private Uri pendingPhoto,selectedVideoUri;
    private MediaPlayer player;
    private volatile CameraDevice camera;
    private volatile CameraCaptureSession session;
    private Surface cameraSurface,videoSurface;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private int bufferWidth=640,bufferHeight=480,rotation;
    private String sourceName="Фото не вибране",exportText="";
    private final ArrayDeque<String> log=new ArrayDeque<>();
    private final Runnable ticker=new Runnable(){public void run(){
        if(destroyed)return;
        long now=SystemClock.elapsedRealtime();
        if(testing){
            if(active&&ready&&(mode==2||(player!=null&&prepared&&player.isPlaying())))testElapsed+=Math.max(0,now-lastTick);
            if(testElapsed>=12000)finishTest("12 секунд завершено");
        }
        lastTick=now;
        if(active&&pendingPhoto!=null)loadPendingPhoto();
        if(active&&mode==0&&photo!=null&&autoPhotoPending&&selected==AUTO&&enabled.isChecked()&&!holdComparison)capture(false);
        if(active&&ready&&mode!=0&&!holdComparison&&enabled.isChecked()&&now-lastSample>=750&&
            (mode==2||(player!=null&&prepared&&player.isPlaying()))){lastSample=now;capture(false);}
        if(pairCaptured>0)pairLabel.setText("Оригінал / результат ОДНОГО кадру • давність "+((now-pairCaptured)/1000.0)+" с");
        ui.postDelayed(this,200);
    }};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        processor=new Lab3Processor(this);LabRuntime.init(this);
        cameraThread=new HandlerThread("lab3-camera");cameraThread.start();cameraHandler=new Handler(cameraThread.getLooper());
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(8),dp(6),dp(8),dp(6));root.setBackgroundColor(Color.rgb(28,32,37));
        TextView title=label("МЕТІ ТУМАН LAB 4 AUTO",18);root.addView(title);
        LinearLayout sources=row(root);
        button(sources,"Фото",()->pick(10,"image/*"));button(sources,"Відеофайл",()->pick(11,"video/*"));
        button(sources,"Камера",this::selectCamera);button(sources,"▶ / Ⅱ",()->{if(player!=null&&prepared){userPaused=!userPaused;if(userPaused)player.pause();else player.start();}});
        button(sources,"Налаштування",this::help);
        LinearLayout controls=row(root);algorithms=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,CHOICES);
        algorithms.setAdapter(adapter);algorithms.setSelection(selected);controls.addView(algorithms);
        algorithms.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int position,long id){selectAlgorithm(position);}
        });
        enabled=new CheckBox(this);enabled.setText("Антитуман");enabled.setTextColor(Color.WHITE);enabled.setChecked(true);controls.addView(enabled);
        enabled.setOnCheckedChangeListener((b,on)->{gate.reset();errors=0;resetAuto();holdComparison=false;if(!on){displayed=null;after.setImageDrawable(null);info.setText("Вимкнено. Джерело працює без обробки.");}else if(selected!=AUTO&&cached[selected]!=null)showPair(cached[selected]);});
        strength=new SeekBar(this);strength.setMax(100);strength.setProgress(70);TextView amount=label("Сила / межа AUTO: 70%",13);root.addView(amount);root.addView(strength,new LinearLayout.LayoutParams(-1,dp(28)));
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){gate.reset();cached=new Lab3Processor.Pair[COUNT];displayed=null;resetAuto();holdComparison=false;}
            public void onProgressChanged(SeekBar s,int p,boolean user){amount.setText("Сила / межа AUTO: "+p+"%");}
        });
        LinearLayout actions=row(root);button(actions,"Обробити кадр",()->capture(false));button(actions,"Порівняти 5",()->capture(true));
        button(actions,"Тест 12 с",this::startTest);button(actions,"Зберегти PNG",()->export(12,"image/png","Meti-LAB4.png"));button(actions,"Звіт TXT",()->export(13,"text/plain","Meti-LAB4-report.txt"));
        sourceLabel=label(sourceName,12);root.addView(sourceLabel);
        preview=new TextureView(this);LinearLayout.LayoutParams previewLayout=new LinearLayout.LayoutParams(dp(178),dp(100));previewLayout.gravity=Gravity.CENTER_HORIZONTAL;root.addView(preview,previewLayout);preview.setVisibility(View.GONE);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){if(mode==2&&active)openCamera();else if(mode==1&&selectedVideoUri!=null&&active){if(player==null)openVideoWhenReady(selectedVideoUri,sourceEpoch);else{if(videoSurface!=null)videoSurface.release();videoSurface=new Surface(t);player.setSurface(videoSurface);transformPreview();if(prepared&&!userPaused)player.start();}}}
            public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){transformPreview();}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t){closeCamera();if(player!=null){player.setSurface(null);ready=false;}return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture t){ready=true;if(testing)sourceFrames++;}
        });
        pairLabel=label("Оригінал / результат одного кадру",13);root.addView(pairLabel);
        LinearLayout pairRow=new LinearLayout(this);before=new ImageView(this);after=new ImageView(this);
        before.setScaleType(ImageView.ScaleType.FIT_CENTER);after.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pairRow.addView(before,new LinearLayout.LayoutParams(0,-1,1));pairRow.addView(after,new LinearLayout.LayoutParams(0,-1,1));root.addView(pairRow,new LinearLayout.LayoutParams(-1,dp(186)));
        info=label("Вибери фото, відеофайл або камеру. Оригінальна LAB 2 встановлюється окремо.",13);
        ScrollView status=new ScrollView(this);status.addView(info);root.addView(status,new LinearLayout.LayoutParams(-1,dp(65)));
        LinearLayout legacy=row(root);button(legacy,"CLASSIC LIVE · LAB 2",()->startActivity(new Intent(this,MainActivity.class)));
        button(legacy,"PHOTO MAX · DCP",()->startActivity(new Intent(this,PhotoActivity.class)));
        button(legacy,"Діагностика CLASSIC",()->startActivity(new Intent(this,DiagnosticsActivity.class)));
        ScrollView layoutScroll=new ScrollView(this);layoutScroll.setFillViewport(true);layoutScroll.addView(root);setContentView(layoutScroll);ui.post(ticker);
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView label(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.rgb(226,235,240));t.setTextSize(size);return t;}
    private LinearLayout row(LinearLayout root){HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(r);root.addView(scroll);return r;}
    private void button(LinearLayout row,String name,Runnable action){Button b=new Button(this);b.setText(name);b.setTextSize(12);b.setAllCaps(false);b.setOnClickListener(v->action.run());row.addView(b);}
    private static String algorithmName(int algorithm){return algorithm<0?"Оригінал":Lab3Processor.NAMES[algorithm];}
    private void resetAuto(){autoPolicy.reset();lastAutoProbe=0;autoStrength=strength==null?.7f:strength.getProgress()/100f;autoPhotoPending=selected==AUTO;}
    private void selectAlgorithm(int position){
        selected=position;gate.reset();errors=0;resetAuto();if(position==AUTO)holdComparison=false;displayed=null;
        if(position!=AUTO&&cached[position]!=null)showPair(cached[position]);
        else{after.setImageDrawable(null);info.setText(position==AUTO?"AUTO: вибери джерело — алгоритм обирається автоматично.":"Режим вибрано. Натисни «Обробити кадр».");}
    }
    private void note(String s){info.setText(s);log.addLast(s);while(log.size()>200)log.removeFirst();}
    private void invalidate(){if(testing)finishTest("Зміна джерела");sourceEpoch++;resetAuto();holdComparison=false;displayed=null;gate.reset();ready=false;photo=null;pendingPhoto=null;selectedVideoUri=null;cached=new Lab3Processor.Pair[COUNT];pairCaptured=0;before.setImageDrawable(null);after.setImageDrawable(null);}
    private void pick(int code,String type){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(type);startActivityForResult(i,code);}
    private void selectCamera(){invalidate();closeSource();mode=2;sourceName="Камера · оригінальний потік; нижче — оброблені знімки";sourceLabel.setText(sourceName);preview.setVisibility(View.VISIBLE);
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},15);
        else if(preview.isAvailable())openCamera();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==15&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED&&mode==2&&active)openCamera();else if(request==15)note("Доступ до камери не надано. Фото і відеофайли доступні.");}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==10){invalidate();closeSource();mode=0;preview.setVisibility(View.GONE);pendingPhoto=uri;sourceName="Фото";sourceLabel.setText(sourceName);note("Завантаження фото…");}
        else if(request==11){invalidate();closeSource();mode=1;selectedVideoUri=uri;preview.setVisibility(View.VISIBLE);sourceName="Відеофайл · оригінальний потік; нижче — знімки";sourceLabel.setText(sourceName);preview.post(()->openVideoWhenReady(uri,sourceEpoch));}
        else if(request==12||request==13){final Bitmap image=exportBitmap;final String text=exportText;worker.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){
            if(out==null)throw new IOException("Файл недоступний");if(request==12){if(image==null||!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Немає PNG");}else out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ui.post(()->{if(!destroyed)note("Збережено.");});}catch(Exception e){ui.post(()->{if(!destroyed)note("Помилка збереження: "+e.getMessage());});}});}
    }
    private void loadPendingPhoto(){
        // Decoding is tied to the source, not to a filter selection. Switching
        // algorithms or pausing the app must not drop the user's chosen photo.
        final Uri uri=pendingPhoto;
        if(uri==null)return;
        pendingPhoto=null;
        final int epoch=sourceEpoch;
        worker.execute(()->{
            Bitmap loaded=null;String error=null;
            try{loaded=decodePhoto(uri);}catch(Exception|OutOfMemoryError e){error=e.toString();}
            final Bitmap image=loaded;final String failure=error;
            ui.post(()->{
                if(destroyed||epoch!=sourceEpoch)return;
                if(failure!=null){note("Фото: "+failure);return;}
                photo=image;before.setImageBitmap(photo);
                sourceLabel.setText("Фото "+photo.getWidth()+"×"+photo.getHeight()+" · максимум 1600 px");
                autoPhotoPending=selected==AUTO;note(selected==AUTO?"Фото готове. AUTO порівнює алгоритми…":"Фото готове. Обери режим або «Порівняти 5».");
            });
        });
    }
    private Bitmap decodePhoto(Uri uri) throws Exception {
        BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;
        try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,opts);}
        if(opts.outWidth<1||opts.outHeight<1||opts.outWidth>100000||opts.outHeight>100000)throw new IOException("Непідтримуване фото");
        opts.inSampleSize=1;while(Math.max(opts.outWidth,opts.outHeight)/opts.inSampleSize>3200)opts.inSampleSize*=2;opts.inJustDecodeBounds=false;
        Bitmap b;try(InputStream in=getContentResolver().openInputStream(uri)){b=BitmapFactory.decodeStream(in,null,opts);}if(b==null)throw new IOException("Декодування не вдалося");
        int orientation=1;try(InputStream in=getContentResolver().openInputStream(uri)){orientation=new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}
        Matrix m=new Matrix();switch(orientation){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;}
        if(!m.isIdentity())b=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);return Lab3Processor.fit(b,1600);
    }
    private void capture(boolean all){
        if(!enabled.isChecked()){note("Спочатку увімкни «Антитуман».");return;}
        if(mode==0&&photo==null){note("Спочатку вибери фото.");return;}if(mode!=0&&(!ready||!preview.isAvailable()))return;
        long ticket=gate.begin();if(ticket<0){if(testing)skipped++;if(all)note("Попередній кадр ще обробляється.");return;}
        holdComparison=all;autoPhotoPending=false;
        Bitmap raw=photo;
        if(mode!=0){try{int w=Math.max(1,preview.getWidth()),h=Math.max(1,preview.getHeight());double scale=320./Math.max(w,h);raw=preview.getBitmap(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));}catch(RuntimeException e){gate.finish(ticket);note("Захоплення: "+e.getMessage());return;}}
        if(raw==null){gate.finish(ticket);return;}
        final Bitmap snapshot=raw;final long captured=SystemClock.elapsedRealtime();final int algorithm=selected,flags=LabRuntime.flags();final float amount=strength.getProgress()/100f;final boolean isPhoto=mode==0,automatic=algorithm==AUTO;
        final boolean probe=automatic&&(all||isPhoto||!autoPolicy.initialized()||captured-lastAutoProbe>=AutoPolicy.PROBE_MS);
        final float useStrength=automatic&&!probe?autoStrength:amount;
        final int current=autoPolicy.current();final boolean[] allowed=new boolean[COUNT];for(int k=0;k<COUNT;k++)allowed[k]=autoPolicy.allowed(k,captured);
        if(isPhoto||all)note(automatic?"AUTO порівнює результати…":"Обробка…");
        worker.execute(()->{
            Lab3Processor.Pair[] pairs=new Lab3Processor.Pair[COUNT];AutoProcessing.Batch batch=null;String failure=null;
            try{
                if(automatic)batch=AutoProcessing.run(all?Lab3Processor.fit(snapshot,256):snapshot,useStrength,isPhoto,flags,allowed,probe?-2:current,processor::process);
                else{Bitmap input=all?Lab3Processor.fit(snapshot,256):snapshot;if(all){for(int k=0;k<COUNT;k++){BcDehaze.checkCancel();pairs[k]=processor.process(input,k,amount,isPhoto,flags);}}else pairs[algorithm]=processor.process(input,algorithm,amount,isPhoto,flags);}
            }catch(Exception|LinkageError|OutOfMemoryError e){failure=e.toString();}
            final String error=failure;final AutoProcessing.Batch result=batch;
            ui.post(()->{
                boolean valid=gate.finish(ticket);if(destroyed||!valid)return;
                if(error!=null){errors++;note("Помилка обробки: "+error+"\nОригінальне джерело доступне.");if(errors>=3)enabled.setChecked(false);return;}
                errors=0;pairCaptured=captured;if(testing)processed++;
                if(automatic){
                    long now=SystemClock.elapsedRealtime();
                    if(probe){lastAutoProbe=now;autoPolicy.choose(result.quality,result.elapsed,!isPhoto&&!all,now);int chosen=autoPolicy.current();autoStrength=chosen<0?0:result.strength[chosen];}
                    else if(current>=0)autoPolicy.rejectCurrent(result.quality[current],result.elapsed[current],result.elapsed[current]<0,now);
                    cached=result.pairs;showPair(result.selected(autoPolicy.current()));
                    note("AUTO → "+algorithmName(displayed.algorithm)+" • сила "+Math.round(autoStrength*100)+"% • цикл "+result.totalMs+" мс\n"+(all?"Порівняння зафіксовано. «Обробити кадр» відновить AUTO.":"Оцінка якості автоматична; нижче — знімок, не поточний live-кадр."));
                    log.addLast(result.report());
                }else{
                    cached=pairs;Lab3Processor.Pair pair=pairs[algorithm];if(pair==null)pair=pairs[0];showPair(pair);
                    for(Lab3Processor.Pair p:pairs)if(p!=null)log.addLast(p.report);
                    if(all)note("Порівняння готове: усі 5 режимів обробили один кадр до 256 px. Перемикай список.");
                }
                while(log.size()>200)log.removeFirst();
            });
        });
    }
    private void showPair(Lab3Processor.Pair pair){if(pair==null)return;displayed=pair;before.setImageBitmap(pair.original);after.setImageBitmap(enabled.isChecked()?pair.result:pair.original);info.setText(algorithmName(pair.algorithm)+" • "+pair.ms+" мс • "+pair.result.getWidth()+"×"+pair.result.getHeight()+"\nЦе оброблений знімок, не поточний live-кадр.");}
    private void startTest(){if(mode==0||!ready){note("Для тесту відкрий відеофайл або камеру.");return;}gate.reset();holdComparison=false;enabled.setChecked(true);testElapsed=0;lastTick=SystemClock.elapsedRealtime();sourceFrames=processed=skipped=0;testing=true;note("Тест 12 секунд активного відтворення розпочато.");}
    private void finishTest(String reason){testing=false;holdComparison=true;gate.reset();double seconds=Math.max(.001,testElapsed/1000.);note(String.format(Locale.US,"%s • %.1f с\nПотік: %.1f FPS; оброблено: %d (%.2f FPS); пропущені спроби: %d. Повна затримка до екрана не виміряна.",reason,seconds,sourceFrames/seconds,processed,processed/seconds,skipped));}
    private void export(int code,String mime,String name){
        if(code==12){Lab3Processor.Pair p=enabled.isChecked()?displayed:null;if(p==null){note("Спочатку оброби кадр.");return;}exportBitmap=p.result;}
        else exportText="Меті Туман LAB 4 AUTO 4.0.0\nDevice: "+Build.MANUFACTURER+" "+Build.MODEL+" Android "+Build.VERSION.RELEASE+"\nSource: "+sourceName+"\nSelected: "+CHOICES[selected]+"\nSnapshot age ms: "+(pairCaptured==0?-1:SystemClock.elapsedRealtime()-pairCaptured)+"\nAll comparisons use matched original/result frames. Live displayed source bypasses heavy processing. End-to-end latency NOT measured.\n"+String.join("\n",log);
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);i.putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(i,code);
    }
    private void openVideoWhenReady(Uri uri,int epoch){
        if(destroyed||!active||epoch!=sourceEpoch||player!=null)return;if(!preview.isAvailable()){ui.postDelayed(()->openVideoWhenReady(uri,epoch),100);return;}
        try{player=new MediaPlayer();MediaPlayer current=player;videoSurface=new Surface(preview.getSurfaceTexture());current.setSurface(videoSurface);current.setDataSource(this,uri);current.setVolume(0,0);current.setLooping(true);
            current.setOnPreparedListener(p->{if(p!=player||destroyed)return;prepared=true;bufferWidth=Math.max(1,p.getVideoWidth());bufferHeight=Math.max(1,p.getVideoHeight());rotation=0;transformPreview();if(active&&!userPaused)p.start();});
            current.setOnErrorListener((p,what,extra)->{if(p==player){prepared=false;ready=false;note("Відео не підтримується: "+what+"/"+extra);}return true;});current.prepareAsync();
        }catch(Exception e){note("Відео: "+e.getMessage());closeSource();}
    }
    private void openCamera(){
        if(!active||mode!=2||!preview.isAvailable()||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        closeCamera();final int epoch=cameraEpoch;
        try{CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);String chosen=null;CameraCharacteristics characteristics=null;
            for(String id:manager.getCameraIdList()){CameraCharacteristics c=manager.getCameraCharacteristics(id);if(chosen==null||Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(c.get(CameraCharacteristics.LENS_FACING))){chosen=id;characteristics=c;if(Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(c.get(CameraCharacteristics.LENS_FACING)))break;}}
            if(chosen==null||characteristics==null)throw new IOException("Камера відсутня");
            Size[] sizes=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(SurfaceTexture.class);Size pick=sizes[0];
            for(Size s:sizes)if(s.getWidth()<=1280&&s.getHeight()<=720&&s.getWidth()>=640){pick=s;break;}
            bufferWidth=pick.getWidth();bufferHeight=pick.getHeight();Integer sensor=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int display=getWindowManager().getDefaultDisplay().getRotation()*90;rotation=((sensor==null?0:sensor)-display+360)%360;
            preview.getSurfaceTexture().setDefaultBufferSize(bufferWidth,bufferHeight);transformPreview();
            manager.openCamera(chosen,new CameraDevice.StateCallback(){
                public void onOpened(CameraDevice device){ui.post(()->{if(destroyed||!active||mode!=2||epoch!=cameraEpoch){device.close();return;}camera=device;
                    try{cameraSurface=new Surface(preview.getSurfaceTexture());Surface surface=cameraSurface;
                        device.createCaptureSession(Collections.singletonList(surface),new CameraCaptureSession.StateCallback(){
                            public void onConfigured(CameraCaptureSession s){if(destroyed||!active||camera!=device||epoch!=cameraEpoch){s.close();return;}session=s;try{CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);request.addTarget(surface);request.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);s.setRepeatingRequest(request.build(),null,cameraHandler);}catch(Exception e){ui.post(()->{if(!destroyed)note("Камера: "+e.getMessage());});}}
                            public void onConfigureFailed(CameraCaptureSession s){ui.post(()->{if(!destroyed)note("Не вдалося налаштувати камеру.");});}
                        },cameraHandler);
                    }catch(Exception e){note("Камера: "+e.getMessage());closeCamera();}
                });}
                public void onDisconnected(CameraDevice c){c.close();ui.post(()->{if(camera==c){camera=null;ready=false;}});}
                public void onError(CameraDevice c,int error){c.close();ui.post(()->{if(!destroyed){ready=false;note("Camera2 error "+error);}});}
            },cameraHandler);
        }catch(Exception e){note("Камера: "+e.getMessage());}
    }
    private void transformPreview(){int w=preview.getWidth(),h=preview.getHeight();if(w==0||h==0)return;int rw=(rotation%180==0?bufferWidth:bufferHeight),rh=(rotation%180==0?bufferHeight:bufferWidth);int targetWidth=Math.min(getResources().getDisplayMetrics().widthPixels-dp(16),Math.max(dp(40),Math.round(dp(100)*rw/(float)rh)));if(w!=targetWidth){preview.getLayoutParams().width=targetWidth;preview.requestLayout();return;}float s=Math.min(w/(float)rw,h/(float)rh);Matrix m=new Matrix();m.setScale(bufferWidth/(float)w,bufferHeight/(float)h);m.postTranslate(-bufferWidth/2f,-bufferHeight/2f);m.postRotate(rotation);m.postScale(s,s);m.postTranslate(w/2f,h/2f);preview.setTransform(m);}
    private void closeCamera(){cameraEpoch++;if(session!=null){session.close();session=null;}if(camera!=null){camera.close();camera=null;}if(cameraSurface!=null){cameraSurface.release();cameraSurface=null;}ready=false;}
    private void closeSource(){closeCamera();if(player!=null){player.release();player=null;}if(videoSurface!=null){videoSurface.release();videoSurface=null;}prepared=false;userPaused=false;}
    private void help(){new AlertDialog.Builder(this).setTitle("Коротке налаштування")
        .setMessage("1. AUTO вже вибраний. Почни з фото й сили 70%; обробка запуститься сама.\n2. AUTO сам обирає алгоритм за евристичною оцінкою. «Порівняти 5» фіксує результати; «Обробити кадр» відновлює AUTO.\n3. Нові режими: CAP та FAST. AUTO також підбирає силу в межах повзунка. BC/CR: карта до 512 px для фото. AI: зображення до 256 px, локальна модель.\n4. Відео / камера: верхнє вікно — оригінальний потік, нижче — парні знімки з їхнім віком.\n5. Тест 12 с та Звіт TXT показують реальну швидкість обробки.\n6. Для плавного поточного відео відкрий CLASSIC LIVE. Для попереднього фотоалгоритму — PHOTO MAX.\n7. AUTO має окремий пакет. Перевірка режимів приблизно кожні 6 с; 2 підтвердження й 8 с утримання захищають від частих перемикань. За поганого результату показується оригінал.")
        .setPositiveButton("Зрозуміло",null).show();}
    @Override protected void onResume(){super.onResume();active=true;lastTick=SystemClock.elapsedRealtime();if(selected==AUTO&&mode==0&&photo!=null&&displayed==null)autoPhotoPending=true;if(mode==2&&preview!=null&&preview.isAvailable())openCamera();if(mode==1&&selectedVideoUri!=null&&player==null)openVideoWhenReady(selectedVideoUri,sourceEpoch);else if(player!=null&&prepared&&!userPaused)player.start();}
    @Override protected void onPause(){active=false;gate.reset();if(testing)finishTest("Тест зупинено: додаток згорнуто");closeCamera();if(player!=null){player.release();player=null;}if(videoSurface!=null){videoSurface.release();videoSurface=null;}prepared=false;ready=false;super.onPause();}
    @Override protected void onDestroy(){destroyed=true;gate.reset();ui.removeCallbacks(ticker);closeSource();worker.execute(processor::close);worker.shutdown();cameraThread.quitSafely();super.onDestroy();}
}
