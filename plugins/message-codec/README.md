# Message codec provider

`MessageCodecProviderPlugin` supplies `KcodeMessageCodec` with one independently owned
`ChatMessageCodec`. Session readers and conversation writers inject the service definition;
they never construct an envelope implementation or fall back to a host codec.

The default provider preserves the existing v1 structured-message envelope, plain-text history,
unknown status handling and malformed-envelope recovery. It does not change the Room schema.
Each mount owns calls through `PluginOperationOwner`; withdrawal rejects retained codec
references and suspends both readers and writers. No runtime resource belongs to a file singleton.

Actual JAR/APK tests cover private class identity, SDK identity, disable/re-enable, persisted
installation state across restart and explicit restoration of the built-in provider after uninstall.
Format round trips live here; consumer tests inject alternate formats to prove delegation.

## Known Limitations and Deferred Work

This is the current row-content codec, not the reserved Harness session event-log implementation.
Changing formats requires the replacement provider to implement compatibility with stored data.
