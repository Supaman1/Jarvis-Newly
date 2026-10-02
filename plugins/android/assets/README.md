# Wake-word model files (put them here before building)

The wake-word engine (`xyz.rementia:openwakeword:0.1.3`, wrapping
[openWakeWord](https://github.com/dscripka/openWakeWord)) needs three ONNX
files sitting in this folder. They are binary model weights — nothing here
can generate them for you, they have to be downloaded/trained once.

```
melspectrogram.onnx    <- shared audio preprocessing model (Apache 2.0)
embedding_model.onnx   <- shared speech embedding model (Apache 2.0)
jarvis.onnx            <- YOUR trained "Jarvis" wake-word classifier
```

## 1. The two shared files

Same for every wake word — just download once:
- From the openWakeWord repo: https://github.com/dscripka/openWakeWord
  (`openwakeword/resources/models/melspectrogram.onnx` and
  `embedding_model.onnx`)
- Or from the sample app assets in
  https://github.com/Re-MENTIA/openwakeword-android-kt

## 2. jarvis.onnx — train it yourself (free, ~10-15 min)

1. Open the official training notebook:
   https://colab.research.google.com/drive/1q1oe2zOyZp7UsB3jJiQ1IFn8z5YfjwEb
2. Run the cells, enter "jarvis" as the target phrase.
3. Download the exported `.onnx` file, rename it to `jarvis.onnx`.
4. Drop it in this folder.

## What happens if you skip this

`expo prebuild` (and therefore every EAS build) copies whatever `.onnx`
files it finds here into `android/app/src/main/assets/` automatically — see
`withJarvisNative.js`. If this folder is empty, the build still succeeds;
the wake-word engine just fails to start at runtime and JARVIS falls back to
Idle. The app's **push-to-talk button** works regardless, so you can test
everything else before the wake word is wired up.
