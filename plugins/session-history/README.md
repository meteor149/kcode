# History-backed session provider

`provider.sessions.history` consumes `KcodeHistory` and provides `KcodeSessions` / `ConversationSessionFactory`. The shared module owns only the contracts and observable ConversationState model; session projection, restore, pinning, deletion, standalone task results and presentation changes are implemented here.

The provider owns one shared conversation data plane and mutation mutex. Each consumer lease
owns a supervisor and captures the exact execution jobs allocated through its state views. Failed initial reads leave it unready and retryable; mutations publish the projection after the repository write succeeds. Disposable ownership cancels and waits for background operations and tracked generation jobs. Factories and sessions reject calls after disposal. UI disposal releases its lease and joins only its jobs; missing session providers pause the default application UI without rebuilding an implicit implementation.

Consumers share loaded state, message/result publication, pending/floating lists and ID
allocation while retaining independent selection and scope ownership. Opening another
projection does not run crash recovery against a live pending task. Job-slot cleanup is
conditional on the exact job assigned by that lease, so delayed old cleanup cannot clear a
newer job. Background and UI consumers require no presentation bridge to synchronize.

Tests cover restore, standalone results, recent/floating lists, pinning, write failure consistency, provider disposal and dependency behavior. This is the history-backed projection seam; an append-only event log and model-visible context projection remain further session data-plane work.
