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
    private volatile boolean enhanced=true,fill=true,frozen=false;
    // 0: full-frame 50/50 wipe (no stretching), 1: separate FIT frames, 2: processed fullscreen.
    private volatile int viewMode=0;
    private volatile float zoom=1f,panX=0f,panY=0f;
    private volatile int userRotation=0;
    private int cropLoc,panLoc;
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
    void setFill(boolean value){fill=value;}
    void setViewMode(int value){viewMode=Math.max(0,Math.min(2,value));}
    int getViewMode(){return viewMode;}
    void setFrozen(boolean value){frozen=value;}
    void setZoom(float value){zoom=Math.max(1f,Math.min(8f,value));}
    float getZoom(){return zoom;}
    void panBy(float dx,float dy){float range=.5f*(1f-1f/Math.max(1,zoom));panX=Math.max(-range,Math.min(range,panX+dx));panY=Math.max(-range,Math.min(range,panY+dy));}
    void resetZoom(){zoom=1f;panX=0;panY=0;}
    void rotate90(){userRotation=(userRotation+90)%360;}
    int effectiveRotation(){return (rotation+userRotation)%360;}
    interface CaptureCallback{void onCaptured(android.graphics.Bitmap bitmap);}
    private volatile CaptureCallback capture;
    void captureNext(CaptureCallback c){capture=c;}
    void refresh(){view.requestRender();}
    void setStrength(float value){strength=Math.max(0f,Math.min(1f,value));}
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
        activity.runOnUiThread(()->textureCallback.onReady(surfaceTexture));
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

    @Override public void onDrawFrame(GL10 unused) {
        if(surfaceTexture==null||screenWidth<2||screenHeight<2)return;
        if(framePending.getAndSet(false)&&!frozen){
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
