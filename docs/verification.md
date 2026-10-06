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

## Early native startup recovery

`NativeProfileStartupRecoveryTest` now covers package/workspace/staging obstructions,
unavailable repository paths, a failing alternate module factory and cancellation before
product allocation. Draft/preview/activation commands remain host-owned; failed attempts
preserve repository state and obstructions. Repairing the underlying temporary path permits
retry on the same host. Preview allocates no product. Existing alternate factory count tests
prove preflight definitions are consumed once and later products receive fresh definitions.

Seventy-four Profiles and 66 native Desktop cases passed without failures/errors/skips.
Both applications and the instrumentation APK built against unchanged SDK 74/Cordis 6a9b4e4
(`profile-early-startup-validation.log`, `profile-early-startup-owned-validation.log`). Initial
failed builds/runs are retained: incompatible Android fixture file helpers and duplicate
preflight factory invocation were corrected before final validation. The final UI fixture
APK build passed separately (`profile-early-startup-ui-build.log`).

The physical Android run passed four `AndroidProfileEarlyStartupTest` cases and
`AndroidProfileRecoveryUiTest.moduleCatalogueFailureCanBeRepairedThroughTheHostSurface`:
`OK (5 tests)`, instrumentation code -1 (`profile-early-startup-device-validation.log`). It
covers real filesystem obstructions, same-host retries and a real recovery window before
catalogue construction. English/Chinese labels, invalid JSON retention and successful
replacement-root rendering were observed. Provider allocation occurs only on activation;
metadata remains unchanged through failed preparation/preview. The recovery screenshot was
inspected and its shell transfer file removed.

This is evidence for these path/module faults and this Android host window. It does not prove
every storage failure, Desktop rendering/dialogs, power-loss durability, corrupt-authority
repair, or the full autonomous/background-work admission boundary. No privileged execution
or broad unchanged device suite was repeated in this phase.


### Profile repository authority repair (2026-10-06)

The repository-recovery phase passed 82 Profile tests, 16 recovery-session tests and
67 selected native Desktop integration tests. Both native applications and the Android
instrumentation APK built successfully (`profile-repository-recovery-validation.log`).
Tests cover verified checkpoint restoration, empty catalogue creation, invalid UTF-8 evidence,
missing authority, orphan retention, changed review inputs, competing repairs, evidence-write
failure, staged draft nonpublication, stale revisions and explicit post-repair activation.

Physical Android UI instrumentation passed both `damagedAuthorityCanRestoreCheckpointThroughReviewedHostUi`
and `damagedAuthorityWithoutCheckpointCanStartASeparateRepairProfile`: `OK (2 tests)` and
`INSTRUMENTATION_CODE: -1` (`profile-repository-recovery-device-validation.log`). The rendered
host offers confirmation and cancellation, retains damaged-file evidence and business data,
and allocates the product only after a separate activation. This evidence does not establish
Desktop native dialogs, broad default management navigation, Shizuku/root authorization or
all autonomous/background execution admission paths. These remain separate acceptance work.
