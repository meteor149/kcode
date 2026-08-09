# kcode H5 container

Local H5 apps opened through `preview_h5_app` use standard Web APIs. Container-specific transport is injected before page scripts and is not part of the public application API:

```js
const position = await new Promise((resolve, reject) => {
  navigator.geolocation.getCurrentPosition(resolve, reject, { enableHighAccuracy: true })
})

const accelerometer = new Accelerometer({ frequency: 10 })
accelerometer.addEventListener('reading', () => {
  console.log(accelerometer.x, accelerometer.y, accelerometer.z)
})
accelerometer.start()
```

The container leaves an existing browser implementation untouched whenever possible. When the embedded engine lacks Geolocation, Battery Status, Vibration, Accelerometer, Gyroscope, Magnetometer or Orientation Sensor support, the injected compatibility layer exposes the corresponding standard interface backed by native services. Permission denial from a browser implementation is never bypassed by a native fallback.

Camera, microphone, `MediaRecorder`, `MediaStreamTrack` constraints and HTML file input remain browser-owned because their standard objects cannot be accurately recreated from a native file result. Android connects WebView media permission, geolocation permission and file chooser callbacks to the host. iOS delegates media and motion permission decisions back to WebKit. Desktop and browser previews use their browser implementations directly.

Only the isolated local preview origin may reach the Android bridge. External HTTP(S) navigation is handed to the system browser, and native fallback subscriptions are stopped when the container closes.

## Agent lifecycle tools

Every preview receives a stable container ID and reports whether it is in the `foreground` or `background`. The agent can use `list_h5_containers` to inspect running previews, `set_h5_container_state` to switch visibility without stopping the app, `screenshot_h5_container` to feed the current rendered viewport back to a vision-capable model as a PNG tool-result attachment, and `close_h5_container` to stop a selected preview. Android and iOS retain their WebView while returning to kcode; the browser target hides its isolated overlay; desktop minimizes or restores its managed Chromium app window through the DevTools protocol.

The preview navigation bar also exposes a manual background action. It uses the same lifecycle path as `set_h5_container_state`, so the page remains running and can later be restored by the agent instead of being closed.

While one or more previews report the `background` state, kcode shows an in-app floating H5 dock. Its collapsed form displays the number of background previews; expanding it lists each title and entry path. Selecting a row restores that preview to the foreground, while the row's close action stops only that container.

The debugging tools support an agent-driven edit/run/debug loop without exposing arbitrary script evaluation. `inspect_h5_container` reports visible interactive elements with stable handles and viewport bounds. `interact_h5_container` can click, replace input text, scroll, dispatch keys, reload, or navigate back. `get_h5_console` returns buffered `console` messages and uncaught page errors using a cursor so callers can request only new output. Interactions are scoped to the selected running container; handles should be refreshed after navigation or substantial DOM replacement.
