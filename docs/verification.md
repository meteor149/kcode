# Verification evidence

Build and device evidence have separate scopes. Kotlin compilation and APK assembly establish
source/package compatibility, not successful activation, platform authorization or runtime IO.
Record the exact source commit, targets, test counts and relevant limitations in the topic guide.
Do not include credentials, device identifiers or private data in committed evidence.

## Plugin lifecycle and identity

Use real JAR/APK packages to verify shared SDK/framework identity, private implementation
identity, deployment resource origins and independent closure. Lifecycle scenarios include
withdrawal/recovery, stale references, suspended allocation cancellation, replacement, failed
durable publication and cleanup failure. Pure compiler tests do not establish these behaviors.

## Profiles

Test the same compiler used by preview and execution. File-repository tests need generation CAS,
revision conflicts, immutable history, unreachable staged files, legacy migration, corruption and
atomic selection/generation reads. Native switch tests must use actual providers and durable
settings/history/workspace data, verify task admission and stale callbacks, and reconstruct the
old locked generation after target failure without advancing its history.

Focused test selection is phase evidence. Run the full relevant suites and both host builds at
the final boundary. Any classes compiled but not executed remain unverified. In particular,
legacy typed replacement and full management/recovery UI require their own observable checks.

AndroidProfileUiRenderingTest mounts shipped private settings/Profile/theme APK renderers in
a native window and checks definition editing, system-back confirmation, invalid-save retention,
live English/Chinese labels and save/discard return against durable drafts. Confirmation action
text bounds and an inspected screenshot cover the tested layout. The fixture replaces the
application root and uses Compose semantic actions for editing/clicks; it does not establish
full application navigation, touch/keyboard accessibility, every dismissal path, all tree forms
or desktop rendering. Keep these evidence limits separate from SDK and real-package loading tests.

## Android execution worlds

App-UID shell/Ubuntu tests establish only application execution, scoped paths and packaged
resources. Real Root/Shizuku behavior requires actual authorization, actual UID evidence and
successful operations in that world. Never infer authorization from unit tests, a Binder mock
or app-UID instrumentation. Scoped ADB rejection before authorization is rejection evidence,
not successful ADB workspace support. ADB settings commands require checking the command
result (`result=-1` on rejection), not only the adb process exit code.

Atomic-file restart tests do not simulate sudden power loss or every filesystem's durability.
Keep those limits explicit rather than treating a green build as broader evidence.
