# kcode H5 container

Local H5 apps opened through `preview_h5_app` receive the same Promise-based API before their own scripts run:

```js
const capabilities = await kcode.capabilities.list()
const location = await kcode.capabilities.invoke('location.current', { accuracy: 'high' })

const compass = await kcode.capabilities.subscribe('sensor.compass', { intervalMs: 100 }, event => {
  console.log(event)
})
await compass.unsubscribe()
```

The API surface is stable across platforms. `list()` is the source of truth for runtime availability; a platform or device may return `available: false` with a reason. Sensitive capabilities require an explicit approval for the current preview session in addition to operating-system permissions.

Implemented Android capabilities include camera capture/pick, current/watched location, compass, orientation, accelerometer, gyroscope, magnetic field, pressure, light, proximity, vibration, flashlight, battery, network state, system settings and audio recording. iOS implements camera capture/pick, current/watched location, compass, orientation, accelerometer, gyroscope, proximity, vibration, battery and system settings. Desktop exposes the identical API and reports mobile hardware capabilities unavailable.

Only the isolated local preview origin may use the Android bridge. External HTTP(S) navigation is handed to the system browser, media output remains under `/workspace`, subscriptions are stopped when the container closes, and returned media is size bounded.
