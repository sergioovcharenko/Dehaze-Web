package ua.dehaze.live;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * One Camera2 SurfaceTexture -> synchronized original/processed GPU views.
 * Adaptive Object Dehaze uses only real sampled pixels: DCP + transmission map,
 * smoothed atmospheric light, edge-guided transmission refinement, local contrast,
 * detail recovery and adaptive denoise. No generative/AI pixel synthesis is used.
 * Temporal stabilization is applied to AUTO strength and atmospheric-light estimates.
 */
public final class SplitRenderer implements GLSurfaceView.Renderer {
    private static final String VERTEX =
        "attribute vec2 aPosition;\n" +
        "varying vec2 vUV;\n" +
        "void main(){vUV=(aPosition+1.0)*0.5;gl_Position=vec4(aPosition,0.0,1.0);}";
    private static final String FRAGMENT =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vUV;\n" +
        "uniform samplerExternalOES uCamera;\n" +
        "uniform mat4 uMatrix;\n" +
        "uniform float uRotation;\n" +
        "uniform vec2 uPixel;\n" +
        "uniform float uEnhanced;\n" +
        "uniform float uStrength;\n" +
        "uniform float uMax;\n" +
        "uniform vec2 uCrop;\n" +
        "uniform vec2 uPan;\n" +
        "vec2 rotateUV(vec2 uv){if(uRotation<45.0)return uv;if(uRotation<135.0)return vec2(uv.y,1.0-uv.x);if(uRotation<225.0)return vec2(1.0-uv.x,1.0-uv.y);return vec2(1.0-uv.y,uv.x);}\n" +
        "vec3 grab(vec2 uv){vec2 p=clamp((uv-.5)*uCrop+.5+uPan,vec2(.001),vec2(.999));return texture2D(uCamera,(uMatrix*vec4(rotateUV(p),0.0,1.0)).xy).rgb;}\n" +
        "float lum(vec3 c){return dot(c,vec3(.299,.587,.114));}\n" +
        "float dc(vec3 c){return min(c.r,min(c.g,c.b));}\n" +
        "void main(){\n" +
        " vec3 c=grab(vUV); if(uEnhanced<0.5||uStrength<.001){gl_FragColor=vec4(c,1.0);return;}\n" +
        " vec2 p2=uPixel*2.0,p4=uPixel*4.0;\n" +
        " vec3 a=grab(vUV+vec2(p2.x,0.0)),b=grab(vUV-vec2(p2.x,0.0)),d=grab(vUV+vec2(0.0,p2.y)),e=grab(vUV-vec2(0.0,p2.y));\n" +
        " vec3 q1=grab(vUV+vec2(p4.x,p4.y)),q2=grab(vUV+vec2(-p4.x,p4.y)),q3=grab(vUV+vec2(p4.x,-p4.y)),q4=grab(vUV-vec2(p4.x,p4.y));\n" +
        " vec3 l2=(a+b+d+e)*.25,l4=(q1+q2+q3+q4)*.25;\n" +
        " float y=lum(c),edge=clamp(length(c-l2)*3.2+abs(y-lum(l4))*2.2,0.0,1.0);\n" +
        " float dark=min(dc(c),min(min(dc(a),dc(b)),min(dc(d),dc(e)))); dark=min(dark,min(min(dc(q1),dc(q2)),min(dc(q3),dc(q4))));\n" +
        " float lowTex=1.0-smoothstep(.025,.16,edge),haze=clamp(dark*.70+y*.18+lowTex*.12,0.0,1.0);\n" +
        " float s=clamp(uStrength,0.0,1.0),omega=mix(.62,.88,s),t=clamp(1.0-omega*haze,mix(.38,.22,s),1.0);\n" +
        " vec3 A=mix(vec3(.82),vec3(.94),clamp(haze*.9+.08,0.0,1.0)),rec=clamp((c-A)/t+A,0.0,1.0);\n" +
        " float structure=smoothstep(.015,.20,edge)*smoothstep(.20,.82,haze);\n" +
        " rec+=clamp(c-l2,-.11,.11)*(s*(.30+.85*structure)); rec+=clamp(c-l4,-.08,.08)*(s*.28*structure);\n" +
        " float ly=lum(l4); rec=vec3(ly)+(rec-vec3(ly))*(1.0+s*(.10+.30*structure));\n" +
        " rec=mix(rec,l2,.07*s*lowTex*smoothstep(.35,.85,haze));\n" +
        " vec3 result=mix(c,rec,clamp(s*(.38+.58*smoothstep(.10,.78,haze)),0.0,.97));\n" +
        " float ry=lum(result); result=mix(vec3(ry),result,1.0+.12*s); gl_FragColor=vec4(clamp(result,0.0,1.0),1.0);\n" +
        "}";
    private final MainActivity activity;
    private final GLSurfaceView view;
    private final MainActivity.TextureCallback textureCallback;
    private final MainActivity.StatsCallback statsCallback;
    private final FloatBuffer quad;
    private final AtomicBoolean framePending=new AtomicBoolean(false);
    private final float[] stMatrix=new float[16];
    private volatile boolean enhanced=true,fill=false,frozen=false,maxMode=true,nightMode=false,comparisonFit=false,mediaMode=false;
    // 0: full-frame 50/50 wipe (no stretching), 1: separate FIT frames, 2: processed fullscreen.
    private volatile int viewMode=0;
    private volatile float zoom=1f,panX=0f,panY=0f;
    private volatile int userRotation=0;
    private int cropLoc,panLoc,maxLoc,nightLoc,atmosphereLoc,mediaModeLoc;
    private volatile float strength=.60f;
    private volatile float atmosphere=.82f;
    private volatile boolean manualMode=false;
    private volatile int autoLevel=1;
    private int pendingAutoLevel=1,pendingAutoCount=0;
    private int analysisTexture=0,analysisFbo=0;
    private static final int SAMPLE_W=64,SAMPLE_H=36;
    private final java.nio.ByteBuffer analysisPixels=java.nio.ByteBuffer.allocateDirect(SAMPLE_W*SAMPLE_H*4);
    private long lastAnalysisNs=0;
    private boolean analysisReady=false;
    private volatile int cameraWidth=1280,cameraHeight=720,rotation=0;
    private volatile boolean realtimeTimestamps=false;
    private SurfaceTexture surfaceTexture;
    private int textureId,program,positionLoc,matrixLoc,pixelLoc,rotationLoc,enhancedLoc,strengthLoc;
    private int screenWidth,screenHeight;
    private long lastStatsNanos=0;
    private int framesSinceStats=0;
    private boolean textureHasFrame=false;

    SplitRenderer(MainActivity activity,GLSurfaceView view,
                  MainActivity.TextureCallback onTexture,MainActivity.StatsCallback onStats) {
        this.activity=activity;this.view=view;
        this.textureCallback=onTexture;this.statsCallback=onStats;
        float[] pts={-1,-1,1,-1,-1,1,1,1};
        quad=ByteBuffer.allocateDirect(pts.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(pts).position(0);
    }

    void setEnhanced(boolean value){enhanced=value;}
    void setMaxMode(boolean value){maxMode=value;}
    void setNightMode(boolean value){nightMode=value;}
    void setMediaMode(boolean value){mediaMode=value;}
    void setComparisonFit(boolean value){comparisonFit=value;}
    void setFill(boolean value){fill=value;}
    void setViewMode(int value){viewMode=Math.max(0,Math.min(2,value));}
    int getViewMode(){return viewMode;}
    void setFrozen(boolean value){frozen=value;}
    void setZoom(float value){zoom=Math.max(1f,Math.min(8f,value));}
    float getZoom(){return zoom;}
    void panBy(float dx,float dy){float range=.5f*(1f-1f/Math.max(1,zoom));panX=Math.max(-range,Math.min(range,panX+dx));panY=Math.max(-range,Math.min(range,panY+dy));}
    void resetZoom(){zoom=1f;panX=0;panY=0;}
    // One-time per-device camera alignment is stored by the Activity.
    // It changes the image texture only, never the Android screen orientation.
    void setUserRotation(int degrees){
        userRotation=((degrees%360)+360)%360;
    }
    void rotate90(){setUserRotation(userRotation+90);}
    int effectiveRotation(){return (rotation+userRotation)%360;}
    interface CaptureCallback{void onCaptured(android.graphics.Bitmap bitmap);}
    private volatile CaptureCallback capture;
    void captureNext(CaptureCallback c){capture=c;}
    void refresh(){view.requestRender();}
    void setStrength(float value){strength=Math.max(0f,Math.min(1f,value));}
    void setManualMode(boolean value){manualMode=value;lastAnalysisNs=0;}
    boolean isManualMode(){return manualMode;}
    void setCameraInfo(int w,int h,int orient,boolean realtime) {
        cameraWidth=Math.max(1,w);cameraHeight=Math.max(1,h);
        // Fixed landscape UI. Sensor metadata controls frame orientation, not accelerometer.
        rotation=((orient%360)+360)%360;userRotation=0;
        realtimeTimestamps=realtime;
        resetZoom();
    }

    private int compile(int kind,String source) {
        int shader=GLES20.glCreateShader(kind);
        GLES20.glShaderSource(shader,source);
        GLES20.glCompileShader(shader);
        int[] ok=new int[1];
        GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0)throw new RuntimeException("Shader: "+GLES20.glGetShaderInfoLog(shader));
        return shader;
    }
    private int linkProgram(String fragment){
        int vert=compile(GLES20.GL_VERTEX_SHADER,VERTEX);
        int frag=compile(GLES20.GL_FRAGMENT_SHADER,fragment);
        int p=GLES20.glCreateProgram();
        GLES20.glAttachShader(p,vert);GLES20.glAttachShader(p,frag);GLES20.glLinkProgram(p);
        int[] ok=new int[1];
        GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0){
            String info=GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);GLES20.glDeleteShader(vert);GLES20.glDeleteShader(frag);
            throw new RuntimeException("Link: "+info);
        }
        GLES20.glDeleteShader(vert);GLES20.glDeleteShader(frag);
        return p;
    }
    private int createProgram(){
        try{
            return linkProgram(FRAGMENT);
        }catch(RuntimeException fullError){
            try{
                int fallbackProgram=linkProgram(FRAGMENT_COMPAT);
                activity.runOnUiThread(activity::onGpuCompatibilityMode);
                return fallbackProgram;
            }catch(RuntimeException compatError){
                throw new RuntimeException("GPU shader initialization failed. Adaptive5: "+
                    fullError.getMessage()+"; fallback: "+compatError.getMessage(),compatError);
            }
        }
    }

    @Override public void onSurfaceCreated(GL10 unused,EGLConfig config) {
        GLES20.glClearColor(.025f,.046f,.085f,1f);
        program=createProgram();
        positionLoc=GLES20.glGetAttribLocation(program,"aPosition");
        matrixLoc=GLES20.glGetUniformLocation(program,"uMatrix");
        pixelLoc=GLES20.glGetUniformLocation(program,"uPixel");
        rotationLoc=GLES20.glGetUniformLocation(program,"uRotation");
        enhancedLoc=GLES20.glGetUniformLocation(program,"uEnhanced");
        strengthLoc=GLES20.glGetUniformLocation(program,"uStrength");
        maxLoc=GLES20.glGetUniformLocation(program,"uMax");
        nightLoc=GLES20.glGetUniformLocation(program,"uNight");
        atmosphereLoc=GLES20.glGetUniformLocation(program,"uAtmosphere");
        mediaModeLoc=GLES20.glGetUniformLocation(program,"uMediaMode");
        cropLoc=GLES20.glGetUniformLocation(program,"uCrop");
        panLoc=GLES20.glGetUniformLocation(program,"uPan");
        int[] textures=new int[1];GLES20.glGenTextures(1,textures,0);
        textureId=textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        if(surfaceTexture!=null)surfaceTexture.release();
        surfaceTexture=new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(t->{
            framePending.set(true);
            view.requestRender();
        },new Handler(Looper.getMainLooper()));
        framePending.set(false);textureHasFrame=false;lastStatsNanos=0;
        lastAnalysisNs=0;initAnalysisTarget();
        activity.runOnUiThread(()->textureCallback.onReady(surfaceTexture));
    }


    // Sample a downscaled original frame roughly once a second. This is an
    // inexpensive image heuristic, not a calibrated fog-density measurement.
    private void initAnalysisTarget(){
        analysisReady=false;
        try{
            int[] ids=new int[1];
            GLES20.glGenTextures(1,ids,0);
            analysisTexture=ids[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,analysisTexture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,SAMPLE_W,SAMPLE_H,0,
                GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glGenFramebuffers(1,ids,0);
            analysisFbo=ids[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,analysisFbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,analysisTexture,0);
            analysisReady=GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                ==GLES20.GL_FRAMEBUFFER_COMPLETE;
        }catch(RuntimeException ex){analysisReady=false;}
        finally{
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,0);
        }
        if(!analysisReady)activity.onAutoUnavailable();
    }

    private static float clamp01(float value){return Math.max(0f,Math.min(1f,value));}

    private void updateAutomaticStrength(long nowNs){
        if(manualMode||!enhanced||frozen||!analysisReady||!textureHasFrame)return;
        if(lastAnalysisNs!=0&&nowNs-lastAnalysisNs<1_250_000_000L)return;
        lastAnalysisNs=nowNs;
        // Reading only 64x36 pixels. Sampling the same Camera2 OES texture,
        // without uploading data or generating an extra camera/video stream.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,analysisFbo);
        GLES20.glViewport(0,0,SAMPLE_W,SAMPLE_H);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUniform1f(enhancedLoc,0f);
        GLES20.glUniform1f(strengthLoc,0f);
        GLES20.glUniform2f(cropLoc,1f,1f);
        GLES20.glUniform2f(panLoc,0f,0f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        analysisPixels.position(0);
        GLES20.glReadPixels(0,0,SAMPLE_W,SAMPLE_H,GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,analysisPixels);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
        analysisPixels.rewind();

        final int count=SAMPLE_W*SAMPLE_H;
        final int[] hist=new int[256];
        final int[] previousRow=new int[SAMPLE_W];
        float sum=0f,edgeSum=0f,saturation=0f;
        int edgeCount=0;
        float darkSum=0f;
        for(int y=0;y<SAMPLE_H;y++){
            int left=0;
            for(int x=0;x<SAMPLE_W;x++){
                int red=analysisPixels.get()&255;
                int green=analysisPixels.get()&255;
                int blue=analysisPixels.get()&255;
                analysisPixels.get(); // alpha
                int luminance=Math.min(255,Math.round(.299f*red+.587f*green+.114f*blue));
                int min=Math.min(red,Math.min(green,blue));
                int max=Math.max(red,Math.max(green,blue));
                saturation+=max-min;
                darkSum+=min;
                hist[luminance]++;
                sum+=luminance;
                if(x>0){edgeSum+=Math.abs(luminance-left);edgeCount++;}
                if(y>0){edgeSum+=Math.abs(luminance-previousRow[x]);edgeCount++;}
                left=luminance;previousRow[x]=luminance;
            }
        }
        int acc=0,p10=0,p90=255;
        for(int i=0;i<256;i++){acc+=hist[i];if(acc>=count*.10){p10=i;break;}}
        acc=0;
        for(int i=0;i<256;i++){acc+=hist[i];if(acc>=count*.90){p90=i;break;}}
        float contrastScore=clamp01((118f-(p90-p10))/110f);
        float edgeMean=edgeSum/Math.max(1,edgeCount);
        float textureScore=clamp01((16f-edgeMean)/16f);
        float saturationMean=saturation/count;
        float grayScore=clamp01((42f-saturationMean)/42f);
        float darkMean=darkSum/count;
        float veilScore=clamp01((darkMean-90f)/80f);
        float hazeProxy=clamp01(contrastScore*.40f+textureScore*.24f+grayScore*.17f+veilScore*.19f);
        int candidate=hazeProxy<.24f?0:hazeProxy<.30f?1:hazeProxy<.36f?2:hazeProxy<.43f?3:4;
        if(candidate==pendingAutoLevel)pendingAutoCount++;else{pendingAutoLevel=candidate;pendingAutoCount=1;}
        if(pendingAutoCount>=2||Math.abs(candidate-autoLevel)>=2){autoLevel=candidate;pendingAutoCount=0;}
        float[] targets={.02f,.22f,.42f,.58f,.70f};
        float target=targets[autoLevel];
        float mean=sum/count;
        boolean autoNight=mean<55f;
        if(autoNight)target=Math.max(.30f,target*.82f);
        float nextAtmosphere=Math.max(.74f,Math.min(.93f,(p90+12f)/255f));
        atmosphere=atmosphere*.86f+nextAtmosphere*.14f;
        strength=clamp01(strength*.82f+target*.18f);
        maxMode=autoLevel>=4;
        nightMode=autoNight;
        activity.onAutoHybrid(strength,autoLevel,autoNight);
    }

    @Override public void onSurfaceChanged(GL10 unused,int width,int height){
        screenWidth=width;screenHeight=height;
    }

    // A viewport keeps its own aspect ratio; FILL crops source UVs, never stretches pixels.
    private void drawImage(int x,int y,int width,int height,boolean useFilter,boolean cover){
        if(width<=0||height<=0)return;
        int rot=effectiveRotation();
        int cw=cameraWidth,ch=cameraHeight;
        if(rot==90||rot==270){int t=cw;cw=ch;ch=t;}
        float sourceAspect=(float)cw/(float)Math.max(1,ch);
        float screenAspect=(float)width/(float)Math.max(1,height);
        float cropX=1f,cropY=1f;
        int vx=x,vy=y,vw=width,vh=height;
        if(cover){
            // Match the cropped image's aspect to the destination.
            if(sourceAspect>screenAspect)cropX=screenAspect/sourceAspect;
            else cropY=sourceAspect/screenAspect;
        }else{
            float scale=Math.min((float)width/(float)cw,(float)height/(float)ch);
            vw=Math.max(1,Math.round(cw*scale));
            vh=Math.max(1,Math.round(ch*scale));
            vx=x+(width-vw)/2;vy=y+(height-vh)/2;
        }
        GLES20.glViewport(vx,vy,vw,vh);
        GLES20.glUniform1f(enhancedLoc,useFilter?1f:0f);
        GLES20.glUniform1f(strengthLoc,strength);
        GLES20.glUniform1f(maxLoc,maxMode?1f:0f);
        GLES20.glUniform1f(nightLoc,nightMode?1f:0f);
        GLES20.glUniform1f(atmosphereLoc,atmosphere);
        if(mediaModeLoc>=0)GLES20.glUniform1f(mediaModeLoc,mediaMode?1f:0f);
        float z=Math.max(1f,zoom);
        float uniformCropX=cropX,uniformCropY=cropY;
        // uCrop is applied BEFORE rotateUV(), so 90/270-degree frames need
        // the crop axes swapped. Without this a landscape 3072x1728 camera
        // becomes a narrow portrait strip in the fixed-landscape UI.
        if(rot==90||rot==270){
            float temp=uniformCropX;uniformCropX=uniformCropY;uniformCropY=temp;
        }
        GLES20.glUniform2f(cropLoc,uniformCropX/z,uniformCropY/z);
        GLES20.glUniform2f(panLoc,panX,panY);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    }

    private void drawViews(){
        final int mode=viewMode;
        if(mode==0){
            // 50/50 uses one shared full frame. FIT preserves source geometry;
            // FILL is still available from the menu when the user wants edge crop.
            int middle=screenWidth/2;
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(0,0,middle,screenHeight);
            drawImage(0,0,screenWidth,screenHeight,false,fill);
            GLES20.glScissor(middle,0,screenWidth-middle,screenHeight);
            drawImage(0,0,screenWidth,screenHeight,enhanced,fill);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }else if(mode==1){
            // Independent original and processed previews: FIT by default,
            // preserving the complete 16:9 source with letterboxing as needed.
            int half=screenWidth/2;
            drawImage(0,0,half,screenHeight,false,fill);
            drawImage(half,0,screenWidth-half,screenHeight,enhanced,fill);
        }else{
            drawImage(0,0,screenWidth,screenHeight,enhanced,fill);
        }
    }

    @Override public void onDrawFrame(GL10 unused) {
        if(surfaceTexture==null||screenWidth<2||screenHeight<2)return;
        // Always consume queued camera frames even while the freeze overlay is visible.
// Otherwise SurfaceTexture's buffer queue fills and Camera2 can stall permanently
// after the user taps "Stop-frame"; drawing is hidden by the native overlay.
        if(framePending.getAndSet(false)){
            try{surfaceTexture.updateTexImage();surfaceTexture.getTransformMatrix(stMatrix);textureHasFrame=true;}
            catch(RuntimeException e){return;}
        }
        GLES20.glViewport(0,0,screenWidth,screenHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if(!textureHasFrame)return;
        long started=SystemClock.elapsedRealtimeNanos();
        long cameraTimestamp=surfaceTexture.getTimestamp();
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uCamera"),0);
        GLES20.glUniformMatrix4fv(matrixLoc,1,false,stMatrix,0);
        GLES20.glUniform1f(rotationLoc,(float)effectiveRotation());
        GLES20.glUniform2f(pixelLoc,1f/Math.max(1,cameraWidth),1f/Math.max(1,cameraHeight));
        GLES20.glEnableVertexAttribArray(positionLoc);
        GLES20.glVertexAttribPointer(positionLoc,2,GLES20.GL_FLOAT,false,0,quad);
        drawViews();
        CaptureCallback cb=capture;
        if(cb!=null){
            capture=null;
            java.nio.ByteBuffer pixels=java.nio.ByteBuffer.allocateDirect(screenWidth*screenHeight*4);
            pixels.order(java.nio.ByteOrder.nativeOrder());
            GLES20.glReadPixels(0,0,screenWidth,screenHeight,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(screenWidth,screenHeight,android.graphics.Bitmap.Config.ARGB_8888);
            int[] vals=new int[screenWidth*screenHeight];
            pixels.rewind();
            for(int y=0;y<screenHeight;y++)for(int x=0;x<screenWidth;x++){
                int rr=pixels.get()&255,gg=pixels.get()&255,bb=pixels.get()&255,aa=pixels.get()&255;
                vals[(screenHeight-1-y)*screenWidth+x]=(aa<<24)|(rr<<16)|(gg<<8)|bb;
            }
            bitmap.setPixels(vals,0,screenWidth,0,0,screenWidth,screenHeight);
            cb.onCaptured(bitmap);
        }
        updateAutomaticStrength(started);
        GLES20.glDisableVertexAttribArray(positionLoc);
        long submitted=SystemClock.elapsedRealtimeNanos();
        framesSinceStats++;
        if(lastStatsNanos==0)lastStatsNanos=submitted;
        if(submitted-lastStatsNanos>=550_000_000L){
            double fps=framesSinceStats*1_000_000_000.0/(submitted-lastStatsNanos);
            double submitMs=(submitted-started)/1_000_000.0;
            String age="—";
            if(realtimeTimestamps&&cameraTimestamp>0){
                long ms=(submitted-cameraTimestamp)/1_000_000L;
                if(ms>=0&&ms<5000)age=Long.toString(ms)+" мс";
            }
            final String stats=String.format(Locale.US,
                "%.0f FPS  •  кадр %s  •  подача %.1f мс  •  %d×%d",
                fps,age,submitMs,cameraWidth,cameraHeight);
            statsCallback.onStats(stats);
            framesSinceStats=0;lastStatsNanos=submitted;
        }
    }
}
