#!/usr/bin/env node
/**
 * Patches the Capacitor-generated android/ project with:
 *  - our custom Kotlin plugin/service/tile/activity files
 *  - AndroidManifest.xml entries (permissions, service, tile, activity)
 *  - a transparent theme for the consent trampoline activity
 *  - Kotlin support + extra gradle dependencies
 *
 * Run this AFTER `npx cap add android` (or `npx cap sync android` on an
 * existing android/ folder) and BEFORE `./gradlew assembleDebug`.
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const androidDir = path.join(root, 'android');
const pkgPath = 'com/joshua/screenrecorder';
const javaDir = path.join(androidDir, 'app/src/main/java', pkgPath);
const manifestPath = path.join(androidDir, 'app/src/main/AndroidManifest.xml');
const valuesDir = path.join(androidDir, 'app/src/main/res/values');
const stylesPath = path.join(valuesDir, 'styles.xml');
const rootGradlePath = path.join(androidDir, 'build.gradle');
const appGradlePath = path.join(androidDir, 'app/build.gradle');

function log(msg) { console.log('[patch-android] ' + msg); }

function ensureDir(p) { fs.mkdirSync(p, { recursive: true }); }

// 1. Copy Kotlin sources
ensureDir(javaDir);
const srcDir = path.join(root, 'native-src');
for (const file of fs.readdirSync(srcDir)) {
  if (!file.endsWith('.kt')) continue;
  fs.copyFileSync(path.join(srcDir, file), path.join(javaDir, file));
  log('copied ' + file);
}

// 2. Patch AndroidManifest.xml
let manifest = fs.readFileSync(manifestPath, 'utf8');

const permissions = `
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
`;
if (!manifest.includes('FOREGROUND_SERVICE_MEDIA_PROJECTION')) {
  manifest = manifest.replace('<application', permissions + '\n    <application');
}

const components = `
        <service
            android:name="com.joshua.screenrecorder.RecordingService"
            android:foregroundServiceType="mediaProjection"
            android:exported="false" />

        <service
            android:name="com.joshua.screenrecorder.RecordingTileService"
            android:exported="true"
            android:icon="@android:drawable/presence_video_online"
            android:label="Screen Recorder"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>

        <activity
            android:name="com.joshua.screenrecorder.CaptureConsentActivity"
            android:exported="false"
            android:theme="@style/Theme.Transparent"
            android:excludeFromRecents="true"
            android:taskAffinity="" />
`;
if (!manifest.includes('RecordingTileService')) {
  manifest = manifest.replace('</application>', components + '    </application>');
}
fs.writeFileSync(manifestPath, manifest);
log('patched AndroidManifest.xml');

// 3. Add transparent theme
ensureDir(valuesDir);
let styles = fs.existsSync(stylesPath)
  ? fs.readFileSync(stylesPath, 'utf8')
  : '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n';
if (!styles.includes('Theme.Transparent')) {
  const themeEntry = `    <style name="Theme.Transparent" parent="Theme.AppCompat.NoActionBar">
        <item name="android:windowIsTranslucent">true</item>
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowIsFloating">false</item>
        <item name="android:backgroundDimEnabled">false</item>
    </style>
`;
  styles = styles.replace('</resources>', themeEntry + '</resources>');
  fs.writeFileSync(stylesPath, styles);
  log('added Theme.Transparent to styles.xml');
}

// 4. Root build.gradle: add Kotlin plugin classpath
let rootGradle = fs.readFileSync(rootGradlePath, 'utf8');
if (!rootGradle.includes('kotlin-gradle-plugin')) {
  rootGradle = rootGradle.replace(
    /dependencies\s*{/,
    `dependencies {\n        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24"`
  );
  fs.writeFileSync(rootGradlePath, rootGradle);
  log('added Kotlin classpath to root build.gradle');
}

// 5. app/build.gradle: apply kotlin plugin + add dependencies
let appGradle = fs.readFileSync(appGradlePath, 'utf8');
if (!appGradle.includes("kotlin-android")) {
  appGradle = appGradle.replace(
    "apply plugin: 'com.android.application'",
    "apply plugin: 'com.android.application'\napply plugin: 'kotlin-android'"
  );
}
if (!appGradle.includes('androidx.documentfile')) {
  appGradle = appGradle.replace(
    /dependencies\s*{/,
    `dependencies {\n    implementation "org.jetbrains.kotlin:kotlin-stdlib:1.9.24"\n    implementation "androidx.documentfile:documentfile:1.0.1"\n    implementation "androidx.localbroadcastmanager:localbroadcastmanager:1.1.0"`
  );
}
fs.writeFileSync(appGradlePath, appGradle);
log('patched app/build.gradle');

// 6. Register the plugin in MainActivity
function findMainActivity() {
  const base = path.join(androidDir, 'app/src/main/java');
  let found = null;
  function walk(dir) {
    for (const f of fs.readdirSync(dir)) {
      const full = path.join(dir, f);
      if (fs.statSync(full).isDirectory()) walk(full);
      else if (f === 'MainActivity.java' || f === 'MainActivity.kt') found = full;
    }
  }
  walk(base);
  return found;
}

const mainActivityPath = findMainActivity();
if (mainActivityPath) {
  let content = fs.readFileSync(mainActivityPath, 'utf8');
  if (!content.includes('ScreenRecorderPlugin')) {
    if (mainActivityPath.endsWith('.kt')) {
      if (!content.includes('import com.joshua.screenrecorder.ScreenRecorderPlugin')) {
        content = content.replace(
          /package [^\n]+\n/,
          (m) => m + '\nimport com.joshua.screenrecorder.ScreenRecorderPlugin\n'
        );
      }
      if (content.includes('class MainActivity')) {
        if (/class MainActivity\s*:\s*BridgeActivity\s*\(\s*\)\s*\{?/.test(content) && !content.includes('onCreate')) {
          content = content.replace(
            /class MainActivity\s*:\s*BridgeActivity\s*\(\s*\)\s*\{?/,
            `class MainActivity : BridgeActivity() {\n    override fun onCreate(savedInstanceState: android.os.Bundle?) {\n        registerPlugin(ScreenRecorderPlugin::class.java)\n        super.onCreate(savedInstanceState)\n    }`
          );
        }
      }
    } else {
      // Java
      if (!content.includes('import com.joshua.screenrecorder.ScreenRecorderPlugin;')) {
        content = content.replace(
          /package [^\n]+;\n/,
          (m) => m + '\nimport com.joshua.screenrecorder.ScreenRecorderPlugin;\n'
        );
      }
      if (!content.includes('onCreate')) {
        content = content.replace(
          /public class MainActivity extends BridgeActivity\s*\{?/,
          `public class MainActivity extends BridgeActivity {\n    @Override\n    public void onCreate(android.os.Bundle savedInstanceState) {\n        registerPlugin(ScreenRecorderPlugin.class);\n        super.onCreate(savedInstanceState);\n    }`
        );
      }
    }
    fs.writeFileSync(mainActivityPath, content);
    log('registered ScreenRecorderPlugin in ' + path.basename(mainActivityPath));
  }
} else {
  log('WARNING: could not find MainActivity to register the plugin. Register ScreenRecorderPlugin manually.');
}

log('Android project patched successfully.');
