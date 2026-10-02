package com.jarvis.newly;
import android.Manifest;import android.content.*;import android.content.pm.PackageManager;import android.os.*;import android.provider.Settings;import com.facebook.react.bridge.*;import com.facebook.react.modules.core.DeviceEventManagerModule;
public class JarvisModule extends ReactContextBaseJavaModule {
 private final ReactApplicationContext c; private static ReactApplicationContext ctx;
 JarvisModule(ReactApplicationContext c){super(c);this.c=c;ctx=c;}
 @Override public String getName(){return "JarvisNative";}
 @ReactMethod(isBlockingSynchronousMethod = true) public boolean start(){
  if(android.os.Build.VERSION.SDK_INT>=23 && c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return false;
  try{
    Intent i=new Intent(c,JarvisService.class);
    if(android.os.Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);
    return true;
  }catch(RuntimeException e){return false;}
 }
 @ReactMethod public void setCredentials(String geminiKeys){
  c.getSharedPreferences("jarvis",0).edit()
    .putString("gemini_keys",geminiKeys==null?"":geminiKeys)
    .apply();
 }
 @ReactMethod public void stop(){c.stopService(new Intent(c,JarvisService.class));}
 /** Starts (if needed) the foreground service and triggers command listening immediately, bypassing the wake word — the push-to-talk fallback. */
 @ReactMethod(isBlockingSynchronousMethod = true) public boolean pushToTalk(){
  if(android.os.Build.VERSION.SDK_INT>=23 && c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return false;
  if(JarvisService.instance==null){
    try{
      Intent i=new Intent(c,JarvisService.class);
      if(android.os.Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);
    }catch(RuntimeException e){return false;}
  }
  new Handler(Looper.getMainLooper()).postDelayed(()->{if(JarvisService.instance!=null)JarvisService.instance.pushToTalk();},400);
  return true;
 }
 @ReactMethod(isBlockingSynchronousMethod = true) public String getState(){return c.getSharedPreferences("jarvis",0).getString("state","Idle");}
 @ReactMethod public void openAccessibilitySettings(){Intent i=new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(i);}
 @ReactMethod public void isAccessibilityEnabled(Promise p){p.resolve(JarvisAccessibilityService.instance!=null);}
 /** Called from JarvisService (a plain Android Service with no ReactContext of its own) to push live updates to the JS UI. */
 public static void push(String event,String value){if(ctx==null)return;try{WritableMap m=Arguments.createMap();m.putString("value",value==null?"":value);ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(event,m);}catch(Exception ignored){}}
 public static void emit(ReactApplicationContext c,String event,String value){WritableMap m=Arguments.createMap();m.putString("value",value==null?"":value);c.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(event,m);}
}
