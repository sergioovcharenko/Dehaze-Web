package ua.dehaze.live;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.view.View;
import android.graphics.Color;
import android.util.Base64;
import android.widget.Toast;
import android.app.ActivityManager;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;

/** A local, entirely offline photo editor. Does not share the Camera2 SurfaceTexture. */
public final class PhotoActivity extends Activity {
    private static final int PICK_PHOTO=41;
    private WebView webView;
    private android.webkit.ValueCallback<Uri[]> pendingFiles;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(35,37,40));
        getWindow().setNavigationBarColor(Color.rgb(35,37,40));
        webView=new WebView(this);
        webView.setBackgroundColor(Color.rgb(38,40,43));
        WebSettings cfg=webView.getSettings();
        cfg.setJavaScriptEnabled(true);
        cfg.setDomStorageEnabled(true);
        cfg.setAllowFileAccess(true);
        cfg.setAllowContentAccess(true);
        cfg.setMediaPlaybackRequiresUserGesture(true);
        cfg.setJavaScriptCanOpenWindowsAutomatically(false);
        cfg.setAllowFileAccessFromFileURLs(false);
        cfg.setAllowUniversalAccessFromFileURLs(false);
        if(Build.VERSION.SDK_INT>=21)
            cfg.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new PhotoBridge(),"MetiAndroid");
        webView.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request){
                Uri uri=request.getUrl();
                return !"file".equals(uri.getScheme()) ||
                    !("android_asset".equals(uri.getHost()));
            }
        });
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView view,
                android.webkit.ValueCallback<Uri[]> callback,
                WebChromeClient.FileChooserParams params){
                if(pendingFiles!=null)pendingFiles.onReceiveValue(null);
                pendingFiles=callback;
                try{
                    Intent open=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    open.addCategory(Intent.CATEGORY_OPENABLE);
                    open.setType("image/*");
                    startActivityForResult(open,PICK_PHOTO);
                    return true;
                }catch(Exception e){
                    pendingFiles.onReceiveValue(null);
                    pendingFiles=null;
                    Toast.makeText(PhotoActivity.this,"Галерея недоступна",Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/photo.html");
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_PHOTO)return;
        if(pendingFiles==null)return;
        Uri[] uris=(result==RESULT_OK&&data!=null&&data.getData()!=null)
          ?new Uri[]{data.getData()}:null;
        pendingFiles.onReceiveValue(uris);
        pendingFiles=null;
    }

    public final class PhotoBridge {
        @JavascriptInterface public void goLive(){runOnUiThread(()->finish());}
        @JavascriptInterface public void savePng(String pngBase64){
            new Thread(()->{
                try{
                    String base64=pngBase64;
                    int comma=base64.indexOf(',');
                    if(comma>=0)base64=base64.substring(comma+1);
                    byte[] bytes=Base64.decode(base64,Base64.DEFAULT);
                    if(bytes.length<100)throw new Exception("Порожній результат");
                    String filename="Meti-Tuman-MAX-"+System.currentTimeMillis()+".png";
                    if(Build.VERSION.SDK_INT>=29){
                        ContentValues values=new ContentValues();
                        values.put(MediaStore.Images.Media.DISPLAY_NAME,filename);
                        values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
                        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                            "Pictures/MetiTumanMAX");
                        values.put(MediaStore.Images.Media.IS_PENDING,1);
                        Uri uri=getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
                        if(uri==null)throw new Exception("Немає доступу до Галереї");
                        try(OutputStream stream=getContentResolver().openOutputStream(uri)){
                            if(stream==null)throw new Exception("Пам'ять недоступна");
                            stream.write(bytes);
                        }
                        values.clear();
                        values.put(MediaStore.Images.Media.IS_PENDING,0);
                        getContentResolver().update(uri,values,null,null);
                    }else{
                        File folder=getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
                        if(folder==null)throw new Exception("Пам'ять недоступна");
                        try(FileOutputStream stream=new FileOutputStream(new File(folder,filename))){
                            stream.write(bytes);
                        }
                    }
                    show("Збережено: "+filename);
                }catch(Exception ex){show("Не вдалося зберегти: "+ex.getMessage());}
            },"photo-export").start();
        }
        private void show(String msg){runOnUiThread(()->{
            Toast.makeText(PhotoActivity.this,msg,Toast.LENGTH_LONG).show();
            if(webView!=null)webView.evaluateJavascript(
                "window.onNativeSave&&window.onNativeSave("+org.json.JSONObject.quote(msg)+")",null);
        });}
    }

    @Override protected void onDestroy(){
        if(pendingFiles!=null){pendingFiles.onReceiveValue(null);pendingFiles=null;}
        if(webView!=null){
            webView.removeJavascriptInterface("MetiAndroid");
            webView.destroy();webView=null;
        }
        super.onDestroy();
    }
}
