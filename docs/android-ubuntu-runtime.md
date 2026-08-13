# Android Ubuntu runtime

kcode exposes two deliberately separate command tools on Android:

- `execute_shell_command` runs Android `/system/bin/sh` as the selected App,
  ADB/Shizuku, or Root identity.
- `execute_ubuntu_command` runs GNU/Linux commands in an Ubuntu 24.04 ARM64
  root filesystem through PRoot. It follows the same selected identity: the
  application UID, ADB shell UID 2000 through Shizuku, or real UID 0 through
  `su`. PRoot's `-0` option separately emulates Linux root metadata inside the
  guest and does not change the real Android identity.

## Operit mechanism studied

The reference implementation is split between
[Operit](https://github.com/AAswordman/Operit) and its
[`terminal` submodule](https://github.com/AAswordman/OperitTerminalCore). The
source revision inspected for this implementation was Operit
`ed2748192640471a3b903f37e48cb2d21b2ea5a3` with Terminal Core
`e4442bc6a047b6165bf59103721ad143149c620d`.

Operit's relevant chain is:

1. Package executable PRoot and its loader as `.so` files in `jniLibs`. Android
   extracts native libraries onto an executable filesystem; the app creates
   command-friendly links under its private `usr/bin` directory.
2. Package a PRoot-Distro-derived Ubuntu Noble ARM64 tar.xz asset. On first use,
   copy and extract it into an app-private rootfs guarded by an install lock,
   temporary directory, and completion marker.
3. Construct a clean guest environment (`HOME`, `PATH`, locale, and terminal),
   use `proot -0 -r <rootfs>`, and bind Android pseudo-filesystems, shared
   storage, and the app's data paths into the guest.
4. Offer interactive PTY sessions and a separate hidden reusable shell. Hidden
   commands use unique begin/end/PID markers so output, exit status, timeout,
   and cancellation can be associated with the correct tool invocation.
5. Map Linux paths back to the host rootfs or bind source when file tools need
   to operate on files created inside Ubuntu.

## kcode implementation

kcode keeps the essential isolation and reliability properties while fitting
its existing one-shot `AgentShellExecutor` contract:

- App and Root modes install the runtime under the application's private
  `filesDir/ubuntu_runtime` and bind the private agent workspace to
  `/workspace`. Root mode starts the same runtime through `su -c` and rejects
  the command unless `id -u` is actually `0`.
- ADB mode requires a Shizuku provider started by `adb` and binds a UserService
  that verifies UID 2000. Because that identity cannot access the app-private
  rootfs, it installs a separate shell-owned runtime and workspace under
  `/data/local/tmp/ai.meteor.kcode/ubuntu`.
- The rootfs asset is SHA-256 verified before extraction.
- Extraction occurs in an `installing` staging directory below the selected
  identity's runtime; every archive path is containment-checked, symlink
  traversal is rejected, device nodes are skipped because `/dev` is bound at
  runtime, and the completed root is moved into place atomically.
- A version marker plus required executable checks prevent a partial or stale
  rootfs from being treated as installed. Interrupted staging directories are
  removed on the next attempt.
- The host process environment is cleared. `PROOT_LOADER` and `PROOT_TMP_DIR`
  are set explicitly, while PRoot keeps its normal seccomp-assisted syscall
  handling. The guest starts through `/usr/bin/env -i` with a Linux-only
  `PATH`.
- `/workspace` is bound directly to the identity-specific agent workspace.
  App and Root therefore share files with kcode's Android file tools; ADB has a
  separate UID-2000 workspace. Readable `/dev`, `/proc`, `/sys`, and the
  current Android user's emulated storage are added when available.
- ADB `/workspace` is not visible to the app-UID file tools. App and Root share
  a workspace, but real-root commands can create host ownership or permissions
  that make files inaccessible when a later command or file tool returns to
  the application UID.
- Tool cancellation force-stops the tracing PRoot process. Output and the real
  guest exit code are returned through the same tool-result contract as the
  existing shell.
- Only ARM64 is enabled, matching the inspected Operit runtime. Other Android
  ABIs fail explicitly instead of attempting to execute incompatible code.

The runtime is intentionally not a VM: it shares the Android kernel and the
selected Android UID. `apt`, Python, Node.js installed through apt, compilers,
and ordinary Linux CLI software work, but the guest does not provide a booted
kernel or systemd. PRoot itself grants no kernel privilege. Root mode really
runs as Android UID 0, so any additional host-kernel capability comes from the
device's `su` implementation and remains subject to Linux capabilities and
SELinux policy.

## Vendored artifacts

| File | SHA-256 |
| --- | --- |
| `ubuntu-noble-aarch64-pd-v4.18.0.tar.xz` | `91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa` |
| `libkcode_proot.so` | `cb40a1ced11cee76569b4008a9e478c87883ce831152be4eb9570763b82e580d` |
| `libkcode_proot_loader.so` | `f149774236db1e69b36cc1e4ed3866c7094db2eee52da0d4956aa63a9bb26929` |

The binaries and rootfs archive were obtained without modification from the
Terminal Core revision above; only the packaged library filenames were changed.
PRoot upstream source is at <https://github.com/proot-me/proot>. Ubuntu package
source is available through the `deb-src` archives corresponding to
<http://ports.ubuntu.com/ubuntu-ports/>. Runtime package notices remain under
`/usr/share/doc` inside the rootfs.
