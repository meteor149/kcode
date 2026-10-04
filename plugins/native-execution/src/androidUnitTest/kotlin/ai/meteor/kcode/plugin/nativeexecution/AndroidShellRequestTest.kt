package ai.meteor.kcode.plugin.nativeexecution

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AndroidShellRequestTest {
    @Test
    fun androidShellAcceptsAndNormalizesAbsoluteWorkingDirectories() {
        val request = normalizeAndroidShellCommandRequest(
            command = "  echo delegated  ",
            workingDirectory = "/sdcard/Download/../Documents",
        )

        assertEquals("echo delegated", request.command)
        assertEquals("/sdcard/Documents", request.workingDirectory)
        assertFailsWith<IllegalArgumentException> {
            normalizeAndroidShellCommandRequest("pwd", "relative/path")
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeAndroidShellCommandRequest("pwd", "C:\\Windows")
        }
    }

    @Test
    fun ubuntuShellNormalizesLinuxPathsAndAllowsLongScripts() {
        val request = normalizeUbuntuShellCommandRequest(
            command = "  python3 - <<'PY'\nprint('ok')\nPY  ",
            workingDirectory = "/workspace/project/../demo",
        )

        assertEquals("python3 - <<'PY'\nprint('ok')\nPY", request.command)
        assertEquals("/workspace/demo", request.workingDirectory)
        assertEquals("/workspace", normalizeUbuntuShellCommandRequest("pwd", null).workingDirectory)
        assertFailsWith<IllegalArgumentException> {
            normalizeUbuntuShellCommandRequest("pwd", "relative/path")
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeUbuntuShellCommandRequest("pwd", "C:\\Windows")
        }
    }

    @Test
    fun ubuntuProotCommandUsesIsolatedGuestEnvironmentAndWorkspaceBind() {
        val runtime = UbuntuRuntimePaths(
            runtimeDirectory = Path.of("runtime"),
            rootFileSystem = Path.of("rootfs"),
            temporaryDirectory = Path.of("tmp"),
            prootExecutable = Path.of("proot"),
            loaderExecutable = Path.of("loader"),
        )
        val command = buildUbuntuProotCommand(
            runtime = runtime,
            request = UbuntuShellCommandRequest("printf '%s' \"\$PATH\"", "/workspace"),
            bindMounts = listOf(UbuntuBindMount(Path.of("workspace"), "/workspace")),
        )

        assertEquals("proot", command.first())
        assertEquals("-0", command[1])
        assertTrue("workspace:/workspace" in command)
        assertTrue("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" in command)
        assertEquals("printf '%s' \"\$PATH\"", command.last())
        assertFalse(command.any { "system/bin" in it })
    }

    @Test
    fun rootUbuntuCommandVerifiesUidAndPreservesEachArgument() {
        val runtime = UbuntuRuntimePaths(
            runtimeDirectory = Path.of("/data/user/0/ai.meteor.kcode/files/ubuntu runtime"),
            rootFileSystem = Path.of("/data/user/0/ai.meteor.kcode/files/rootfs"),
            temporaryDirectory = Path.of("/data/user/0/ai.meteor.kcode/files/tmp"),
            prootExecutable = Path.of("/data/app/lib/libkcode_proot.so"),
            loaderExecutable = Path.of("/data/app/lib/libkcode_proot_loader.so"),
        )

        val command = buildRootUbuntuCommand(
            runtime = runtime,
            ubuntuCommandLine = listOf("proot", "-r", "root fs", "/bin/bash", "-lc", "printf '%s' \"\$PATH\""),
        )

        assertTrue(command.contains("actual_uid=\$(id -u)"))
        assertTrue(command.contains("expected 0"))
        assertTrue(command.contains("cd '${runtime.runtimeDirectory}'"))
        assertTrue(command.contains("PROOT_LOADER='${runtime.loaderExecutable}'"))
        assertTrue(command.contains("/system/bin/sh -c 'exec \"\$@\"' kcode-proot"))
        assertTrue(command.contains("'root fs'"))
        assertTrue(command.endsWith("'printf '\\''%s'\\'' \"\$PATH\"'"))
    }

    @Test
    fun privilegedUbuntuResultKeepsIdentityHeaderAndExitCode() {
        val result = parseUbuntuPrivilegedResult(
            "environment=ubuntu-proot\nmode=adb\nandroidUid=2000\ncwd=/workspace\nexitCode=7\nfailed",
        )

        assertEquals(7, result.exitCode)
        assertTrue(result.output.contains("mode=adb"))
        assertTrue(result.output.contains("androidUid=2000"))
        assertFalse(result.output.contains("exitCode="))
    }

    @Test
    fun rootfsArchivePathsCannotEscapeAtomicInstallDirectory() {
        assertEquals(
            Path.of("usr/bin/python3"),
            validatedRootfsRelativePath("ubuntu-noble-aarch64/usr/bin/python3"),
        )
        assertEquals(null, validatedRootfsRelativePath("ubuntu-noble-aarch64/"))
        assertFailsWith<IllegalArgumentException> {
            validatedRootfsRelativePath("ubuntu-noble-aarch64/../../escape")
        }
        assertFailsWith<IllegalArgumentException> {
            validatedRootfsRelativePath("different-root/etc/passwd")
        }
    }
}
