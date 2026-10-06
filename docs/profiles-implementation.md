# Profile implementation

## Early native startup recovery

Native factories now build directory/catalogue configuration inside the Profile host startup
boundary. A shared `NativeProfilePreparation` retains only successfully validated metadata;
failed preparation is retried by explicit preview or activation. Catalogue validation is shared
with runtime construction and creates no Context or provider effects. Alternate definitions
created by preflight are consumed once by the first product; subsequent products call the
original factories for fresh definitions. Native configuration errors therefore remain visible
without an active product rather than throwing before host creation.

Profile repository and legacy composition directories open on use, with repository opening
on the I/O dispatcher. Unavailable storage reports failure without deleting an obstruction or
resetting authority; a failed lazy initialization can be retried after the path is repaired.
Draft commands remain available independently of native preparation when storage is readable.
Preview and complete-archive preparation use suspending catalogue suppliers. Failed preview
preparation returns diagnostics, cancellation propagates, and templates are queried again after
successful preparation. Compatibility runtime factories still close/rethrow failed startup.
SDK 74/shared exports and Cordis 6a9b4e4 are unchanged.

Concentrated local validation passed 74 Profiles tests and 66 native Desktop tests with zero
failures/errors/skips. Desktop cases include package/workspace/staging/repository obstructions,
failed module factories, cancellation, retirement failures, stable command admission, fresh
alternate-definition counts, actual Bundle archives and 15 real package integration cases.
Both applications and the instrumentation APK built successfully. The first build exposed
Android test use of unavailable Java file helpers; compatible file I/O corrected the fixture.
The next run exposed a real duplicate alternate-factory call in preflight; one-use definitions
fixed it while retaining the existing per-product count assertions.

Physical Android validation passed five cases, `OK (5 tests)` and instrumentation code -1.
Four native cases cover package directory, Bundle staging, repository path and module factory
failure/retry with preserved metadata and zero provider allocations during preview. The fifth
opens the real host recovery window before catalogue construction, checks English/Chinese
rendering and invalid JSON handling, then repairs/activates a replacement root exactly once.
The recovery screenshot was inspected; the shell-owned transfer copy was removed.

Evidence: `profile-early-startup-validation.log`, `profile-early-startup-final-validation.log`,
`profile-early-startup-owned-validation.log`, `profile-early-startup-ui-build.log`,
`profile-early-startup-device-package.log` and `profile-early-startup-device-validation.log`.
Logs and screenshot are local ignored artifacts. These cases do not simulate every I/O fault
or establish Desktop recovery/dialog rendering. Explicit corrupt-authority repair, the full
background execution-admission audit, remaining feature export schemas and completion audit
remain outstanding; the overall Profile goal remains active.

## Complete archive commands and owned native files

SDK 74 adds `ProfileArchiveImport`, `ProfileArchiveReference` and two management client
methods. Import checks revision/create-only identity before preparation and again at draft
publication, preserving independent scopes and frozen layers without activation. Export uses
a suspending consumer with a host-owned temporary archive; consumers cannot nominate host
output paths or export review callbacks. Success, failure and cancellation release the lease.
Host admission and the owned Cordis bridge carry these metadata calls through Ready and
RecoveryRequired and reject withdrawn clients. The SDK namespace already exports the new
contracts; private transport implementations remain outside it.

Both native factories supply verified transport and their builtin module catalogue. The optional
Profile screen has separate complete archive import/export actions, retaining JSON and ordered
Bundle formats. Import captures revision before picking and streams a temporary input. Export
saves from the host lease with digest and 512 MiB checks. Desktop publishes a verified sibling
temporary file atomically; Android streams to the selected document provider and cannot claim
atomic rollback on provider failure. Cancelled slots remain reserved until OS callbacks return.
UI withdrawal cancels/joins owned work; dirty editors and duplicate file actions are rejected.

Concentrated checks passed 94 SDK tests, 73 Profile tests and 33 UI tests, with zero failures,
errors or skips. Initial native validation passed 25 tests (actual archive 1, package integration
15, gateway 9); focused validation passed the updated archive case and private Profile UI host
case. The updated archive case proves that a consumer cannot close its own host, external host
closure cancels/joins consumption, and temporary archive/directory cleanup finishes before
closure returns. Both applications and the instrumentation APK built against API 74 and
unchanged Cordis 6a9b4e4. New DTOs require no additional shared namespace; regenerated real
JAR/APK packages use the matching SDK/framework fingerprint.

Physical Android verification covered 13 distinct cases under API 74: complete cross-host
archive commands, ordered Bundle imports, private Profile UI/picker cancellation, feature
export review, three recovery cases and six host/SDK/data-scope cases. The initial batch had
12 passes and one obsolete test expectation: the typed-alternate restart case expected a
missing module to throw instead of returning the supported RecoveryRequired host. The test
now verifies failure details, unchanged authority, retained selection, no active product,
zero allocations and explicit failed-host closure. Its isolated retry passed `OK (1 test)`
with instrumentation code -1; the other 12 successful cases were not repeated. Complete
archive import/export ran through the native SDK, preserving exact code identities while
verifying the Android variant, and export lease cleanup was observed. Private APK UI opened
and cancelled the complete-archive OS picker without authority or runtime changes.

Evidence logs (local ignored files): `profile-archive-command-validation.log`,
`profile-archive-command-final-validation.log`, `profile-archive-command-device-package.log`,
`profile-archive-command-device-validation.log`, `profile-archive-host-contract-package.log`
and `profile-archive-host-contract-device.log`. Backend verification and picker cancellation
do not establish selected-file round trips, touch/keyboard accessibility, real Shizuku
authorization or root execution.

Selected-file OS picker round trips, additional feature export schemas, early startup recovery
and the full execution-admission audit remain outstanding. The overall goal remains active.

## Bundle publishing tooling phase

`ProfileBundleArchiveWriter` generates pure-data Bundle manifests from frozen SDK Bundle
definitions, declared native targets and code archive/digest inputs. It streams owned code
snapshots with Cordis entry/expanded-size bounds, verifies copied container identities and
derives dependency versions and content-addressed file records. Duplicate code identities
are rejected, code declarations are sorted for deterministic output, and Bundle operation
order is preserved. Cordis validates/inspects the completed archive before atomic output
replacement. Invalid input preserves existing output. Temporary payload cleanup attempts
all releases. The writer allocates no product services and SDK API remains 73.

The Desktop `packProfileBundle` Gradle task runs the shipping CLI from a strict bounded
UTF-8 JSON request. Relative paths use the request directory, data-only layers are supported,
and request/definition/code input paths cannot be selected as the normalized output path.
Target compatibility, SDK/ABI, complete layered structure and combined code dependency
checks remain native import responsibilities; a layer can depend on earlier layers.

Concentrated verification passed 69 local cases: 68 Profiles and the actual native Bundle
JAR case. New cases cover deterministic code ordering, generated metadata, frozen definition
round trips, invalid digests, duplicate package IDs, unknown Bundle formats, output/input
protection and CLI-relative paths/data-only output. The existing real JAR case now publishes
both ordered archives with the writer before bridge import/restart/export. Both application
builds and instrumentation APK assembly passed. The actual Gradle task produced a data-only
archive whose root manifest/runtime and entry set were independently inspected.

Physical ARM64/API 36 validation passed the actual Bundle APK case with `OK (1 test)` and
`INSTRUMENTATION_CODE: -1`. It publishes its archive with the shared writer, imports through
the host SDK client, preserves bootstrap activation, deletes source/outer deployment, restarts
twice and exports through verified schema review. Unchanged UI/recovery cases were not repeated
in this phase. See [publishing](profile-bundle-publishing.md) for command examples.

Self-contained committed Profile archive export, cross-platform exact-lock adaptation and
selected-file OS picker acceptance still remain open, as do other feature export schemas,
pre-catalogue/corrupt-authority recovery and execution/resource ownership audit. The writer
is publisher tooling and does not replace host-reviewed Profile export.

## Native Bundle import command phase

SDK 73 adds `ProfileBundleArchiveReference`, `ProfileBundleImport` and
`ProfileManagementClient.importBundles`. The shared API export namespace already covers
these identities; native package SDK/ABI metadata was regenerated and real private JAR
loading/package integration passed. The host prepares ordered native archives and creates
an isolated draft through the existing portable import publisher. Revision and create-only
checks run before staging and at publication; cancellation is checked before publication.
Input archive addresses are ephemeral and do not enter the portable document. The command
is available through the host gateway and the owned product-tree bridge in Ready/Recovery.
Bridge withdrawal joins admitted imports and retained clients reject later calls.

The optional native settings package adds a separate localized Bundle archive action.
Desktop uses multiple-file selection and Android uses OpenMultipleDocuments. Both stream
the picker-returned inputs into temporary files, compute SHA-256 and retain the files until
the import returns. Selection is limited to sixteen archives and 512 MiB combined; cleanup
runs on success, error and cancellation. UI import captures revision before selection,
rejects dirty/busy editors, leaves preview empty and never submits activation. Android read,
write and Bundle requests share a busy guard and keep cancelled slots until OS completion.
Blocking document-provider I/O can delay withdrawal until it responds.

Concentrated local verification passed 121 cases: 65 Profiles, 30 Profile UI and 26 native
Desktop cases (15 package integration, 9 gateway, one default private UI and one actual
Bundle JAR case). The Bundle case now imports via the Cordis bridge, preserves the active
bootstrap Profile, rejects a stale revision and rejects the retained bridge after closure.
It then restarts/exports frozen intent after deleting source archives and Bundle deployments.
The metadata case covers preflight conflict, concurrent publication conflict and cancellation.
UI cases cover order, pre-picker revision, cancellation and withdrawal; file staging verifies
bytes/order, bounded reads and cleanup after consumer failure. Both applications and the
instrumentation APK built successfully. A first bridge fixture used an unknown override ID;
changing its capture to the existing replaceable UI module corrected the fixture, and the
final concentrated Desktop package/gateway checks passed.

Physical ARM64/API 36 validation passed seven cases with `OK (7 tests)` and
`INSTRUMENTATION_CODE: -1`. The actual Bundle APK case imports through the SDK host client,
keeps the bootstrap Profile active and restarts from frozen metadata/code cache. The private
Profile settings case opens/cancels both the JSON and Bundle system pickers, confirms unchanged
catalogue/current generation, then exercises edit/save/discard and language behavior. Verified
schema export, private default UI loading and three recovery cases also passed under API 73.
This proves native command behavior and picker cancellation, not selected-file acceptance or
a complete binary import/export round trip. Desktop native dialog rendering remains unverified.

Full native picker acceptance round trips, a shipping Bundle builder, self-contained archive
export and cross-platform portable-lock adaptation remain open. Other feature export schemas,
pre-catalogue/corrupt-authority recovery and the execution/resource ownership audit also remain
outstanding. This phase does not establish completion of the overall Profile objective.

## External Bundle preparation phase

`ProfileBundleArchive` implements pure-data Cordis archives with the
`ai.meteor.kcode.profile-bundle` extension and `kcode-profile-bundle` data runtime.
Outer container validation is reused from Cordis; the native resolver separately verifies
embedded JAR/APK packages, actual dependencies and SDK/ABI identities. Ordered Bundle
inputs share identical code releases, reject conflicting declarations and compile through
the existing Profile compiler before staging native code. Preparation creates no provider,
draft or committed metadata. Its returned portable document enters the existing create-only
revision-checked import boundary; activation remains separate.

Both native factories resolve first imported drafts through locked SHA-256 cache locators,
ahead of newer bundled offers. The resolver still verifies those files and imported-lock
checks remain in force. Committed/history offers retain their existing semantics. Frozen
definitions and code caches allow startup without original archive paths or outer Bundle
deployments. Bundle metadata does not create a fake code module in the lock. SDK API is 72.

Concentrated verification passed 81 local cases (64 Profiles and 17 native Desktop cases),
Desktop application compilation, Android application assembly and instrumentation APK
assembly. The real JAR case combines two layers sharing Localization code, verifies the
second layer's configuration, deletes both input archives and outer deployment, starts
twice and exports frozen intent through the verified feature schema. Invalid digest,
mismatched code version, unknown metadata version and duplicate Bundle inputs are rejected.
An additional case proves imported digest precedence over newer offers without rewriting
committed/history offers.

Physical ARM64/API 36 verification passed seven related cases before the ordered-input
refactor, including actual Bundle APK preparation/restart, export schema, private default
UI loading, Profile UI and recovery. The latest test APK then passed the isolated Bundle
APK case again with `OK (1 test)` and `INSTRUMENTATION_CODE: -1`. Both native builds and
the local ordered-input test cover the final implementation. Device proof currently covers
a single Bundle; it does not establish the binary picker or archive export user flow.

Neutral binary Bundle commands, native picker integration, archive publishing/export tools,
cross-platform portable-lock adaptation and other feature export schemas remain open.
Pre-catalogue/corrupt-authority recovery and the execution/resource ownership audit also
remain outstanding. See the [archive guide](profile-bundle-archives.md) for format details.

## Verified feature export schema phase

Native hosts now select export reviews from the exact generation's verified package
manifest through `ProfilePackageExportReviews`. The resolver verifies immutable deployment,
native artifact, SDK, descriptor and lock identities before returning the
`ai.meteor.kcode.profile-export` extension. Newer offers, host APK resources and live
product callbacks do not supply historical rules. Missing rules remain denied.

Feature-owned `src/profile-export/<package-id>.json` files are inputs to release manifest
metadata/content versions. Goal and Web Search explicitly permit Unit configuration.
Localization permits Unit and dictionary JSON with declared language/translation fields
and bounded text maps. The closed version-1 dialect supports exact field/codec selection,
null, boolean, integer, enum, bounded string and object rules, explicit required properties
and optional schema-controlled map values. Unknown schema fields/types, undeclared config
fields and invalid codec identities reject export. Rule metadata is detached before use.
Runtime ConfigValidator validation remains separate from export permission.

Real dictionary activation exposed typed JSON coercion in generic container copying.
Cordis commit `6a9b4e4` retains/detaches JsonObject/JsonArray during composition,
Loader interpolation and tree transaction snapshots/restoration. Typed JSON containing
`__jsExpr` remains literal; explicit JsExpr/plain-map expression semantics remain available.
65 Loader/Include JVM cases passed, including insertion/overrides, literal JSON validation
and failed-publication restoration. Kcode concentrated validation passed 79 local cases:
63 Profiles and 16 native export/gateway/startup recovery cases. Both applications and
the Android instrumentation APK built successfully. The real private JAR case activates
custom dictionary JSON, exports it via host metadata review, then rejects a tampered locked
archive without changing Profile metadata. SDK API remains 72; framework ABI descriptors
and native package metadata were regenerated from the changed framework bytecode.

Physical ARM64 API 36 validation passed 6 cases with `OK (6 tests)` and
`INSTRUMENTATION_CODE: -1`. The new actual private APK case activates dictionary JSON,
exports it through the native host's verified schema reader, tampers with its locked
archive and verifies denial with unchanged catalogue. Private default APK loading, native
file-picker cancellation/editor navigation and three recovery cases also passed. This
does not establish external Bundle installation or a user file-exchange round trip.

Other feature configuration schemas, verified external Bundle import and real file/archive
round trips remain open. Corrupt-authority recovery, Desktop native dialog rendering and
the full execution-admission/resource-ownership audit remain outstanding.

## Native file exchange UI phase

The optional private Profile settings package now exposes native file import/export.
Android uses shared Activity Result contracts with system document providers; Desktop
uses an AWT native file dialog. Reads accept bounded (2 MiB), strictly decoded UTF-8.
Desktop writes force a temporary file and atomically replace the chosen target; Android
provider output is explicitly not atomic. Cancelling a picker changes no Profile metadata.

Import captures the catalogue revision before opening the picker, publishes an isolated
draft through the neutral client and leaves preview empty. It does not implicitly prepare
code or activate. Export requires committed/history intent and completes host review
before showing the save picker. Dirty editors cannot exchange files. Repeated file actions
are rejected while busy rather than queued; withdrawal cancels/joins picker operations.
Android retains cancelled request slots until the OS callback returns, preventing a late
result from being consumed by another request. Blocking provider I/O may delay cancellation
until it returns; external provider failures may leave partial output.

Concentrated validation passed 27 desktop UI cases (20 session, 6 form, 1 localization),
Desktop application compilation, Android application assembly and instrumentation APK
assembly. Session cases cover unprepared import, cancelled selection, pre-picker revision
conflicts, dirty guards, host rejection before file selection, historical export identity,
cancelled save, duplicate action rejection and withdrawal joining the pending picker.
SDK ABI remains 72; platform file operations and the exchange document remain private.

Physical ARM64 API 36 acceptance passed 5 related instrumentation cases with
`OK (5 tests)` and `INSTRUMENTATION_CODE: -1`: actual private default APK loading,
Profile settings rendering/navigation and three recovery cases. The settings case opens
the real OS document picker, cancels it, verifies unchanged catalogue/active Profile,
then exercises the existing bilingual edit/save/discard flow. Its first attempt timed out;
the test helper used fixed editor/list positions. Explicit ID-field tagging, updated list
positions and named wait failures corrected the fixture; both isolated and concentrated
device runs then passed. This proves picker cancellation, not a full file import/export
round trip or touchscreen/keyboard acceptance.

Shipped feature schema policies/native policy wiring, verified external Bundle import,
real archive file exchange, corrupt-authority recovery and the complete execution/resource
ownership audit remain outstanding. Desktop native dialog rendering is not yet verified.

## Module-aware export review phase

Private host reviews now receive module/package ID, entry ID, configuration kind, source field/
location and original JSON. `ProfileExportPolicies` routes by a detached module map and denies
unknown identities. Hosts can supply this review at management construction; the neutral client
still cannot approve its own values. Existing native constructors preserve default denial.

The export session uses the shared compiler to track original operations in declared Bundle order,
regardless of frozen storage order. Configure/context fields use the current owner. Replace
re-reviews inherited fields against the receiving module and rejects conflicting portable values;
movement retains origin, cleared contexts do not transfer and removed/reinserted IDs cannot borrow
old approvals. Every historical stored value is reviewed, including values overridden or removed
later. Input/output JSON snapshots prevent mutable policy results from altering previous approvals.
Policy failures omit value-bearing exception messages/causes; cancellation preserves its identity.

Final concentrated verification passed 74 desktop cases: 59 Profiles, 9 command gateway and
6 native startup recovery tests, plus Android Profiles compilation. New tests exercise the
module/codec transitions, inherited context, ordering, movement, identity reuse, detached policy
maps/results, exception/cancellation boundaries and host-selected policy through the real Cordis
management bridge without changing active configuration or durable intent. SDK ABI remains 72;
no shared namespace, document format or product UI changed. No device or full app build is claimed
for this private implementation phase.

This provides the policy execution/selection boundary, not completed shipped feature policies.
Feature-owned schema metadata/registration, actual native policy wiring, native file operations/UI,
verified external Bundle import and real portable archive exchange remain open. Corrupt-authority
recovery, desktop rendering and the execution-admission/resource-ownership audit remain outstanding.

## Neutral portable exchange client phase

Plugin API 72 adds `ProfilePortableImport`/`ProfilePortableExport` and neutral management client
methods. The request types remain in the existing shared Profile namespace; the portable JSON
envelope and review implementation remain private. Client import publishes a new revision-checked
draft with isolated data scopes. Export requires the requested catalogue revision and reads only
committed/historical recipes. Consumers cannot supply approval callbacks; explicit unknown
configuration still fails closed until feature-schema review policies are integrated.

Both methods use the host's metadata admission boundary, including RecoveryRequired. The injected
bridge owns its suspend calls, cancels/joins them on withdrawal and rejects stale clients. Import
never changes the running composition; explicit activation retains the existing host-owned queue
and command handles. Tests verify import/activation/export through a real Cordis management bridge,
stale revision rejection, withdrawn clients and metadata import after failed runtime restoration.

Concentrated validation passed 110 local tests: 44 SDK, 50 Profiles, 8 command gateway, 6 native
startup recovery, 1 actual private default JAR and 1 shipped archive/production-classpath case.
Desktop compilation, Android application assembly and instrumentation APK assembly passed.
SDK/framework ABI fingerprints and shipped archives were regenerated for 72. Actual JAR and APK
loaders retain host identity for both new request classes. The shipped archive test initially had
two stale assertions for 58 packages; these now require 59 and explicitly require the Profile UI
package. Its existing Active checks remain intact; the optional settings child waits for management
without making the packaged root inactive. The failed initial package run is not passing evidence.

Five cases passed on the physical ARM64/API 36 device (`OK (5 tests)`, completion -1): private APK
identity/loading, private Profile settings rendering and three recovery cases. These exercise the
rebuilt SDK 72 boundary, not file import/export UI or real portable package exchange. Native file
operations/UI, feature-schema policies, verified external Bundle import, corrupt-authority recovery,
desktop rendering and the execution-admission/resource-ownership audit remain outstanding.

## Imported frozen draft phase

Host-side management can now import a portable document into a create-only, revision-checked
draft with independent data scopes. Import is metadata-only and does not prepare code or alter
selection. Draft envelope format 2 retains imported frozen bundles/locks through management
edits, repository saves, cloning and reopening. The decoder retains format 1 support; newly
published draft documents use format 2, and format 1 cannot carry an imported recipe.

Preview, startup from an uncommitted imported draft and explicit activation use those frozen
bundles even if the installed catalogue changes or no longer contains them. Required imported
packages cannot resolve to a same-name builtin. Archive changes reject before resolution;
after native resolver verification, versions, variants, ABI and dependency identities must
match the imported lock. Removed packages need not remain installed; explicitly added packages
use normal verified offers. Successful local publication becomes the new authoritative base.
Committed/historical host-side export is revision checked and rejects legacy local descriptors
that cannot provide verified portable archive identities. API remains 71; these implementation
classes are outside the shared SDK export set.

Final concentrated verification passed 50 Profiles desktop tests and 6 native startup recovery
tests, plus Android Profiles compilation. New cases cover metadata-only/CAS import, editing and
cloning, repository reopening, catalogue-independent startup, preview and staged publication,
raw descriptor export rejection, draft envelope upgrade and imported lock mismatch rejection.
Package resolution is exercised with test resolvers here; this does not prove real JAR/APK
portable exchange. Native startup recovery tests run against the existing desktop integration.

Neutral client import/export commands, feature-schema export policies, native file UI, verified
external Bundle import and real archive exchange remain outstanding. Corrupt-authority recovery,
desktop rendering and the complete execution-admission/resource-ownership audit remain open.

## Portable export boundary phase

The host-side Profiles implementation now includes `ProfilePortableExporter` and a versioned
`PortableProfileDocument`. Export uses a committed generation's frozen declared bundles and
package locks, excludes runtime composition/paths and machine/launch layers, and resets all
three data scopes to independent Profile scopes. Every explicit configuration/injection/interception
value requires host-owned feature-schema review, including nested entries and overridden values
in older layers. Unknown values fail closed; diagnostics omit their contents. Review callbacks
are trusted host policy and must not be installed as unconditional identity approvals.

Strict decode validates the envelope, complete frozen bundles, lock structure and compiled tree
without resource allocation. Decode does not establish package authenticity or activate anything.
No exported SDK contract changed; API remains 71. Concentrated verification passed all 44 Profiles
desktop tests (including 5 new exchange cases) and Android Profiles compilation. An initial nested
fixture used a Loader path rather than the composer's entry ID; the fixture was corrected before
the passing run. No device or full application regression was required for this pure common layer.

This is an exchange foundation, not completed user-facing import/export. Neutral management
commands, feature-schema policies, native file exchange, imported frozen-draft publication and
verified external Bundle import remain outstanding, as do corrupt-authority recovery, desktop
rendering and the execution-admission/resource-ownership audit.

## Historical and template recovery phase

Recovery now lists generations for the selected Profile and can activate a historical target
directly. Historical JSON is read-only in both recovery and default settings, including the
raw editor/save boundary. Users can copy the selected generation into a new draft to edit it;
the existing clone contract retains its frozen recipe. Save-and-activate only writes when
there are actual edits, so a clean historical selection cannot silently become a latest-base
draft before activation.

Native hosts expose detached distribution templates outside the product tree. Recovery uses
create-only publication under a new ID and separate settings/history/workspace scopes, retaining
the original failed intent and its data. Template lookup failures retain metadata management.
An unreadable selected definition still publishes its catalogue so other choices/templates
remain accessible. An unavailable history list does not prevent editing an available draft.
The templates use the configured native composition; they do not inject default product
providers into alternative roots. No exported SDK contract changed; Plugin API remains 71.

Concentrated final validation passed 46 local tests: 13 recovery-session tests, 23 default
Profile UI tests, 4 Profile management tests and 6 native startup recovery tests. Desktop
compilation, Android application assembly and native instrumentation APK assembly passed.
An initial instrumentation compilation error in a positional fixture constructor was corrected
before device acceptance; it was not counted as successful validation.

Five selected cases passed on a physical ARM64/API 36 device (`OK (5 tests)`, completion -1).
The historical case starts from a broken generation 2 and activates generation 1's frozen
Bundle through the actual recovery UI, verifies generation 3 retains that Bundle, preserves
generation 2 and creates no draft. The template case creates an isolated repair draft from
all three shipped layers, preserves the original broken draft, activates it and renders the
private default product. The existing JSON-repair/alternative-root, private default APK loading
and private Profile settings cases also passed. Screenshots were inspected.

Verified external Bundle import, credential-safe portable export, corrupt-authority and
pre-catalogue/package-staging recovery remain outstanding, along with desktop rendering and
the complete execution-admission/resource-ownership audit. These targeted tests do not prove
full touch/keyboard behavior, root/Shizuku authorization or overall Profile completion.

## Native recovery surface phase

Both shipped applications now use the suspending Profile-host factories and host-linked
`profile-recovery-ui`. Ready hosts retain their own renderer/theme. Recovery shows the cause
and optional full diagnostics, saved committed/draft choices, a JSON editor, save/discard,
saved activation and save-and-activate. Its neutral session reads definitions without module
resolution; it preserves dirty write revisions and rejects invalid JSON or changed identity.
Accepted activation remains host-owned across UI transitions and observer cancellation.

The reusable UI library now owns `KcodeDefaultDesignTokens`; the default theme aliases the
same values. The independent recovery surface uses `KcodeTheme` with standard Material roles
and private English/Chinese XML defaults. This adds a shared UI symbol within the existing
exports and changes Plugin API to 71. Native package generation rebuilt the SDK/framework
fingerprint and all shipped packages.

Concentrated validation passed 56 local tests: 8 recovery-session cases, 43 SDK cases,
4 native startup recovery cases and 1 real-JAR default UI case. Desktop compilation, Android
application assembly and native Android test APK assembly passed.

On a physical ARM64/API 36 device, all three selected instrumentation cases passed (runner
reported `OK (3 tests)` and completion code -1): startup recovery through invalid JSON and
successful repaired activation into an alternative rendered root; private default UI APK
loading; and shipped private Profile settings rendering with unsaved-edit confirmation.
Recovery text switched between English and Chinese, and screenshots were inspected.

The first device run exposed a missing static Profile UI fixture dependency. After repairing
the fixture, the real APK case exposed duplicate entry removal during runtime closure.
Both native controllers now allow already-retired entries only in their close path, while
ordinary uninstall remains strict and code release continues. The real-JAR case and all
three device cases passed after that fix. These failures were not counted as passing evidence.

This phase does not repair corrupt repository authority or pre-catalogue/package-staging
failures. Template restoration, historical selection in recovery UI, verified external Bundle
import and credential-safe portable export remain outstanding. It does not establish desktop
rendering, complete touch/keyboard behavior or full Profile completion.

## Target and ownership

Profiles compose an empty plugin tree using ordered bundle layers, profile operations,
machine overrides and launch overrides. The interpretation is shared with Cordis Include.
Cordis owns generic composition and tree lifecycle. Kcode owns named profiles, package
resolution, data scopes, persistence, agent-turn boundaries and native management UI.

Portable definitions contain data only. Module allocation and ConfigValidator execution
belong to activation. Package IDs and entry IDs are separate identities. Bundle references
are versioned; unknown targets produce diagnostics rather than silently changing intent.

## Isolated development

- Kcode branch: `meteor/profiles`, managed worktree `kcode-profiles`.
- Cordis branch: `meteor/profile-composition`, sibling worktree `cordis-kotlin-profiles`.
- Build Kcode against the Cordis worktree using `-PcordisSource=<absolute path>`.
- Keep validation and commits at phase boundaries.

## Phases

1. Shared detached composition, patch diagnostics and sources; portable Profile models.
2. Observable transactional tree updates and Include candidate publication.
3. Profile repository, immutable package locks, generation commits and migration.
4. Default bundles, host selection and runtime application/switching.
5. Profile management, preview, import/export and host recovery UI.
6. Cross-platform package/lifecycle checks and user documentation.

## Outstanding invariants

Package preparation now uses the existing PluginPackageResolver with dependency archive
hints and verified closure locks. Entry IDs remain independent of release IDs, and group
structure is preserved for runtime binding. Profile configuration keeps Unit/scalar/JSON
codec identities; omitted configuration requests module defaults.

Legacy migration projects enable state, explicit uninstalls and external configuration into
profile operations. Bootstrap reads the last committed intent before a draft and retains
legacy snapshots after interrupted initial migration. ProfileCompositionSession implements
the existing composition store boundary and provides atomic definition/snapshot publication.

The runtime now accepts ProfileActivation through its managed startup facade. Native controllers
register modules without creating package-level instances; the Cordis tree retains instance
IDs, groups, per-instance configuration and code origins. Configurations, inventories, settling
and application frame preparation precede the atomic generation publisher. Legacy manager
enable mutations now use Profile transactions. Native imports, code upserts and removals
also route through joint module-generation and tree transactions. Full declaration editing,
Profile switching, external bundle import and management/recovery UI remain pending.

The shipped hosts now select this mode through ProfileStartupFactory. Native bundle layers,
explicit IDs, saved selection, machine configuration and committed restart are wired. Settings
and history scopes are bound on both platforms; desktop workspace scopes are bound as well.
Android workspace scope enforcement, Profile updating/switching, management UI and recovery
remain outstanding. Do not claim complete native Profile support from startup tests alone.

Phase-one validation passed: Cordis `:include:jvmTest` (14 tests), Kcode
`:plugins:profiles:desktopTest` (4 tests), using the Cordis composite source build.
No host or instrumentation validation has been claimed at this stage.

Cordis now provides `withTreeTransaction`, coordinated with module HMR. Include candidates
publish parsed content only after successful tree application, and failed files can be
retried unchanged. Durable publication runs without cancellation. The direct legacy
EntryGroup update remains best effort; product entry points must use managed transactions.

The JVM Profile repository atomically publishes definition, lock and runtime snapshot in
one committed document, separately from editable drafts. It checks generation conflicts and
serializes repository instances. Runtime integration must make that publication the final
fallible transaction step. Profile selection is a separate atomic record; switching still
needs an explicit recovery policy. Power-loss guarantees for directory metadata have not
been established; synced atomic files prove application-level publication, not all hardware
failure modes.

Phase-two validation: Loader, Include and HMR JVM suites including real JAR reload; nine
Profile compiler/repository desktop tests and Profile Android debug compilation. An existing
relocated-provider isolation test showed one transient PENDING/ACTIVE mismatch under the
combined build, then passed in isolation. Keep its settling behavior in the final lifecycle
audit; do not silently treat a targeted rerun as evidence for the entire suite.

Active agent turns must finish or be explicitly cancelled before application. Different
profiles switch by runtime shutdown/recreation initially. Durable provider data survives
withdrawal. Credentials and host paths do not belong in exported profile definitions.

Completion requires all phases, migration tests, real package loading evidence, desktop
and Android build validation and documentation. Phase-one tests alone are not completion.

The phase evidence below records behavior at each checkpoint. Later sections supersede
earlier pending-status statements; the [Profile guide](profiles.md) describes current behavior.

## Runtime startup phase evidence

Declarative startup validation passed against the Cordis worktree: 16 Profile compiler,
repository and preparation tests; 6 ProfileRuntimeTest scenarios; 14 existing runtime tests;
and 15 existing package integration tests. The new real-JAR scenario verifies private code
identity, SDK identity, per-instance configuration, code origin and independent cleanup.
The isolation scenario runs two configured providers and consumers in separate group realms.
Failure scenarios cover pre-allocation validation and resource cleanup after publication refusal.
Pending consumers and rejection of late startup were verified as discovered test cases in XML.

Android platform debug Kotlin compilation and the final runtime Android compilation passed.
No Android device Profile loading, full Profile package update or
Profile switching evidence is claimed. Public lazy mount export changes Plugin API to 64;
the exact existing shared exports cover the new member and external packages must be rebuilt.

## Native host startup phase evidence

The final phase build passed 57 tests: 21 Profile compiler/repository/preparation tests,
6 Profile runtime tests, 1 real native host test, 14 existing runtime tests and 15 package
integration tests. Desktop application compilation and Android APK assembly passed in the
same build against the Cordis worktree. The host test writes actual settings/history, closes
the runtime, selects a Profile and verifies persisted isolated data after restart; a corrupt
draft cannot displace the successful commit. Explicit native startup reads its separate data.

Format 2 commits freeze Bundle contents, always serialize their version, and retain code
locks on restart. Tests cover omitted-version format 1 recovery/upgrade and refusal to commit
incomplete frozen bundles. JSON null configuration survives serialization; machine overlays
apply to every matching instance without copying host paths into portable definitions.
These tests do not establish Android device persistence or complete mutation/switch semantics.

## Enable transaction phase evidence

The next phase build passed 61 tests: the same 21 Profile tests, 10 Profile runtime tests,
1 real native host test, 14 legacy runtime tests and 15 package integration tests. Desktop
compilation and Android APK assembly passed. Four new tests verify independent instance
withdrawal/recovery with a pending consumer, failed generation publication and successful
retry, rejection of an invalid batch before withdrawal, and launch-layer precedence over
saved Enable intent. The legacy manager routes individual and enable-only batch operations
through managed Profile tree transactions. Release imports/upserts/removals, configuration
changes, Profile switching and recovery UI remain pending.

## Package transaction phase evidence

Native controllers share prepared module-generation transactions. Runtime commands now
compile portable install/configure/enable/remove intent, retain required code dependencies,
bind candidate exports, validate all instances, settle the tree and prepare frames before
publishing a generation. Failure restores the old tree before releasing candidate code;
cancellation completes metadata/inventory/frame recovery without cancelling cleanup.
Unchanged releases retain bindings, and a code upgrade does not recreate a removed default
instance. Startup prefers saved releases over catalogue shadows, except explicit caller
overrides. Raw local descriptors stay in local snapshots rather than claiming archive locks.

The cancellation test exposed a Cordis lifecycle hang: explicit disposal did not cancel a
suspended apply. Cordis commit `ef00190` cancels the allocation child while the transition
owner completes suspending cleanup. Commit `f08918d` preserves provider exception identity
when crossing that child boundary. The final Cordis Core/Loader/Include/HMR JVM run passed
122 tests (60/35/16/11). Dependency-relocation behavior remains covered by existing tests.

The first Kcode run after the lifecycle fix passed all eight new real-JAR/archive package
transaction tests, including cancellation, publication/allocation failure, retry, independent
removal, unrelated resources, mixed batches, immutable releases and archive restart. An
existing exception-identity assertion failed and prompted the second Cordis fix. The final
complete Kcode phase build passed 71 tests: 23 Profile compiler/repository/preparation tests,
14 runtime tests, 1 native host test, 15 existing package integration tests, 10 Profile runtime
tests and 8 Profile package transaction tests. Desktop compilation and Android APK assembly
passed in the same build against Cordis commit `f08918d`. This does not establish Android
device package loading, complete Profile editing, switching or recovery UI.

## Immutable repository phase evidence

The JVM repository now stages immutable generation documents and atomically publishes a
single authority containing histories and current selection. Legacy committed/selection files
are imported once without rewriting them. Consistent authority reads include the selected
generation; joint commit/select compares both the repository revision and target generation.
Logical deletion withdraws the record without deleting provider data or adopting old files on
recreation. History includes published pointers only; staged/orphan files remain unreachable.

Seven new tests cover immutable history, selection advancement, revision conflicts, concurrent
switch publication, orphan/retry isolation, corrupt authority, deletion/recreation and legacy
selection migration. The existing format-upgrade and corrupt-generation tests now inspect
actual immutable files. Phase validation passed 78 tests: 30 Profile tests and the same 48
desktop runtime/native/package tests. Android platform compilation passed against the same
Cordis worktree. Runtime switching, recovery UI and Android device/power-loss behavior are
not established by this repository implementation.

## Prepared switch publication phase evidence

Profile sessions can stage candidate startup and definition updates until the host explicitly
publishes generation and selection together. Repeated staging advances once, rejection permits
retry, discard prevents later writes, and successful publication returns the session to normal
durable operation. Native staging captures the authority revision before target preparation and
does not save a migration draft. Four runtime tests cover allocated candidate invisibility,
refused publication/retry, concurrent authority conflict/discard and allocation cleanup. Two
repository/preparation tests cover target history and draft-free native staging. Phase validation
passed 84 tests (32 Profile tests and 52 desktop runtime/native/package tests), plus Android
platform compilation against Cordis `f08918d`. Stable host facades, old-runtime shutdown/recreation, turn admission and recovery UI
remain necessary before this becomes a complete live switching feature.

## Host coordinator phase evidence

`KcodeProfileHost` owns stable chat, plugin-manager, overlay and application facades around
replaceable complete runtimes. Admission closes before preparation, default switching refuses
active calls/overlay leases, and explicit cancellation joins admitted work before withdrawal.
The old locked preparation is retained before closure. Allocation or publication failure
reconstructs that exact committed intent without advancing history or changing selection.
Failed old closure or failed reconstruction enters `RecoveryRequired` and refuses execution.
Callback-initiated switch/closure is rejected. Post-publication cancellation leaves the
committed candidate live. Overlay withdrawal joins finishing leases and rejects stale updates;
foreground updates serialize with switches.

Eleven coordinator tests use real Cordis runtimes/providers and file-backed Profile sessions,
covering success, preflight failure, allocation/publication failure, cancellation/join, recovery
failure, old closure failure, callbacks, committed-boundary cancellation and overlay leases.
The phase build passed 95 tests (32 Profile and 63 desktop runtime/native/package tests), plus
Android platform compilation. Native factory integration, public management SDK, recovery UI
and Android device evidence are still required; this evidence does not claim those features.

## Desktop native host integration evidence

Desktop factories now retain only the module ID catalogue and host configuration, create fresh
host inputs for each product allocation, and return the coordinator's stable facades. Native
preparation supplies both target and old locked recipes before withdrawal. Existing factory
tests inspect admitted host diagnostics rather than retaining the raw product owner. Two new
native tests exercise live settings/history scope changes, stale store rejection, saved-selection
restart and failed target allocation followed by restoration of actual locked package providers.
Phase validation passed 97 tests (32 Profile and 65 desktop runtime/native/package tests),
including all three actual native factory tests. Desktop application compilation and Android
platform compilation passed against Cordis `f08918d`. Android factory integration, the exported
management SDK/UI and recovery UI remain outstanding; no Android device switching evidence is
claimed.

## Android native host integration evidence

Android factories now return the coordinator's stable facades and prepare target/restoration
recipes from the module ID catalogue. Every initial, candidate and restored allocation receives
fresh host inputs. Settings/history scopes use the existing MMKV/Room providers. Scoped file
tools, App shell defaults and Ubuntu `/workspace` share the configured app-private directory.
Unit configuration retains legacy paths. ADB rejects app-private scoped workspaces before
authorization rather than silently selecting shared data. Root paths are configured but actual
root authorization and execution are not established by this phase.

The phase build assembled the Android app and platform instrumentation APK, compiled the app's
instrumentation sources and passed the 97 related desktop tests (32 Profile and 65 native/runtime/
package tests), against Cordis `f08918d`. The dedicated instrumentation APK then ran all three
`AndroidProfileHostTest` cases successfully on an ARM64 API 36 physical device (`OK (3 tests)`).
The switch case requires the real APK Ubuntu provider on ARM64 and executes a command through
its scoped `/workspace` binding. It also verifies private filesystem implementation identity,
scoped settings/history/files, stale service rejection and persisted-selection restart. The
other cases verify failed target allocation restores the old locked state and history, and
scoped ADB rejection happens before authorization. Execution took 183.358 seconds.

Existing instrumentation owner-access adaptations were compiled, not run in this phase. Full
typed replacement, public Profile management SDK, declaration editing, external bundle import,
credential-safe export and management/recovery UI remain outstanding. This evidence does not
establish successful Shizuku/Root execution, full device-suite coverage or sudden power-loss
durability. See [verification](verification.md) for evidence boundaries.

## Public active-declaration editing evidence

Plugin API 65 moves portable definitions, entries, bundle references/declarations and operations
into `plugins/api`, under the existing shared `ai.meteor.kcode.plugin.api` export. Implementation
repositories, activations and native coordinators remain private. Entry serialization retains
omitted-versus-explicit-null meaning and now describes the optional configuration field in its
SDK descriptor. Older external packages require rebuilding against the generated API 65 ABI.

`AgentPluginManager.currentProfile` returns detached committed intent/generation. `editProfile`
compares active identity and generation, freezes incoming operations and compiles against the same
bundle/machine/launch stack as startup. Required package closure, validation, instance bindings,
tree/module rollback and publication use the existing manager transaction. Context operations
support partial inject/intercept/isolate replacement and empty-map clearing. Stable host facades
delegate these commands to the admitted current runtime; stale identity after switching rejects.

The final phase build passed 141 tests: 41 SDK, 33 Profile compiler/repository/preparation and
67 related desktop runtime/native/package tests. Desktop app compilation, Android app assembly
and platform instrumentation APK assembly passed against Cordis `f08918d`. New scenarios cover
wire null/default distinctions and descriptor fields, context layer provenance, grouped multi-instance
editing, configuration/context changes, group removal, unaffected providers, detached collections,
stale/no-op edits, invalid configuration, refused publication and successful retry. The real JAR
fixture verifies Profile DTO identity is shared with the host SDK while its implementation is private.

The final API 65 instrumentation APK was installed and all three `AndroidProfileHostTest` cases
passed on the ARM64 API 36 physical device (`OK (3 tests)`, 191.826 seconds). This revalidates
actual APK-provider switching, scoped data/App/Ubuntu workspace IO, stale references, restart,
failed allocation recovery and pre-authorization ADB rejection against the new SDK. The new
active-edit commands are behavior-tested on Desktop/common runtime and compiled on Android;
these device cases do not independently exercise the SDK editing command or prove privileged
authorization. All 141 desktop tests reported zero failures/errors and zero skipped cases.

This phase exposes active declaration editing, not complete Profile catalogue/draft/history
management. Public preview and historical activation, external Bundle import, credential-safe
export, typed host-module replacement and management/recovery UI still require implementation
and their own validation. Final whole-suite and platform evidence remain necessary for completion.

## Draft, preview and historical activation evidence

Plugin API 66 adds catalogue/draft reads, revision-checked draft creation/update, cloning,
deletion, preview, history and explicit committed/draft/historical activation. New shared DTOs
remain under the existing exported SDK namespace; external packages must rebuild against the
generated API 66 ABI. Preview reuses native preparation without allocating providers or
publishing metadata. Its package verification flag does not prove runtime configuration
validation, provider allocation or service readiness. Clones retain frozen bundle/code intent
while defaulting to independent business data scopes. Applying a historical recipe appends a
new generation rather than rewinding the committed head.

Drafts now use immutable documents and atomic authority pointers. Format 1 authorities and
legacy profile.json drafts remain readable; successful mutation publishes format 2 authority.
Tests cover stale writes, failed pointer publication after staging, readonly legacy migration,
frozen clone intent, structural preview diagnostics and historical append semantics. Native
host coverage checks preview does not allocate, active deletion is refused, failed draft
activation restores the committed runtime and deletion preserves business data.

The phase build passed 147 tests: 42 SDK, 37 Profile compiler/repository/preparation and 68
related desktop runtime/native/package tests, with zero failures/errors/skipped cases. Desktop
app compilation, Android app assembly and platform instrumentation APK assembly passed against
Cordis f08918d. The final API 66 instrumentation APK ran all four AndroidProfileHostTest cases
on the ARM64 API 36 physical device: OK (4 tests), 269.597 seconds. The added case exercises
SDK clone/preview, draft activation and historical restoration using actual APK providers and
preserved workspace files. Existing cases continue to cover scoped settings/history/workspace,
actual Ubuntu workspace execution, stale references, selection restart, failed allocation
reconstruction and rejection of scoped ADB before authorization.

This is focused phase evidence. Public management UI, independent recovery UI, verified Bundle
import, credential-safe portable export and full typed host-module replacement remain pending.
It does not establish full device-suite coverage, real Root/Shizuku authorization, sudden
power-loss durability or completion of the overall Profile goal.

## Host recovery commands and retained owner cleanup evidence

The stable native manager now admits host-owned metadata commands in RecoveryRequired even
when no product runtime remains. Catalogue activeProfileId is null in that state; durable
selection and history do not change merely because restoration failed. Explicit SDK activation
uses the same prepared generation publisher to recover committed, draft or historical intent.
The host's recoverTo(id) command supports normal recipe selection independently of the product
tree. These changes use existing API 66 contracts and do not expand the exported plugin ABI.

Old, candidate and restoration runtimes whose closure fails are retained for cleanup retry.
Unresolved closure prevents automatic restoration and new candidate allocation. Recovery first
prepares the target, then retries retained cleanup before allocating; continued failure leaves
RecoveryRequired. Failure or cancellation closes any returned candidate, retains unpublished
authority, and permits a later retry. Cancellation after durable publication retains the new
runtime. Normal agent/composition calls remain closed until successful recovery.

The phase passed 35 focused desktop tests: 14 KcodePluginRuntimeTest, 16 ProfileHostTest and
5 NativeProfileHostTest, with zero failures/errors/skipped cases. Six added cases cover failed
recovery and successful retry, old-owner retirement failure, cancelled allocation, failed candidate
retirement blocking automatic restoration, cancellation at recovery publication and SDK metadata/
draft/preview/activation without a live product runtime. Desktop app compilation and Android app
assembly also passed against Cordis f08918d. The prior phase's four Android device tests are not
new evidence for this recovery path; no device recovery-after-restoration-failure result is claimed.

Independent recovery presentation, initial construction failure recovery, plugin-facing command
submission ownership, full typed module replacement and Bundle import/export remain outstanding.
This phase does not mark the overall Profile goal complete.

## Typed alternate-module runtime transaction evidence

KcodePluginRuntime.replacePlugin(packageId, replacement) supports declarative in-process
alternates. It records a distinct stable module ID for every instance referencing the old
module, without changing instance IDs, configuration, groups, context or enable state. It
uses ordinary Replace operations and the existing candidate composition/tree/application/
generation transaction. Candidate module availability is published only after generation
publication, and failed validation/allocation/publication restores prior bindings and intent.
Cancellation withdraws suspended candidate allocation before restoring the old instance.

Selected code is a declaration reference, not a serialized Kotlin object. Restart without the
selected module rejects before product allocation; supplying the alternate replays retained
configuration and scopes. Same-ID typed overwrite rejects because it cannot record distinct
code selection. Legacy non-Profile replacement retains its existing entry point and behavior.
Borrowed in-process code remains host-authorized code, without claiming archive digest or ABI
verification. The shared Plugin API remains 66; these runtime commands are implementation APIs.

The final phase passed 77 focused desktop tests across runtime, Profile host/compiler integration
and real package transactions, with zero failures/errors/skipped cases. Three added tests cover
grouped multi-instance replacement, independent scopes, disabled/default configuration, retained
unrelated providers, missing selected code on restart, candidate identity conflicts, validation/
allocation/publication rollback and cancellation. Desktop app compilation and Android app
assembly passed against Cordis f08918d. No Android typed-alternate device execution is claimed.

Native factories still require explicit alternate-module catalogue factories and stable host
command exposure to retain these choices across native switching/restart. This runtime phase
does not supply that integration, public management/recovery UI, Bundle import/export or the
overall goal's final validation.

## Native alternate-module catalogue and stable selection evidence

Desktop and Android host/runtime factories now accept a detached moduleFactories map. Each
product allocation produces fresh lazy module definitions, validates keys against descriptor IDs
and rejects collisions with default code, native releases and infrastructure. Alternates are
available code only: shipped Bundle definitions exclude those IDs and startup does not create
implicit alternate instances. Factory functions must keep resource allocation inside provider
apply. Host-supplied code remains borrowed and does not claim verified archive identity.

KcodeProfileHost.selectProfileModule(packageId, moduleId, expected) admits selection through
the current runtime and checks active Profile identity/generation while holding its mutation
boundary. Selection uses the existing typed tree/generation transaction. Native switching,
restoration and restart reconstruct definitions through the same factory catalogue instead of
retaining old runtime mounts. The catalogue must be supplied again on restart; absent selected
code rejects preparation before provider allocation. Runtime and native factory implementation
APIs changed; the shared Plugin API remains 66.

The final phase build passed 79 focused desktop runtime/native/Profile/package tests, with zero
failures/errors/skipped cases. Two added native tests verify factory-map detachment, no implicit
instances, multiple configured instance selection, stale generation rejection, scoped clone/
switching, persisted selection restart, missing selected code, factory ID/default collisions and
failed selection rollback. Desktop app compilation, Android app assembly and the final Android
instrumentation APK assembly passed against Cordis f08918d.

The final instrumentation APK was installed on the ARM64 API 36 physical device and the added
typedAlternateCatalogueRetainsSelectionAndApkDataAcrossSwitchingAndRestart case passed:
OK (1 test), 88.686 seconds. It verifies no implicit alternate instance, stable selection,
stale generation refusal, actual APK filesystem implementation identity, App shell workspace IO,
scoped history isolation, stale filesystem rejection, missing-code preparation refusal and
persisted selection/data restoration with a freshly supplied factory catalogue. Only this new
case ran in this phase; the four earlier device cases retain their separately recorded evidence.
This does not establish privileged Root/Shizuku execution, device recovery after failed
restoration, full instrumentation-suite coverage or sudden power-loss durability.

Plugin-facing catalogue/command services, management and independent recovery UI, initial startup
failure recovery, verified Bundle import and credential-safe export remain outstanding. Focused
phase checks do not replace the final requirement audit and platform validation.

## Host-owned injected management commands (Plugin API 67)

Native Desktop/Android hosts now allocate a ProfileCommandGateway independently of the
replaceable product tree. Every allocation mounts a fresh infrastructure bridge exporting
KcodeProfiles. The neutral SDK exposes metadata/module queries, management state, a bounded
command queue and observable handles for activation, active edits and typed module selection.
Submit detaches request data and accepts synchronously; accepted work survives withdrawal of
the submitting bridge, and cancelling an awaiting observer does not cancel the command.
Withdrawn clients reject new calls. Explicit cancellation after durable publication returns
the command's own committed result, recorded only after product/application publication.
Closing the host cancels and joins running/queued work before releasing runtime resources.
RecoveryRequired retains the host client for metadata and explicit recovery activation.
Starting clients reject requests until host binding; consumers must not block plugin apply.

The final concentrated build passed 164 tests: 42 SDK, 37 Profile and 85 focused Desktop
runtime/native/Profile/package cases, with zero failures/errors/skipped cases. Desktop app
compilation, Android app assembly and instrumentation APK assembly passed against Cordis
f08918d. Six new gateway tests cover accepted activation across observer cancellation and
bridge withdrawal, detached serialized edits, cancelled queued work, queue saturation, host
closure, restoration failure/recovery, and cancellation at activation/edit publication.
The two publication tests also call the active agent to verify the committed implementation.
Those strengthened six tests and the updated instrumentation APK then passed a focused check.
Actual JAR loading checks shared identity for KcodeProfiles and ProfileCommandHandle.

The updated SDK 67 instrumentation APK was installed on the physical ARM64 API 36 device.
injectedManagementCommandsSurviveWithdrawalAndShareSdkIdentityWithApkProviders passed:
OK (1 test), 35.957 seconds. It exercises an injected client, cloning/draft activation,
cancelling an awaiting observer, withdrawal/replacement of the submitting client, module
catalogue queries, actual APK SDK identity and filesystem/App-shell scoped workspace IO.
Only the new case ran in this phase; prior device cases retain their separate evidence.
This does not establish Root/Shizuku authorization, startup failure recovery, device recovery
after failed restoration, full instrumentation-suite coverage or sudden power-loss durability.

Management/recovery UI, initial startup failure recovery, verified Bundle import,
credential-safe export, ordering/move operations and the final requirement/platform audit
remain outstanding. The implementation goal remains active.

## Positioned insertion and tree movement (Plugin API 68)

ProfileOperation.Insert now accepts an optional position, and ProfileOperation.Move records
a stable instance ID, destination group (null selects the root) and optional position.
Positions are evaluated after removal; null appends, negative values count from the end and
out-of-range values are clamped. Legacy insert documents continue to append. The existing
Profile/machine/launch compiler delegates both operations to the shared Cordis composer,
including source diagnostics and field provenance. Unknown parents, non-group destinations
and ancestry cycles reject preparation without modifying committed intent.

Cordis commit 9f1ceac supplies the generic composition and lifecycle changes. Cross-parent
movement withdraws the old branch before destination allocation and recreates its context;
same-parent ordering retains Fibers and resources. Cancellation cleans up loading descendants
before provider withdrawal, including nested moving groups. Failed publication restores the
previous hierarchy and bindings. Its complete core/loader/include/hmr JVM validation passed
132 tests (60/40/21/11), with zero failures, errors or skipped cases.

The concentrated kcode build passed 170 tests: 43 SDK, 39 Profile and 88 focused Desktop
runtime/Profile/package tests, with zero failures, errors or skipped cases. Desktop app
compilation, Android app assembly and instrumentation APK assembly passed against 9f1ceac.
New runtime tests cover context rebinding, committed-intent restart, invalid ancestry,
failed publication rollback and withdrawal/recovery through disabled groups. The actual JAR
fixture verifies moved instances remain active and share the new Move SDK identity.

The SDK 68 instrumentation APK was installed on the physical ARM64 API 36 device. The extended
injectedManagementCommandsSurviveWithdrawalAndShareSdkIdentityWithApkProviders case passed:
OK (1 test), 40.997 seconds, INSTRUMENTATION_CODE -1. It moves the actual APK filesystem provider
into a newly inserted group through the injected host-owned command queue, verifies generation
publication, stale filesystem rejection, stable instance identity, shared Move SDK identity,
and workspace access through the replacement filesystem and App shell. Only this extended
case ran in this phase; previous device evidence remains separate. This does not establish
privileged Root/Shizuku authorization, device recovery after failed restoration, full
instrumentation coverage or sudden power-loss durability.

Management/recovery UI, initial startup failure recovery, verified Bundle import,
credential-safe export, explicit native Bundle catalogues and the final requirement/platform
audit remain outstanding. Ordering and movement are now implemented; the overall goal remains active.

## Explicit shipped Bundle membership

The native distribution now declares the module membership of kcode.base, kcode.agent and
kcode.default-ui explicitly. NativeProfileBundles no longer derives membership from core.*
or provider.ui.* prefixes, and unknown default modules fail preparation with their identities.
This guards distribution changes; it does not restrict the extensible module catalogue or
explicit Profile selections. Alternate factories remain available without implicit instances.
Platform-unavailable members are omitted, and offered catalogue order is preserved within each
layer. Existing Bundle IDs, version 1 membership and frozen committed snapshots remain intact.
No SDK ABI change is required in this phase.

The concentrated validation passed 37 Desktop tests: 3 new Bundle declaration tests, 7 native
Profile host cases, 12 runtime Profile cases and 15 actual package integration cases. All were
executed with zero failures, errors or skipped cases. These cover explicit subset composition,
default UI layer omission, undeclared and invalid identities, empty templates, shipped native
startup/switching/restart, and actual JAR loading. Desktop app compilation and Android app
assembly also passed against Cordis 9f1ceac. This phase did not run Android instrumentation;
earlier physical-device evidence remains recorded separately.

Management/recovery UI, initial startup failure recovery, verified Bundle import,
credential-safe export and the final requirement/platform audit remain outstanding. Explicit
native Bundle membership is now implemented; the overall goal remains active.

## Initial default management surface

The new optional ui-profiles package exports DefaultProfileUiPlugin as the private native
provider.ui.settings.profiles release. A child waits for KcodeProfiles/KcodeUiSlots and
registers one settings section. Its session owns metadata queries and command observers;
withdrawal cancels and joins those calls without cancelling accepted host work. No SDK ABI
change is required. Fresh templates use kcode.default-ui version 2; frozen older generations
retain their previous Bundle content rather than gaining a new instance implicitly.

The initial screen supports catalogue/source selection, creation, cloning, JSON definition
editing, revision-checked draft saving, explicit discard, verified preview, effective trees,
diagnostics, module catalogue, history activation, confirmed deletion and host command state.
Refresh retains unsaved documents and their original authority revision. Confirmation binds
deletion to the selected target/revision. English/Chinese resource defaults follow app language
and allow translation catalogue overrides; spacing/colors use shared design tokens.

The concentrated integration build passed 38 Desktop tests: 3 Bundle, 7 native host, 12 Profile
runtime, 15 package integration and 1 new actual private-JAR UI contribution test. Six initial
session tests passed as part of that build. The first attempt exposed two static fallback tests
missing the new compile-only implementation from their test classpath; the test-only dependency
was corrected without adding that implementation to the production host classpath.

After strengthening deletion confirmation and reading the durable host command stream, the
final focused build passed all 7 session tests and the actual-JAR contribution test, with zero
failures, errors or skipped cases. Desktop compilation and Android app assembly passed again.
The session tests cover stale revisions/retained edits, invalid documents/identity changes,
save-before-preview, explicit discard/history targets, isolated creation/cloning, deletion
selection changes, command-observer withdrawal, pending-query cleanup and unverified activation
refusal. The actual native test covers private package registration, SDK-backed clone/preview,
version 2 selection, withdrawal and fresh contribution recovery.

No desktop/device rendering or Android instrumentation ran for this UI phase. These checks
establish session behavior, registration and build compatibility, not visual acceptance or
actual APK UI resource rendering. Visual tree forms, navigation-away confirmation, rendered
acceptance, independent recovery UI, initial startup failure recovery, verified Bundle import,
credential-safe export and the final requirement/platform audit remain outstanding.

## Structured tree and Bundle draft editing

The ui-profiles settings section now includes forms for plugin/group insertion, parent/position
movement, module replacement, configuration codecs, enable/disable, removal and service scope
editing. Bundle references can be added, removed or reordered, and the name field can rename
the selected Profile. Form values become ordinary SDK operations; the shared compiler remains
the authority for actual composition. Form validation checks identities, available code,
positions, scalar codecs and parent cycles without provider allocation.

Each structured action binds the selected source and authority revision, saves a draft and
reloads the host preview. It cannot overwrite an unsaved raw document or a newer editor state.
Historical intent must be cloned before structured editing. Successful draft publication is
recorded before preview queries, so a query failure does not hide the saved document. Service
scope fields initialize from the effective entry, and field-source inspection uses its preview
origins. Late asynchronous callbacks quietly withdraw through PluginOperationOwner.runIfOpen;
retained synchronous edit/activation references still reject stale calls.

The concentrated validation passed 17 tests: 10 UI session, 6 tree form/compiler and 1 actual
native private-JAR contribution case, with zero failures, errors or skipped cases. New cases
cover nested insertion/reparenting/root return, default versus explicit Unit configuration,
configuration/identity/module/position refusal, replacement identity guards, local/shared
service realms, enable/disable/removal, stale/dirty structured forms, successful save followed
by failed preview, ordered Bundle references and historical editing refusal. The native case
now saves a grouped filesystem move and disable/enable operations in a cloned draft, verifies
updated package-verified previews and durable draft operations, and proves the active Profile
and generation stay unchanged. Desktop compilation and Android app assembly passed against
Cordis 9f1ceac. No public SDK ABI change is required.

This phase did not run desktop/device rendering or Android instrumentation. Visual acceptance,
navigation-away confirmation, independent recovery UI, initial startup failure recovery,
verified Bundle import, credential-safe export and the final requirement/platform audit remain
outstanding. Structured forms are implemented; the overall goal remains active.

## Unsaved navigation phase evidence

Plugin API 69 adds SettingsSection.onLeave within the existing default-UI shared namespace
and BottomSheetOverlay.onDismissAttempt within the existing component exports. The default
shell routes section return, system back and sheet dismissal through the section gate before
starting the exit animation. Withdrawn section snapshots bypass the old gate; provider removal
never asks permission to withdraw. External packages are rebuilt for the generated ABI.

Profile sessions retain a saved-document baseline and capture the document, target, revision
and first navigation destination for confirmation. Continue editing retains input, discard
restores the baseline without a write, and save/leave publishes a revision-checked draft before
calling navigation outside the owned operation. Invalid documents and authority conflicts keep
the prompt and edits. Changed confirmations cannot discard newer input; closure cancels owned
queries and drops pending navigation. Saving a draft does not activate it.

Concentrated validation passed 99 tests: 20 Profile UI session/form, 13 default-UI bridge,
43 SDK and 23 focused native host/private-JAR package tests, with zero failures, errors or
skipped cases. The optional default-UI API desktop test task has no test sources; it is not
counted as a test suite. Desktop application compilation, Android application assembly and
platform instrumentation APK assembly passed against Cordis 9f1ceac in the same build.

The newly built API 69 instrumentation APK passed
AndroidProfileHostTest.injectedManagementCommandsSurviveWithdrawalAndShareSdkIdentityWithApkProviders
on the ARM64 API 36 physical device: OK (1 test), INSTRUMENTATION_CODE -1, 40.896 seconds.
This verifies accepted command completion across provider withdrawal, shared SDK identity with
actual APK implementations, grouped filesystem movement and stale-reference rejection under
the new ABI. It does not render or interact with the unsaved-edit confirmation dialog.

This evidence does not establish rendered confirmation behavior. Desktop/device rendering,
independent recovery UI, failures before initial host construction, verified Bundle import,
credential-safe export, recovery/closure failure auditing and final platform acceptance remain
outstanding. The overall goal remains active.

## Private Android management rendering evidence

The native-window instrumentation case uses the shipped private settings, Profile and theme
APK renderers, with an injected management client and a test replacement for the application
root. It opens the Profile section, edits its definition through Compose semantics, sends a
real system-back key, checks invalid-save retention, switches the app language inside the live
dialog, continues editing, saves/leaves and discards/leaves. Durable draft reads prove saving
precedes return and does not change the active Profile/generation. It waits for enabled editing
after asynchronous metadata loading rather than treating the first visible label as readiness.

Actual rendering exposed MissingResourceException: the shared Compose Android reader looks
up feature string assets through the host context. Profile strings now compile from the existing
feature-owned XML into a private dictionary, following the localization provider's established
pattern. No host APK resource fallback or new shared ABI identity is introduced. App-language
selection and custom translation overrides remain intact. Existing locked generations still
require explicit package upgrades to adopt changed implementation bytes.

Screenshot inspection also exposed a clipped Continue editing action when AlertDialog wrapped
separate confirm/dismiss slots. All three actions now use one measured column; save is a filled
button and other actions use the semantic onSurface color. The device test checks each action's
text bounds against its measured size and captures the actual confirmation window. A busy
save cannot dismiss the confirmation before its durable result; a session test covers that
pending-write boundary. The final screenshot was inspected with all actions visible/readable.

Final concentrated validation passed 23 tests: 15 UI session, 6 tree form/compiler, 1 generated
text-default and 1 actual native private-JAR contribution case, with zero failures, errors or
skipped cases. Desktop compilation, Android application assembly and platform instrumentation
APK assembly passed against Cordis 9f1ceac. AndroidProfileUiRenderingTest passed on the ARM64
API 36 physical device: OK (1 test), INSTRUMENTATION_CODE -1, 47.895 seconds. No public SDK
ABI change is required beyond the existing API 69 boundary.

This establishes the tested Android management/edit-confirmation surface, not full application
navigation, all structured forms, activation from UI, touch/keyboard accessibility, every sheet
dismissal path or desktop rendering. Independent recovery UI, initial startup failure recovery,
verified Bundle import, credential-safe export, closure/restoration failure audit and final
requirement/platform acceptance remain outstanding. The overall goal remains active.

## Provider retirement failure evidence

Cordis a95f74f propagates collected cleanup failures rather than merely logging them.
It attempts every release, preserves the original allocation cause with cleanup failures
suppressed, and prevents update/restart/dependency recovery from allocating another instance
after unsafe retirement. Repeated disposal retains its result without repeating releases.
The final core/loader/include/HMR suites passed 135 tests with zero failures, errors or skips.
An earlier combined run repeated the known IsolationParityTest relocated-provider ACTIVE/PENDING
mismatch; subsequent full-suite execution passed, but this does not establish that the
intermittent isolation settling issue has been fixed. That audit remains outstanding.

The desktop host fixture now injects failure inside the actual provider's collected disposer,
rather than only throwing in a host adapter after successful runtime close. It proves that
switching leaves durable selection/history unchanged, enters RecoveryRequired, rejects agent
calls, attempts independent cleanup, and cannot allocate the target through explicit recovery.
Clearing the injected error does not rerun the failed disposer or certify resource retirement.
Host closure likewise preserves the terminal failure. Adapter failures after successful close
remain separately retryable. Final focused host/runtime validation passed 36 tests (7 native
host, 17 host, 12 runtime), with zero failures, errors or skips, against the rebuilt packages.
Desktop application compilation and Android application assembly passed in the same phase.

This establishes provider cleanup propagation at the host switch boundary. It does not prove
intra-Profile failed-restoration escalation, independent recovery UI, failure handling before
initial host construction, device cleanup-failure behavior, or final platform acceptance.
The overall goal remains active.

## Incomplete active-composition restoration

Cordis a1bd8d0 exposes TreeRestorationException in the existing org.cordis.loader shared
boundary. A successful restoration keeps the original command failure; an incomplete one
reports candidate/publication failure as its cause and retirement/restoration failures as
suppressed exceptions. Rollback retires every tracked entry, including residual entries
outside the current root recipe. Failed retirement prevents restoration allocation over
uncertain resources. Final core/loader/include/HMR validation passed 137 tests without
failures, errors or skips. The previously observed isolation-settling intermittency remains
an outstanding audit; passing this run does not prove it fixed.

Kcode marks incomplete tree/module restoration as a private runtime recovery failure.
Both general Profile edits and the enable-state transaction path stop ordinary runtime
calls and withdraw prepared UI projections. The Host clears its current view, retains the
failed owner for retirement, and enters RecoveryRequired. A concurrent switch that cancels
an edit rechecks the state after joining it and cannot reset an absent/failed runtime to Ready.
Successful restoration after failed publication preserves Ready and the old durable authority.

Final focused host/runtime validation passed 39 tests (7 native host, 20 host, 12 runtime),
with zero failures, errors or skips. The restoration case exercises both general editing and
enable-state editing, checks retained runtime references reject work, and explicitly recovers
to a new target after retiring the failed owner. A controlled publication/cancellation race
proves switching cannot overwrite RecoveryRequired with Ready. The successful-restoration
case proves ordinary publication failure still admits work against the old committed Profile.

Plugin API is now 70 because the shared framework gains the public restoration exception.
PluginHostApiPackages continues exporting the existing org.cordis namespace; no additional
shared namespace is needed. Generated external packages were rebuilt against the matching
SDK/framework fingerprint. SDK allTests passed 91 executions (43 desktop, 24 Android debug,
24 Android release), with zero failures, errors or skips. Desktop application compilation
and Android application assembly passed against the final implementation.

Independent recovery UI, failures before initial host construction, verified Bundle import,
credential-safe export, autonomous-work admission auditing, Android device evidence under
API 70 and final full-platform acceptance remain outstanding. The overall goal remains active.

## Initial Profile failure ownership

The suspending native Profile-host constructors now retain host-owned management after
initial Profile preparation or product allocation fails. Catalogue construction errors still
throw. A failed initial Profile creates no current product view and enters RecoveryRequired;
metadata and accepted activation commands work without a product tree. The desktop test repairs
an unavailable-module draft and activates it through the same host-owned command gateway,
with no failed-startup generation or selection publication. Compatibility runtime constructors
continue closing a failed host and throwing rather than returning an empty runtime to existing
application callers.

Startup cleanup retains a private resource owner when retirement fails. Runtime closure and
startup cleanup explicitly retire Loader entries, including entries whose failed disposer has
already removed its parent effect. They abort in-flight allocation, attempt all retirements,
then release native loading and Context resources. A green parent Context close alone does not
certify those residual entries. Initial incomplete tree restoration remains uncertified even
when its remaining parent cleanup succeeds; explicit recovery cannot overlap that owner.
Partial-startup and failed-edit tests prove retained cleanup failure cannot be forgotten, and
that the disposer is not rerun. The existing successful-restoration recovery case remains green.

Cancellation joins initial allocation cleanup and closes the unbound command gateway. An
additional common-host boundary case cancels after initial creation returns and proves the
created owner is retired before cancellation propagates, without publishing a host.

Final focused validation passed 44 tests (7 native host, 21 host, 12 runtime, 4 startup recovery),
with zero failures, errors or skips. Desktop compilation and Android application assembly passed
against the final implementation and Cordis a1bd8d0. Host-private signatures and failure types
do not change PluginHostApiPackages or exported SDK identities; Plugin API remains 70.

This establishes the recovery Host and command boundary, not an interactive startup experience.
The applications still use the compatibility runtime constructors. Independent recovery UI
and app integration, corrupt authority repair, failure before catalogue construction or during
bundled-package staging, verified Bundle import, credential-safe export, autonomous-work
admission audit, device evidence and full platform acceptance remain outstanding.
The overall goal remains active.

## Committed Profile archive backend

`ProfileArchiveExchange` now exports host-reviewed committed/historical generations with frozen
ordered Bundle layers and exact code archives. Import verifies the container and native code
before rebuilding the target variant/ABI lock. Code IDs, versions, digests and dependencies
remain fixed. It returns isolated portable intent without draft publication or product allocation.
Pure JSON exchange keeps strict source variant/ABI checks. The shared data writer serves both
Bundle and complete Profile containers. Plugin API remains 73; complete archive SDK commands
and native picker actions are not yet wired.

Concentrated validation passed 71 Profile desktop tests and one actual native JAR archive test,
with zero failures, errors or skips. Both applications compiled/assembled and the Android
instrumentation APK assembled. An additional native JAR check repacked a valid outer container
with false source code-version declarations and confirmed refusal. Physical Android execution
of the actual Desktop-exported two-Bundle archive passed one test (`OK (1 test)`, instrumentation
code -1): exact code identities were retained, a distinct Android variant was verified, draft
publication remained separate, source/outer files were removed, and the host started the real
APK and exported frozen intent. Logs: `profile-archive-validation.log`,
`profile-archive-native-identity-validation.log`, `profile-archive-android-validation.log` and
`profile-archive-device-validation.log` (local ignored evidence, not distributed artifacts).

This proves the native backend and this cross-host transfer. Complete archive management/UI
commands, selected-file OS picker acceptance, additional feature export schemas, early startup
recovery and the full execution-admission audit remain outstanding. The overall goal remains active.

## Shipped configuration export declarations

The subsequent API 74 archive-command phase wired neutral SDK commands, owned export leases
and native picker actions (see profile-archives.md); selected-file OS acceptance remains distinct.
All 66 shipped provider releases now own explicit Unit export declarations. Model settings
permit finite minimumTemperature/maximumTemperature JSON numbers in 0..2; subagents permit
integer maxConcurrency in 1..64. The private export dialect supports bounded numbers and
integers without changing SDK 74. Feature ConfigValidator still owns relational/runtime rules.
Unknown codecs/properties, machine bindings and credentials gain no implicit permission.

Concentrated validation passed 74 Profiles desktop tests and two actual native JAR schema
tests, zero failures/errors/skips, plus both application builds and the Android test APK.
Two physical Android schema tests passed (`OK (2 tests)`, instrumentation code -1), including
the full default APK composition with explicit Unit and numeric overrides. Both platforms
preserved layers and authority on export, and rejected unknown fields hidden beneath a later
valid override. Desktop additionally compared complete archive and JSON frozen intent.
Logs: profile-default-export-validation.log and profile-default-export-device-validation.log
(ignored local evidence). This does not establish native selected-file roundtrip acceptance,
all feature JSON configurations, early startup recovery or the full background admission audit.
