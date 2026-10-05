package ai.meteor.kcode.plugin.packages

import java.io.InterruptedIOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PackageIoCancellationTest {
    @Test
    fun cancelledBlockedReadDoesNotFailItsParent(): Unit = runBlocking {
        PipedOutputStream().use { output ->
            PipedInputStream(output).use { input ->
                val entered = CountDownLatch(1)
                val reading = async(Dispatchers.IO) {
                    interruptiblePackageOperation {
                        entered.countDown()
                        input.read()
                    }
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                reading.cancel()
                assertFailsWith<CancellationException> { reading.await() }
                reading.join()
            }
        }
    }

    @Test
    fun interruptionWithoutOwnerCancellationPreservesIoFailure(): Unit = runBlocking {
        val failure = InterruptedIOException("fixture IO interruption")
        assertSame(failure, assertFailsWith<InterruptedIOException> {
            interruptiblePackageOperation<Unit> { throw failure }
        })
    }
}
