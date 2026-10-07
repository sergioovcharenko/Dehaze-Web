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
        "void main(){\n" +
        "  vUV=(aPosition+1.0)*0.5;\n" +
        "  gl_Position=vec4(aPosition,0.0,1.0);\n" +
        "}";

    private static final String FRAGMENT =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vUV;\n" +
        "uniform samplerExternalOES uVideo;\n" +
        "uniform mat4 uMatrix;\n" +
        "uniform vec2 uPixel;\n" +
        "uniform float uMode;\n" +
        "uniform float uStrength;\n" +
        "vec3 at(vec2 uv){\n" +
        "  vec2 q=clamp(uv,vec2(.002),vec2(.998));\n" +
        "  return texture2D(uVideo,(uMatrix*vec4(q,0.0,1.0)).xy).rgb;\n" +
        "}\n" +
        "float lum(vec3 c){return dot(c,vec3(.299,.587,.114));}\n" +
        "float dark(vec3 c){return min(c.r,min(c.g,c.b));}\n" +
        "vec3 recover(vec3 c,float t,vec3 a){return clamp((c-a)/max(.12,t)+a,0.0,1.0);}\n" +
        "float bc(float c,float a){\n" +
        "  float lo=(a-c)/max(.001,a-.118);\n" +
        "  float hi=(c-a)/max(.001,1.176-a);\n" +
        "  return clamp(max(lo,hi),0.0,1.0);\n" +
        "}\n" +
        "void main(){\n" +
        "  vec3 c=at(vUV);\n" +
        "  if(uMode<0.5){gl_FragColor=vec4(c,1.0);return;}\n" +
        "  vec3 l=(at(vUV+vec2(uPixel.x*2.0,0.0))+at(vUV-vec2(uPixel.x*2.0,0.0))+\n" +
        "          at(vUV+vec2(0.0,uPixel.y*2.0))+at(vUV-vec2(0.0,uPixel.y*2.0)))*.25;\n" +
        "  float y=lum(c);\n" +

        "  if(uMode<1.5){\n" +
        "    float edge=length(c-l);\n" +
        "    float sky=smoothstep(.63,.86,y)*(1.0-smoothstep(.012,.070,edge))*smoothstep(.35,.90,vUV.y);\n" +
        "    float protect=1.0-.92*sky;\n" +
        "    float t=max(.52,1.0-uStrength*(.25+.16*y));\n" +
        "    vec3 r=recover(c,t,vec3(.86));\n" +
        "    r=mix(c,r,.82*uStrength*protect);\n" +
        "    r+=clamp(c-l,vec3(-.10),vec3(.10))*.40*uStrength*protect;\n" +
        "    gl_FragColor=vec4(clamp(r,0.0,1.0),1.0);return;\n" +
        "  }\n" +

        "  if(uMode<2.5){\n" +
        "    float d=dark(c);\n" +
        "    d=min(d,dark(at(vUV+vec2(-uPixel.x*3.0,-uPixel.y*3.0))));\n" +
        "    d=min(d,dark(at(vUV+vec2(0.0,-uPixel.y*3.0))));\n" +
        "    d=min(d,dark(at(vUV+vec2(uPixel.x*3.0,-uPixel.y*3.0))));\n" +
        "    d=min(d,dark(at(vUV+vec2(-uPixel.x*3.0,0.0))));\n" +
        "    d=min(d,dark(at(vUV+vec2(uPixel.x*3.0,0.0))));\n" +
        "    d=min(d,dark(at(vUV+vec2(-uPixel.x*3.0,uPixel.y*3.0))));\n" +
        "    d=min(d,dark(at(vUV+vec2(0.0,uPixel.y*3.0))));\n" +
        "    d=min(d,dark(at(vUV+vec2(uPixel.x*3.0,uPixel.y*3.0))));\n" +
        "    float t=max(.27,1.0-.93*uStrength*d/.93);\n" +
        "    vec3 r=recover(c,t,vec3(.93));\n" +
        "    r+=clamp(c-l,vec3(-.08),vec3(.08))*.18*uStrength;\n" +
        "    gl_FragColor=vec4(clamp(r,0.0,1.0),1.0);return;\n" +
        "  }\n" +

        "  if(uMode<3.5){\n" +
        "    float hi=max(c.r,max(c.g,c.b));\n" +
        "    float lo=min(c.r,min(c.g,c.b));\n" +
        "    float sat=(hi-lo)/max(.001,hi);\n" +
        "    float depth=max(0.0,.121779+.959710*hi-.780245*sat);\n" +
        "    float t=max(.24,exp(-1.18*depth*uStrength));\n" +
        "    vec3 r=recover(c,t,vec3(.92));\n" +
        "    gl_FragColor=vec4(clamp(r,0.0,1.0),1.0);return;\n" +
        "  }\n" +

        "  if(uMode<4.5){\n" +
        "    vec3 a=vec3(.90);\n" +
        "    float t=max(bc(c.r,a.r),max(bc(c.g,a.g),bc(c.b,a.b)));\n" +
        "    float localT=max(bc(l.r,a.r),max(bc(l.g,a.g),bc(l.b,a.b)));\n" +
        "    t=mix(t,(t+localT)*.5,.34);\n" +
        "    t=max(.25,mix(1.0,t,.88*uStrength));\n" +
        "    vec3 r=recover(c,t,a);\n" +
        "    gl_FragColor=vec4(clamp(r,0.0,1.0),1.0);return;\n" +
        "  }\n" +

        "  vec3 local=(c+l)*.5;\n" +
        "  vec3 logRatio=log(c+vec3(.035))-log(local+vec3(.035));\n" +
        "  vec3 r=c+logRatio*(.20*uStrength);\n" +
        "  float ry=lum(r);\n" +
        "  r+=(r-vec3(ry))*.13*uStrength;\n" +
        "  r=(r-.5)*(1.0+.22*uStrength)+.5;\n" +
        "  gl_FragColor=vec4(clamp(r,0.0,1.0),1.0);\n" +
        "}";

    private final GLSurfaceView view;
    private final Callback callback;
    private final FloatBuffer quad;
    private final AtomicBoolean framePending = new AtomicBoolean(false);
    private final float[] matrix = new float[16];

    private SurfaceTexture texture;
    private int oesTexture;
    private int program;
    private int aPosition;
    private int uMatrix;
    private int uPixel;
    private int uMode;
    private int uStrength;
    private int surfaceW;
    private int surfaceH;
    private int videoW = 1920;
    private int videoH = 1080;
    private volatile int solo = -1;
    private boolean hasFrame = false;
    private long lastStats = 0;
    private int frames = 0;

    BenchmarkRenderer(GLSurfaceView view, Callback callback) {
        this.view = view;
        this.callback = callback;
        float[] vertices = {-1f,-1f, 1f,-1f, -1f,1f, 1f,1f};
        quad = ByteBuffer.allocateDirect(vertices.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(vertices).position(0);
        Matrix.setIdentityM(matrix, 0);
    }

    void setVideoSize(int w, int h) {
        if (w > 0) videoW = w;
        if (h > 0) videoH = h;
        view.requestRender();
    }

    void setSolo(int index) {
        solo = index < 0 ? -1 : Math.min(5, index);
        view.requestRender();
    }

    void release() {
        view.queueEvent(() -> {
            if (texture != null) {
                texture.release();
                texture = null;
            }
        });
    }

    private int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String message = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile: " + message);
        }
        return shader;
    }

    private int makeProgram() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (ok[0] == 0) {
            String message = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("Program link: " + message);
        }
        return p;
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(.015f, .017f, .020f, 1f);
        program = makeProgram();
        GLES20.glUseProgram(program);

        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        uMatrix = GLES20.glGetUniformLocation(program, "uMatrix");
        uPixel = GLES20.glGetUniformLocation(program, "uPixel");
        uMode = GLES20.glGetUniformLocation(program, "uMode");
        uStrength = GLES20.glGetUniformLocation(program, "uStrength");
        int sampler = GLES20.glGetUniformLocation(program, "uVideo");
        GLES20.glUniform1i(sampler, 0);

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        oesTexture = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        texture = new SurfaceTexture(oesTexture);
        texture.setOnFrameAvailableListener(t -> {
            framePending.set(true);
            view.requestRender();
        }, new Handler(Looper.getMainLooper()));

        callback.onVideoSurfaceReady(texture);
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceW = width;
        surfaceH = height;
    }

    private void bindCommon() {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glUniformMatrix4fv(uMatrix, 1, false, matrix, 0);
        GLES20.glUniform2f(uPixel, 1f / Math.max(1, videoW), 1f / Math.max(1, videoH));
        GLES20.glUniform1f(uStrength, .76f);
        GLES20.glEnableVertexAttribArray(aPosition);
        quad.position(0);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad);
    }

    private void drawCell(int x, int y, int w, int h, int mode) {
        if (w < 2 || h < 2) return;
        float sourceAspect = (float)videoW / Math.max(1, videoH);
        float targetAspect = (float)w / Math.max(1, h);
        int vx = x;
        int vy = y;
        int vw = w;
        int vh = h;
        if (sourceAspect > targetAspect) {
            vh = Math.max(1, Math.round(w / sourceAspect));
            vy = y + (h - vh) / 2;
        } else {
            vw = Math.max(1, Math.round(h * sourceAspect));
            vx = x + (w - vw) / 2;
        }
        GLES20.glViewport(vx, vy, vw, vh);
        GLES20.glUniform1f(uMode, (float)mode);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawGrid() {
        int cw = surfaceW / 3;
        int ch = surfaceH / 2;
        for (int mode = 0; mode < 6; mode++) {
            int col = mode % 3;
            int rowTop = mode / 3;
            int x = col * cw;
            int y = rowTop == 0 ? surfaceH - ch : 0;
            int w = col == 2 ? surfaceW - x : cw;
            int h = rowTop == 0 ? ch : surfaceH - ch;
            drawCell(x, y, w, h, mode);
        }
    }

    @Override public void onDrawFrame(GL10 gl) {
        if (texture == null || surfaceW < 2 || surfaceH < 2) return;
        if (framePending.getAndSet(false)) {
            try {
                texture.updateTexImage();
                texture.getTransformMatrix(matrix);
                hasFrame = true;
            } catch (RuntimeException ignored) {
                return;
            }
        }

        GLES20.glViewport(0, 0, surfaceW, surfaceH);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (!hasFrame) return;

        long started = SystemClock.elapsedRealtimeNanos();
        bindCommon();

        int selected = solo;
        if (selected >= 0) drawCell(0, 0, surfaceW, surfaceH, selected);
        else drawGrid();

        GLES20.glDisableVertexAttribArray(aPosition);

        long now = SystemClock.elapsedRealtimeNanos();
        frames++;
        if (lastStats == 0) lastStats = now;
        if (now - lastStats >= 600_000_000L) {
            double fps = frames * 1_000_000_000.0 / (now - lastStats);
            double submitMs = (now - started) / 1_000_000.0;
            callback.onStats(String.format(Locale.US, "%.0f FPS • GL %.1f ms", fps, submitMs));
            frames = 0;
            lastStats = now;
        }
    }
}
