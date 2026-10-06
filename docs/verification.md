# Verification evidence

Build and device evidence have separate scopes. Kotlin compilation and APK assembly establish
source/package compatibility, not successful activation, platform authorization or runtime IO.
Record the exact source commit, targets, test counts and relevant limitations in the topic guide.
Do not include credentials, device identifiers or private data in committed evidence.

## Reviewed theme configuration export

The exact UI theme release declares bounded JSON roles/fields in its profile-export schema.
The private review dialect supports only the fixed hex-color string format, preserving
RGB/ARGB data and rejecting arbitrary text, invalid color lengths and unknown formats.
ProfileExportSchemaTest checks these outcomes without changing exported SDK ABI 76.

profile-theme-export-and-acceptance-validation.log passed all 83 Profiles tests and both
NativeProfileExportSchemaTest cases. The actual packaged native composition activates JSON
colors, extended colors, spacing/radius/size/glass/overlay/fontScale, preserves the frozen
recipe through JSON and complete archive export, and denies invalid color strings, unknown
roles and out-of-range spacing hidden under later valid overrides. The same suite's separate
acceptance-verifier test failed on its saved-selection assumption; the build therefore failed.

profile-theme-export-and-acceptance-final-validation.log passed the corrected backend verifier,
two theme tests, desktop compilation and Android assembly. Unchanged successful Profile/schema
cases were not repeated. These logs remain ignored local evidence. No selected-file native
roundtrip or physical Android schema test was performed in this phase.

## Desktop acceptance launcher and verifier

The explicit platform-desktop profileDesktopAcceptance task creates an isolated native host
and renders the shipped root/settings/Profile contribution. It requires selected JSON and
complete archive exports/imports, verifies artifacts and unchanged generation/selection after
window closure, then closes the host. Compose exitProcessOnExit is false so closure cannot
skip checks and return a false Gradle success. Initial saved selection may legitimately be null.

The initial live attempt was interrupted by physical Escape before file exchange. Its old
launcher terminated before checks, so profile-desktop-native-acceptance.log is not a passing
acceptance result and contains no PROFILE_ACCEPTANCE_PASSED marker. Computer Use was not
resumed. Real native file interaction remains outstanding.

ProfileDesktopAcceptanceVerifierTest uses API-produced artifacts to check missing JSON,
missing archive, missing/partial imports, complete verified exchange and changed JSON. The
initial test exposed the incorrect non-null selection assumption; the final corrected case
passed in profile-theme-export-and-acceptance-final-validation.log alongside native app builds.
This is backend verifier evidence, not native dialog acceptance. Direct window-close behavior
with the revised launcher still needs interactive evidence.

## Synchronous generation handoff rejection

The retained-runner reproduction failed both new cases before the fix: startResponse threw
from an actual closed OwnedChatGenerationRunner, and send retained isGenerating. The private
handoff now owns failure/completion cleanup before clearing prepared state. Cancellation is
propagated after cleanup, and a normal rejected response reports false rather than leaving
the conversation busy. Public ABI remains 76.

`profile-runner-rejection-validation.log` passed 23 conversation tests, 18 Goal tests and 12
Schedule tests, with zero failures/errors/skips, plus desktop compilation and Android app
assembly. New cases cover send/regeneration history preservation, response rejection and
fresh-runner retry, and synchronous cancellation with completion cleanup holding admission.

`profile-runner-rejection-private-validation.log` passed the actual-JAR conversation-policy
case and two generation lifecycle cases. Both executor and generation provider load privately;
withdrawing generation, using its retained runner, reenabling and completing a new response
preserves SDK identities and existing transcript. The response service is a deterministic
fixture, not a model network request. The initial red test log is
`profile-runner-rejection-reproduction.log`. Logs remain local ignored evidence.

This phase does not establish Android device behavior for the new path or desktop native
dialog rendering. The execution audit, remaining feature schemas and complete Profile
acceptance remain outstanding.

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


### Product execution admission (SDK 75, 2026-10-06)

The SDK/runtime/agent/conversation/schedule phase passed 44 SDK tests, five boundary tests,
19 agent-loop tests, 16 conversation-execution tests and 12 schedule tests. The baseline
ABI rebuild and selected native host/package tests passed in
`profile-execution-admission-validation.log`. Expanded lifecycle checks exposed two old
expectations that allowed composition mutation during generation; they now assert rejection
until work is explicitly cancelled and cleanup finishes. Nine corrected/expanded actual-JAR tests,
Profile cancellation, private schedule rejection/retry, generation lifecycle and failed-startup
retirement passed and are recorded in `profile-execution-admission-final-validation.log`.

Physical Android instrumentation passed due-task rejection/retry and private-APK generation
foreground-policy lifecycle (`OK (2 tests)`, code -1,
`profile-execution-admission-device-validation.log`), then the separately selected private-APK
schedule cancellation/cleanup case (`OK (1 test)`, code -1,
`profile-execution-admission-cancellation-device.log`). The first device selection used a
nonexistent name for the cancellation case; only the separate one-test run establishes it.
Foreground policy tests use owned host fixtures and do not prove OS background authorization.

The shared export prefix covers `KcodeExecution`/`ExecutionAdmission`; implementations stay
runtime-private. Native kernel ownership blocks direct product work during preparation,
joins cancellation, restores retained admission after failure and retires stale handles.
The evidence does not prove full startup/candidate publication admission, all pre-generation
command paths, arbitrary detached providers, Desktop native dialogs or complete management
navigation. These remain required work before the full Profile goal can be declared complete.

Final root-context binding and early startup-retirement cleanup were rebuilt for both hosts
and the instrumentation APK in `profile-execution-admission-final-validation.log`. The final
APK repeated only the affected schedule preparation/retry and cancellation paths: `OK (2 tests)`
and code -1 in `profile-execution-admission-final-device.log`. The earlier foreground-policy
case remains separate fixture evidence; no UI/dialog acceptance is inferred from it.


## Profile publication execution barrier

Product allocation now begins with execution unpublished. The separate publication bit
survives composition pause/resume, rejects candidate work and cannot reopen retired handles.
Native Profile factories defer release until durable target publication and host visibility;
initial host binding and verified old-generation restoration release the same barrier.
Direct runtime allocation releases it only after successful startup preparation.

`profile-publication-barrier-validation.log` passed six runtime boundary tests, the selected
NativeProfileHost/ProfileHost/NativeProfileExecutionAdmission suites, desktop compilation,
Android assembly and instrumentation APK assembly. After adding initial-publication cleanup,
`profile-publication-barrier-final-validation.log` passed 21 ProfileHost tests and three native
admission tests and rebuilt the instrumentation APK. The native admission cases cover apply-time
rejection, failed candidate retirement, unchanged repository state and old-generation recovery.

Physical Android ran `AndroidProfileHostTest#failedApkTargetAllocationRestoresLockedProvidersAndHistory`
with actual private APK providers. `profile-publication-barrier-device-validation.log` reports
`OK (1 test)` and instrumentation code -1. The apply-time probe rejects execution on initial,
failed and restored roots; old and failed admission handles remain closed after recovery, and
the restored root admits work. This evidence does not prove every autonomous producer uses
admission, pre-generation command protection, Desktop native dialogs or complete navigation.
Those remain required acceptance work. The final host build is recorded separately in
`profile-publication-barrier-host-validation.log`. No SDK ABI changed in this phase.


## Conversation request admission (SDK 76)

`ConversationExecution.startResponse` is now suspending. The standard executor captures root
execution admission before command resolution, conversation allocation, message-ID reservation,
generation flags, setup feedback or regeneration persistence. Command requests remain admitted
through asynchronous history cleanup. Responses enter the same gate when transferred to a
caller-supplied generation runner, retaining the runner's independent lifetime when the page
withdraws. A cancelled launch whose body never starts clears prepared generation state.

`profile-command-admission-unit-validation.log` passed 44 SDK tests, 16 Goal tests and 12
Schedule tests. The final conversation suite in `profile-command-admission-native-validation.log`
passed 20 tests, including closed-admission side-effect rejection, command cleanup joining,
page-independent generation and rejected handoff state cleanup. That run also rebuilt native
packages for ABI 76, passed selected actual-JAR conversation/generation/schedule/native admission
suites, compiled desktop, assembled Android and assembled the instrumentation APK.

`profile-command-admission-private-validation.log` then passed the expanded actual-JAR policy
case and rebuilt the instrumentation APK. Its private executor rejects send/setup/new-conversation
and suspending response calls during host Preparing; transcript, prior failure, next message ID
and generation count remain unchanged. Failed preparation restores Ready. The shared chat and
plugin API export prefixes remain authoritative; the executor is still privately loaded.

Physical Android ran the equivalent private-APK executor case and both selected private-APK
Schedule rejection/retry and cancellation/cleanup cases. `profile-command-admission-device-validation.log`
reports `OK (3 tests)` and instrumentation code -1. No real model request or OS background
permission claim follows from these fixtures. Additional detached producers/feature preparation,
Desktop native dialogs and full management navigation still require acceptance before declaring
the overall Profile goal complete. External consumers/providers must rebuild for ABI 76.


## Goal preparation admission

Goal UI actions and restoration now capture root execution admission before response
cancellation, resume-state mutation, session allocation and history-backed status changes.
Automatic restoration clears its resume marker only after a response request is accepted.
`profile-goal-admission-final-validation.log` passed 18 Goal tests, the actual-JAR
Goal/Schedule identity/lifecycle case, desktop compilation and Android assembly. An initial
test compile used a nonexistent enum value; the final test iterates the actual Goal statuses.
`profile-goal-admission-cleanup-validation.log` passed the final 18-test suite after extending
the cleanup case: admission stays held while NonCancellable history cleanup is suspended,
withdrawal waits, and stale button callbacks remain quiet. Production source did not change
between these successful runs. SDK ABI remains 76.

Closed-admission tests prove that every status/clear action preserves the running response,
resume marker and goal state, and that restoration allocates no session. Refused/cancelled
response tests preserve resume intent until acceptance. These are shared behavioral tests;
the JAR case establishes private Goal/Schedule loading, not actual button gestures or rendering.
Android application assembly is build evidence, not new device acceptance of this UI path.
Desktop native management/file dialogs and broader producer acceptance remain outstanding.

## Direct subagent execution admission

The standard in-process factory captures root execution admission at apply. Direct
coordinator calls enter it before callbacks/state changes; child turns enter separately
so caller-supplied scopes cannot bypass Profile switching. A denied queued child handoff
becomes Interrupted without product callbacks and releases its live slot for retry.
Structured descendants finish before Completed is reported, and admitted ownership extends
through cancellation cleanup. Shutdown remains available while admission is closed.
Ordinary manager changes reject active children; explicit Profile cancellation joins them
before preparing the candidate. Retired factory/coordinator references reject new calls.
This changes private implementations, not exported contracts; SDK ABI remains 76.

Current desktop XML evidence records 15 subagent tests and five native tests (four
NativeProfileExecutionAdmissionTest cases plus the actual private-JAR provider case),
with zero failures/errors/skips. The native switch case uses a caller-supplied scope,
checks default switch refusal, retained repository authority during held cleanup,
rejection during preparation, successful cancellation/switch and stale-reference refusal.
Both application targets passed in profile-subagent-admission-complete-validation.log.
The first attempt at the expanded native test omitted a withTimeout import; that compile
error was corrected before the successful final run. Android instrumentation APK assembly
passed in profile-subagent-admission-android-package.log. Android execution evidence is
recorded separately in verification.md; assembly alone is not device acceptance.

Core acceptance now takes precedence over richer editors and native-dialog polish. The
remaining work is a current requirement/code/test audit and integrated core validation;
historical UI acceptance gaps are deferred, not converted into passing evidence.
The first API 35 emulator execution failed its 90-second test-wide timeout while
initializing the native default catalogue, before subagent assertions. The log
profile-subagent-admission-emulator-validation.log reports Tests run: 1, Failures: 1;
instrumentation code -1 alone does not mean success. The fixture now allows a bounded
300 seconds for cold aggregate-APK initialization while retaining its existing 5-second
operation/cleanup waits. This is a test deadline adjustment, not a product performance
fix. A rebuilt APK and subsequent emulator run are required for acceptance.
## Core integrated acceptance audit

The current requirement-to-code/test map is profile-core-acceptance.md. Cordis baseline
6a9b4e4 passed all nine selected JVM/packager tasks: 182 cases in 33 XML suites, zero
failures/errors/skips, 39 executed tasks. The kcode allTests XML records 1,135 cases in
279 suites with zero failures/errors. Logs: profile-core-cordis-integrated-validation.log
in the Cordis worktree and profile-core-integrated-validation.log in the kcode worktree.

The first full desktop platform run executed 222 cases with two failures. CapabilityCompositionTest
still expected plugin disable to cancel active subagents; it now checks refusal, explicit
shutdown, joined cleanup, then disable/re-enable and stale identities. RuntimeCloseTest
still expected diagnostics to resolve live services during closure; it now checks refusal
while preserving concurrent/cancelled close ownership, release joining and single cleanup.
The final run in profile-core-integrated-final-validation.log passed all 222 desktop
platform cases, zero failures/errors/skips, plus allTests and both app targets. It finished
successfully in 4m 47s with 3,373 tasks (254 executed, 3,119 up-to-date). Final XML confirms
both corrected lifecycle suites passed. These results do not close the Android gap below.

The API 35 emulator's second subagent run failed process startup with an ANR. System dex
precompilation of the same installed aggregate test APK succeeded. The subsequent run
entered the test but exceeded its 300-second bound, with a waiting runBlocking stack.
Logs: profile-subagent-admission-emulator-final-validation.log,
profile-subagent-admission-emulator-exit.log,
profile-subagent-admission-emulator-dex-compile.log and
profile-subagent-admission-emulator-compiled-validation.log. The suspended operation is
not yet identified; do not classify the whole failure as environment-only or infer passing
APK behavior from successful assembly/dex compilation. Android subagent execution acceptance
and final core completion remain open. No further unchanged blind retry was launched.
