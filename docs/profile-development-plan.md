# Profile development specification

This document is the implementation and acceptance brief for an agent developing kcode
Profiles. It specifies the target architecture; it does not certify that existing branches
have completed it. Topic documentation is in English; the accompanying review discussion
may be in Chinese.

## 1. Inspect and reuse existing work

Source inspection on 2026-10-06 found these revisions:

| Repository | Main checkout | Existing Profile development checkout |
| --- | --- | --- |
| DeepSeek Harness | `47f943859bef60e4160492346772ded9b24f765a` | Reference only |
| cordis-kotlin | `d359bacc4b95d40ef46cd3f4b4b7cc2140bc919c` | `C:/Users/meteor/workspace/source/cordis-kotlin-profiles`, branch `meteor/profile-composition`, revision `6a9b4e4` |
| kcode | `2f36402229806582bb9b93aa1557640fee4ceb0d` | `C:/Users/meteor/.codex/worktrees/kcode-profiles/kcode`, branch `meteor/profiles`, revision `17c1c70c8785cadc9c5b86bcf281643027fae750` |

Latest planning inspection found five uncommitted files in the kcode development worktree:
three acceptance/implementation/verification documents and the Android Profile host and
private subagent loading test fixtures. Cordis remained at `6a9b4e4` with a clean worktree.
Preserve and inspect these changes before staging or building. The development checkout's
`docs/profile-core-acceptance.md` records substantial implementation and validation evidence;
inspect that evidence before deciding what remains. This brief does not independently certify
the recorded runs, and the table above is an observation rather than an execution lock.

Start with `git status`, `git worktree list`, applicable AGENTS.md files, and the diff from
main in both repositories. Prefer continuing the existing suitable worktrees; preserve
uncommitted user changes. Recheck revisions because these are observations, not fixed inputs.
Do not implement a second competing Profile subsystem from main.

The kcode development worktree already contains `docs/profiles.md`,
`docs/profiles-implementation.md`, architecture/development guides, Profile SDK contracts,
compiler/repository, runtime host, management UI and native integration tests. Read them
before changing interfaces. Main currently lacks the two architecture guides referenced by
its AGENTS.md; consult the development checkout rather than inventing their contents.

Its implementation history explicitly leaves some acceptance work outstanding. Treat that
history as prior evidence to inspect, not as a claim that this specification has passed.
Resolve documented gaps against current code and tests; do not infer gaps solely from older
entries in the chronological history. Read CurrentPluginApiVersion in the selected checkout:
main has API 63 and the Profile branch has later ABI revisions.

## 2. Target behavior and reference semantics

The first delivery is a usable core: deterministic arbitrary plugin composition, exact code
resolution, isolated data scopes, transactional activation/rollback, restart recovery, and
minimal commands for draft/preview/activation/import/export. Complete this delivery before
expanding the editor or polishing native dialogs. "Arbitrary composition" means instances
obey declared service dependencies and platform/ABI constraints; it does not guarantee that
every incompatible plugin combination becomes runnable.

A Profile is portable composition intent. It selects an ordered sequence of data Bundles,
then applies user operations to produce a Cordis entry tree. It is neither an agent persona
nor a collection of on/off flags over an unavoidable default product.

The composition order is fixed:

`empty tree -> ordered Bundles -> Profile operations -> machine overrides -> launch overrides`

Machine and launch overrides remain separate inputs; do not bake them into an exported
Profile. An empty Bundle list is valid. Native hosts must not silently restore default UI,
model providers or tools when those entries are absent.

Use these DeepSeek Harness sources as the reference:

- `packages/boot/app-boot/src/profile.ts`: manifest, ordered Bundle discovery, installation
  versus Profile resolution, initialization and `composeEntries` over an empty tree.
- `vendor/include/src/index.ts`: `applyEntryPatches`, detached inputs, whole-field replacement,
  recursive groups and indexing inserts so later patches can target earlier inserted rows.
- `packages/boot/app-boot/src/index.ts`: root Include ownership, the same composition for
  mounting/config dumps, and transactionally reapplied user layers.
- `packages/boot/app-boot/tests/profile.spec.ts` and `tests/user-patches.spec.ts`: behavioral
  reference cases; `packages/bundle/*/cordis.patch.yml`: product composition examples.

Preserve these semantics, not Node-specific mechanisms. Do not port pnpm, node_modules
symlinks, package.json resolution or arbitrary JavaScript evaluation to native Kotlin.
Use existing verified `.kplugin` packages and declarative JSON. YAML can be a later adapter
over the same data model; it must not become a second composition implementation.

Config replacement replaces the complete value, not a deep merge. Distinguish omitted
configuration (use the feature's default) from explicit JSON null. Preserve scalar types,
arrays, nested objects and the existing Unit/default codec distinction through persistence.

Harness warns and skips unmatched patch targets. Preserve that behavior in the generic
compatibility adapter, but kcode activation should reject destructive or ambiguous unresolved
operations through an explicit strict validation policy. Diagnostics must retain layer,
operation and target provenance. Document deliberate differences; do not silently change
reference behavior.

## 3. Ownership boundary

| Responsibility | Owner |
| --- | --- |
| Context, services, inject/intercept/isolate, Effect/Fiber disposal | Cordis core |
| Entry tree, groups, movement, field updates, module exports and lifecycle settlement | Cordis loader |
| Pure patch composition, detached snapshots, structural diagnostics, JSON/YAML adapters | Cordis include/composition |
| Generic package manifests, variants, dependency graphs, digest/archive validation and packaging tasks | Existing Cordis packages/packager |
| Profile/Bundle contracts and management commands shared with external plugins | kcode `plugins/api` |
| Profile compilation, Bundle resolution policy, locks, revisions, repository and portable exchange | kcode `plugins/profiles` |
| Execution admission, product host, tree/module transactions, committed frames and recovery state | kcode `plugins/runtime` |
| Existing installation metadata and migration inputs | kcode `plugins/installation-store` |
| Verified code catalogue and deployment integration | kcode `plugins/package-provider` and native platform plugins |
| Shipped declarative product Bundles/templates | kcode `plugins/bundle-native` |
| Optional Profile settings/editor contribution | kcode `plugins/ui-profiles` |
| Independent native startup recovery surface | kcode `plugins/profile-recovery-ui` and native apps |
| Domain defaults, config codecs/validation, exportable fields and optional settings forms | Owning feature plugin |

Cordis must not know about kcode Profiles, conversation history, agent cancellation, UI roots,
AppSettingsStore, credentials or the active product. A generic tree transaction can restore
Cordis resources; the application transaction coordinates its own durable publication and
tasks. Do not introduce a global framework ProfileManager or duplicate native package loaders.

Native apps select a Profile and provide platform primitives. They do not import concrete
feature providers to reconstruct a default product. Product consumers use exported contracts
and inject; optional UI consumers may also depend on default-ui-api.

## 4. Data model and identities

Reuse the Profile branch's existing names and serialized contracts where they fit this
specification. Conceptually separate these documents:

1. **Definition:** format version, stable ID/display name, ordered Bundle references, operations
   and logical data scopes. Portable; no paths, Kotlin objects, callbacks or credentials.
2. **Bundle:** versioned data operations and declared code requirements. Reading a Bundle
   never executes its plugins. A Bundle may ship ordinary glue plugins referenced by its data.
3. **Resolved lock:** exact code package IDs/versions/digests, selected platform variants,
   SDK/framework ABI identity and dependency closure. Resolve before provider allocation.
4. **Committed generation:** frozen definition/layers, effective recipe/lock and generation
   identity sufficient for deterministic restart and historical recovery.
5. **Repository authority:** revision, Profile/draft/history references and selected generation,
   published through one atomic authority boundary with compare-and-set.

Package ID identifies code availability; entry ID identifies one configured instance. Multiple
entries can reference the same verified code release but own independent config, contexts and
effects. Bundle ID identifies a composition layer. Do not conflate these identities.
Dependencies needed for code loading do not automatically create product instances.

Support Insert, Configure, Enable, Disable, Replace, Remove, Move and context configuration.
Structural validation covers stable IDs, duplicates, nonexistent parents/targets, group shape,
cycles, illegal moves and conflicting package requirements. Group context semantics must come
from Cordis. Avoid a parallel kcode service-isolation implementation.

Pure compilation produces the effective tree, resolved requirements, diagnostics and source
provenance without Context construction, provider apply, UI mounting or filesystem mutation.
Preview and activation consume the same compiled representation. State explicitly when a
preview is structural only: missing injected services can legitimately leave consumers pending;
they must not be replaced with placeholder providers or silently declared ready.

## 5. Persistence, configuration and portability

Use a single authoritative repository publication, not separately written active-pointer,
recipe and lock files that can disagree after a crash. Stage immutable objects first, publish
their references atomically, and leave orphan cleanup outside the commit path. Use bounded
strict UTF-8 decoding, schema/version checks and cross-writer revision checks. The existing
development repository's authority/checkpoint design should be reused if it meets these rules.

Failed publication preserves the previous authority. Corrupt authority opens explicit recovery;
do not silently adopt orphan generations, clear user data or select an arbitrary Profile.
Offer validated checkpoint restoration or explicit empty-authority repair with preserved evidence.

Keep settings/history/workspace scopes explicit. Clone defaults to independent Profile-owned
settings/history; sharing requires an explicit logical scope. Switching Profile does not erase
data or migrate repository-provider data automatically. Resolve scope IDs inside host-owned
roots rather than accepting imported absolute paths. Credentials stay in host-managed stores.

Migrate legacy plugin-installations metadata once, transactionally and idempotently into a
compatible default Profile, retaining enable states and historical migration inputs. Do not
keep the old store and new authority as competing writers after migration. Maintain existing
manager behavior through a documented translation layer rather than bypassing AgentPluginManager.

Features decode JSON to their typed configuration and run ConfigValidator before apply.
Typed deployment bindings and schema/export reviews must come from the verified release.
Portable export permits only explicitly reviewed fields/codecs, including earlier overridden
layers; flattening the final tree must not conceal secrets in the original recipe.

Provide distinct JSON intent exchange, ordered Bundle archive import, and self-contained
Profile archive exchange. Freeze Bundle layers so restart does not require source archives.
Verify every embedded code archive. Cross-platform import preserves exact package versions
and digests while selecting/verifying a compatible included target variant; it must not silently
upgrade code to make an import work. A JSON-only lock can be platform-specific and reject a
mismatch. Document that difference. Reuse Cordis archive validation and packaging machinery.

## 6. Execution and activation transaction

Use a host outside the replaceable product tree to own repository access, activation commands,
recovery and the current product. UI callbacks submit host-owned commands and observe their
results; they do not unload their own provider while its operation is still running.

Use explicit host states such as Ready, Preparing, Switching, RecoveryRequired and Closed.
Reject stale command clients and serialize composition changes. Admission belongs to the
runtime, not the management UI. All standard producers must enter it **before** reading or
writing execution state: chat send/regenerate, command preparation/startResponse, schedules,
Goal restoration/actions, agent/subagent generation and supported external execution services.
Account for structured child jobs and cancellation cleanup, not only visible chat turns.

For cross-Profile activation use a complete product restart as the correctness-first default:

1. Capture expected revision/generation and the previous committed checkpoint.
2. Close new execution admission. Reject active work unless explicit cancellation was requested;
   when requested, cancel and join owned work before releasing provider resources.
3. Prepare/verify/freeze the candidate recipe and packages without provider effects.
4. Retire the old product, then allocate and settle the candidate with execution still closed.
   Do not run two product trees concurrently against exclusive platform/data resources.
5. Prepare the application frame and catalogues; validate required root readiness without
   demanding that every intentionally optional consumer activate.
6. Atomically publish the candidate generation/selection under the captured revision.
7. Publish the current view, then open execution admission. Merely resuming a gate must not
   admit work before durable host publication.

On preparation failure retain/resume the previous certified product. After retirement, rebuild
the previous committed product on candidate allocation or publication failure. Release candidate
resources and join cleanup before reopening old execution. If restoration/retirement cannot be
certified, enter RecoveryRequired, retain the failed owner for cleanup and keep admission closed.
Do not falsely report Ready because a parent Context disposer returned successfully.

Cancellation uses the same restoration path with uncancellable owned cleanup. Test races around
preparation, allocation, publication, frame installation and host closure. Whole-product rollback
restores composition/resources; it cannot undo arbitrary network or durable side effects from
plugin apply. Providers must defer execution-producing startup work until publication and own
any allocation side effects that can be rolled back.

Keep same-Profile manager edits coordinated through existing tree/module transactions. Preserve
compatible unchanged instances where established behavior requires it. Native code replacement
must borrow/import the right generation and retain rollback code until tree restoration ends.

## 7. User surface and recovery

The optional default UI supports list/create/clone/rename, ordered Bundles, tree operations,
config editing, preview/diagnostics, save draft, activation, history and import/export. Draft
save/import never implicitly activates. Make active/draft/historical states explicit; avoid
silently discarding a dirty editor. Persist changes through commands, not UI repository calls.

Recovery is available even when the product root, localization, settings, catalogue preparation
or repository startup fails. It is a host surface with its own resource/text defaults, not a
placeholder service in the product. Alternative UI roots must not require default UI packages.
Missing optional UI providers may remove contributions without crashing the host.

Verify actual desktop windows/file dialogs and Android document pickers with selected files,
success, cancellation and withdrawal. Backend sessions or offscreen rendering do not establish
native dialog acceptance. Android document-provider writes do not imply atomic rollback.
These are full native-UI acceptance items. They do not block the core delivery if the command
API and file exchange are verified, the minimal recovery surface works, and the unverified
native-UI behavior is explicitly deferred rather than reported as passed.

## 8. Implementation phases and acceptance

First build a requirement-to-code-to-test evidence matrix from the existing development branches.
Mark each row implemented, verified, incomplete or intentionally deferred, with current file/test
references. Continue incomplete phases; do not redo completed implementation for a new plan.

| Phase | Deliverable | Required observable acceptance |
| --- | --- | --- |
| A | Cordis pure composition and Include delegation | No input aliasing; inserted rows targetable later; field/null/type semantics; nested groups/context; deterministic repeated compile; documented warning behavior |
| B | kcode SDK/compiler/repository/locks | Empty-root composition; alternate/no-default-UI roots; multiple instances; scope isolation; strict diagnostics; crash/publication/revision failures; migration/restart |
| C | Runtime host and native manager integration | Active-work admission; cancellation joins; startup/candidate/publication/restore failure; stale callbacks/clients; real JAR/APK replacement and shared/private type identity |
| D | Default Bundles and portable exchange | Declarative default product; frozen layers; deterministic archive tooling; exact code locks; compatible cross-platform import; reviewed configs with no credential leakage |
| E | Minimal management and independent recovery UI | Draft/preview/apply separation; broken root/catalogue/authority recovery; commands remain usable after product withdrawal. Rich editor and native selected-file gestures are later acceptance |
| F | Final audit and integration readiness | Requirement matrix complete; appropriate full builds/tests; ABI/docs aligned; remaining limitations explicit; final source revisions recorded |

For core completion, prioritize the execution-producer/stale-reference audit and final integrated
acceptance. Confirm each gap against current code before declaring it missing. Desktop native
UI/dialog polish and additional feature export schemas are deferred unless required to export
the chosen core acceptance Profiles. Unsupported config export must fail clearly rather than
silently drop data. In particular test synchronous generation-launch failure after transcript
preparation, rejected/cancelled Goal restoration retaining resume intent, and cleanup that holds
admission until child work ends. These are audit cases, not confirmed defects from this review.

Use Kotlin test suites appropriate to each change. Required lifecycle coverage includes cancellation,
stale references, withdrawal/recovery, replacement and failed persistence commits. Native package
acceptance uses actual private JAR/APK packages, including independent resources; unit tests do not
substitute for class-loader evidence. Device tests do not establish Shizuku/root authorization.

Windows uses JDK 21 and gradlew.bat. Integrate Cordis with
`-PcordisSource=C:/Users/meteor/workspace/source/cordis-kotlin-profiles`. Inspect settings.gradle.kts
before selecting tasks. Relevant targets include Cordis loader/include JVM tests, kcode api allTests,
Profile/UI desktop tests, platform-desktop tests, desktop app compilation, Android app assembly,
and platform-android instrumentation on API 35+. Run relevant focused checks per phase; run the
broader integrated checks after the final related changes. Do not repeatedly rerun unchanged
expensive checks without a new failure, change or unresolved concern.

Review exported SDK/framework identities and increment CurrentPluginApiVersion for public ABI
changes; regenerate packages and validate both version and actual binary compatibility. Keep
README.md/README.zh-CN.md aligned, English topic guides indexed through docs/README.md, and
feature entry/configuration docs current. Reserved Harness contracts receive no fake providers.

Commit reviewable phase-sized changes with concise Conventional Commit messages, recording
validation and evidence limits. The planning request authorizes preparing this brief, not pushing,
merging, publishing releases or claiming development is complete. Implementation agents should
follow the human's subsequent execution authorization and repository contribution instructions.

## 9. Core acceptance matrix

The implementation agent must map these cases to current source and executable tests. Existing
tests count when they exercise the current code; do not duplicate them simply to match this list.

| Case | Required result |
| --- | --- |
| Empty Profile | No implicit default product; host management/recovery remains accessible |
| Ordered Bundles and overrides | Later layers override earlier layers; earlier inserts are targetable; preview equals activation input |
| Multiple instances of one package | Independent configs/effects; removing one leaves the other intact |
| Alternative product | Default UI/model/tool providers can be omitted or replaced through contracts |
| Context groups | Nested inject/intercept/isolate use Cordis behavior; missing providers leave dependent consumers pending |
| Invalid intent/config/package | Clear layer/target diagnostics before old product withdrawal; no provider effects during preview |
| Active chat, Goal, schedule or subagent | Change rejected by default; explicit cancellation joins task descendants and cleanup before replacement |
| Candidate or durable publication failure | Previous committed composition/selection restored, or explicit closed RecoveryRequired if restoration fails |
| Restart and competing writer | Certified generation restored; stale revision rejected; corrupt authority requires explicit recovery |
| Data scopes and clone | Default private settings/history; explicit sharing only; switching/uninstalling does not erase durable data |
| Import/export | Draft-only import; reproducible intent and supported archives; no host paths/credentials; incompatible locks rejected |
| Native code boundaries | Real desktop JAR and API 35+ Android APK/dex preserve shared SDK identity and private implementation/resources |

Core completion requires all rows to be verified or an explicitly agreed scope exception.
An unavailable device is an evidence gap, not a passing native-package test. Rich tree-editor
interactions, native-dialog polish, YAML, package marketplace and automatic hot-reload are
outside this core milestone. Record them separately without repeatedly extending core work.

## 10. Ready-to-use implementation instruction

The following instruction can be passed to the implementation agent with this document:

> Implement kcode Profiles using this brief. Start by inspecting the existing
> `meteor/profile-composition` Cordis worktree and `meteor/profiles` kcode worktree, their
> uncommitted changes, AGENTS.md instructions, and current tests. Produce a compact
> requirement/code/test matrix, then continue only missing or defective core behavior.
> Keep reusable composition and loader behavior in cordis-kotlin; keep Profile policy,
> persistence, execution admission and native product transactions in kcode. Reuse one
> compiler for preview and activation. Preserve empty-root composition and replaceable
> product providers. Use staged worktree commits and focused validation after coherent
> changes; run integrated validation at the final core milestone. Do not rerun unchanged
> expensive suites after every small edit. Prioritize core cases in section 9 and defer
> peripheral UI/editor/dialog work. At delivery, report commits, implementation locations,
> test evidence, and remaining limitations. Do not claim unrun tests passed. Do not push,
> merge or publish without subsequent human authorization.
