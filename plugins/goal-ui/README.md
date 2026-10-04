# Goal conversation UI plugins

`provider.ui.chat.goal` contributes the Goal decoration to `uiSlots`.
`consumer.goals.chat-restoration` separately contributes automatic restoration as
a conversation page effect. Both depend on the Goal session and conversation
execution providers. Disabling the decoration removes its UI without disabling
automatic restoration; disabling restoration leaves manual Goal controls available.

The shared page contains a generic contribution host and layout. This module owns
status labels, completion visibility, expanded presentation, measured occupied
height, and pause/resume/clear decisions. Button jobs remain children of their
Compose scopes and are also tracked by the plugin owner. Disposal cancels and
joins unfinished work; completed jobs leave the tracking set, and withdrawn
callbacks stay quiet. Persisted mutations use the Goal session contract.

Desktop tests use the actual generic conversation contribution host to verify
Compose effect/presenter cleanup and stable page state after disable, enable, and
uninstall. Additional tests cover completion visibility and disposal during a
pending Goal mutation. Android loads a real APK that contributes both a decoration
and a page effect, renders them through shared ABI contracts, and withdraws them.

Restoration eligibility and resume flags belong to this module. It requests the
provider's continuation prompt, then submits a generic `ConversationResponseRequest`
to the executor. The Goal provider owns the prompt and termination policy; the
executor has no fixed Goal restoration method or Goal status transitions.
