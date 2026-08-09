package ai.meteor.kcode.shell;

interface IPrivilegedShellService {
    void destroy() = 16777114;
    String execute(String command, String relativeWorkingDirectory, int timeoutSeconds, int maxOutputBytes) = 1;
    void cancel() = 2;
    int uid() = 3;
}
