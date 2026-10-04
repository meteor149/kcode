package ai.meteor.kcode.shell;

import android.os.ParcelFileDescriptor;

/** One deployment per UserService instance; artifact bytes are passed as owned FD copies. */
interface IPrivilegedPluginBridge {
    void destroy() = 16777114;
    int uid() = 1;
    void close() = 3;
    IBinder load(in ParcelFileDescriptor[] artifacts, in String[] sha256, String entryClass) = 2;
}
