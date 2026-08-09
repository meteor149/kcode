# HarmonyOS app

The HarmonyOS host is built with ArkTS while the shared application and UI are compiled to native `libkn.so` binaries with Kotlin/Native.

From the repository root, publish both HarmonyOS ABIs with:

```powershell
.\gradlew.bat -p apps\harmonyApp\kotlin publishDebugBinariesToHarmonyApp
```

Then open `apps/harmonyApp` in DevEco Studio or run its Hvigor `assembleHap` task. The standalone Gradle build intentionally isolates the CPF Kotlin/Compose fork from the main build, so Android, desktop, iOS, and Wasm continue to use the repository's upstream Kotlin toolchain.
