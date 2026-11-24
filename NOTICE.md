# License notices

XiaoAiSwitch's original source code is licensed under [GNU GPL version 3 only](LICENSE) (`GPL-3.0-only`).

- The Gradle Wrapper scripts and JAR are upstream Gradle files under [Apache License 2.0](licenses/Apache-2.0.txt). Their original notices are preserved. See the [Gradle project](https://github.com/gradle/gradle).
- Dependencies resolved by Gradle remain under their upstream licenses, including [Miuix](https://github.com/compose-miuix-ui/miuix/blob/v0.8.7/LICENSE), [AndroidX](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt), and [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt). See `app/build.gradle` and `core/build.gradle` for the dependency list.
- Local DEX inspection uses [Google's smali-dexlib2](https://github.com/google/smali), distributed under the BSD 3-Clause license. [Upstream notices](core/src/main/resources/META-INF/xiaoaiswitch-smali-NOTICE.txt) are included in the APK.
- Xiaomi app binaries, extracted resources and LSPosed binaries are not part of this repository. The module references some functionality already present in the installed assistant; this does not change the license of that software.
