# JARVIS — Newly architecture

This is a React Native / Expo-style project with a native Android foreground service for the parts that cannot be made reliable with JS alone.

## What is real/native
- True on-device wake-word detection via Picovoice Porcupine.
- Built-in `JARVIS` keyword; no keyboard/ENTER fallback.
- Android foreground microphone service.
- Android SpeechRecognizer for the post-wake command.
- Native Android TTS.
- Gemini REST calls with up to 9-key failover.
- Gemini Google Search grounding for time-sensitive questions.
- Android app/deep-link actions.
- Persistent state/log events to the React Native UI.

## Required setup
1. Put a Picovoice AccessKey in the app's JARVIS screen. Porcupine requires an AccessKey.
2. Put one or more Gemini API keys in the Gemini field, comma separated, maximum 9.
3. Build with Newly's Android/React Native pipeline or `npx expo prebuild` + Android build.

## Important
The true offline wake detector is `Jarvis` itself. Natural speech such as `Hey Jarvis` is handled by the detector's Jarvis keyword and the post-detection speech pipeline. A custom exact `Hey Jarvis` acoustic model can be substituted later with a Picovoice `.ppn` model without changing the service architecture.

Android may still restrict microphone use depending on device vendor, battery optimization, permission state, or OS policy. The app uses a foreground microphone service and persistent notification.
