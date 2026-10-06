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

## Android selected-file Profile exchange

AndroidProfileFileRoundtripTest uses shipped private settings/Profile/theme APK renderers and
the real DocumentsUI CreateDocument/OpenDocument activities on a physical ARM64 API 36 device.
It commits a Localization-only source, returns to the default product, selects that exact
committed source, saves JSON and complete archive files to Downloads, then selects each file
for import. The saved JSON and archive profile.json equal the reviewed source; both imports
produce durable drafts with unchanged active generation and exactly one authority revision
increment each. Test-owned Downloads files are removed. Shell reads inspect output bytes;
application import/export still uses actual SAF selections and streams, without injected results.

Initial runs exposed fixture races (clicking before selection finished), ambiguous repeated
Committed labels and a non-clickable Downloads breadcrumb. Stable per-Profile selection tags,
enabled-action waits and clickable root selection corrected the fixture. The final run passed
one test, OK (1 test), instrumentation code -1 (profile-file-roundtrip-controls-device.log).
Thirty-three UI desktop tests passed with zero failures/errors/skips, and the instrumentation
APK assembled (profile-file-roundtrip-controls-build.log). Earlier failed runs remain in local
ignored logs; no temporary product logging or debugger sessions were introduced.

This proves JSON and complete-archive selected-file exchange in this private Android settings
page. At that phase it did not prove Desktop dialogs, multi-Bundle selected-file exchange, complete default
application navigation, all accessibility methods or every DocumentsProvider implementation.

## Ordered multi-Bundle file selection

The subsequent AndroidProfileFileRoundtripTest extension publishes two test-owned archives
in an isolated Downloads directory: a Localization APK insertion Bundle and a data-only
configuration override Bundle. DocumentsUI's Select all action selects those two inputs;
no activity result is injected. The private page shows their names, permits reordering and
does not create a draft until confirmation. The test changes the returned order, ensures
the insertion precedes the override, imports a draft and previews the resulting Chinese
default language with verified packages. Authority advances once and the active generation
is unchanged. MediaStore entries and the empty test directory are removed.

The final physical ARM64 API 36 run passed one extended roundtrip test, OK (1 test), code -1
(profile-bundle-order-stable-device.log). Earlier fixture attempts failed because long-click
semantics/gestures did not enter selection mode and native transition nodes became stale.
An isolated directory, actual Select all menu action and fresh-node waits resolved those
test assumptions. The private review workflow additionally passed 34 UI desktop tests with
zero failures/errors/skips, covering reorder, cancellation, withdrawal and revision conflict.
Both app builds and the test APK passed (profile-bundle-order-validation.log,
profile-bundle-order-device-build.log and profile-bundle-order-stable-build.log).

This covers this Android provider/page and two ordered layers. It does not establish Desktop
native dialogs, every selection gesture/provider, all sixteen layers, full application
navigation or the remaining startup-recovery/background-admission audit.
