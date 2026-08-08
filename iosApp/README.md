# kcode iOS host

The iOS host contains no duplicated screens. `KcodeRootView` mounts the `MainViewController` exported by the `KcodeShared` framework, so Android and iOS render the same `commonMain` Compose UI.

On macOS with [XcodeGen](https://github.com/yonaskolb/XcodeGen) installed:

```bash
cd iosApp
xcodegen generate
open iosApp.xcodeproj
```

The generated application target runs the Gradle `embedAndSignAppleFrameworkForXcode` task before each Xcode build and links the resulting `KcodeShared` framework. The deployment target is iOS 14.
