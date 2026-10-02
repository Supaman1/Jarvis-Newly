import React, {useEffect, useState} from 'react';
import {NativeModules, NativeEventEmitter, PermissionsAndroid, Platform, SafeAreaView, StatusBar, StyleSheet, Text, TextInput, TouchableOpacity, View, ScrollView} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

const {JarvisNative} = NativeModules;
const emitter = JarvisNative ? new NativeEventEmitter(JarvisNative) : null;

export default function App(){
  const [running,setRunning]=useState(false), [state,setState]=useState('Idle'), [keys,setKeys]=useState(''), [log,setLog]=useState<string[]>([]);
  const [accEnabled,setAccEnabled]=useState(false);
  useEffect(()=>{(async()=>{setKeys(await AsyncStorage.getItem('gemini_keys')||'');})();
    const subs=emitter ? ['state','log'].map(n=>emitter.addListener(`jarvis_${n}`, (x:any)=>{if(n==='state')setState(x.value);else setLog(v=>[x.value,...v].slice(0,30));})) : [];
    const poll=setInterval(async()=>{try{setAccEnabled(!!(await JarvisNative?.isAccessibilityEnabled()));}catch{}},2000);
    return ()=>{subs.forEach(s=>s.remove());clearInterval(poll);};
  },[]);
  async function request(){if(Platform.OS==='android'){const p=[PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS];for(const x of p){try{await PermissionsAndroid.request(x)}catch{}}}}
  async function start(){await request();await AsyncStorage.setItem('gemini_keys',keys);JarvisNative?.setCredentials(keys);const ok=JarvisNative?.start();if(ok!==false)setRunning(true)}
  async function stop(){JarvisNative?.stop();setRunning(false);setState('Idle')}
  async function pushToTalk(){await request();await AsyncStorage.setItem('gemini_keys',keys);JarvisNative?.setCredentials(keys);JarvisNative?.pushToTalk()}
  return <SafeAreaView style={s.root}><StatusBar barStyle="light-content" backgroundColor="#050505"/><ScrollView contentContainerStyle={s.c}>
    <Text style={s.title}>JARVIS</Text><View style={s.ring}><Text style={s.ringText}>{state==='Listening'?'◉':state==='Processing'?'…':state==='Speaking'?'◌':'○'}</Text></View><Text style={s.state}>{state.toUpperCase()}</Text>
    <Text style={s.label}>GEMINI API KEYS · comma separated · up to 9</Text><TextInput value={keys} onChangeText={setKeys} placeholder="key1,key2,key3..." placeholderTextColor="#666" secureTextEntry style={s.input}/>
    <TouchableOpacity style={[s.btn,running&&s.stop]} onPress={running?stop:start}><Text style={s.btnText}>{running?'STOP JARVIS':'START JARVIS'}</Text></TouchableOpacity>
    <TouchableOpacity style={s.ptt} onPress={pushToTalk}><Text style={s.btnText}>PUSH TO TALK</Text></TouchableOpacity>
    <Text style={s.hint}>Push-to-talk bypasses the wake word entirely — use it to test commands while the on-device "Jarvis" wake-word model is still being set up (see plugins/android/assets/README.md), or any time you'd rather not wait for the wake word.</Text>
    <Text style={s.label}>SCREEN CONTROL · {accEnabled?'ENABLED':'DISABLED'}</Text>
    <TouchableOpacity style={[s.btn,accEnabled&&s.stop]} onPress={()=>JarvisNative?.openAccessibilitySettings()}><Text style={s.btnText}>{accEnabled?'SCREEN CONTROL ON':'ENABLE SCREEN CONTROL'}</Text></TouchableOpacity>
    <Text style={s.hint}>Opens Android's Accessibility settings. Find JARVIS in the list and turn it on. If the switch is greyed out, open this app's own Settings page first via Apps → JARVIS → (⋮ menu) → "Allow restricted settings" — Android blocks sideloaded apps from this permission by default.</Text>
    <Text style={s.hint}>Wake word: “Jarvis”, detected fully on-device (no cloud, no API key). The foreground service keeps the listener alive as Android permits. With screen control on, commands like “open whatsapp and message John” or “open youtube, search cats, open the third video” are carried out step by step instead of just answered.</Text>
    <Text style={s.label}>DEBUG</Text>{log.map((x,i)=><Text key={i} style={s.log}>{x}</Text>)}
  </ScrollView></SafeAreaView>
}
const s=StyleSheet.create({root:{flex:1,backgroundColor:'#050505'},c:{padding:24,paddingTop:34,paddingBottom:60},title:{color:'#eee',fontSize:30,fontWeight:'800',letterSpacing:6,textAlign:'center'},ring:{width:150,height:150,borderRadius:75,borderWidth:2,borderColor:'#777',alignSelf:'center',marginTop:28,justifyContent:'center',alignItems:'center'},ringText:{color:'#fff',fontSize:56},state:{color:'#bbb',fontSize:14,letterSpacing:3,textAlign:'center',marginTop:16},label:{color:'#888',fontSize:11,letterSpacing:1.2,marginTop:26,marginBottom:8},input:{backgroundColor:'#111',borderWidth:1,borderColor:'#292929',borderRadius:10,color:'#eee',padding:14},btn:{marginTop:28,backgroundColor:'#eee',padding:17,borderRadius:12,alignItems:'center'},stop:{backgroundColor:'#2a2a2a'},ptt:{marginTop:14,backgroundColor:'#1c1c1c',borderWidth:1,borderColor:'#3a3a3a',padding:17,borderRadius:12,alignItems:'center'},btnText:{color:'#050505',fontWeight:'800',letterSpacing:2},hint:{color:'#777',fontSize:12,lineHeight:18,marginTop:18},log:{color:'#777',fontSize:11,marginTop:4}});
