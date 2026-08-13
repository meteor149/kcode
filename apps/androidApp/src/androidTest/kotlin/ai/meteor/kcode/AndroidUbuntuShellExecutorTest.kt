package ai.meteor.kcode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidUbuntuShellExecutorTest {
    @Test
    fun installsCompleteUbuntuAndSharesAgentWorkspace() {
        runBlocking {
            withTimeout(5 * 60 * 1_000L) {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val workspace = File(context.filesDir, "agent_workspace").apply { mkdirs() }
                val sharedFile = File(workspace, "ubuntu-runtime-smoke.txt")
                sharedFile.delete()
                val executor = AndroidUbuntuShellExecutor(context)

                val result = executor.execute(
                    command = """
                        set -eu
                        . /etc/os-release
                        printf 'ubuntu=%s\n' "${'$'}PRETTY_NAME"
                        printf 'arch=%s\n' "${'$'}(uname -m)"
                        printf 'apt=%s\n' "${'$'}(apt --version | head -n 1)"
                        python3 -c 'import platform; print("python=" + platform.python_version())'
                        mkdir -p /workspace/reused-runtime
                        printf 'workspace-ok\n' > /workspace/ubuntu-runtime-smoke.txt
                        cat /workspace/ubuntu-runtime-smoke.txt
                    """.trimIndent(),
                    workingDirectory = "/workspace",
                )

                assertEquals(result.output, 0, result.exitCode)
                assertTrue(result.output, "Ubuntu 24.04" in result.output)
                assertTrue(result.output, "arch=aarch64" in result.output)
                assertTrue(result.output, "apt=apt " in result.output)
                assertTrue(result.output, "python=" in result.output)
                assertTrue(result.output, "workspace-ok" in result.output)
                assertEquals("workspace-ok\n", sharedFile.readText())

                val reusedResult = executor.execute(
                    command = "pwd && python3 -c 'print(\"reused-ok\")'",
                    workingDirectory = "/workspace/reused-runtime",
                )
                assertEquals(reusedResult.output, 0, reusedResult.exitCode)
                assertTrue(reusedResult.output, "/workspace/reused-runtime" in reusedResult.output)
                assertTrue(reusedResult.output, "reused-ok" in reusedResult.output)

                File(workspace, "reused-runtime").delete()
                sharedFile.delete()
                Unit
            }
        }
    }
}
