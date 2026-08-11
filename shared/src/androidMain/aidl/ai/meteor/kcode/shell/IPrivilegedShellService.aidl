package ai.meteor.kcode.shell;

import android.os.ParcelFileDescriptor;

interface IPrivilegedShellService {
    void destroy() = 16777114;
    ParcelFileDescriptor execute(String command, String workingDirectory) = 1;
    void cancel() = 2;
    int uid() = 3;
}
