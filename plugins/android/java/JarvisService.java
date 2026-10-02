package com.jarvis.newly;

import android.Manifest;import android.app.*;import android.content.*;import android.content.pm.PackageManager;import android.content.pm.ServiceInfo;import android.net.Uri;import android.os.*;import android.speech.*;import android.speech.tts.TextToSpeech;import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import org.json.*;

public class JarvisService extends Service implements RecognitionListener {
  private static final String CH="jarvis"; private static final int NOTIF=44;
  public static JarvisService instance;
  private JarvisWakeWord wake; private SpeechRecognizer speech; private Intent speechIntent; private TextToSpeech tts; private boolean running=false, command=false; private String state="Idle"; private Handler h=new Handler(Looper.getMainLooper());
  private static final String PROMPT="You are JARVIS, a private personal voice assistant. Speak only concise natural sentences, normally one or two. No markdown, bullets, URLs, filler, or robotic narration. Be calm, dry, competent and direct. For current events, weather, schedules, prices, scores, releases, documentation and other time-sensitive facts, use Google Search grounding before answering. Never invent uncertain facts. You have Android device-control capabilities when the relevant tools are available. When screen control is enabled, you can inspect the current screen through accessibility UI data and visual screenshots, then use taps, typing, scrolling, swipes, long presses, and navigation to carry out the user request. Do not say device control is beyond your capabilities merely because a UI element is not exposed in accessibility data; use a current screenshot and coordinate-based gesture when visual control is available. Re-observe the screen after actions when needed and never claim an action succeeded unless the tool actually executed. If the required capability is unavailable, say so plainly. For device commands, act immediately when possible. If an action is ambiguous, ask one short clarification. If an action fails, state the failure plainly in five words or fewer.";
  @Override public void onCreate(){super.onCreate();instance=this;createChannel();Notification n=notification("Listening for Jarvis");if(Build.VERSION.SDK_INT>=29){startForeground(NOTIF,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);}else{startForeground(NOTIF,n);}tts=new TextToSpeech(this,r->{if(r==TextToSpeech.SUCCESS){tts.setLanguage(Locale.US);}}); speechIntent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3);}
  @Override public int onStartCommand(Intent i,int flags,int id){running=true;startWake();return START_NOT_STICKY;}
  public String getState(){return state;}
  private void setState(String s){state=s;JarvisModule.push("jarvis_state",s);update(s);}
  private void startWake(){
    if(!running)return;
    try{
      if(wake!=null)wake.release();
      wake=new JarvisWakeWord(this, () -> h.post(this::onWake));
      wake.start(reason -> h.post(() -> { setState("Idle"); update("Wake word unavailable: "+reason); }));
      setState("Listening");
    }catch(Exception e){update("Wake engine failed");retryWake();}
  }
  /** Bypasses the wake word entirely — lets the UI's push-to-talk button work even if the on-device wake model isn't set up yet. */
  public void pushToTalk(){h.post(()->{try{if(wake!=null)wake.stop();}catch(Exception ignored){}if(!command)startCommandRecognition();});}
  private void retryWake(){if(running)h.postDelayed(this::startWake,1200);}
  private void onWake(){if(!running)return;try{if(wake!=null)wake.stop();}catch(Exception ignored){}setState("Listening");startCommandRecognition();}
  private void startCommandRecognition(){h.post(()->{command=true;try{if(speech!=null)speech.destroy();speech=SpeechRecognizer.createSpeechRecognizer(this);speech.setRecognitionListener(this);setState("Listening");speech.startListening(speechIntent);}catch(Exception e){command=false;retryWake();}});}
  @Override public void onResults(Bundle b){try{if(speech!=null)speech.stopListening();}catch(Exception ignored){} ArrayList<String> r=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);command=false;if(r!=null&&!r.isEmpty()){String q=r.get(0).trim();if(!q.isEmpty())process(q);else h.post(this::startWake);}else h.post(this::startWake);}
  private void process(String q){
    if(isAction(q)&&JarvisAccessibilityService.instance!=null){new Thread(()->runAgentTask(q)).start();return;}
    setState("Processing");new Thread(()->{try{String answer=gemini(q);handleAction(q);if(answer!=null&&!answer.isBlank())speak(answer);else h.post(this::startWake);}catch(Exception e){speak("Network request failed.");}}).start();
  }
  private boolean isAction(String q){String x=" "+q.toLowerCase(Locale.US)+" ";String[] w={"open ","tap ","click ","search ","scroll ","type ","send ","forward ","copy ","message ","text ","call ","play ","go to ","find "};for(String s:w)if(x.contains(s))return true;return false;}
  private static final String AGENT_PROMPT="You control this Android phone's screen to carry out the user's task. Each turn you get the latest numbered accessibility UI list and, when available, a current visual screenshot of the screen, plus the actions already taken. Call exactly one function per turn. Use open_app to launch an app before interacting with its contents. Prefer accessibility element indexes when they expose the target reliably. If the accessibility list does not expose something visible or is insufficient, use the screenshot to identify the target and use the gesture tool with screen coordinates. After every tap, typing action, scroll, or gesture, treat the screen as changed and rely on the next fresh observation rather than old indexes or positions. Use wait when a screen needs time to load. Prefer the fewest steps that reliably finish the task. When it is fully done, call finish with a short spoken confirmation of what happened. If it truly cannot be done, call fail with a short spoken explanation. Never claim success without observing or otherwise confirming the result.";
  private void runAgentTask(String cmd){
    setState("Acting");
    JSONArray history=new JSONArray();
    try{
      for(int step=0;step<12;step++){
        if(JarvisAccessibilityService.instance==null){speak("Screen control isn't turned on. Enable it in accessibility settings.");return;}
        String dump=JarvisAccessibilityService.instance.dumpScreen();
        JSONObject call=agentStep(cmd,dump,history);
        String name=call.optString("name","fail");
        JSONObject args=call.optJSONObject("args");if(args==null)args=new JSONObject();
        JSONObject rec=new JSONObject();rec.put("step",step);rec.put("name",name);rec.put("args",args);history.put(rec);
        update(name+" "+args.toString());
        if(name.equals("finish")){speak(args.optString("message","Done."));return;}
        if(name.equals("fail")){speak(args.optString("message","I couldn't do that."));return;}
        execute(name,args);
        Thread.sleep(650);
      }
      speak("Stopped after too many steps.");
    }catch(Exception e){speak("Screen action failed.");}
  }
  private void execute(String name,JSONObject args){
    JarvisAccessibilityService a=JarvisAccessibilityService.instance;if(a==null)return;
    try{
      switch(name){
        case "tap": a.tap(args.optInt("index",-1)); break;
        case "type_text": a.typeText(args.optInt("index",-1),args.optString("text","")); break;
        case "scroll": a.scroll(args.optString("direction","down"), args.has("index")?args.optInt("index"):-1); break;
        case "gesture": a.gesture(args.optString("action","tap"), args.optInt("x1",-1), args.optInt("y1",-1), args.optInt("x2",-1), args.optInt("y2",-1), args.optInt("duration_ms",300)); break;
        case "open_app": a.openApp(args.optString("name","")); break;
        case "key": a.key(args.optString("action","back")); break;
        case "wait": Thread.sleep(Math.min(3000,Math.max(0,args.optInt("ms",500)))); break;
        default: break;
      }
    }catch(Exception ignored){}
  }
  private JSONObject agentStep(String cmd,String dump,JSONArray history)throws Exception{
    String keys=getPrefs().getString("gemini_keys","");String[] ks=keys.split(",");if(ks.length>9)ks=Arrays.copyOf(ks,9);
    Exception last=null;
    for(String raw:ks){
      String key=raw.trim();if(key.isEmpty())continue;
      try{
        JSONObject body=new JSONObject();
        body.put("system_instruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",AGENT_PROMPT))));
        String userText="TASK: "+cmd+"\n\nCURRENT ACCESSIBILITY SCREEN:\n"+dump+"\n\nACTIONS SO FAR:\n"+history.toString();
        JSONArray userParts=new JSONArray();
        userParts.put(new JSONObject().put("text",userText));
        String screenshot=JarvisAccessibilityService.instance.getScreenshotBase64();
        if(screenshot!=null&&!screenshot.isEmpty()) userParts.put(new JSONObject().put("inline_data",new JSONObject().put("mime_type","image/jpeg").put("data",screenshot)));
        body.put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",userParts)));
        body.put("tools",new JSONArray().put(new JSONObject().put("function_declarations",toolDecls())));
        body.put("tool_config",new JSONObject().put("function_calling_config",new JSONObject().put("mode","ANY")));
        String u="https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key="+URLEncoder.encode(key,"UTF-8");
        String rawJson=post(u,body.toString());
        JSONObject o=new JSONObject(rawJson);
        JSONArray c=o.optJSONArray("candidates");if(c==null||c.length()==0)throw new IOException("No candidate");
        JSONArray parts=c.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        for(int i=0;i<parts.length();i++){
          JSONObject fc=parts.getJSONObject(i).optJSONObject("functionCall");
          if(fc!=null){JSONObject r=new JSONObject();r.put("name",fc.optString("name","fail"));r.put("args",fc.optJSONObject("args")==null?new JSONObject():fc.getJSONObject("args"));return r;}
        }
        throw new IOException("No function call returned");
      }catch(Exception e){last=e;}
    }
    throw last==null?new IOException("No API key"):last;
  }
  private JSONArray toolDecls()throws JSONException{
    JSONArray d=new JSONArray();
    d.put(tool("tap","Tap the UI element with this index from the current screen list.",p("index","INTEGER","Index number from the dump",true)));
    d.put(tool("type_text","Type text into the editable field with this index (it is focused automatically first).",p2("index","INTEGER","Index of the input field",true,"text","STRING","Text to type",true)));
    d.put(tool("scroll","Scroll the screen or one scrollable element.",p2("direction","STRING","up, down, left, or right",true,"index","INTEGER","Optional index of a specific scrollable element",false)));
    d.put(tool("gesture","Perform a visual coordinate-based touch gesture when accessibility indexes cannot identify the target. action=tap, long_press, or swipe. Coordinates are screen pixels from the current screenshot.",gestureParams()));
    d.put(tool("open_app","Launch an installed app by name.",p("name","STRING","App name, e.g. whatsapp, youtube, chrome",true)));
    d.put(tool("key","Press a navigation key.",p("action","STRING","one of back, home, recents",true)));
    d.put(tool("wait","Pause briefly before the next screen read.",p("ms","INTEGER","Milliseconds to wait, max 3000",true)));
    d.put(tool("finish","Call when the task is fully completed.",p("message","STRING","Short spoken confirmation for the user",true)));
    d.put(tool("fail","Call when the task cannot be completed.",p("message","STRING","Short spoken explanation for the user",true)));
    return d;
  }
  private JSONObject tool(String name,String desc,JSONObject params)throws JSONException{JSONObject o=new JSONObject();o.put("name",name);o.put("description",desc);o.put("parameters",params);return o;}
  private JSONObject gestureParams()throws JSONException{
    JSONObject props=new JSONObject();
    props.put("action",new JSONObject().put("type","STRING").put("description","tap, long_press, or swipe").put("enum",new JSONArray().put("tap").put("long_press").put("swipe")));
    props.put("x1",new JSONObject().put("type","INTEGER").put("description","Start X coordinate from the current screenshot"));
    props.put("y1",new JSONObject().put("type","INTEGER").put("description","Start Y coordinate from the current screenshot"));
    props.put("x2",new JSONObject().put("type","INTEGER").put("description","End X coordinate for swipe; omit or repeat x1 for tap/long press"));
    props.put("y2",new JSONObject().put("type","INTEGER").put("description","End Y coordinate for swipe; omit or repeat y1 for tap/long press"));
    props.put("duration_ms",new JSONObject().put("type","INTEGER").put("description","Gesture duration in milliseconds"));
    JSONObject o=new JSONObject();o.put("type","OBJECT").put("properties",props);
    JSONArray r=new JSONArray();r.put("action").put("x1").put("y1");o.put("required",r);return o;
  }
  private JSONObject p(String n,String t,String d,boolean req)throws JSONException{
    JSONObject props=new JSONObject();JSONObject f=new JSONObject();f.put("type",t);f.put("description",d);props.put(n,f);
    JSONObject o=new JSONObject();o.put("type","OBJECT");o.put("properties",props);if(req){JSONArray r=new JSONArray();r.put(n);o.put("required",r);}return o;
  }
  private JSONObject p2(String n1,String t1,String d1,boolean r1,String n2,String t2,String d2,boolean r2)throws JSONException{
    JSONObject props=new JSONObject();
    JSONObject f1=new JSONObject();f1.put("type",t1);f1.put("description",d1);props.put(n1,f1);
    JSONObject f2=new JSONObject();f2.put("type",t2);f2.put("description",d2);props.put(n2,f2);
    JSONObject o=new JSONObject();o.put("type","OBJECT");o.put("properties",props);
    JSONArray req=new JSONArray();if(r1)req.put(n1);if(r2)req.put(n2);if(req.length()>0)o.put("required",req);
    return o;
  }
  private String gemini(String q)throws Exception{String keys=getPrefs().getString("gemini_keys","");String[] ks=keys.split(",");if(ks.length>9)ks=Arrays.copyOf(ks,9);Exception last=null;for(String raw:ks){String key=raw.trim();if(key.isEmpty())continue;try{JSONObject body=new JSONObject();body.put("system_instruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",PROMPT))));body.put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",q)))));body.put("generationConfig",new JSONObject().put("temperature",0.2));body.put("tools",new JSONArray().put(new JSONObject().put("google_search",new JSONObject())));String u="https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key="+URLEncoder.encode(key,"UTF-8");String rawJson=post(u,body.toString());JSONObject o=new JSONObject(rawJson);JSONArray c=o.optJSONArray("candidates");if(c==null||c.length()==0)throw new IOException("No candidate");JSONArray parts=c.getJSONObject(0).getJSONObject("content").getJSONArray("parts");StringBuilder out=new StringBuilder();for(int i=0;i<parts.length();i++)out.append(parts.getJSONObject(i).optString("text",""));return clean(out.toString());}catch(Exception e){last=e;}}throw last==null?new IOException("No API key"):last;}
  private String clean(String s){return s.replaceAll("[*#`]"," ").replaceAll("https?://\\S+"," ").replaceAll("\\s+"," ").trim();}
  private void handleAction(String q){String x=q.toLowerCase(Locale.US).trim();try{if(x.startsWith("open ")){String app=x.substring(5).trim();Intent i=getPackageManager().getLaunchIntentForPackage(pkg(app));if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);}}else if(x.matches(".*https?://\\S+.*")){String u=x.substring(x.indexOf("http"));startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}}catch(Exception ignored){}}
  private String pkg(String a){if(a.equals("spotify"))return "com.spotify.music";if(a.equals("youtube"))return "com.google.android.youtube";if(a.equals("chrome"))return "com.android.chrome";if(a.equals("maps")||a.equals("google maps"))return "com.google.android.apps.maps";if(a.equals("gmail"))return "com.google.android.gm";if(a.equals("whatsapp"))return "com.whatsapp";return a;}
  private void speak(String text){h.post(()->{setState("Speaking");if(tts!=null){tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener(){public void onStart(String id){}public void onDone(String id){startWake();}public void onError(String id){startWake();}});tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"jarvis");}else startWake();});}
  private android.content.SharedPreferences getPrefs(){return getSharedPreferences("jarvis",MODE_PRIVATE);}
  private String post(String u,String data)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setRequestMethod("POST");c.setConnectTimeout(6000);c.setReadTimeout(18000);c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);try(OutputStream os=c.getOutputStream()){os.write(data.getBytes(StandardCharsets.UTF_8));}int code=c.getResponseCode();InputStream in=code<400?c.getInputStream():c.getErrorStream();if(in==null)throw new IOException("HTTP "+code);StringBuilder sb=new StringBuilder();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)sb.append(new String(buf,0,n,StandardCharsets.UTF_8));in.close();String s=sb.toString();if(code>=400)throw new IOException("HTTP "+code);return s;}
  private void update(String s){try{getSharedPreferences("jarvis",0).edit().putString("state",s).apply();}catch(Exception ignored){}JarvisModule.push("jarvis_log",s);}
  private Notification notification(String text){Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CH):new Notification.Builder(this);return b.setContentTitle("JARVIS").setContentText(text).setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build();}
  private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CH,"JARVIS microphone",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(c);}}
  @Override public void onDestroy(){running=false;instance=null;try{if(wake!=null)wake.release();}catch(Exception ignored){}try{if(speech!=null)speech.destroy();}catch(Exception ignored){}if(tts!=null)tts.shutdown();setState("Idle");super.onDestroy();}
  @Override public IBinder onBind(Intent i){return null;}
  public void onReadyForSpeech(Bundle b){} public void onBeginningOfSpeech(){} public void onRmsChanged(float r){} public void onBufferReceived(byte[] b){} public void onEndOfSpeech(){} public void onPartialResults(Bundle b){} public void onEvent(int a,Bundle b){} public void onError(int e){command=false;retryWake();}
          }
    
