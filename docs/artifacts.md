# Artifact storage

Kcode keeps artifact metadata and resources inside its private `/workspace/artifacts` tree. The virtual path maps to the platform's application-data workspace and is never resolved outside that root.

```text
/workspace/artifacts/
├── manifest.json
└── resources/
    └── weather/
        ├── index.html
        ├── app.js
        └── styles.css
```

The current manifest schema is version 1. Only `web_app` is supported.

```json
{
  "version": 1,
  "artifacts": [
    {
      "id": "weather",
      "name": "Weather",
      "type": "web_app",
      "directory": "weather",
      "entry_point": "index.html",
      "description": "A local weather dashboard"
    }
  ]
}
```

`directory` and `entry_point` are relative paths. Absolute paths, empty segments, `.` and `..` are rejected. The referenced HTML entry must exist before the artifact appears in the UI. Selecting the artifact opens `/workspace/artifacts/resources/<directory>/<entry_point>` with the existing WebContainer runtime.
