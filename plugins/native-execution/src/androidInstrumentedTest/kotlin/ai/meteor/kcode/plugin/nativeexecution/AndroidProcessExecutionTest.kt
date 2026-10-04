package ai.meteor.kcode.plugin.nativeexecution

import ai.meteor.kcode.plugin.nativeexecution.shell.PrivilegedShellUserService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidProcessExecutionTest {
    @Test(timeout = 30_000)
    fun cancellationJoinsAnOwnedNativeProcessBeforeReturning() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = File(context.cacheDir, "kcode-shell-ready-${System.nanoTime()}")
        val process = ProcessBuilder("/system/bin/sh", "-c", "echo ready > '${ready.path}'; exec sleep 60")
            .redirectErrorStream(true).start()
        val running = async(Dispatchers.Default) { executeAndroidProcess { process } }
        try {
            withTimeout(5_000) { while (!ready.exists()) delay(10) }
            withTimeout(5_000) { running.cancelAndJoin() }
            assertFalse(process.isAlive)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            withTimeout(5_000) { running.cancelAndJoin() }
            ready.delete()
        }
    }

    @Test(timeout = 30_000)
    fun cancellationSignalsRemoteBeforeJoiningItsBlockingCall() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var cancellationCalls = 0
        val running = async(Dispatchers.Default) {
            executePrivilegedShellCall({ cancellationCalls += 1; release.countDown() }) {
                entered.countDown()
                release.await()
                "remote result"
            }
        }
        try {
            withTimeout(5_000) { while (entered.count != 0L) delay(10) }
            withTimeout(5_000) { running.cancelAndJoin() }
            assertEquals(1, cancellationCalls)
        } finally { release.countDown(); withTimeout(5_000) { running.cancelAndJoin() } }
    }

    @Test(timeout = 30_000)
    fun serviceCancellationWaitsForCleanupAndAllowsTheNextOperation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = File(context.cacheDir, "kcode-service-ready-${System.nanoTime()}")
        val service = PrivilegedShellUserService()
        service.beginRequest("first")
        val running = async(Dispatchers.IO) {
            runCatching {
                service.execute("first", "echo ready > '${ready.path}'; exec sleep 60", context.cacheDir.path).close()
            }
        }
        try {
            withTimeout(5_000) { while (!ready.exists()) delay(10) }
            service.cancel("unrelated")
            assertFalse(running.isCompleted)
            service.cancel("first")
            assertTrue(withTimeout(5_000) { running.await() }.isFailure)
            service.finishRequest("first")
            val output = withTimeout(5_000) {
                executePrivilegedShellCall(
                    cancelRemote = { service.cancel("next") },
                    prepareRemote = { service.beginRequest("next") },
                    finishRemote = { service.finishRequest("next") },
                ) {
                    ParcelFileDescriptor.AutoCloseInputStream(
                        service.execute("next", "echo reusable", context.cacheDir.path),
                    ).use { it.readBytes().decodeToString() }
                }
            }
            assertTrue(output.contains("reusable"))
            assertTrue(output.contains("exitCode=0"))
        } finally {
            service.cancel("first")
            service.finishRequest("first")
            withTimeout(5_000) { running.join() }
            ready.delete()
        }
    }


    @Test(timeout = 30_000)
    fun cancellationDuringRegistrationCannotStartALateRemoteOperation() = runBlocking {
        val prepared = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var executed = false
        val running = async(Dispatchers.Default) {
            executePrivilegedShellCall(
                cancelRemote = {},
                prepareRemote = { prepared.countDown(); release.await() },
                finishRemote = { finished.countDown() },
            ) { executed = true }
        }
        try {
            withTimeout(5_000) { while (prepared.count != 0L) delay(10) }
            running.cancel()
            assertFalse(running.isCompleted)
            release.countDown()
            withTimeout(5_000) { running.join() }
            assertFalse(executed)
            assertEquals(0L, finished.count)
        } finally {
            release.countDown()
            withTimeout(5_000) { running.cancelAndJoin() }
        }
    }

    @Test(timeout = 30_000)
    fun registeredCancellationRejectsExecutionAndReleasesTheRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = PrivilegedShellUserService()
        val marker = File(context.cacheDir, "kcode-cancelled-registration-${System.nanoTime()}")
        service.beginRequest("cancelled")
        try {
            service.cancel("cancelled")
            assertTrue(runCatching {
                service.execute("cancelled", "echo unexpected > '${marker.path}'", context.cacheDir.path).close()
            }.isFailure)
            assertFalse(marker.exists())
        } finally {
            service.finishRequest("cancelled")
            marker.delete()
        }
        service.beginRequest("replacement")
        service.finishRequest("replacement")
    }


    @Test(timeout = 30_000)
    fun cancellationClosesChildrenWhileTheForegroundShellIsStillRunning() = runBlocking {
        verifyProcessGroupCancellation(parentExits = false)
    }

    @Test(timeout = 30_000)
    fun cancellationClosesChildrenRetainingStdoutAfterTheShellHasExited() = runBlocking {
        verifyProcessGroupCancellation(parentExits = true)
    }

    private suspend fun verifyProcessGroupCancellation(
        parentExits: Boolean,
        privilegedTransport: Boolean = false,
    ) = kotlinx.coroutines.coroutineScope {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = File(context.cacheDir, "kcode-process-tree-${System.nanoTime()}")
        val command = "sleep 60 & child=${'$'}!; printf '%s %s' \"${'$'}${'$'}\" \"${'$'}child\" > '${ready.path}'; " +
            if (parentExits) "exit 0" else "wait"
        val process = if (privilegedTransport) {
            startAndroidPrivilegedProcessGroup(
                ProcessBuilder("/system/bin/sh", "-c", registeredAndroidProcessGroupCommand(command))
                    .redirectErrorStream(true),
                signalGroup = ::signalTestProcessGroup,
            )
        } else {
            startAndroidProcessGroup(
                ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true),
            )
        }
        val running = async(Dispatchers.Default) { executeAndroidProcess { process } }
        try {
            withTimeout(5_000) { while (!ready.exists() || ready.length() == 0L) delay(10) }
            val pids = ready.readText().trim().split(' ').map(String::toInt)
            if (parentExits) {
                withTimeout(5_000) { while (runCatching { process.exitValue() }.isFailure) delay(10) }
                assertFalse(running.isCompleted)
            }
            withTimeout(10_000) { running.cancelAndJoin() }
            assertFalse(process.isAlive)
            pids.forEach { pid ->
                val alive = try {
                    Os.kill(pid, 0)
                    true
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.ESRCH) throw error
                    false
                }
                assertFalse(alive, "Owned process $pid survived group cleanup")
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            withTimeout(10_000) { running.cancelAndJoin() }
            ready.delete()
        }
    }

    @Test(timeout = 30_000)
    fun privilegedTransportCancellationWaitsForTheWholeRegisteredGroup() = runBlocking {
        verifyProcessGroupCancellation(parentExits = false, privilegedTransport = true)
    }

    @Test(timeout = 30_000)
    fun privilegedTransportCancellationClosesChildrenAfterTheCommandExits() = runBlocking {
        verifyProcessGroupCancellation(parentExits = true, privilegedTransport = true)
    }

    @Test(timeout = 30_000)
    fun privilegedCommandRegistrationPreservesQuotingAndExitCode(): Unit = runBlocking {
        val process = startAndroidPrivilegedProcessGroup(
            ProcessBuilder(
                "/system/bin/sh", "-c",
                registeredAndroidProcessGroupCommand("printf '%s' \"quote ' and space\"; exit 7"),
            ).redirectErrorStream(true),
            signalGroup = ::signalTestProcessGroup,
        )
        val result = withTimeout(5_000) { executeAndroidProcess { process } }
        assertEquals("quote ' and space", result.output)
        assertEquals(7, result.exitCode)
    }

    @Test(timeout = 30_000)
    fun failedPrivilegedSignalCannotReportAnAliveGroupAsCleaned(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = File(context.cacheDir, "kcode-denied-signal-${System.nanoTime()}")
        val process = startAndroidProcessGroup(
            ProcessBuilder("/system/bin/sh", "-c", "echo ${'$'}${'$'} > '${ready.path}'; exec sleep 60")
                .redirectErrorStream(true),
        )
        // An active group under the same UID exercises the signal failure protocol;
        // this deliberately does not claim actual su authorization was tested.
        try {
            withTimeout(5_000) { while (!ready.exists() || ready.length() == 0L) delay(10) }
            val groupId = ready.readText().trim().toInt()
            assertFailsWith<IllegalStateException> {
                signalAndroidPrivilegedProcessGroup(groupId, OsConstants.SIGKILL) {
                    ProcessBuilder("/system/bin/sh", "-c", "exit 1").start()
                }
            }
            assertTrue(process.isAlive)
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            process.inputStream.close()
            process.outputStream.close()
            process.errorStream.close()
            ready.delete()
        }
    }

    @Test(timeout = 30_000)
    fun failedPrivilegedCleanupUnwindsOutputCollectionAndReportsTheFailure(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = File(context.cacheDir, "kcode-failed-cleanup-${System.nanoTime()}")
        val process = startAndroidPrivilegedProcessGroup(
            ProcessBuilder(
                "/system/bin/sh", "-c",
                registeredAndroidProcessGroupCommand(
                    "sleep 60 & printf '%s %s' \"${'$'}${'$'}\" \"${'$'}!\" > '${ready.path}'; wait",
                ),
            ).redirectErrorStream(true),
            signalGroup = { _, _ -> error("signal denied") },
        )
        val owner = SupervisorJob()
        val running = CoroutineScope(owner + Dispatchers.Default).async { executeAndroidProcess { process } }
        try {
            withTimeout(5_000) { while (!ready.exists() || ready.length() == 0L) delay(10) }
            assertTrue(androidProcessGroupAlive(ready.readText().trim().substringBefore(' ').toInt()))
            running.cancel()
            withTimeout(5_000) { running.join() }
            // await surfaces the cleanup error rather than claiming ordinary cancellation.
            val error = assertFailsWith<IllegalStateException> { running.await() }
            assertEquals("signal denied", error.message)
        } finally {
            if (ready.exists() && ready.length() > 0L) {
                signalTestProcessGroup(ready.readText().trim().substringBefore(' ').toInt(), OsConstants.SIGKILL)
            }
            withTimeout(5_000) { running.cancelAndJoin() }
            owner.cancelAndJoin()
            ready.delete()
        }
    }

    @Test(timeout = 30_000)
    fun cancellableOutputCollectionDrainsMultipleBuffersBeforeReturning(): Unit = runBlocking {
        val result = withTimeout(5_000) {
            executeAndroidProcess {
                startAndroidProcessGroup(
                    ProcessBuilder("/system/bin/sh", "-c", "head -c 32768 /dev/zero; printf 'tail'")
                        .redirectErrorStream(true),
                )
            }
        }
        assertEquals(0, result.exitCode)
        assertEquals("\u0000".repeat(32_768) + "tail", result.output)
    }

    @Test(timeout = 30_000)
    fun abandonedPrivilegedRegistrationCannotRunACommandAfterTransportClosure(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = File(context.cacheDir, "kcode-abandoned-root-${System.nanoTime()}")
        val process = ProcessBuilder(
            "/system/bin/sh", "-c",
            registeredAndroidProcessGroupCommand("echo unexpected > '${marker.path}'"),
        ).redirectErrorStream(true).start()
        try {
            withTimeout(5_000) { while (process.inputStream.available() == 0) delay(10) }
            process.outputStream.close()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(126, process.exitValue())
            assertFalse(marker.exists())
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            process.inputStream.close()
            process.errorStream.close()
            marker.delete()
        }
    }

    @Test(timeout = 30_000)
    fun crossUidPermissionDenialMeansTheProcessGroupIsStillAlive(): Unit = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // UiAutomation passes the string to Runtime.exec, without shell quote parsing.
        // Feed the script through stdin so argv does not depend on shell expansion.
        val descriptors = automation.executeShellCommandRw("/system/bin/setsid /system/bin/sh")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { output ->
            output.write("id -u\necho ${'$'}${'$'}\nexec sleep 60\n".toByteArray())
        }
        ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { input ->
            assertEquals("2000", input.readLine())
            val groupId = input.readLine().toInt()
            try {
                val denial = assertFailsWith<ErrnoException> { Os.kill(-groupId, 0) }
                assertEquals(OsConstants.EPERM, denial.errno)
                assertTrue(androidProcessGroupAlive(groupId))
            } finally {
                ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand("/system/bin/kill -9 -- -$groupId"),
                ).use { it.readBytes() }
                withTimeout(5_000) { while (androidProcessGroupAlive(groupId)) delay(10) }
            }
            assertFalse(androidProcessGroupAlive(groupId))
        }
    }

    private fun signalTestProcessGroup(groupId: Int, signal: Int) {
        signalAndroidPrivilegedProcessGroup(groupId, signal) { command ->
            ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
        }
    }

}
