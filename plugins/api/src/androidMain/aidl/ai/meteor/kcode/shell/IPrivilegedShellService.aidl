package ai.meteor.kcode.shell;

import android.os.ParcelFileDescriptor;

interface IPrivilegedShellService {
    void destroy() = 16777114;
    ParcelFileDescriptor execute(String requestId, String command, String workingDirectory) = 1;
    void cancel(String requestId) = 2;
    int uid() = 3;
    void beginRequest(String requestId) = 5;
    void finishRequest(String requestId) = 6;
    ParcelFileDescriptor executeUbuntu(String requestId, String command, String workingDirectory) = 4;
}
