# Static image bootstrap

`AndroidStaticImageBootstrap` is a host OS primitive. Its installed executable maps
the caller's private ARM64 static ELF from the caller's filesystem namespace at
`/.__kcode_native_image/image.elf`, then transfers control while preserving `x0` and
the initial stack pointer. It contains no shell, rootfs, tool, or execution policy.
The caller supplies the namespace mapping and owns the image until its processes
have exited. Nothing falls back to a host product image.

The current ABI accepts little-endian ARM64 ET_EXEC images with initialized PT_LOAD
segments and no interpreter, within 0x1000000000 through 0x2800000000. It rejects
writable executable segments. The bootstrap's text is outside that range, at
0x3000000000. This is a narrow static-image bridge, not a general dynamic linker.

The checked-in binary was built with Android NDK 29.0.14206865 for API 35. On Windows:

```powershell
./build-native-image.ps1 -NdkRoot <Android-SDK>/ndk/29.0.14206865
```

APK hosts must extract the bootstrap as a native executable. Imported plugin
images remain private to each imported generation; their implementation class
loaders and cleanup belong to the plugin runtime. API 15 identifies this host
capability. The real-device tests live in native-execution and platform-android.
