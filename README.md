# JARVIS — EAS build package

This package includes the native Android wake-word engine and the required ONNX model files.

## Wake word
- `melspectrogram.onnx`
- `embedding_model.onnx`
- `jarvis.onnx` (the supplied `hey_jarvis_v0.1.onnx`, renamed for the native wrapper)
- Native dependency: `xyz.rementia:openwakeword:0.1.3`

The wake-word detection runs on-device and does not require a Picovoice API key.

## Build with EAS

This package intentionally does **not** include a `package-lock.json`. EAS will therefore use `npm install` rather than `npm ci`, avoiding the previous lock-file mismatch.

From the project directory:

```bash
eas build -p android --profile production
```

The native Expo config plugin copies the Java/Kotlin sources and ONNX models into the generated Android project during prebuild.

## Screen control

Enable JARVIS under Android Accessibility settings. JARVIS prefers the accessibility UI tree and can fall back to screenshots and coordinate gestures when an element is not exposed.
