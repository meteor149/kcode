# History-backed session provider

`provider.sessions.history` consumes `KcodeHistory` and provides `KcodeSessions` / `ConversationSessionFactory`. The shared module owns only the contracts and observable ConversationState model; session projection, restore, pinning, deletion, standalone task results and presentation changes are implemented here.

Each projection owns a supervisor and serialized operations. Failed initial reads leave it unready and retryable; mutations publish the projection after the repository write succeeds. Disposable ownership cancels and waits for background operations and tracked generation jobs. Factories and sessions reject calls after disposal. UI disposal releases its projection; missing session providers pause the default application UI without rebuilding an implicit implementation.

Tests cover restore, standalone results, recent/floating lists, pinning, write failure consistency, provider disposal and dependency behavior. This is the history-backed projection seam; an append-only event log and model-visible context projection remain further session data-plane work.
