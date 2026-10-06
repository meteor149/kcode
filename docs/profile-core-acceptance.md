# Profile core acceptance

This is the current implementation-to-evidence map for the core Profile milestone, not
a certification based on historical green logs. The audited kcode baseline is `b3d7d814`
and the Cordis development baseline is `6a9b4e4`. Public plugin ABI is 76. The worktrees
are `meteor/profiles` and `meteor/profile-composition`; main has not been merged.

## Reference and ownership

DeepSeek Harness revision `47f943859bef60e4160492346772ded9b24f765a` supplies the behavioral
reference: `packages/boot/app-boot/src/profile.ts` composes ordered layers over an empty
tree; `vendor/include/src/index.ts` detaches input, indexes inserted rows, replaces whole
fields and warns/skips unmatched targets. Its boot Include and config dump share that
interpreter. Native kcode retains those data semantics with strict activation diagnostics,
verified native packages and explicit host publication rather than Node resolution or
JavaScript config evaluation.

Cordis `include/Composition.kt` owns pure composition; core/loader own contexts, realms,
service suspension, effects and tree lifecycle. kcode `profiles` owns portable policy,
locks and repository authority; `runtime` owns execution admission, product transactions
and committed projections. Native platform modules own verified JAR/APK loading and
platform bindings. Feature plugins own typed defaults, validation and export schemas.

## Requirement map

Paths below are relative to their repository. Test names refer to observable cases;
the existence of a case is not equivalent to a passing current execution.

| Requirement | Implementation | Behavioral evidence to inspect |
| --- | --- | --- |
| Empty-root ordered composition; preview/activation agree | profiles/ProfileCompiler; Cordis include/Composition | ProfileCompilerTest: allSurfacesUseTheSameOrderedComposition, emptyProfilesAndExplicitReplacementAreSupported, positionedInsertAndMoveHonorAllLayerPrecedenceAndPersistedIntent |
| Detached intent, omitted/null and whole-value config | SDK ProfileEntrySerializer; ProfileCompiler; Cordis FieldPatch | ProfileCompilerTest: persistedExplicitNullRemainsDifferentFromOmittedConfiguration; Cordis composition/Include tests |
| Multiple instances, movement and replacement | runtime profile edits; Cordis loader transactions | ProfileRuntimeTest: realJarCreatesTwoInstancesWithDifferentConfigurationsAndCodeOrigins, enableTransactionsWithdrawAndRecoverOnlyTheSelectedInstance; ProfileMoveRuntimeTest; ProfileTypedReplacementTest |
| Replaceable roots; no mandatory product defaults | NativeProfileRuntime and distribution Bundle catalogue | NativeProfileHostTest: alternateCatalogueDoesNotMountDefaultsAndRetainsSelectionAcrossSwitchingAndRestart, shippedFactoryBootsDefaultAndSelectedHeadlessProfilesWithScopedStorage |
| Group contexts and declared dependencies | Cordis contexts/realms; ProfileCompiler context translation | ProfileRuntimeTest: groupsKeepProviderConfigurationAndServiceRealmsIndependent, missingRequiredServicesCommitPendingConsumers; Cordis isolation parity tests |
| Preflight without provider effects; accurate locks | ProfileResolver, prepareProfileActivation, verified package catalogue | ProfileActivationPreparationTest: selectedPackagesResolveWithTheirDependencyClosureAndKeepEntryIdentity; ProfileRuntimeTest: allConfigurationsAreValidatedBeforeAnyProviderAllocation; archive/package tests |
| Settings/history/workspace scope isolation and cloning | ProfileDataScope and native scope binding | NativeProfileHostTest: nativeFacadesSwitchScopesAndRestartTheSavedSelection, sdkManagesDraftsPreviewsHistoryAndNativeActivationWithoutCopyingBusinessData; AndroidProfileHostTest |
| One durable authority; migration, CAS and restart | FileProfileRepository, ProfilePreparedSession and bootstrap | FileProfileRepositoryTest: competingSwitchPublicationsSelectExactlyOneCompleteGeneration, unpublishedGenerationFilesNeverBecomeRecoveryCandidates; ProfileActivationPreparationTest: bootstrapMigratesOnceAndPrefersLastCommitOverBrokenDrafts |
| Failure rollback, cancellation and failed retirement | KcodeProfileHost and runtime/module/tree transactions | ProfileHostTest: refusedPublicationRestoresOldRuntimeAndKeepsSelectionAndHistory, providerCleanupFailureClosesAdmissionAndCannotAllocateThroughRecovery; ProfilePackageTransactionTest; NativeProfileExecutionAdmissionTest |
| Execution closed before preparation/publication; stale references | ProductExecutionAdmission; standard agent, generation, conversation, Goal, Schedule and subagent providers | ProductExecutionAdmissionTest; NativeProfileExecutionAdmissionTest; OwnedSubagentFactoryTest; conversation/Goal/Schedule admission and private loading tests |
| Portable intent/archive exchange with reviewed config | ProfilePortableExport, ProfileArchiveTransport, Bundle/Profile archive preparation and release-owned export schemas | ProfilePortableImportTest, ProfilePortableExportTest, ProfileArchiveExchangeTest, ProfileArchiveImportTest; NativeProfileBundleArchiveTest, NativeProfileExportSchemaTest; Android archive/schema cases |
| Host management and explicit recovery independent of product | ProfileCommandGateway, native recovery UI, repository repair | ProfileCommandGatewayTest; NativeProfileStartupRecoveryTest, NativeProfileRepositoryRecoveryTest; FileProfileRecoveryTest |
| Shared SDK versus private implementation/resource identity | PluginHostApiPackages and platform deployment graph | Full platform-desktop test suite using real JARs; AndroidSubagentProviderPrivateLoadingTest and existing native APK package/host tests |

## Current validation status

The integrated kcode run uses `allTests :plugins:platform-desktop:test
:apps:desktopApp:compileKotlin :apps:androidApp:assembleDebug` with the Cordis source
worktree. The first log, `profile-core-integrated-validation.log`, finished with 222 desktop
platform cases and two failed legacy expectations: active subagents must be joined before
disable, and a closing runtime rejects diagnostics that access live services. Those cases
now retain lifecycle coverage with the current contract. The final run in
`profile-core-integrated-final-validation.log` passed all 222 desktop platform cases,
zero failures/errors/skips, and both app targets. It finished successfully in 4m 47s
with 3,373 tasks (254 executed, 3,119 up-to-date).
Multiplatform XML records 1,135 cases in 279 suites with zero failures/errors. The separate
full Cordis JVM/packager run passed 182 cases in 33 suites, zero failures/errors/skips,
in `profile-core-cordis-integrated-validation.log`. Its 39 tasks executed successfully.

The latest focused subagent phase passed 15 shared/desktop subagent cases and five native
desktop cases, plus both application builds. Android instrumentation APK assembly passed.
The API 35 emulator's first run timed out during native catalogue initialization; its
second run failed startup with ANR before test entry. System package dex precompilation
succeeded, and the third run entered the test but timed out at 300 seconds with only a
waiting runBlocking stack. The exact suspended phase remains undiagnosed. None is passing
APK execution evidence. Logs: `profile-subagent-admission-emulator-final-validation.log`,
`profile-subagent-admission-emulator-exit.log`,
`profile-subagent-admission-emulator-dex-compile.log` and
`profile-subagent-admission-emulator-compiled-validation.log`. Do not infer an environment-only
cause from the startup ANR or count the timeout as a passing lifecycle test. No source
assertion or package-identity requirement is waived; current Android acceptance is open.

The core milestone includes minimal commands and independent recovery. Rich tree editors,
full desktop native-dialog gestures, YAML, marketplace and hot-reload are deferred UI/product
work. Their historical evidence gaps remain explicit and are not counted as passed. Device
fixtures do not establish root, Shizuku authorization, model network calls or universal
coordination of arbitrary external code: autonomous providers must honor ExecutionAdmission.
