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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * One Camera2 SurfaceTexture -> two synchronized GPU views in a single GL surface.
 * Left: unmodified camera. Right: optional fast one-pass anti-haze shader.
 * This is a low-latency prototype, not full multi-scale photo DCP/Fusion.
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
        "uniform float uHybrid;\n" +
        "uniform sampler2D uTransmission;\n" +
        "uniform sampler2D uClahe;\n" +
        "uniform vec3 uAir;\n" +
        "uniform float uRetinex;\n" +
        "uniform float uFusion;\n" +
        "uniform vec2 uCrop;\n" +
        "uniform vec2 uPan;\n" +
        "vec2 rotateUV(vec2 uv){\n" +
        "  if(uRotation<45.0) return uv;\n" +
        "  if(uRotation<135.0) return vec2(uv.y,1.0-uv.x);\n" +
        "  if(uRotation<225.0) return vec2(1.0-uv.x,1.0-uv.y);\n" +
        "  return vec2(1.0-uv.y,uv.x);\n" +
        "}\n" +
        "vec3 grab(vec2 uv){vec2 p=clamp((uv-.5)*uCrop+.5+uPan,vec2(.001),vec2(.999));return texture2D(uCamera,(uMatrix*vec4(rotateUV(p),0.0,1.0)).xy).rgb;}\n" +
        "void main(){\n" +
        " vec3 color=grab(vUV);\n" +
        " vec2 aligned=clamp((vUV-.5)*uCrop+.5+uPan,vec2(0.001),vec2(0.999));\n" +
        " if(uEnhanced<0.5||uStrength<0.001){gl_FragColor=vec4(color,1.0);return;}\n" +
        " vec3 local=(grab(vUV+vec2(uPixel.x*2.0,0.0))+grab(vUV-vec2(uPixel.x*2.0,0.0))+\n" +
        "             grab(vUV+vec2(0.0,uPixel.y*2.0))+grab(vUV-vec2(0.0,uPixel.y*2.0)))*0.25;\n" +
        " float luminance=dot(color,vec3(.299,.587,.114));\n" +
        " float edge=length(color-local);\n" +
        " float sky=smoothstep(.62,.84,luminance)*(1.0-smoothstep(.01,.065,edge))*smoothstep(.18,.85,vUV.y);\n" +
        " float protect=1.0-.95*sky;\n" +
        " if(uHybrid>0.5){\n" +
        "  vec4 m=texture2D(uTransmission,aligned);\n" +
        "  float transmission=max(.26,m.r);\n" +
        "  float protectedSky=clamp(m.g,0.0,1.0);\n" +
        "  float blend=clamp(uStrength*1.02*(1.0-.91*protectedSky),0.0,.96);\n" +
        "  vec3 restored=clamp((color-uAir)/transmission+uAir,0.0,1.0);\n" +
        "  vec3 dcp=mix(color,restored,blend);\n" +
        "  float lum=dot(dcp,vec3(.299,.587,.114));\n" +
        "  vec2 loc=clamp(aligned*vec2(8.0,6.0)-.5,vec2(0.0),vec2(7.0,5.0));\n" +
        "  vec2 low=floor(loc),high=min(low+vec2(1.0),vec2(7.0,5.0));\n" +
        "  vec2 factor=fract(loc);\n" +
        "  float bin=floor(clamp(lum,0.0,1.0)*255.0+.5);\n" +
        "  float a=texture2D(uClahe,vec2((bin+.5)/256.0,(low.y*8.0+low.x+.5)/48.0)).r;\n" +
        "  float b=texture2D(uClahe,vec2((bin+.5)/256.0,(low.y*8.0+high.x+.5)/48.0)).r;\n" +
        "  float c=texture2D(uClahe,vec2((bin+.5)/256.0,(high.y*8.0+low.x+.5)/48.0)).r;\n" +
        "  float d=texture2D(uClahe,vec2((bin+.5)/256.0,(high.y*8.0+high.x+.5)/48.0)).r;\n" +
        "  float mapped=mix(mix(a,b,factor.x),mix(c,d,factor.x),factor.y);\n" +
        "  float delta=clamp(mapped-lum,-.18,.18)*.39*blend*(1.0-protectedSky);\n" +
        "  dcp=clamp(dcp+vec3(delta),0.0,1.0);\n" +
        "  dcp=clamp(dcp+clamp(color-local,vec3(-.09),vec3(.09))*(.35*blend*m.a),0.0,1.0);\n" +
        "  if(uRetinex>.5){\n" +
        "   float darkness=clamp((.58-m.b)*1.7,0.0,1.0);\n" +
        "   float gamma=1.0-.28*darkness*(1.0-protectedSky);\n" +
        "   vec3 ret=pow(max(dcp,vec3(.001)),vec3(gamma));\n" +
        "   dcp=mix(dcp,ret,clamp(uStrength,0.0,1.0));\n" +
        "  }\n" +
        "  if(uFusion>.5){\n" +
        "   float ft=max(.55,1.0-uStrength*(.23+.14*luminance));\n" +
        "   vec3 fast=clamp((color-vec3(.84))/ft+vec3(.84),0.0,1.0);\n" +
        "   float wt=.30*m.a*(1.0-protectedSky)*uStrength;\n" +
        "   dcp=mix(dcp,fast,clamp(wt,0.0,.35));\n" +
        "  }\n" +
        "  gl_FragColor=vec4(dcp,1.0);return;\n" +
        " }\n" +
        " float level=mix(uStrength,min(1.0,uStrength*1.16),uMax);\n" +
        " float t=max(mix(.60,.48,uMax),1.0-level*mix(.24+.13*luminance,.32+.17*luminance,uMax));\n" +
        " vec3 corrected=clamp((color-vec3(.84))/t+vec3(.84),0.0,1.0);\n" +
        " vec3 result=mix(color,corrected,level*mix(.76,.90,uMax)*protect);\n" +
        " result+=clamp(color-local,-.10,.10)*(mix(.34,.46,uMax)*level*protect);\n" +
        " gl_FragColor=vec4(clamp(result,0.0,1.0),1.0);\n" +
        "}";
    private static final String FAST_FRAGMENT =
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
        "vec2 rotateUV(vec2 uv){\n" +
        "  if(uRotation<45.0) return uv;\n" +
        "  if(uRotation<135.0) return vec2(uv.y,1.0-uv.x);\n" +
        "  if(uRotation<225.0) return vec2(1.0-uv.x,1.0-uv.y);\n" +
        "  return vec2(1.0-uv.y,uv.x);\n" +
        "}\n" +
        "vec3 grab(vec2 uv){vec2 p=clamp((uv-.5)*uCrop+.5+uPan,vec2(.001),vec2(.999));return texture2D(uCamera,(uMatrix*vec4(rotateUV(p),0.0,1.0)).xy).rgb;}\n" +
        "void main(){\n" +
        " vec3 color=grab(vUV);\n" +
        " if(uEnhanced<0.5||uStrength<0.001){gl_FragColor=vec4(color,1.0);return;}\n" +
        " vec3 local=(grab(vUV+vec2(uPixel.x*2.0,0.0))+grab(vUV-vec2(uPixel.x*2.0,0.0))+\n" +
        "             grab(vUV+vec2(0.0,uPixel.y*2.0))+grab(vUV-vec2(0.0,uPixel.y*2.0)))*0.25;\n" +
        " float luminance=dot(color,vec3(.299,.587,.114));\n" +
        " float edge=length(color-local);\n" +
        " float sky=smoothstep(.62,.84,luminance)*(1.0-smoothstep(.01,.065,edge))*smoothstep(.18,.85,vUV.y);\n" +
        " float protect=1.0-.95*sky;\n" +
        " float level=mix(uStrength,min(1.0,uStrength*1.16),uMax);\n" +
        " float t=max(mix(.60,.48,uMax),1.0-level*mix(.24+.13*luminance,.32+.17*luminance,uMax));\n" +
        " vec3 corrected=clamp((color-vec3(.84))/t+vec3(.84),0.0,1.0);\n" +
        " vec3 result=mix(color,corrected,level*mix(.76,.90,uMax)*protect);\n" +
        " result+=clamp(color-local,-.10,.10)*(mix(.34,.46,uMax)*level*protect);\n" +
        " gl_FragColor=vec4(clamp(result,0.0,1.0),1.0);\n" +
        "}";
    private final MainActivity activity;
    private final GLSurfaceView view;
    private final MainActivity.TextureCallback textureCallback;
    private final MainActivity.StatsCallback statsCallback;
    private final FloatBuffer quad;
    private final AtomicBoolean framePending=new AtomicBoolean(false);
    private final float[] stMatrix=new float[16];
    private volatile boolean enhanced=true,fill=true,frozen=false,maxMode=true;
    // 0: full-frame 50/50 wipe (no stretching), 1: separate FIT frames, 2: processed fullscreen.
    private volatile int viewMode=0;
    private volatile float zoom=1f,panX=0f,panY=0f;
    private volatile int userRotation=0;
    private int cropLoc,panLoc,maxLoc,hybridLoc,airLoc,mapSamplerLoc,lutSamplerLoc,retinexLoc,fusionLoc;
    private volatile float strength=.60f;
    private volatile boolean manualMode=false;
    private int analysisTexture=0,analysisFbo=0;
    private static final int SAMPLE_W=64,SAMPLE_H=36;
    private final java.nio.ByteBuffer analysisPixels=java.nio.ByteBuffer.allocateDirect(SAMPLE_W*SAMPLE_H*4);
    private long lastAnalysisNs=0;
    private long lastHybridNs=0;
    private boolean hybridTargetReady=false;
    private int hybridTargetTexture,hybridTargetFbo,transmissionTexture,claheTexture;
    private final java.nio.ByteBuffer hybridPixels=java.nio.ByteBuffer.allocateDirect(VideoDehazeProcessor.N*4);
    private final ExecutorService hybridWorker=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"meti-video-dcp");t.setDaemon(true);return t;
    });
    private final AtomicBoolean hybridBusy=new AtomicBoolean(false);
    private final AtomicInteger sourceEpoch=new AtomicInteger(0);
    private volatile VideoDehazeProcessor.Result readyHybrid;
    private VideoDehazeProcessor.Result currentHybrid;
    private boolean hybridMapsUploaded=false;
    private boolean hybridShaderAvailable=true;
    private boolean analysisReady=false;
    private volatile int cameraWidth=1280,cameraHeight=720,rotation=0;
    private volatile boolean realtimeTimestamps=false;
    private SurfaceTexture surfaceTexture;
    private int textureId,program,fastProgram,activeProgram,positionLoc,matrixLoc,pixelLoc,rotationLoc,enhancedLoc,strengthLoc;
    private volatile boolean forceFast=false;
    private int drawErrors=0;
    private long lastDeviceSampleNs=0;
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
    void setForceFast(boolean value){forceFast=value;view.requestRender();}
    void shutdown(){hybridWorker.shutdownNow();}
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
        sourceEpoch.incrementAndGet();
        readyHybrid=null;currentHybrid=null;hybridMapsUploaded=false;lastHybridNs=0;
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
    private int buildProgram(String source){
        int vert=compile(GLES20.GL_VERTEX_SHADER,VERTEX),frag=0,p=0;
        try{
            frag=compile(GLES20.GL_FRAGMENT_SHADER,source);
            p=GLES20.glCreateProgram();
            GLES20.glAttachShader(p,vert);GLES20.glAttachShader(p,frag);
            GLES20.glLinkProgram(p);
            int[] ok=new int[1];
            GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
            if(ok[0]==0)throw new RuntimeException("Link: "+GLES20.glGetProgramInfoLog(p));
            return p;
        }catch(RuntimeException ex){
            if(p!=0)GLES20.glDeleteProgram(p);
            throw ex;
        }finally{
            GLES20.glDeleteShader(vert);
            if(frag!=0)GLES20.glDeleteShader(frag);
        }
    }

    private int createProgram(){
        // Always create the proven FAST program. It displays the original view
        // independently, even when the hybrid program compiles but draws black.
        fastProgram=buildProgram(FAST_FRAGMENT);
        try{
            hybridShaderAvailable=true;
            return buildProgram(FRAGMENT);
        }catch(RuntimeException error){
            android.util.Log.w("MetiVideoMax","Hybrid shader failed; independent FAST program active",error);
            hybridShaderAvailable=false;
            LabRuntime.state(LabRuntime.GPU,"РЕЗЕРВНИЙ","Не підтримується гібридний шейдер: "+error.getMessage());
            activity.onRendererStatus("GPU: резервний режим • оригінал камери активний");
            return fastProgram;
        }
    }

    // Two independent GL programs: the original view never samples DCP textures.
    private void activateProgram(int next){
        if(next==0)return;
        if(activeProgram!=next){
            GLES20.glUseProgram(next);
            activeProgram=next;
            positionLoc=GLES20.glGetAttribLocation(next,"aPosition");
            matrixLoc=GLES20.glGetUniformLocation(next,"uMatrix");
            pixelLoc=GLES20.glGetUniformLocation(next,"uPixel");
            rotationLoc=GLES20.glGetUniformLocation(next,"uRotation");
            enhancedLoc=GLES20.glGetUniformLocation(next,"uEnhanced");
            strengthLoc=GLES20.glGetUniformLocation(next,"uStrength");
            maxLoc=GLES20.glGetUniformLocation(next,"uMax");
            hybridLoc=GLES20.glGetUniformLocation(next,"uHybrid");
            airLoc=GLES20.glGetUniformLocation(next,"uAir");
            retinexLoc=GLES20.glGetUniformLocation(next,"uRetinex");
            fusionLoc=GLES20.glGetUniformLocation(next,"uFusion");
            mapSamplerLoc=GLES20.glGetUniformLocation(next,"uTransmission");
            lutSamplerLoc=GLES20.glGetUniformLocation(next,"uClahe");
            cropLoc=GLES20.glGetUniformLocation(next,"uCrop");
            panLoc=GLES20.glGetUniformLocation(next,"uPan");
            GLES20.glUniform1i(GLES20.glGetUniformLocation(next,"uCamera"),0);
            if(next==program&&hybridShaderAvailable){
                GLES20.glUniform1i(mapSamplerLoc,1);
                GLES20.glUniform1i(lutSamplerLoc,2);
            }
        }
        GLES20.glUniformMatrix4fv(matrixLoc,1,false,stMatrix,0);
        GLES20.glUniform1f(rotationLoc,(float)effectiveRotation());
        GLES20.glUniform2f(pixelLoc,1f/Math.max(1,cameraWidth),1f/Math.max(1,cameraHeight));
        if(positionLoc>=0){
            GLES20.glEnableVertexAttribArray(positionLoc);
            quad.position(0);
            GLES20.glVertexAttribPointer(positionLoc,2,GLES20.GL_FLOAT,false,0,quad);
        }
    }

    @Override public void onSurfaceCreated(GL10 unused,EGLConfig config) {
        GLES20.glClearColor(.025f,.046f,.085f,1f);
        program=createProgram();
        activeProgram=0;
        drawErrors=0;
        lastDeviceSampleNs=0;
        positionLoc=GLES20.glGetAttribLocation(program,"aPosition");
        matrixLoc=GLES20.glGetUniformLocation(program,"uMatrix");
        pixelLoc=GLES20.glGetUniformLocation(program,"uPixel");
        rotationLoc=GLES20.glGetUniformLocation(program,"uRotation");
        enhancedLoc=GLES20.glGetUniformLocation(program,"uEnhanced");
        strengthLoc=GLES20.glGetUniformLocation(program,"uStrength");
        maxLoc=GLES20.glGetUniformLocation(program,"uMax");
        hybridLoc=GLES20.glGetUniformLocation(program,"uHybrid");
        airLoc=GLES20.glGetUniformLocation(program,"uAir");
        retinexLoc=GLES20.glGetUniformLocation(program,"uRetinex");
        fusionLoc=GLES20.glGetUniformLocation(program,"uFusion");
        mapSamplerLoc=GLES20.glGetUniformLocation(program,"uTransmission");
        lutSamplerLoc=GLES20.glGetUniformLocation(program,"uClahe");
        cropLoc=GLES20.glGetUniformLocation(program,"uCrop");
        panLoc=GLES20.glGetUniformLocation(program,"uPan");
        // Distinct sampler units are mandatory even if the hybrid branch is disabled.
        // Previously all sampler uniforms initially pointed at GL_TEXTURE0,
        // mixing external OES and TEXTURE_2D samplers and blacking out preview.
        GLES20.glUseProgram(program);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uCamera"),0);
        GLES20.glUniform1i(mapSamplerLoc,1);
        GLES20.glUniform1i(lutSamplerLoc,2);
        GLES20.glUniform1f(hybridLoc,0f);
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
        lastAnalysisNs=0;initAnalysisTarget();initHybridTargets();
        if(hybridShaderAvailable)LabRuntime.state(LabRuntime.GPU,"ГОТОВО","GLSL зібрано; очікування відеокадрів");
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
        float contrastScore=clamp01((105f-(p90-p10))/100f);
        float edgeMean=edgeSum/Math.max(1,edgeCount);
        float textureScore=clamp01((18f-edgeMean)/18f);
        float saturationMean=saturation/count;
        float grayScore=clamp01((48f-saturationMean)/48f);
        float hazeProxy=contrastScore*.45f+textureScore*.35f+grayScore*.20f;
        float target=.22f+.64f*hazeProxy;
        float mean=sum/count;
        if(mean<65f)target=.22f+(target-.22f)*.65f;
        // Smooth per-frame changes to avoid pulsing when the camera pans.
        strength=clamp01(strength*.72f+target*.28f);
        activity.onAutoStrength(strength);
    }


    private static int createRgbaTexture(int w,int h,boolean linear){
        int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);
        int id=ids[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,id);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,
            linear?GLES20.GL_LINEAR:GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,
            linear?GLES20.GL_LINEAR:GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,w,h,0,
            GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
        return id;
    }

    private void initHybridTargets(){
        hybridTargetReady=false;hybridMapsUploaded=false;
        readyHybrid=null;currentHybrid=null;lastHybridNs=0;sourceEpoch.incrementAndGet();
        try{
            hybridTargetTexture=createRgbaTexture(VideoDehazeProcessor.W,VideoDehazeProcessor.H,true);
            transmissionTexture=createRgbaTexture(VideoDehazeProcessor.W,VideoDehazeProcessor.H,true);
            claheTexture=createRgbaTexture(VideoDehazeProcessor.LUT_W,VideoDehazeProcessor.LUT_H,false);
            int[] ids=new int[1];GLES20.glGenFramebuffers(1,ids,0);hybridTargetFbo=ids[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,hybridTargetFbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,hybridTargetTexture,0);
            hybridTargetReady=GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                ==GLES20.GL_FRAMEBUFFER_COMPLETE;
        }catch(RuntimeException ignored){hybridTargetReady=false;}
        finally{GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,0);}
    }

    private static java.nio.ByteBuffer directBytes(byte[] src){
        java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocateDirect(src.length);
        buffer.put(src).position(0);
        return buffer;
    }

    private void bindHybridMaps(){
        VideoDehazeProcessor.Result next=readyHybrid;
        if(hybridShaderAvailable&&next!=null&&next!=currentHybrid){
            currentHybrid=next;
            readyHybrid=null;
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,transmissionTexture);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,VideoDehazeProcessor.W,
                VideoDehazeProcessor.H,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,
                directBytes(next.map));
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,claheTexture);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,VideoDehazeProcessor.LUT_W,
                VideoDehazeProcessor.LUT_H,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,
                directBytes(next.lut));
            hybridMapsUploaded=true;
        }
        GLES20.glUniform1f(hybridLoc,(maxMode&&hybridShaderAvailable&&hybridMapsUploaded)?1f:0f);
        // Always bind valid textures at units 1/2, even before the first map.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,transmissionTexture);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,claheTexture);
        if(hybridMapsUploaded&&currentHybrid!=null)
            GLES20.glUniform3f(airLoc,currentHybrid.ar,currentHybrid.ag,currentHybrid.ab);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private void scheduleHybrid(long nowNs){
        if(!hybridShaderAvailable||!hybridTargetReady||!maxMode||forceFast||!textureHasFrame||frozen||hybridBusy.get())return;
        if(lastHybridNs>0&&nowNs-lastHybridNs<400_000_000L)return;
        lastHybridNs=nowNs;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,hybridTargetFbo);
        GLES20.glViewport(0,0,VideoDehazeProcessor.W,VideoDehazeProcessor.H);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        // Sample raw OES texture using the FAST program, not the costly hybrid shader.
        activateProgram(fastProgram);
        GLES20.glUniform1f(enhancedLoc,0f);
        GLES20.glUniform1f(hybridLoc,0f);
        GLES20.glUniform1f(maxLoc,0f);
        GLES20.glUniform1f(strengthLoc,0f);
        GLES20.glUniform2f(cropLoc,1f,1f);
        GLES20.glUniform2f(panLoc,0f,0f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        hybridPixels.position(0);
        GLES20.glReadPixels(0,0,VideoDehazeProcessor.W,VideoDehazeProcessor.H,
            GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,hybridPixels);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
        if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)return;
        byte[] sample=new byte[VideoDehazeProcessor.N*4];
        hybridPixels.position(0);
        hybridPixels.get(sample);
        final int epoch=sourceEpoch.get();
        final VideoDehazeProcessor.Result previous=currentHybrid;
        if(!hybridBusy.compareAndSet(false,true))return;
        try{
            hybridWorker.execute(()->{
                try{
                    final int flags=LabRuntime.flags();
                    VideoDehazeProcessor.Result mapped=
                        VideoDehazeProcessor.process(sample,previous,flags);
                    if(epoch==sourceEpoch.get()){
                        readyHybrid=mapped;
                        int[] stages={LabRuntime.DCP,LabRuntime.GUIDED,
                            LabRuntime.CLAHE,LabRuntime.TEMPORAL};
                        long[] times={mapped.dcpMs,mapped.guidedMs,
                            mapped.claheMs,mapped.temporalMs};
                        for(int i=0;i<stages.length;i++){
                            int stage=stages[i];
                            if(!LabRuntime.enabled(stage))continue;
                            if((mapped.fallbackBits&(1<<(stage-2)))!=0)
                                LabRuntime.autoDisable(stage,"Проміжний етап не вдався; резервний вихід");
                            else LabRuntime.live(stage,times[i],"Обробка кадру • "+mapped.computationMs+" мс загалом");
                        }
                    }
                }catch(RuntimeException error){
                    LabRuntime.error(LabRuntime.DCP,error);
                    LabRuntime.autoDisable(LabRuntime.DCP,error.toString());
                    // A bad DCP frame must not interrupt the original video.
                }finally{hybridBusy.set(false);}
            });
        }catch(java.util.concurrent.RejectedExecutionException ignored){hybridBusy.set(false);}
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
        final boolean useHybridProgram=useFilter&&maxMode&&hybridShaderAvailable&&!forceFast;
        activateProgram(useHybridProgram?program:fastProgram);
        GLES20.glUniform1f(enhancedLoc,useFilter?1f:0f);
        GLES20.glUniform1f(strengthLoc,strength);
        GLES20.glUniform1f(maxLoc,maxMode?1f:0f);
        GLES20.glUniform1f(hybridLoc,(useHybridProgram&&hybridMapsUploaded)?1f:0f);
        GLES20.glUniform1f(retinexLoc,LabRuntime.enabled(LabRuntime.RETINEX)?1f:0f);
        GLES20.glUniform1f(fusionLoc,LabRuntime.enabled(LabRuntime.FUSION)?1f:0f);
        if(useHybridProgram&&hybridMapsUploaded&&currentHybrid!=null)
            GLES20.glUniform3f(airLoc,currentHybrid.ar,currentHybrid.ag,currentHybrid.ab);
        float z=Math.max(1f,zoom);
        GLES20.glUniform2f(cropLoc,cropX/z,cropY/z);
        GLES20.glUniform2f(panLoc,panX,panY);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    }

    private void drawViews(){
        final int mode=viewMode;
        if(mode==0){
            // Both draws use identical full-screen geometry. Scissor only decides
            // which half of the same undistorted source frame is visible.
            int middle=screenWidth/2;
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(0,0,middle,screenHeight);
            drawImage(0,0,screenWidth,screenHeight,false,true);
            GLES20.glScissor(middle,0,screenWidth-middle,screenHeight);
            drawImage(0,0,screenWidth,screenHeight,enhanced,true);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }else if(mode==1){
            // Independent original and processed previews: FIT by default,
            // preserving the complete 16:9 source with letterboxing as needed.
            int half=screenWidth/2;
            drawImage(0,0,half,screenHeight,false,fill);
            drawImage(half,0,screenWidth-half,screenHeight,enhanced,fill);
        }else{
            drawImage(0,0,screenWidth,screenHeight,enhanced,true);
        }
    }

    /**
     * Read real pixels from the original (left) and filtered (right) previews.
     * GPU framebuffer readback runs at most once per 0.8 s, never per frame.
     * The two halves use the same FILL geometry during an explicit device test.
     */
    private double[] readTestPatch(int centerX,int centerY){
        final int size=16;
        ByteBuffer pixels=ByteBuffer.allocateDirect(size*size*4);
        pixels.order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(centerX-size/2,centerY-size/2,size,size,
            GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
        int err=GLES20.glGetError();
        if(err!=GLES20.GL_NO_ERROR){
            DeviceTest.readbackError(err);
            return null;
        }
        pixels.rewind();
        double sum=0,squared=0;
        for(int i=0;i<size*size;i++){
            int red=pixels.get()&255,green=pixels.get()&255,blue=pixels.get()&255;
            pixels.get();
            double lum=.299*red+.587*green+.114*blue;
            sum+=lum;squared+=lum*lum;
        }
        double mean=sum/(size*size);
        return new double[]{mean,Math.max(0,squared/(size*size)-mean*mean)};
    }
    private void sampleDevicePixels(long now){
        if(!DeviceTest.running()||screenWidth<80||screenHeight<80||
           viewMode!=1||now-lastDeviceSampleNs<800_000_000L)return;
        lastDeviceSampleNs=now;
        double[] left=readTestPatch(screenWidth/4,screenHeight/2);
        double[] right=readTestPatch(screenWidth*3/4,screenHeight/2);
        if(left!=null&&right!=null)
            DeviceTest.sample(left[0],left[1],right[0],right[1]);
    }

    @Override public void onDrawFrame(GL10 unused) {
        if(surfaceTexture==null||screenWidth<2||screenHeight<2)return;
        // Always consume queued camera frames even while the freeze overlay is visible.
// Otherwise SurfaceTexture's buffer queue fills and Camera2 can stall permanently
// after the user taps "Stop-frame"; drawing is hidden by the native overlay.
        if(framePending.getAndSet(false)){
            try{
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(stMatrix);
                textureHasFrame=true;
                DeviceTest.newFrame();
            }
            catch(RuntimeException e){return;}
        }
        GLES20.glViewport(0,0,screenWidth,screenHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if(!textureHasFrame)return;
        long started=SystemClock.elapsedRealtimeNanos();
        long cameraTimestamp=surfaceTexture.getTimestamp();
        activeProgram=0;
        activateProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uCamera"),0);
        GLES20.glUniformMatrix4fv(matrixLoc,1,false,stMatrix,0);
        GLES20.glUniform1f(rotationLoc,(float)effectiveRotation());
        GLES20.glUniform2f(pixelLoc,1f/Math.max(1,cameraWidth),1f/Math.max(1,cameraHeight));
        GLES20.glEnableVertexAttribArray(positionLoc);
        GLES20.glVertexAttribPointer(positionLoc,2,GLES20.GL_FLOAT,false,0,quad);
        bindHybridMaps();
        drawViews();
        int drawError=GLES20.glGetError();
        if(drawError!=GLES20.GL_NO_ERROR)DeviceTest.gpuError(drawError);
        sampleDevicePixels(started);
        if(drawError!=GLES20.GL_NO_ERROR&&hybridShaderAvailable&&!forceFast){
            drawErrors++;
            if(drawErrors>=2){
                forceFast=true;
                LabRuntime.state(LabRuntime.GPU,"РЕЗЕРВНИЙ",
                    "Помилка гібридного GPU "+drawError+"; перемкнуто на FAST");
                android.util.Log.e("MetiLab","Hybrid GPU error "+drawError+"; FAST fallback");
                activity.onRendererStatus("GPU: резервний режим після помилки "+drawError);
            }
        }else if(drawError==GLES20.GL_NO_ERROR){
            drawErrors=0;
            if(!forceFast&&hybridShaderAvailable&&maxMode){
                LabRuntime.live(LabRuntime.GPU,0,"Hybrid OpenGL: кадри обробляються");
                if(hybridMapsUploaded&&enhanced){
                    LabRuntime.live(LabRuntime.RETINEX,0,"GPU-прохід (реальна якість не оцінена)");
                    LabRuntime.live(LabRuntime.FUSION,0,"GPU-прохід (реальна якість не оцінена)");
                }
            }else if(!forceFast){
                LabRuntime.live(LabRuntime.GPU,0,"FAST OpenGL: оригінальний кадр доступний");
            }
        }
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
        scheduleHybrid(started);
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
                fps,age,submitMs,cameraWidth,cameraHeight)+(hybridShaderAvailable?"":" • GPU FAST");
            statsCallback.onStats(stats);
            framesSinceStats=0;lastStatsNanos=submitted;
        }
    }
}
