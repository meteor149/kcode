# Configure models and search through ADB

The broadcast action is `ai.meteor.kcode.action.CONFIGURE_SETTINGS`, and the receiver is
`ai.meteor.kcode/.AdbSettingsReceiver`. Target the receiver with `-n`, or use
`-p ai.meteor.kcode` to send the action to this application. The receiver retains the
`android.permission.DUMP` permission restriction.

Settings are validated and saved through the current plugin runtime. Open the application
first and enable the target model provider, settings, settings-command, and search-settings
plugins. Broadcasting after `force-stop` does not create the Activity/runtime; start
`MainActivity` first. The receiver waits up to five seconds for asynchronous runtime
publication and returns an error if it is unavailable. It does not bypass plugins to write
MMKV directly.

## PowerShell example

Set the `KCODE_MODEL_API_KEY` and `KCODE_SEARCH_API_KEY` environment variables in the current
PowerShell session first. The following is a complete script: the argument array is created
after checking the environment variables. Do not copy only the final `adb @broadcastArgs` call.

```powershell
$ErrorActionPreference = 'Stop'

if (-not $env:KCODE_MODEL_API_KEY -or -not $env:KCODE_SEARCH_API_KEY) {
    throw 'Set the KCODE_MODEL_API_KEY and KCODE_SEARCH_API_KEY environment variables first'
}

adb shell am start -W -n ai.meteor.kcode/.MainActivity
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to start kcode'
}

$broadcastArgs = @(
    'shell', 'am', 'broadcast'
    '--include-stopped-packages'
    '-a', 'ai.meteor.kcode.action.CONFIGURE_SETTINGS'
    '-n', 'ai.meteor.kcode/.AdbSettingsReceiver'
    '--es', 'model-provider', 'deepseek'
    '--es', 'model', 'deepseek-v4-pro'
    '--es', 'model-api-key', $env:KCODE_MODEL_API_KEY
    '--es', 'temperature', '0.3'
    '--es', 'search-provider', 'exa'
    '--es', 'search-api-key', $env:KCODE_SEARCH_API_KEY
)

$result = adb @broadcastArgs
$adbExitCode = $LASTEXITCODE
$result

if ($adbExitCode -ne 0 -or
    ($result -join "`n") -notmatch 'Broadcast completed: result=-1(?:,|$)') {
    throw 'Failed to save settings; check the broadcast result above'
}
```

On success, the application recreates the Activity and reloads settings. No further
force-stop is needed. Never put real credentials in repository scripts or documentation.

## Parameters

Pass all parameters as string extras with `--es`. Omitted fields retain their current values.

| Extra | Meaning |
| --- | --- |
| `model-provider` | An enabled provider ID. Built-in aliases include `openai`, `azure_openai`, `anthropic`, `google`, `deepseek`, `openrouter`, `bedrock`, `mistral`, `alibaba`, `ollama`, and `glm`. An alias does not imply that a provider is available on the platform. |
| `model` | A model ID in the current provider catalog. When only the provider changes, the first available model is selected if the old model is incompatible. |
| `model-api-key` | Credentials for the selected/current provider; an empty string clears that provider's key. |
| `model-endpoint` | Connection endpoint, such as the address required by Azure OpenAI/Ollama. |
| `model-region`, `model-deployment`, `model-api-version` | Provider connection parameters. |
| `dashscope-region` | `china_mainland`, `singapore`, or `united_states`. |
| `temperature` | A finite number; settings commands accept 0–1. |
| `search-provider` | An ID in the current search catalog; built-ins are `google`, `exa`, and `bright_data`. |
| `search-api-key` | Credentials for the selected/current search provider, which must support API keys. |

The current committed catalog and providers determine provider, model, and connection policy;
there is no static-list fallback. See the [settings-command plugin](../plugins/settings-commands/README.md)
and [host receiver](../apps/androidApp/src/main/kotlin/ai/meteor/kcode/AdbSettingsReceiver.kt)
for implementations.

## Results and troubleshooting

An `adb` exit code of zero means only that the shell command succeeded, not that settings
were saved. Success should include both `result=-1` and `Updated kcode settings: ...`.
Only field names are returned; credentials are not echoed.

| Result or symptom | Check |
| --- | --- |
| `result=0` with no data | Check the action, package, receiver, installed version, and permissions. This does not establish a successful save. |
| `Open kcode before configuring settings` | Start the Activity, wait for the plugin runtime, then send the broadcast. |
| Settings/commands reported as disabled | Restore the required plugins; starting an empty root UI is insufficient. |
| `No settings were supplied` | Confirm that the argument array exists and includes `--es` settings. |
| Unsupported model or provider | Check the current model catalog and confirm that the provider is enabled. |
| Incorrect credential extra type | Use `--es` for credentials, not `--ei` or `--ez`. |

The device regression entry point is `AdbSettingsPluginTest`. It covers `-p`/`-n`, models/keys,
preservation of other keys, provider replacement/revocation, and runtime publication.
See the [verification guide](verification.md) for execution instructions.
