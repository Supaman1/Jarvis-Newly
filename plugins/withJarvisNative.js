/**
 * withJarvisNative
 * -----------------
 * Local Expo config plugin. Runs during `expo prebuild` (and therefore during
 * any EAS Build, which prebuilds automatically when no android/ directory is
 * committed). It re-applies everything that used to live in the hand-written
 * android/ folder:
 *
 *   1. Copies the custom native sources (JarvisModule, JarvisService,
 *      JarvisAccessibilityService, JarvisPackage, JarvisWakeWord.kt) into the
 *      generated project, plus any wake-word .onnx model files dropped into
 *      android/assets/ (see android/assets/README.md).
 *   2. Copies accessibility_service_config.xml into res/xml.
 *   3. Adds the accessibility_service_description string.
 *   4. Registers <service> entries for JarvisService and
 *      JarvisAccessibilityService in AndroidManifest.xml.
 *   5. Registers JarvisPackage in MainApplication's getPackages().
 *   6. Adds the openWakeWord Gradle dependency to app/build.gradle (Maven
 *      Central, Apache 2.0, no API key — see README.md for why this replaced
 *      Picovoice Porcupine).
 *
 * Because prebuild now regenerates android/ from Expo's own template every
 * time, this plugin is the *only* place the Jarvis-specific native code
 * lives — it never has to be hand-edited again after an SDK upgrade.
 */
const fs = require('fs');
const path = require('path');
const {
  withAndroidManifest,
  withStringsXml,
  withAppBuildGradle,
  withMainApplication,
  withDangerousMod,
  AndroidConfig,
} = require('expo/config-plugins');

const JAVA_FILES = [
  'JarvisModule.java',
  'JarvisService.java',
  'JarvisAccessibilityService.java',
  'JarvisPackage.java',
  'JarvisWakeWord.kt',
];

const ACCESSIBILITY_DESCRIPTION =
  'Lets JARVIS read what is on screen and tap, type, or scroll on your behalf when you give it a voice command.';

// 1 + 2: copy native Java sources and the accessibility service XML config
// into the generated project. withDangerousMod runs after the native
// project exists but before the build, which is exactly when these files
// need to land on disk.
function withJarvisFiles(config) {
  return withDangerousMod(config, [
    'android',
    async (config) => {
      const platformRoot = config.modRequest.platformProjectRoot; // .../android
      const pkg = config.android.package;
      if (!pkg) {
        throw new Error(
          'withJarvisNative: app.json is missing android.package — required to know where to place the native Java files.'
        );
      }
      const pkgPath = pkg.split('.').join('/');
      const javaDir = path.join(platformRoot, 'app/src/main/java', pkgPath);
      const xmlDir = path.join(platformRoot, 'app/src/main/res/xml');
      fs.mkdirSync(javaDir, { recursive: true });
      fs.mkdirSync(xmlDir, { recursive: true });

      const srcJavaDir = path.join(__dirname, 'android', 'java');
      for (const file of JAVA_FILES) {
        fs.copyFileSync(path.join(srcJavaDir, file), path.join(javaDir, file));
      }

      fs.copyFileSync(
        path.join(__dirname, 'android', 'accessibility_service_config.xml'),
        path.join(xmlDir, 'accessibility_service_config.xml')
      );

      // Wake-word ONNX model files (melspectrogram.onnx, embedding_model.onnx,
      // jarvis.onnx) are binary weights the user must supply themselves — see
      // plugins/android/assets/README.md. Copy whatever is there; if the
      // folder is empty (models not added yet) this is a no-op and the app
      // falls back to push-to-talk at runtime instead of failing the build.
      const srcAssetsDir = path.join(__dirname, 'android', 'assets');
      if (fs.existsSync(srcAssetsDir)) {
        const assetsDir = path.join(platformRoot, 'app/src/main/assets');
        fs.mkdirSync(assetsDir, { recursive: true });
        for (const file of fs.readdirSync(srcAssetsDir)) {
          if (!file.endsWith('.onnx')) continue; // skip README.md etc.
          fs.copyFileSync(path.join(srcAssetsDir, file), path.join(assetsDir, file));
        }
      }

      return config;
    },
  ]);
}

// 3: string used by accessibility_service_config.xml's android:description
function withJarvisStrings(config) {
  return withStringsXml(config, (config) => {
    config.modResults = AndroidConfig.Strings.setStringItem(
      [
        {
          $: { name: 'accessibility_service_description' },
          _: ACCESSIBILITY_DESCRIPTION,
        },
      ],
      config.modResults
    );
    return config;
  });
}

// 4: <service> entries in AndroidManifest.xml
function withJarvisManifest(config) {
  return withAndroidManifest(config, (config) => {
    const mainApplication = AndroidConfig.Manifest.getMainApplicationOrThrow(
      config.modResults
    );
    mainApplication.service = mainApplication.service || [];

    const hasService = (name) =>
      mainApplication.service.some((s) => s.$['android:name'] === name);

    if (!hasService('.JarvisService')) {
      mainApplication.service.push({
        $: {
          'android:name': '.JarvisService',
          'android:exported': 'false',
          'android:foregroundServiceType': 'microphone',
        },
      });
    }

    if (!hasService('.JarvisAccessibilityService')) {
      mainApplication.service.push({
        $: {
          'android:name': '.JarvisAccessibilityService',
          'android:exported': 'true',
          'android:permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE',
        },
        'intent-filter': [
          {
            action: [
              {
                $: {
                  'android:name': 'android.accessibilityservice.AccessibilityService',
                },
              },
            ],
          },
        ],
        'meta-data': [
          {
            $: {
              'android:name': 'android.accessibilityservice',
              'android:resource': '@xml/accessibility_service_config',
            },
          },
        ],
      });
    }

    return config;
  });
}

// 5: Android 11+ package visibility for apps JARVIS can explicitly launch, plus a
// launcher-intent query so the fuzzy name-match fallback in openApp() can see any
// installed app with a launcher icon (not just the ones named below).
function withJarvisQueries(config) {
  return withAndroidManifest(config, (config) => {
    const manifest = config.modResults.manifest;
    manifest.queries = manifest.queries || [];
    const packages = [
      'com.whatsapp',
      'com.google.android.youtube',
      'com.android.chrome',
      'com.google.android.apps.maps',
      'com.google.android.gm',
      'com.spotify.music',
      'com.instagram.android',
      'com.android.settings',
    ];
    const existing = new Set();
    for (const q of manifest.queries) {
      for (const p of (q.package || [])) {
        if (p.$ && p.$['android:name']) existing.add(p.$['android:name']);
      }
    }
    let queryBlock = manifest.queries[0];
    if (!queryBlock) {
      queryBlock = { package: [] };
      manifest.queries.push(queryBlock);
    }
    queryBlock.package = queryBlock.package || [];
    for (const name of packages) {
      if (!existing.has(name)) {
        queryBlock.package.push({ $: { 'android:name': name } });
      }
    }
    const hasLauncherIntentQuery = manifest.queries.some((q) =>
      (q.intent || []).some((it) =>
        (it.action || []).some((a) => a.$ && a.$['android:name'] === 'android.intent.action.MAIN') &&
        (it.category || []).some((c) => c.$ && c.$['android:name'] === 'android.intent.category.LAUNCHER')
      )
    );
    if (!hasLauncherIntentQuery) {
      manifest.queries.push({
        intent: [
          {
            action: [{ $: { 'android:name': 'android.intent.action.MAIN' } }],
            category: [{ $: { 'android:name': 'android.intent.category.LAUNCHER' } }],
          },
        ],
      });
    }
    return config;
  });
}

// 5: register JarvisPackage in MainApplication's getPackages()
function withJarvisPackageRegistration(config) {
  return withMainApplication(config, (config) => {
    const { language } = config.modResults;
    let { contents } = config.modResults;
    const pkg = config.android.package;
    if (!pkg) throw new Error('withJarvisNative: android.package is required.');
    const jarvisPackage = `${pkg}.JarvisPackage`;

    if (contents.includes('JarvisPackage')) return config;

    if (language === 'kt') {
      // Expo/RN 0.81 uses PackageList(this).packages, commonly with an
      // apply block. Patch the expression without relying on indentation.
      const patterns = [
        /PackageList\(this\)\.packages\.apply\s*\{/,
        /PackageList\(this\)\.packages/,
      ];
      let matched = false;
      for (const re of patterns) {
        if (re.test(contents)) {
          contents = contents.replace(
            re,
            (m) => `${m}\n              add(${jarvisPackage}())`
          );
          matched = true;
          break;
        }
      }
      if (!matched) {
        throw new Error(
          'withJarvisNative: could not locate React Native PackageList in MainApplication.kt.'
        );
      }
    } else {
      const anchor = 'new PackageList(this).getPackages()';
      if (!contents.includes(anchor)) {
        throw new Error(
          'withJarvisNative: could not locate React Native PackageList in MainApplication.java.'
        );
      }
      contents = contents.replace(
        anchor,
        `${anchor};\n    packages.add(new ${jarvisPackage}())`
      );
    }

    config.modResults.contents = contents;
    return config;
  });
}


// 6: openWakeWord Android SDK dependency (Maven Central, Apache 2.0, no API key —
// replaces the now-defunct Picovoice Porcupine dependency, see README.md).
function withWakeWordDependency(config) {
  return withAppBuildGradle(config, (config) => {
    let gradle = config.modResults.contents;
    if (!/xyz\.rementia:openwakeword:0\.1\.3/.test(gradle)) {
      const dep = "    implementation('xyz.rementia:openwakeword:0.1.3')";
      if (!/dependencies\s*\{/.test(gradle)) {
        throw new Error('withJarvisNative: generated app/build.gradle has no dependencies block.');
      }
      gradle = gradle.replace(/dependencies\s*\{/, (m) => `${m}\n${dep}`);
    }
    // Bundles ONNX Runtime, which ships native .so files. pickFirst prevents
    // duplicate-file conflicts if another dependency ships the same ABI library.
    if (!/pickFirst\s+['"]\*\*\/libonnxruntime.*\.so['"]/.test(gradle)) {
      if (!/android\s*\{/.test(gradle)) {
        throw new Error('withJarvisNative: generated app/build.gradle has no android block.');
      }
      gradle = gradle.replace(/android\s*\{/, `android {\n    packagingOptions {\n        pickFirst '**/libonnxruntime*.so'\n    }`);
    }
    // Defensive: uncompressed .onnx in the APK avoids a well-known Android pitfall
    // where bundled binary ML models get corrupted by default asset compression if
    // anything in the load path uses offset/mmap-style access instead of a plain
    // stream copy (the same class of issue TensorFlow Lite docs warn about for .tflite).
    if (!/aaptOptions\s*\{[^}]*noCompress\s+['"]onnx['"]/.test(gradle)) {
      if (/aaptOptions\s*\{/.test(gradle)) {
        gradle = gradle.replace(/aaptOptions\s*\{/, (m) => `${m}\n        noCompress 'onnx'`);
      } else {
        gradle = gradle.replace(/android\s*\{/, `android {\n    aaptOptions {\n        noCompress 'onnx'\n    }`);
      }
    }
    config.modResults.contents = gradle;
    return config;
  });
}

module.exports = function withJarvisNative(config) {
  config = withJarvisFiles(config);
  config = withJarvisStrings(config);
  config = withJarvisManifest(config);
  config = withJarvisQueries(config);
  config = withJarvisPackageRegistration(config);
  config = withWakeWordDependency(config);
  return config;
};
