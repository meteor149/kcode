package ai.meteor.kcode.shell

import android.os.IBinder

/** Stable remote engine ABI. close must revoke and join all engine-owned operations. */
interface PrivilegedPluginEngine : AutoCloseable {
    val binder: IBinder
    override fun close()
}

/** Android host entry point; instantiated by Shizuku outside the private plugin loader. */
const val PrivilegedPluginUserServiceClassName = "ai.meteor.kcode.shell.PrivilegedPluginUserService"
