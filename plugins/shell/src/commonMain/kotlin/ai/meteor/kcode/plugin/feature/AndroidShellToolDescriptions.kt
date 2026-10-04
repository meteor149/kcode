package ai.meteor.kcode.plugin.feature

internal val AndroidShellToolDescription = """
    Executes a shell command in an Android OS environment and returns its complete combined output and exit code.
    Commands run through /system/bin/sh, not a desktop Linux shell. Do not assume that bash, GNU utilities, apt,
    systemd, or other desktop Linux programs are installed; prefer Android/toybox-compatible commands and Android
    absolute paths. The user-selected execution identity may be the app UID, adb shell through Shizuku, or root.
    /workspace maps to the app's private agent workspace when the app identity is selected. If workingDirectory is
    omitted, the platform chooses the default directory for the selected identity.
""".trimIndent()

internal val AndroidUbuntuShellToolDescription = """
    Executes a command inside kcode's complete Ubuntu 24.04 ARM64 user space powered by PRoot. This is a regular
    GNU/Linux environment with bash, apt, Python, and standard Linux paths; it is separate from Android's system
    shell. It uses the same user-selected Android execution identity as the system shell tool: app UID, adb shell
    through Shizuku, or root. /workspace is the default working directory; app and root modes share kcode's private
    agent workspace, while adb mode uses a UID-2000 workspace under /data/local/tmp. Each identity-specific Ubuntu
    environment is installed atomically on first use, which can make the first call take longer. The guest reports
    PRoot's emulated root user, while Android filesystem and device access follow the selected real Android UID.
    systemd, kernel modules, real mounts, and other kernel operations remain unavailable under PRoot.
""".trimIndent()
