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
        "vec2 rotateUV(vec2 uv){\n" +
        "  if(uRotation<45.0) return uv;\n" +
        "  if(uRotation<135.0) return vec2(uv.y,1.0-uv.x);\n" +
        "  if(uRotation<225.0) return vec2(1.0-uv.x,1.0-uv.y);\n" +
        "  return vec2(1.0-uv.y,uv.x);\n" +
        "}\n" +
        "vec3 grab(vec2 uv){return texture2D(uCamera,(uMatrix*vec4(rotateUV(uv),0.0,1.0)).xy).rgb;}\n" +
        "void main(){\n" +
        " vec3 color=grab(vUV);\n" +
        " if(uEnhanced<0.5||uStrength<0.001){gl_FragColor=vec4(color,1.0);return;}\n" +
        " vec3 local=(grab(vUV+vec2(uPixel.x*2.0,0.0))+grab(vUV-vec2(uPixel.x*2.0,0.0))+\n" +
        "             grab(vUV+vec2(0.0,uPixel.y*2.0))+grab(vUV-vec2(0.0,uPixel.y*2.0)))*0.25;\n" +
        " float luminance=dot(color,vec3(.299,.587,.114));\n" +
        " float edge=length(color-local);\n" +
        " float sky=smoothstep(.62,.84,luminance)*(1.0-smoothstep(.01,.065,edge))*smoothstep(.18,.85,vUV.y);\n" +
        " float protect=1.0-.95*sky;\n" +
        " float t=max(.60,1.0-uStrength*(.24+.13*luminance));\n" +
        " vec3 corrected=clamp((color-vec3(.84))/t+vec3(.84),0.0,1.0);\n" +
        " vec3 result=mix(color,corrected,uStrength*.76*protect);\n" +
        " result+=clamp(color-local,-.10,.10)*(.34*uStrength*protect);\n" +
        " gl_FragColor=vec4(clamp(result,0.0,1.0),1.0);\n" +
        "}";
    private final MainActivity activity;
    private final GLSurfaceView view;
    private final MainActivity.TextureCallback textureCallback;
    private final MainActivity.StatsCallback statsCallback;
    private final FloatBuffer quad;
    private final AtomicBoolean framePending=new AtomicBoolean(false);
    private final float[] stMatrix=new float[16];
    private volatile boolean enhanced=true;
    private volatile float strength=.60f;
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
    void setStrength(float value){strength=Math.max(0f,Math.min(1f,value));}
    void setCameraInfo(int w,int h,int orient,boolean realtime) {
        cameraWidth=w;cameraHeight=h;rotation=orient;realtimeTimestamps=realtime;
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
    private int createProgram(){
        int vert=compile(GLES20.GL_VERTEX_SHADER,VERTEX);
        int frag=compile(GLES20.GL_FRAGMENT_SHADER,FRAGMENT);
        int p=GLES20.glCreateProgram();
        GLES20.glAttachShader(p,vert);GLES20.glAttachShader(p,frag);GLES20.glLinkProgram(p);
        int[] ok=new int[1];
        GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0)throw new RuntimeException("Link: "+GLES20.glGetProgramInfoLog(p));
        GLES20.glDeleteShader(vert);GLES20.glDeleteShader(frag);
        return p;
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
        activity.runOnUiThread(()->textureCallback.onReady(surfaceTexture));
    }

    @Override public void onSurfaceChanged(GL10 unused,int width,int height){
        screenWidth=width;screenHeight=height;
    }

    private void drawPane(int pane,boolean useFilter){
        int half=screenWidth/2,xStart=pane==0?0:half;
        int paneWidth=pane==0?half:screenWidth-half;
        int camW=cameraWidth,camH=cameraHeight;
        if(rotation==90||rotation==270){int tmp=camW;camW=camH;camH=tmp;}
        float factor=Math.min((float)paneWidth/camW,(float)screenHeight/camH);
        int dw=Math.max(1,Math.round(camW*factor)),dh=Math.max(1,Math.round(camH*factor));
        int x=xStart+(paneWidth-dw)/2,y=(screenHeight-dh)/2;
        GLES20.glViewport(x,y,dw,dh);
        GLES20.glUniform1f(enhancedLoc,useFilter?1f:0f);
        GLES20.glUniform1f(strengthLoc,strength);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    }

    @Override public void onDrawFrame(GL10 unused) {
        if(surfaceTexture==null||screenWidth<2||screenHeight<2)return;
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
        GLES20.glUniform1f(rotationLoc,(float)rotation);
        GLES20.glUniform2f(pixelLoc,1f/Math.max(1,cameraWidth),1f/Math.max(1,cameraHeight));
        GLES20.glEnableVertexAttribArray(positionLoc);
        GLES20.glVertexAttribPointer(positionLoc,2,GLES20.GL_FLOAT,false,0,quad);
        drawPane(0,false);
        drawPane(1,enhanced);
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
                "Вік кадру до показу: %s  |  CPU→GPU: %.1f мс  |  %.0f FPS",
                age,submitMs,fps);
            statsCallback.onStats(stats);
            framesSinceStats=0;lastStatsNanos=submitted;
        }
    }
}
