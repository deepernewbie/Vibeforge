# Writing apps for VibeForge

You are writing an Android project that will be built entirely on GitHub
Actions and installed on a phone. Nobody will open Android Studio. Nobody can
step through a debugger. The person you are working with is on a phone, and
their only feedback channel is a build log and whatever the app prints on
screen.

That single fact drives everything below.

---

## The contract

VibeForge takes a folder from the phone, commits it to a GitHub repository in
one commit, waits for Actions to build it, and installs the resulting APK.

For that to work, the folder you produce must be:

1. A complete, self-contained Gradle project — every file, no placeholders.
2. Buildable with `./gradlew assembleRelease` and nothing else installed.
3. Carrying a workflow that publishes the APK to a GitHub release.

If any of those is missing the push succeeds and nothing useful comes back.

---

## Required layout

```
settings.gradle
build.gradle
gradle/wrapper/gradle-wrapper.properties
.gitignore
.github/workflows/build.yml
app/build.gradle
app/src/main/AndroidManifest.xml
app/src/main/java/<package path>/*.kt
app/src/main/res/…            (only if you truly need resources)
```

There is no `gradlew` script and no `gradle-wrapper.jar` — the workflow
generates them. Do not try to include the jar; it is binary and VibeForge
skips it.

### settings.gradle

```groovy
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "YourApp"
include ':app'
```

### build.gradle (root)

```groovy
plugins {
    id 'com.android.application' version '8.2.2' apply false
    id 'org.jetbrains.kotlin.android' version '1.9.22' apply false
}
tasks.register('clean', Delete) { delete rootProject.layout.buildDirectory }
```

Keep these versions unless there is a reason to change them. They are known to
work together on the Ubuntu runner with JDK 17. A newer AGP may need a newer
Gradle and a newer Kotlin plugin, and finding that out costs a five-minute
build cycle each time.

### gradle/wrapper/gradle-wrapper.properties

```
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.2-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

### app/build.gradle

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.example.yourapp'
    compileSdk 34

    defaultConfig {
        applicationId "com.example.yourapp"
        minSdk 26
        targetSdk 34
        versionCode 1          // raise this on every release
        versionName "1.0"
    }

    signingConfigs {
        release {
            storeFile     file("${rootProject.projectDir}/app/release.keystore")
            storePassword System.getenv("APP_KEYSTORE_PASSWORD") ?: "android"
            keyAlias      System.getenv("APP_KEY_ALIAS")         ?: "androiddebugkey"
            keyPassword   System.getenv("APP_KEY_PASSWORD")      ?: "android"
        }
    }

    buildTypes {
        release {
            minifyEnabled false      // see "Do not enable R8" below
            signingConfig signingConfigs.release
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
}
```

`namespace` and `applicationId` must match the directory path of your Kotlin
files and the `package` line at the top of each. A mismatch is one of the most
common first-build failures and the error message points somewhere else.

### .github/workflows/build.yml

Copy this as-is and change only the app name.

```yaml
name: Build APK

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Restore signing keystore
        env:
          KEYSTORE_B64: ${{ secrets.KEYSTORE_B64 }}
        run: |
          if [ -n "$KEYSTORE_B64" ]; then
            echo "$KEYSTORE_B64" | base64 -d > app/release.keystore
          else
            echo "::warning::No KEYSTORE_B64 — this APK will not install over a previous build."
            keytool -genkeypair -v \
              -keystore app/release.keystore -storetype PKCS12 \
              -storepass android -keypass android -alias androiddebugkey \
              -keyalg RSA -keysize 2048 -validity 10000 \
              -dname "CN=YourApp, OU=YourApp, O=YourApp, L=Phone, S=NA, C=US"
          fi

      - name: Build
        env:
          APP_KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          APP_KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          APP_KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: |
          gradle wrapper --gradle-version=8.2
          chmod +x ./gradlew
          ./gradlew assembleRelease --no-daemon

      - name: Collect APK
        run: |
          SHORT=$(echo "${{ github.sha }}" | cut -c1-7)
          SRC=$(find app/build/outputs/apk/release -name '*.apk' | head -1)
          cp "$SRC" "YourApp-$SHORT.apk"

      - name: Publish release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: latest
          name: Latest build
          body: Built from `${{ github.sha }}`.
          files: YourApp-*.apk
          prerelease: true
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

The keystore alias in the `keytool` line and the `keyAlias` fallback in
`app/build.gradle` must be the same string. They are `androiddebugkey` above.
Getting this wrong fails at the signing step with a message about an unknown
alias.

---

## Write code that compiles the first time

You cannot test. Every mistake costs a full push-build-read cycle, and the
person is doing this on a phone. So:

**Prefer programmatic views over XML layouts and over Compose.** Not a style
preference. An XML layout adds resource IDs, theme attributes and a `R` class
that must all line up; Compose adds a compiler plugin whose version must match
the Kotlin plugin exactly. Both fail in ways that are hard to read from a build
log. `LinearLayout` built in `onCreate` with `addView` has none of that, and
for a tool app it looks fine.

**Keep dependencies to the minimum.** Every library is a version that must
resolve and an API you are recalling rather than reading. `HttpURLConnection`
and `org.json` are in the platform — prefer them over OkHttp and Moshi unless
you genuinely need what they add.

**Do not enable R8 or ProGuard.** `minifyEnabled false`. Obfuscation breaks
reflection, JSON mapping and `@JavascriptInterface` methods in ways that only
appear at run time on the phone, where you cannot see the stack trace.

**Never write a partial file.** VibeForge pushes whole files. A snippet or an
"unchanged parts omitted" comment becomes the actual file contents.

**Handle every error visibly.** A tool app should print what went wrong on its
own screen. A silent `catch (e: Exception) {}` on a phone with no logcat is a
dead end — this is the single biggest difference from writing code you can
debug.

**Nullable and platform types.** Kotlin against Android's Java APIs produces
platform types that will happily be null at run time: `intent.getStringExtra`,
`cursor.getString`, `DocumentFile.getName`. Treat them all as nullable.

---

## Android specifics that bite

### Permissions

Runtime permissions (`READ_CALENDAR`, `RECORD_AUDIO`, `READ_CONTACTS`,
location) must be *both* declared in the manifest *and* requested at run time
with `requestPermissions`, and the result arrives asynchronously in
`onRequestPermissionsResult`. A button that asks and then does nothing visible
until the user comes back is a bug people report as "it doesn't work".

### Play Protect

Google blocks sideloaded installs of apps that declare any of:

- `BIND_NOTIFICATION_LISTENER_SERVICE`
- `BIND_ACCESSIBILITY_SERVICE`
- `RECEIVE_SMS` / `READ_SMS`

If the app needs one of these, say so plainly in your instructions and expect
the user to turn off Play Protect scanning for the install. If it does not,
avoid them — an app that installs without a fight is worth a lot.

Calendar, contacts, microphone, storage and notifications are ordinary
permissions and do not trigger this.

### Foreground services

Anything that must keep running needs a foreground service with a notification
and, from Android 14, a declared `foregroundServiceType` in the manifest and a
matching permission. Omitting the type crashes on start with
`MissingForegroundServiceTypeException`.

### Installing APKs, sharing files

Handing a file to another app needs `FileProvider` — a `file://` URI throws
`FileUriExposedException`. That means a `res/xml/file_paths.xml`, a provider
entry in the manifest, and `FLAG_GRANT_READ_URI_PERMISSION` on the intent.

### The Storage Access Framework

A folder the user picks gives a tree URI. Take a persistable permission
immediately or it is gone on the next launch:

```kotlin
contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
```

And beware: creating a document through SAF *renames it* to match the MIME
type you asked for. Requesting `notes.jsonl` as `application/json` produces
`notes.jsonl.json`, and code that then looks for `notes.jsonl` will not find
it and will create a second file, and a third. Use `text/plain` and match
existing files by prefix.

### WebView

If you are wrapping a web app: `settings.javaScriptEnabled = true`,
`domStorageEnabled = true`, and `addJavascriptInterface` for native calls.
Methods exposed to JavaScript need `@JavascriptInterface` and must take and
return simple types — pass JSON strings for anything structured. They run on a
binder thread, not the main thread, so touch the UI only through
`runOnUiThread`.

---

## Versioning

Raise `versionCode` on every build the user will install. Android refuses to
install an APK whose `versionCode` is lower than the installed one, and the
error just says the app is not installed.

If the signing key changes — which happens whenever there is no persistent
keystore secret — the new APK cannot install over the old one at all. The user
must uninstall first, and that deletes the app's data. Mention it when
relevant rather than letting them find out.

---

## What VibeForge will not push

These are skipped automatically, so do not rely on them reaching the repo:

```
.git/  build/  .gradle/  node_modules/  .idea/  data/  backups/
*.apk  *.aab  *.keystore  *.jks  *.iml  *.log  *.zip
files over 3 MB
```

`.github/` and `.gitignore` *are* pushed. Any dotfile other than those two is
skipped.

If your app needs bundled assets, keep each under 3 MB and put them in
`app/src/main/assets/`.

---

## A minimal project that builds

`app/src/main/java/com/example/yourapp/MainActivity.kt`:

```kotlin
package com.example.yourapp

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "It builds."
            textSize = 24f
        })
        setContentView(root)
    }
}
```

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="YourApp"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Using a platform theme (`@android:style/…`) rather than a Material Components
theme means no `res/values/themes.xml` and no dependency on the Material
library. One less thing to get wrong.

---

## Reading a failed build

VibeForge shows the failing step name and links the run. Common causes, in
rough order of frequency:

| Symptom | Usually |
|---|---|
| `Unresolved reference` | wrong `package` line, or a missing dependency |
| `Manifest merger failed` | two components with the same name, or a missing `exported` |
| `Could not find …` | dependency version does not exist; check the coordinate |
| `Failed to read key` | keystore alias mismatch between workflow and gradle |
| `SDK location not found` | a stray `local.properties` was pushed — delete it |
| Build succeeds, no release | the Collect APK step found no file; check the module name |
| `Unsupported class file major version` | JDK mismatch; keep 17 everywhere |

When the person sends you a failure, ask for the step name and the last twenty
lines. Guessing from the step name alone wastes a cycle.

---

## Working with someone on a phone

Deliver a complete folder as a zip, every time. They unzip it, open VibeForge,
tap Push. Partial diffs and "add this line to that file" do not survive the
trip.

Tell them what to expect on screen after installing — what should appear,
which button to press first. A build that succeeds and an app that opens to a
blank screen are indistinguishable from their side unless you say what to look
for.

And when something is genuinely not possible on Android — background work
without a foreground service, reading another app's data, sending WhatsApp
messages — say so early. Discovering it after three build cycles is worse than
hearing it at the start.
