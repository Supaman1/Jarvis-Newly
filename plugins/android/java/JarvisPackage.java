package com.jarvis.newly;
import com.facebook.react.*;import com.facebook.react.bridge.*;import com.facebook.react.uimanager.ViewManager;import java.util.*;
public class JarvisPackage implements ReactPackage { public List<NativeModule> createNativeModules(ReactApplicationContext c){return Arrays.asList(new JarvisModule(c));} public List<ViewManager> createViewManagers(ReactApplicationContext c){return Collections.emptyList();} }
