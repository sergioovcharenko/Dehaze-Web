package ua.dehaze.live;

import android.content.Context;
import android.graphics.Bitmap;
import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/** Owned and called by the comparison workspace's single background executor. */
final class NeuralDehaze implements AutoCloseable {
    static final int SIDE=256;
    private final Context context;
    private OrtSession session;
    NeuralDehaze(Context context){this.context=context.getApplicationContext();}
    private void load() throws Exception {
        if(session!=null)return;
        byte[] model;
        try(InputStream in=context.getAssets().open("dehazeformer-t-outdoor-256.onnx");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[16384];int count;while((count=in.read(buf))!=-1)out.write(buf,0,count);model=out.toByteArray();
        }
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session=OrtEnvironment.getEnvironment().createSession(model,options);
        }
    }
    Bitmap process(Bitmap input) throws Exception {
        if(input.getWidth()>SIDE||input.getHeight()>SIDE)throw new IllegalArgumentException("AI input exceeds 256");
        load();BcDehaze.checkCancel();int w=input.getWidth(),h=input.getHeight();int[] pixels=new int[w*h];
        input.getPixels(pixels,0,w,0,0,w,h);float[] planes=ImagePlanes.pack(pixels,w,h,SIDE);
        try(OnnxTensor tensor=OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(planes),new long[]{1,3,SIDE,SIDE});
            OrtSession.Result result=session.run(Collections.singletonMap("image",tensor))){
            OnnxValue value=result.get(0);
            if(!(value instanceof OnnxTensor))throw new IllegalStateException("Unexpected model output");
            FloatBuffer f=((OnnxTensor)value).getFloatBuffer();float[] output=new float[f.remaining()];f.get(output);
            BcDehaze.checkCancel();int[] rgb=ImagePlanes.unpack(output,SIDE,w,h);
            return Bitmap.createBitmap(rgb,w,h,Bitmap.Config.ARGB_8888);
        }
    }
    @Override public void close(){if(session!=null){try{session.close();}catch(OrtException ignored){}session=null;}}
}
