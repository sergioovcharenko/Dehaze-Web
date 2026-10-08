package ua.dehaze.benchmark;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class BenchmarkRenderer implements GLSurfaceView.Renderer {
    interface Callback {
        void onVideoSurfaceReady(SurfaceTexture texture);
        void onStats(String text);
    }

    private static final String VERTEX =
        "attribute vec2 aPosition;\n" +
        "varying vec2 vUV;\n" +
        "void main(){vUV=(aPosition+1.0)*0.5;gl_Position=vec4(aPosition,0.0,1.0);}\n";

    private static final String FRAGMENT =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vUV;\n" +
        "uniform samplerExternalOES uVideo;\n" +
        "uniform mat4 uMatrix;\n" +
        "uniform vec2 uPixel;\n" +
        "uniform float uMode;\n" +
        "vec3 at(vec2 uv){vec2 q=clamp(uv,vec2(.002),vec2(.998));return texture2D(uVideo,(uMatrix*vec4(q,0.0,1.0)).xy).rgb;}\n" +
        "float lum(vec3 c){return dot(c,vec3(.299,.587,.114));}\n" +
        "float dark(vec3 c){return min(c.r,min(c.g,c.b));}\n" +
        "vec3 recover(vec3 c,float t,vec3 a){return clamp((c-a)/max(.14,t)+a,0.0,1.0);}\n" +
        "float capDepth(vec3 c){float hi=max(c.r,max(c.g,c.b));float lo=min(c.r,min(c.g,c.b));float sat=(hi-lo)/max(.001,hi);return max(0.0,.121779+.959710*hi-.780245*sat);}\n" +
        "float skyProtect(vec3 c,vec3 local){float y=lum(c);float edge=length(c-local);float bright=smoothstep(.66,.90,y);float flat=1.0-smoothstep(.015,.075,edge);float upper=smoothstep(.25,.90,vUV.y);return clamp(bright*flat*upper,0.0,1.0);}\n" +
        "vec3 cap(vec3 c,vec3 local,float beta,float floorT,float mixAmt){\n" +
        "  float depth=capDepth(c);\n" +
        "  float t=max(floorT,exp(-beta*depth));\n" +
        "  vec3 base=recover(c,t,vec3(.92));\n" +
        "  float protect=1.0-.86*skyProtect(c,local);\n" +
        "  return mix(c,base,mixAmt*protect);\n" +
        "}\n" +
        "void main(){\n" +
        "  vec3 c=at(vUV);\n" +
        "  if(uMode<.5){gl_FragColor=vec4(c,1.0);return;}\n" +
        "  vec3 local=(at(vUV+vec2(uPixel.x*2.0,0.0))+at(vUV-vec2(uPixel.x*2.0,0.0))+at(vUV+vec2(0.0,uPixel.y*2.0))+at(vUV-vec2(0.0,uPixel.y*2.0)))*.25;\n" +
        "  if(uMode<1.5){vec3 r=cap(c,local,.72,.42,.62);r+=clamp(c-local,vec3(-.06),vec3(.06))*.08;gl_FragColor=vec4(clamp(r,0.0,1.0),1.0);return;}\n" +
        "  if(uMode<2.5){vec3 r=cap(c,local,1.00,.31,.78);r+=clamp(c-local,vec3(-.07),vec3(.07))*.12;gl_FragColor=vec4(clamp(r,0.0,1.0),1.0);return;}\n" +
        "  if(uMode<3.5){vec3 r=cap(c,local,1.32,.23,.91);float p=1.0-.80*skyProtect(c,local);r+=clamp(c-local,vec3(-.08),vec3(.08))*.16*p;gl_FragColor=vec4(clamp(r,0.0,1.0),1.0);return;}\n" +
        "  if(uMode<4.5){\n" +
        "    vec3 capr=cap(c,local,1.05,.28,.80);\n" +
        "    float d=dark(c);\n" +
        "    d=min(d,dark(at(vUV+vec2(uPixel.x*3.0,0.0))));d=min(d,dark(at(vUV-vec2(uPixel.x*3.0,0.0))));\n" +
        "    d=min(d,dark(at(vUV+vec2(0.0,uPixel.y*3.0))));d=min(d,dark(at(vUV-vec2(0.0,uPixel.y*3.0))));\n" +
        "    float td=max(.30,1.0-.78*d/.92);\n" +
        "    vec3 dcpr=recover(c,td,vec3(.92));\n" +
        "    float protect=1.0-.90*skyProtect(c,local);\n" +
        "    vec3 r=mix(capr,dcpr,.30*protect);\n" +
        "    r+=clamp(c-local,vec3(-.075),vec3(.075))*.10*protect;\n" +
        "    gl_FragColor=vec4(clamp(r,0.0,1.0),1.0);return;\n" +
        "  }\n" +
        "  vec3 capr=cap(c,local,.92,.32,.76);\n" +
        "  vec3 base=max(local,vec3(.035));\n" +
        "  vec3 ret=capr+(log(capr+vec3(.035))-log(base+vec3(.035)))*.115;\n" +
        "  float y=lum(ret);ret+=(ret-vec3(y))*.07;ret=(ret-.5)*1.08+.5;\n" +
        "  float protect=1.0-.82*skyProtect(c,local);\n" +
        "  vec3 r=mix(capr,ret,.62*protect);\n" +
        "  gl_FragColor=vec4(clamp(r,0.0,1.0),1.0);\n" +
        "}";


    private static final String SAFE_FRAGMENT =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vUV;\n" +
        "uniform samplerExternalOES uVideo;\n" +
        "uniform mat4 uMatrix;\n" +
        "uniform vec2 uPixel;\n" +
        "uniform float uMode;\n" +
        "vec3 at(vec2 uv){vec2 q=clamp(uv,vec2(.002),vec2(.998));return texture2D(uVideo,(uMatrix*vec4(q,0.0,1.0)).xy).rgb;}\n" +
        "void main(){vec3 c=at(vUV);if(uMode<.5){gl_FragColor=vec4(c,1.0);return;}vec3 l=(at(vUV+vec2(uPixel.x*2.0,0.0))+at(vUV-vec2(uPixel.x*2.0,0.0))+at(vUV+vec2(0.0,uPixel.y*2.0))+at(vUV-vec2(0.0,uPixel.y*2.0)))*.25;float y=dot(c,vec3(.299,.587,.114));float s=clamp(.28+.09*uMode,.32,.78);float t=max(.42,1.0-s*(.20+.16*y));vec3 r=clamp((c-vec3(.88))/t+vec3(.88),0.0,1.0);r=mix(c,r,.55+.05*uMode);r+=clamp(c-l,vec3(-.06),vec3(.06))*.10;gl_FragColor=vec4(clamp(r,0.0,1.0),1.0);}\n";

    private final GLSurfaceView view;
    private final Callback callback;
    private final FloatBuffer quad;
    private final AtomicBoolean framePending=new AtomicBoolean(false);
    private final float[] matrix=new float[16];

    private SurfaceTexture texture;
    private int oesTexture,program,aPosition,uMatrix,uPixel,uMode;
    private int surfaceW,surfaceH,videoW=1920,videoH=1080;
    private volatile int solo=-1;
    private boolean hasFrame=false;
    private long lastStats=0;
    private int frames=0;

    BenchmarkRenderer(GLSurfaceView view,Callback callback){
        this.view=view;this.callback=callback;
        float[] vertices={-1f,-1f,1f,-1f,-1f,1f,1f,1f};
        quad=ByteBuffer.allocateDirect(vertices.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(vertices).position(0);
        Matrix.setIdentityM(matrix,0);
    }

    void setVideoSize(int w,int h){if(w>0)videoW=w;if(h>0)videoH=h;view.requestRender();}
    void setSolo(int index){solo=index<0?-1:Math.min(5,index);view.requestRender();}
    void release(){view.queueEvent(()->{if(texture!=null){texture.release();texture=null;}});}

    private int compile(int type,String source){
        int shader=GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader,source);GLES20.glCompileShader(shader);
        int[] ok=new int[1];GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0){String m=GLES20.glGetShaderInfoLog(shader);GLES20.glDeleteShader(shader);throw new RuntimeException("Shader compile: "+m);}
        return shader;
    }

    private int makeProgram(){
        int vs=compile(GLES20.GL_VERTEX_SHADER,VERTEX),fs=compile(GLES20.GL_FRAGMENT_SHADER,FRAGMENT);
        int p=GLES20.glCreateProgram();GLES20.glAttachShader(p,vs);GLES20.glAttachShader(p,fs);GLES20.glLinkProgram(p);
        int[] ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);
        if(ok[0]==0){String m=GLES20.glGetProgramInfoLog(p);GLES20.glDeleteProgram(p);throw new RuntimeException("Program link: "+m);}
        return p;
    }

    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
        GLES20.glClearColor(.015f,.017f,.020f,1f);
        try{
            program=makeProgram();
        }catch(RuntimeException fullShaderError){
            android.util.Log.w("CapVideoTest","Full CAP shader unavailable; using SAFE shader",fullShaderError);
            int vs=compile(GLES20.GL_VERTEX_SHADER,VERTEX);
            int fs=compile(GLES20.GL_FRAGMENT_SHADER,SAFE_FRAGMENT);
            program=GLES20.glCreateProgram();
            GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program);
            int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);
            GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);
            if(ok[0]==0)throw new RuntimeException("SAFE shader link: "+GLES20.glGetProgramInfoLog(program));
        }
        GLES20.glUseProgram(program);
        aPosition=GLES20.glGetAttribLocation(program,"aPosition");
        uMatrix=GLES20.glGetUniformLocation(program,"uMatrix");
        uPixel=GLES20.glGetUniformLocation(program,"uPixel");
        uMode=GLES20.glGetUniformLocation(program,"uMode");
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uVideo"),0);

        int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);oesTexture=ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oesTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);

        texture=new SurfaceTexture(oesTexture);
        texture.setOnFrameAvailableListener(t->{framePending.set(true);view.requestRender();},new Handler(Looper.getMainLooper()));
        callback.onVideoSurfaceReady(texture);
    }

    @Override public void onSurfaceChanged(GL10 gl,int width,int height){surfaceW=width;surfaceH=height;}

    private void bindCommon(){
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oesTexture);
        GLES20.glUniformMatrix4fv(uMatrix,1,false,matrix,0);
        GLES20.glUniform2f(uPixel,1f/Math.max(1,videoW),1f/Math.max(1,videoH));
        GLES20.glEnableVertexAttribArray(aPosition);quad.position(0);
        GLES20.glVertexAttribPointer(aPosition,2,GLES20.GL_FLOAT,false,0,quad);
    }

    private void drawCell(int x,int y,int w,int h,int mode){
        if(w<2||h<2)return;
        float sourceAspect=(float)videoW/Math.max(1,videoH),targetAspect=(float)w/Math.max(1,h);
        int vx=x,vy=y,vw=w,vh=h;
        if(sourceAspect>targetAspect){vh=Math.max(1,Math.round(w/sourceAspect));vy=y+(h-vh)/2;}
        else{vw=Math.max(1,Math.round(h*sourceAspect));vx=x+(w-vw)/2;}
        GLES20.glViewport(vx,vy,vw,vh);GLES20.glUniform1f(uMode,(float)mode);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    }

    private void drawGrid(){
        int cw=surfaceW/3,ch=surfaceH/2;
        for(int mode=0;mode<6;mode++){
            int col=mode%3,rowTop=mode/3,x=col*cw,y=rowTop==0?surfaceH-ch:0;
            int w=col==2?surfaceW-x:cw,h=rowTop==0?ch:surfaceH-ch;
            drawCell(x,y,w,h,mode);
        }
    }

    @Override public void onDrawFrame(GL10 gl){
        if(texture==null||surfaceW<2||surfaceH<2)return;
        if(framePending.getAndSet(false)){
            try{texture.updateTexImage();texture.getTransformMatrix(matrix);hasFrame=true;}
            catch(RuntimeException ignored){return;}
        }
        GLES20.glViewport(0,0,surfaceW,surfaceH);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if(!hasFrame)return;

        long started=SystemClock.elapsedRealtimeNanos();bindCommon();
        int selected=solo;if(selected>=0)drawCell(0,0,surfaceW,surfaceH,selected);else drawGrid();
        GLES20.glDisableVertexAttribArray(aPosition);

        long now=SystemClock.elapsedRealtimeNanos();frames++;
        if(lastStats==0)lastStats=now;
        if(now-lastStats>=600_000_000L){
            double fps=frames*1_000_000_000.0/(now-lastStats),submitMs=(now-started)/1_000_000.0;
            callback.onStats(String.format(Locale.US,"%.0f FPS • GL %.1f ms",fps,submitMs));
            frames=0;lastStats=now;
        }
    }
}
