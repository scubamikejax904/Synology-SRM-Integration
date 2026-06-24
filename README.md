# Synology SRM Integration for Hubitat

Track devices on your Synology router's WiFi/network as Hubitat presence + switch devices, monitor router and mesh-node health, watch internet (WAN) status, and drive Safe Access parental controls — all locally, no cloud.

Built and tested against an **RT6600ax + RT2600ac mesh running SRM 1.3.2**. Communicates with the router's local SYNO Web API over HTTPS.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://github.com/dJOS1475/Synology-SRM-Integration/tree/main?tab=GPL-3.0-1-ov-file)

> **Version 1.1.1**

<!-- Add screenshots here, e.g.:
![Device list](docs/devices.png)
![Router device](docs/router.png)
-->

---

## Features

- **Device presence tracking** — pick any device on your network; each becomes a child device with **PresenceSensor + Switch** (on/present = connected, off/not-present = disconnected) for maximum automation compatibility.
  - Discovery picker split into **Currently online / Offline**, offline sorted by last-seen.
  - Randomised (private) MACs flagged ⚠ so you select the stable one.
  - Configurable **grace period** to debounce phones whose WiFi sleeps.
- **Router & mesh health** — optional Router device plus one child per mesh node:
  - Per-node CPU, memory, uptime, model, firmware, **connection type (wired/wireless)**, connected-device count, node status, ethernet ports up.
  - **Internet/WAN** up/down + public IP.
  - **Firmware update available** indicator.
  - **Reboot** (whole router or single node) — opt-in.
- **Parental controls (Safe Access)** — mark a tracked device as a **Child**, link it to a Safe Access profile, and:
  - **Pause/resume** that person's internet from the device.
  - See **screen time today** (minutes online).

---

## Requirements

- A Synology router running **SRM** (developed on 1.3.2; other versions likely work, may vary).
- An **admin** account (SRM has no read-only API role).
- If you use 2FA, set it to **external access only** so local logins from Hubitat aren't challenged.
- **Tip:** for reliable phone/tablet tracking, set **Private Wi-Fi Address** to *Off/Fixed* for your home network on those devices so their MAC stays stable.

---

## Installation

Install the **drivers first**, then the app. The links below are **raw** URLs — in Hubitat you can click **Import**, paste the URL, and Save (no manual copy/paste needed).

### 1. Drivers (Drivers Code → New Driver → Import → paste URL → Save)

| Driver | File |
|---|---|
| Synology SRM Device | [`drivers/SynologySRMDevice.groovy`](https://raw.githubusercontent.com/dJOS1475/Synology-SRM-Integration/refs/heads/main/drivers/SynologySRMDevice.groovy) |
| Synology SRM Router | [`drivers/SynologySRMRouter.groovy`](https://raw.githubusercontent.com/dJOS1475/Synology-SRM-Integration/refs/heads/main/drivers/SynologySRMRouter.groovy) |
| Synology SRM Node | [`drivers/SynologySRMNode.groovy`](https://raw.githubusercontent.com/dJOS1475/Synology-SRM-Integration/refs/heads/main/drivers/SynologySRMNode.groovy) |

### 2. App (Apps Code → New App → Import → paste URL → Save)

| App | File |
|---|---|
| Synology SRM Integration | [`app/SynologySRMIntegration.groovy`](https://raw.githubusercontent.com/dJOS1475/Synology-SRM-Integration/refs/heads/main/app/SynologySRMIntegration.groovy) |

### 3. Configure

1. **Apps → Add User App → Synology SRM Integration**.
2. Enter your router **host, port (default 8001), HTTPS on, and admin credentials**.
3. Open **Select devices to track** and pick devices (online/offline sections).
4. (Optional) Leave **Router health** enabled to get the Router + node devices.
5. (Optional) For a child: open their device → enable **This is a Child** → choose their Safe Access profile.

Devices update on the **poll interval** (default 5 minutes).

---

## Devices & attributes

### Synology SRM Device (per tracked device)

| Attribute | Notes |
|---|---|
| `presence` | present / not present |
| `switch` | on / off (mirrors connectivity) |
| `mac`, `ipAddress`, `hostname` | identity |
| `band`, `signal`, `linkRate`, `ssid`, `connection`, `meshNode` | connection details |
| `lastSeen` | last time seen online |
| `internetAccess` | allowed / paused / n/a *(Child mode)* |
| `screenTime`, `screenTimeMinutes` | time online today *(Child mode)* |

Commands: `pauseInternet`, `resumeInternet` *(Child mode)*, `refresh`. (`on`/`off` are no-ops — connectivity is router-driven.)

### Synology SRM Router

| Attribute | Notes |
|---|---|
| `presence` | router reachable |
| `internet`, `publicIP` | WAN status + public IP |
| `cpu`, `memory`, `uptime`, `firmware`, `model` | primary-node summary |
| `nodeCount`, `nodesOnline` | mesh size / online count |
| `updateAvailable`, `availableVersion` | firmware update indicator |
| `lastPoll` | last successful health poll |

Commands: `refresh`, `reboot` *(requires "Allow Reboot")*.

### Synology SRM Node (per mesh node)

| Attribute | Notes |
|---|---|
| `presence` | node online/offline |
| `model`, `firmware`, `uptime`, `role` | identity |
| `cpu`, `memory` | per-node load |
| `connectionType` | wired / wireless backhaul |
| `connectedDevices`, `nodeStatus`, `portsUp` | health |

Commands: `reboot` *(requires "Allow Reboot")*.

---

## Automation ideas

- **Internet down alert:** trigger on Router `internet = down` → push notification / flash a light.
- **Bedtime:** at 9pm on school nights, `pauseInternet` on a child's device; resume at 7am.
- **Screen-time cap:** if `screenTimeMinutes > 120`, `pauseInternet`.
- **Presence automations:** use the device `presence`/`switch` like any other presence sensor.
- **Firmware reminder:** notify when Router `updateAvailable = yes`.

---

## Notes & limitations

- **Polling-based** (the router can't push), so presence latency ≈ poll interval.
- Safe Access pause is **per profile** (per person) — it covers all of that person's devices at once.
- Cumulative per-device bandwidth isn't exposed by SRM, so it's not included.
- Reboot commands are opt-in; test them deliberately.

---

## Troubleshooting

- **Login times out:** wrong host/port, or Hubitat can't reach the router's admin port. Confirm the URL you use for the SRM admin UI; make sure Hubitat is on the same LAN.
- **Login error 403 / OTP required:** 2FA is being enforced on local logins — set 2FA to *external access only*, or use an admin account without 2FA.
- **A tracked device keeps dropping:** it's probably using a randomised MAC — set Private Wi-Fi Address to *Off/Fixed* and re-select it.
- **Reboot returns an error:** the reboot method may differ on your firmware — open an issue with the response.

---

## Changelog

**v1.1.1** — Changed namespace to `dJOS`.

**v1.1.0** — Added HTML dashboard tiles (`htmlTile`) to every device; added `importUrl` to all components for one-click import/updates in Hubitat.

**v1.0.0** — Initial release: device presence tracking, router & mesh-node health, internet/WAN status, firmware-update indicator, whole-router & per-node reboot, Safe Access internet pause, screen-time reporting.

---

## Credits & license

Created by Derek Osborn. Protocol approach informed by the [aerialls/synology-srm](https://github.com/aerialls/synology-srm) Python client.

License: [GNU General Public License v3.0](https://github.com/dJOS1475/Synology-SRM-Integration/tree/main?tab=GPL-3.0-1-ov-file).
