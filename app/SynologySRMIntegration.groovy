/**
 *  Synology SRM Integration  (parent app)
 *
 *  Tracks WiFi/network devices on a Synology SRM router (RT6600AX / RT2600AC, SRM 1.3) and
 *  represents each selected device as a Hubitat child device exposing PresenceSensor + Switch.
 *
 *  Data source: SYNO.Core.Network.NSM.Device (method get, version 5) — the connected-device list
 *  (wired + wireless, online + offline). Devices are keyed by MAC.
 *
 *  Requires the companion driver "Synology SRM Device" to be installed.
 *
 *  Setup: install the app, enter the router host/port/admin credentials, then open
 *  "Select devices to track" to discover and pick devices. Tip: set Private Wi-Fi Address to
 *  Off/Fixed on tracked phones/tablets so their MAC is stable (randomised MACs are flagged ⚠).
 *
 *  Author:  Derek Osborn
 *  Version: 1.0.0  (Initial release — 2026-06-17)
 */

import groovy.transform.Field

@Field static final String VERSION    = "1.0.0"
@Field static final String CHILD_NS   = "derekosborn"
@Field static final String CHILD_TYPE = "Synology SRM Device"
@Field static final String NSM_API     = "SYNO.Core.Network.NSM.Device"
@Field static final Integer NSM_VERSION = 5

@Field static final String ROUTER_TYPE = "Synology SRM Router"
@Field static final String ROUTER_DNI  = "SRM-ROUTER"

definition(
    name: "Synology SRM Integration",
    namespace: "derekosborn",
    author: "Derek Osborn",
    description: "Track WiFi/network devices from a Synology SRM Router as presence + switch devices.",
    category: "Convenience",
    iconUrl: "", iconX2Url: "", iconX3Url: "",
    menu: "Integrations", // Valid values are “Integrations”, “Automations”, and “Apps”
)

preferences {
    page(name: "mainPage")
    page(name: "devicePage")
}

/* ---------------- UI ---------------- */

def mainPage() {
    dynamicPage(name: "mainPage", title: "Synology SRM Integration", install: true, uninstall: true) {
        section("Router connection") {
            input "host",     "text",     title: "Router IP / host", required: true
            input "port",     "number",   title: "Port",             defaultValue: 8001, required: true
            input "useHTTPS", "bool",     title: "Use HTTPS",        defaultValue: true
            input "username", "text",     title: "Admin username",   required: true
            input "password", "password", title: "Admin password",   required: true
        }
        section("Devices") {
            href "devicePage", title: "Select devices to track", description: selectedSummary(), state: (getTrackedMacs() ? "complete" : null)
        }
        section("Router health") {
            input "trackRouter",  "bool", title: "Create an SRM Router device (health + per-node status)", defaultValue: true
            input "enableReboot", "bool", title: "Allow the Router device's Reboot command (reboots the whole router — use with care)", defaultValue: false
        }
        section("Parental controls (Safe Access)") {
            input "enableSafeAccess", "bool", title: "Enable Safe Access internet control on Child devices (set per device with 'This is a Child')", defaultValue: true
        }
        section("Polling & behaviour") {
            input "pollInterval", "enum", title: "Poll interval",
                  options: ["1":"1 minute", "5":"5 minutes", "10":"10 minutes", "15":"15 minutes"],
                  defaultValue: "5", required: true
            input "graceCount", "number", title: "Mark a device away only after this many consecutive missed polls",
                  defaultValue: 2, required: true
            input "logEnable",  "bool",   title: "Enable debug logging", defaultValue: true
        }
        section {
            paragraph "<small>Synology SRM Integration v${VERSION}</small>"
        }
    }
}

def devicePage() {
    def opts = discoverDevices()
    def online  = opts.online  ?: []
    def offline = opts.offline ?: []
    // Keep each selection in the section matching the device's current status, so a tracked device
    // that changed online/offline since it was picked isn't pruned on resubmit. Guarded so a failed
    // discovery (both lists empty) never wipes existing selections.
    if (!online.isEmpty() || !offline.isEmpty()) {
        reconcileSelections(macsOf(online), macsOf(offline))
    }
    dynamicPage(name: "devicePage", title: "Select devices to track") {
        if (online.isEmpty() && offline.isEmpty()) {
            section {
                paragraph "No devices found. Check the router host/port/credentials on the previous page, then return here."
            }
        } else {
            section {
                paragraph "⚠ = randomised MAC (may not be stable — prefer a device with Private Wi-Fi Address set to Off/Fixed). Offline devices are sorted by when this app last saw them online (history builds over time)."
            }
            section("Currently online (${online.size()})") {
                input "trackedOnline", "enum", title: "Online devices to track",
                      options: online, multiple: true, required: false
            }
            section("Offline (${offline.size()})") {
                input "trackedOffline", "enum", title: "Offline devices to track",
                      options: offline, multiple: true, required: false
            }
        }
    }
}

// Effective tracked set = the two pickers plus any legacy single-list selection (back-compat).
private List getTrackedMacs() {
    def legacy = (settings.trackedMacs ?: []) as List
    def on     = (settings.trackedOnline ?: []) as List
    def off    = (settings.trackedOffline ?: []) as List
    return (legacy + on + off).unique()
}

private String selectedSummary() {
    int n = getTrackedMacs().size()
    return n ? "${n} device(s) selected — tap to change" : "Tap to discover and select devices"
}

// Extract the MAC keys from an ordered options list ([[mac:label], ...]).
private List macsOf(List opts) { opts.collect { it.keySet().first() } }

// Re-split the tracked set across the two status pickers and fold in any legacy single-list value.
private void reconcileSelections(List onlineMacs, List offlineMacs) {
    def tracked = getTrackedMacs()
    if (!tracked) return
    def onSel  = tracked.findAll { onlineMacs.contains(it) }
    def offSel = tracked.findAll { offlineMacs.contains(it) }
    app.updateSetting("trackedOnline",  [type: "enum", value: onSel])
    app.updateSetting("trackedOffline", [type: "enum", value: offSel])
    // Fold away the legacy single-list setting only once all of its MACs are safely represented
    // in the two pickers (don't drop a device that's temporarily absent from the poll).
    def legacy = (settings.trackedMacs ?: []) as List
    def accounted = (onSel + offSel)
    if (legacy && legacy.every { accounted.contains(it) }) app.removeSetting("trackedMacs")
}

/* ---------------- lifecycle ---------------- */

def installed() { initialize() }

def updated() {
    unschedule()
    initialize()
}

def initialize() {
    // Remove deprecated standalone Safe Access switch devices (now controlled from Child devices).
    getChildDevices().findAll { it.deviceNetworkId.startsWith("SRMSAFE-") }.each {
        logDebug "Removing deprecated Safe Access device ${it.deviceNetworkId}"
        deleteChildDevice(it.deviceNetworkId)
    }
    syncChildren()
    syncRouter()
    schedulePolling()
    runIn(3, "pollDevices")
}

private void schedulePolling() {
    switch (settings.pollInterval ?: "5") {
        case "1":  runEvery1Minute("pollDevices");   break
        case "5":  runEvery5Minutes("pollDevices");  break
        case "10": runEvery10Minutes("pollDevices"); break
        default:   runEvery15Minutes("pollDevices")
    }
}

/* ---------------- child management ---------------- */

private void syncChildren() {
    def tracked = getTrackedMacs()
    def trackedNorm = tracked.collect { norm(it) }

    // create missing children
    tracked.each { mac ->
        String dni = childDni(mac)
        if (!getChildDevice(dni)) {
            String label = friendlyLabel((state.deviceLabels?.get(mac)) ?: mac)
            try {
                def child = addChildDevice(CHILD_NS, CHILD_TYPE, dni,
                        [name: label, label: label, isComponent: false])
                child?.setOnline(false, null)
                logDebug "Created child for ${mac} (${label})"
            } catch (e) {
                log.error "Could not create child for ${mac}: ${e.message}. Is the 'Synology SRM Device' driver installed?"
            }
        }
    }

    // remove device children no longer tracked (never touch the router device)
    getChildDevices().each { c ->
        if (!isDeviceChild(c.deviceNetworkId)) return
        String n = c.deviceNetworkId.replace("SRM-", "")
        if (!trackedNorm.contains(n)) {
            logDebug "Removing child ${c.deviceNetworkId}"
            deleteChildDevice(c.deviceNetworkId)
        }
    }
}

private String norm(String mac)     { mac?.replaceAll("[:\\-]", "")?.toUpperCase() }
private String childDni(String mac) { "SRM-${norm(mac)}" }

// A tracked-device child has DNI "SRM-<12 hex>". Distinguishes them from the router device (SRM-ROUTER).
private boolean isDeviceChild(String dni) { dni ==~ /SRM-[0-9A-F]{12}/ }

// Tidy the router hostname into a child label: drop hyphens/underscores, suffix " - SRM".
// e.g. "Dereks-iPhone" -> "Dereks iPhone - SRM"
private String friendlyLabel(String raw) {
    String clean = (raw ?: "").replaceAll("[-_]", " ").replaceAll("\\s+", " ").trim()
    return "${clean} - SRM"
}

/* ---------------- polling ---------------- */

def pollDevices() {
    List devices = fetchDevices()
    if (devices == null) {
        log.warn "Poll skipped: no device data (login/network issue). Keeping last known states."
        return
    }

    recordLastSeen(devices)
    recordNodeNames(devices)

    def byMac = [:]
    devices.each { d -> if (d?.mac) byMac[norm(d.mac)] = d }

    int grace = (settings.graceCount != null) ? (settings.graceCount as int) : 2
    def misses = state.misses ?: [:]

    getChildDevices().each { c ->
        if (!isDeviceChild(c.deviceNetworkId)) return    // skip the router device
        String n = c.deviceNetworkId.replace("SRM-", "")
        def d = byMac[n]
        boolean onlineNow = (d?.is_online == true)

        if (onlineNow) {
            misses[n] = 0
            c.setOnline(true, d)
        } else {
            misses[n] = ((misses[n] ?: 0) as int) + 1
            if (misses[n] >= grace) {
                c.setOnline(false, d)   // d may be null if absent from the list
            }
            // else: still within grace window — leave current state untouched (debounce)
        }
    }
    state.misses = misses
    logDebug "Polled ${devices.size()} devices"

    pollHealth()
    refreshSafeAccess()
}

// Capture mesh_node_id -> friendly name (e.g. 0 -> "SynologyRouter", 2 -> "Rumpus Room") from the
// device list, so the router's node children can be labelled with real names.
private void recordNodeNames(List devices) {
    def names = state.nodeNames ?: [:]
    devices.each { d ->
        if (d?.mesh_node_id != null && d?.mesh_node_name) names[d.mesh_node_id.toString()] = d.mesh_node_name
    }
    state.nodeNames = names
}

// Returns the device list, or null on failure (so the poller can preserve last states).
private List fetchDevices() {
    if (!ensureSession()) return null
    def params = entryParams(NSM_API, "get", NSM_VERSION)
    def out = null
    try {
        httpGet(params) { resp ->
            def data = asMap(resp)
            if (data?.error?.code in [106, 107]) {        // session expired / duplicate login
                state.sid = null
                if (ensureSession()) {
                    params.headers = ["Cookie": "id=${state.sid}"]
                    httpGet(params) { r2 -> out = extractDevices(asMap(r2)) }
                }
            } else {
                out = extractDevices(data)
            }
        }
    } catch (e) {
        log.error "fetchDevices exception: ${e.message}"
    }
    return out
}

private List extractDevices(Map data) {
    if (data == null) { log.warn "NSM.Device: unparseable response"; return null }
    if (data.success != true) { log.warn "NSM.Device success=false error=${data?.error}"; return null }
    def block = (data.data instanceof Map) ? data.data : null
    return block?.devices ?: (data.data instanceof List ? data.data : null)
}

// Used by the device-selection page. Returns [online: <Map>, offline: <Map>], each an ordered
// MAC->label map. Offline is sorted by app-recorded "last seen online", most recent first.
private Map discoverDevices() {
    List list = fetchDevices()
    if (list == null) return [online: [], offline: []]

    recordLastSeen(list)
    def seen = state.lastSeen ?: [:]

    def onlineList  = list.findAll { it?.mac && it.is_online }
    def offlineList = list.findAll { it?.mac && !it.is_online }

    // Online: most-recently-seen first (effectively "now"), then by name.
    onlineList = onlineList.sort { a, b ->
        long cmp = (seenAt(seen, b.mac) <=> seenAt(seen, a.mac))
        cmp != 0 ? cmp : (a.hostname ?: "").compareToIgnoreCase(b.hostname ?: "")
    }
    // Offline: most-recently-seen first; never-seen (0) sink to the bottom, then by name.
    offlineList = offlineList.sort { a, b ->
        long cmp = (seenAt(seen, b.mac) <=> seenAt(seen, a.mac))
        cmp != 0 ? cmp : (a.hostname ?: "").compareToIgnoreCase(b.hostname ?: "")
    }

    // Build options as ordered Lists of single-entry maps so Hubitat preserves our sort order.
    def labels = [:]
    def online  = []
    def offline = []
    onlineList.each  { d -> online  << [(d.mac): onlineLabel(d)];        labels[d.mac] = d.hostname ?: d.mac }
    offlineList.each { d -> offline << [(d.mac): offlineLabel(d, seen)]; labels[d.mac] = d.hostname ?: d.mac }

    state.deviceLabels = labels
    return [online: online, offline: offline]
}

private String onlineLabel(Map d) {
    String conn = d.is_wireless ? (d.band ?: "wifi") : "eth"
    String warn = isRandomMac(d.mac) ? " ⚠" : ""
    return "${d.hostname ?: d.mac} — ${d.mac} [${conn}]${warn}"
}

private String offlineLabel(Map d, Map seen) {
    long ts = seenAt(seen, d.mac)
    String last = ts ? ", last seen ${relTime(ts)}" : ""
    String warn = isRandomMac(d.mac) ? " ⚠" : ""
    return "${d.hostname ?: d.mac} — ${d.mac}${last}${warn}"
}

// Record current time as "last seen online" for every online device.
private void recordLastSeen(List devices) {
    def seen = state.lastSeen ?: [:]
    long now = now()
    devices.each { d -> if (d?.mac && d.is_online) seen[norm(d.mac)] = now }
    state.lastSeen = seen
}

private long seenAt(Map seen, String mac) { (seen?.get(norm(mac)) ?: 0L) as long }

private String relTime(long ts) {
    long s = (long) ((now() - ts) / 1000)
    if (s < 90)    return "${s}s ago"
    long m = (long) (s / 60)
    if (m < 90)    return "${m}m ago"
    long h = (long) (m / 60)
    if (h < 36)    return "${h}h ago"
    return "${(long)(h / 24)}d ago"
}

// Locally-administered (randomised) MAC: second-least-significant bit of the first octet is set.
private boolean isRandomMac(String mac) {
    try { return (Integer.parseInt(mac.split(":")[0], 16) & 0x02) != 0 }
    catch (e) { return false }
}

/* ---------------- router health ---------------- */

private void syncRouter() {
    def existing = getChildDevice(ROUTER_DNI)
    if (settings.trackRouter == false) {
        if (existing) { logDebug "Removing router device"; deleteChildDevice(ROUTER_DNI) }
        return
    }
    if (!existing) {
        try {
            addChildDevice(CHILD_NS, ROUTER_TYPE, ROUTER_DNI,
                    [name: "Synology SRM Router", label: "Synology SRM Router", isComponent: false])
            logDebug "Created router device"
        } catch (e) {
            log.error "Could not create router device: ${e.message}. Is the 'Synology SRM Router' driver installed?"
        }
    }
}

def pollHealth() {
    if (settings.trackRouter == false) return
    def rd = getChildDevice(ROUTER_DNI)
    if (!rd) return

    def info = fetchEntry("SYNO.Mesh.System.Info", 1)         // per-node model/firmware/uptime
    def util = fetchEntry("SYNO.Mesh.System.Utilization", 1)  // per-node cpu/memory
    if (info == null || util == null) {
        log.warn "Health poll failed — marking router unreachable"
        rd.markUnreachable()
        return
    }
    def nodeList = fetchEntry("SYNO.Mesh.Node.List", 4)            // status, connected_devices, backhaul
    def ethInfo  = fetchEntry("SYNO.Mesh.Network.EthPortInfo", 1)  // ethernet port link state

    def utilByNode = [:];  (util.nodes ?: []).each     { utilByNode[it.node_id] = it }
    def nlByNode   = [:];  (nodeList?.nodes ?: []).each { nlByNode[it.node_id]   = it }
    def ethByNode  = [:];  (ethInfo?.nodes ?: []).each  { ethByNode[it.node_id]  = it }
    def names = state.nodeNames ?: [:]

    def nodes = (info.nodes ?: []).collect { ni ->
        def u  = utilByNode[ni.node_id] ?: [:]
        def nl = nlByNode[ni.node_id]   ?: [:]
        def eth = ethByNode[ni.node_id]
        int memPct = (u.mem_total) ? Math.round((u.mem_used / (double) u.mem_total) * 100) : 0
        // How the node connects upstream: root's WAN is wired; a satellite uses wired/wireless backhaul.
        String connectionType = !nl ? null
                              : ((nl.parent_node_id == -1 || !ni.is_re) ? "wired"
                                 : (nl.is_wireless ? "wireless" : "wired"))
        Integer portsUp = eth ? (eth.eth_port_info ?: []).count { it.has_link } : null
        boolean online  = nl.containsKey("network_status") ? (nl.network_status == "online") : true
        [ nodeId          : ni.node_id,
          model           : ni.model,
          firmware        : ni.firmware_ver,
          uptime          : humanUptime(ni.uptime),
          cpu             : u.cpu_usage,
          memory          : memPct,
          role            : (ni.is_re ? "node" : "primary"),
          name            : (nl.name ?: names[ni.node_id?.toString()]),
          connectedDevices: nl.connected_devices,
          nodeStatus      : nl.node_status,
          connectionType  : connectionType,
          portsUp         : portsUp,
          online          : online ]
    }

    // WAN / internet status (best-effort; don't fail the whole health poll if this errors)
    String internet = "unknown"
    String publicIP = ""
    def wan = fetchEntry("SYNO.Core.Network.Router.ConnectionStatus", 1)
    if (wan?.ipv4) {
        internet = (wan.ipv4.conn_status == "normal") ? "up" : "down"
        publicIP = wan.ipv4.ip ?: ""
    }

    // Firmware update availability — checked at most twice a day (queries Synology's update server).
    if (!state.lastFwCheck || (now() - (state.lastFwCheck as long)) > 43200000L) {
        def upg = fetchEntry("SYNO.Core.Upgrade.Server", 1, "check")
        if (upg != null) {
            state.lastFwCheck   = now()
            state.fwAvailable   = (upg.available == true) ? "yes" : "no"
            state.fwVersion     = upg.version ?: ""
        }
    }

    def primary = nodes.find { it.role == "primary" } ?: (nodes ? nodes[0] : [:])
    rd.updateHealth([
        cpu      : primary.cpu,
        memory   : primary.memory,
        firmware : primary.firmware,
        model    : primary.model,
        uptime   : primary.uptime,
        nodeCount: (info.total ?: nodes.size()),
        internet : internet,
        publicIP : publicIP,
        updateAvailable : (state.fwAvailable ?: "unknown"),
        availableVersion: (state.fwVersion ?: ""),
        nodes    : nodes
    ])
    logDebug "Health: ${nodes.size()} node(s); primary cpu=${primary.cpu}% mem=${primary.memory}%; internet=${internet}"
}

void rebootRouter() {
    if (settings.enableReboot != true) {
        log.warn "Reboot requested but 'Allow Reboot' is disabled in the app settings — ignoring."
        return
    }
    if (!ensureSession()) { log.error "Reboot aborted: no router session"; return }
    log.warn "Sending REBOOT to the SRM router (SYNO.Core.System)…"
    def params = entryParams("SYNO.Core.System", "reboot", 1)
    try {
        httpGet(params) { resp ->
            def d = asMap(resp)
            log.warn "Reboot response: success=${d?.success} error=${d?.error}"
        }
    } catch (e) {
        // A reboot tears down the connection, so an exception here is often expected.
        log.warn "Reboot request sent (connection dropped: ${e.message})"
    }
}

// Reboot a single mesh node. Gated by the same "Allow Reboot" toggle as the whole-router reboot.
void rebootNode(nodeId) {
    if (settings.enableReboot != true) {
        log.warn "Node reboot requested but 'Allow Reboot' is disabled in the app settings — ignoring."
        return
    }
    if (nodeId == null) { log.warn "Node reboot: no node id"; return }
    if (!ensureSession()) { log.error "Node reboot aborted: no router session"; return }
    log.warn "Rebooting mesh node ${nodeId} (SYNO.Mesh.Node reboot)…"
    def params = entryParams("SYNO.Mesh.Node", "reboot", 1)
    params.query << [node_id: (nodeId as int)]
    try {
        httpGet(params) { resp ->
            def d = asMap(resp)
            log.warn "Node ${nodeId} reboot response: success=${d?.success} error=${d?.error}"
        }
    } catch (e) {
        log.warn "Node reboot request sent (connection may drop: ${e.message})"
    }
}

/* ---------------- Safe Access (parental internet pause) ---------------- */

// Fetch the Safe Access profiles (people) into state.safeAccessGroups = [id: [name, pause]].
private void fetchSafeAccessGroups() {
    def res = fetchEntry("SYNO.SafeAccess.AccessControl.ConfigGroup", 1)
    if (res == null) return
    def m = [:]
    (res.config_groups ?: []).findAll { it.name && !it.name.startsWith('$') }.each {
        m[it.config_group_id.toString()] = [name: it.name, pause: (it.pause == true)]
    }
    state.safeAccessGroups = m
}

// Options for the Child device's profile picker: [ "11":"Hugo", "3":"Madeline" ].
Map getSafeAccessProfileOptions() {
    if (!(state.safeAccessGroups)) fetchSafeAccessGroups()
    return (state.safeAccessGroups ?: [:]).collectEntries { k, v -> [(k.toString()): v.name] }
}

// Push each Child device's selected-profile pause state + screen time to it (clears non-Child).
def refreshSafeAccess() {
    if (settings.enableSafeAccess == false) return
    fetchSafeAccessGroups()
    def groups = state.safeAccessGroups ?: [:]

    // which profiles actually have a Child device? (only fetch screen time for those)
    def childGroups = [] as Set
    getChildDevices().each { c ->
        if (!isDeviceChild(c.deviceNetworkId)) return
        if ("${c.getSetting('isChild')}" == "true" && c.getSetting('safeAccessGroup') != null) {
            childGroups << c.getSetting('safeAccessGroup').toString()
        }
    }

    // refresh screen time at most every ~10 min (the TimeSpent call returns large payloads)
    if (childGroups && (!state.lastScreenCheck || (now() - (state.lastScreenCheck as long)) > 600000L)) {
        def st = state.screenTime ?: [:]
        childGroups.each { gid -> def m = fetchScreenTimeMinutes(gid); if (m != null) st[gid] = m }
        state.screenTime = st
        state.lastScreenCheck = now()
    }
    def screen = state.screenTime ?: [:]

    getChildDevices().each { c ->
        if (!isDeviceChild(c.deviceNetworkId)) return
        if ("${c.getSetting('isChild')}" != "true") { c.setInternetAccess(null); return }
        def gid = c.getSetting('safeAccessGroup')
        if (gid == null) { c.setInternetAccess(null); return }
        def g = groups[gid.toString()]
        if (g != null) c.setInternetAccess(g.pause)
        if (screen.containsKey(gid.toString())) c.setScreenTime(screen[gid.toString()])
    }
}

// Sum a profile's per-device "minutes online today" (total_spent.normal across its devices).
private Integer fetchScreenTimeMinutes(gid) {
    def res = fetchEntry("SYNO.SafeAccess.AccessControl.ConfigGroup.Device.TimeSpent", 1, "get",
                         [config_group_id: (gid as int)])
    if (res == null) return null
    def cg = (res.config_groups ?: []).find { it.config_group_id?.toString() == gid.toString() }
    if (cg == null) return 0
    int total = 0
    (cg.device_timespent ?: [:]).each { mac, info -> total += ((info?.total_spent?.normal ?: 0) as int) }
    return total
}

// Called by a Child device. pause=true pauses that profile's internet (all its devices).
void setSafeAccessPause(groupId, boolean pause) {
    if (!ensureSession()) { log.error "Safe Access set aborted: no router session"; return }
    def params = entryParams("SYNO.SafeAccess.AccessControl.ConfigGroup", "set", 1)
    params.query << [config_group_id: (groupId as int), pause: pause]
    logDebug "Safe Access: group ${groupId} -> pause=${pause}"
    try {
        httpGet(params) { resp ->
            def d = asMap(resp)
            if (!d?.success) log.warn "Safe Access set failed for group ${groupId}: ${d?.error}"
        }
    } catch (e) {
        log.error "setSafeAccessPause exception: ${e.message}"
    }
    refreshSafeAccess()   // reflect new state on the Child devices
}

// Generic entry.cgi call that returns the unwrapped `data` map (or null on failure).
private Map fetchEntry(String api, Integer version, String method = "get", Map extra = [:]) {
    if (!ensureSession()) return null
    def params = entryParams(api, method, version)
    if (extra) params.query << extra
    def out = null
    try {
        httpGet(params) { resp ->
            def data = asMap(resp)
            if (data?.error?.code in [106, 107]) {
                state.sid = null
                if (ensureSession()) {
                    params.headers = ["Cookie": "id=${state.sid}"]
                    httpGet(params) { r2 -> def d2 = asMap(r2); if (d2?.success) out = d2.data }
                }
            } else if (data?.success) {
                out = data.data
            } else {
                log.warn "${api} success=false error=${data?.error}"
            }
        }
    } catch (e) {
        log.error "${api} exception: ${e.message}"
    }
    return out
}

private String humanUptime(seconds) {
    if (seconds == null) return ""
    long s = seconds as long
    long d = (long) (s / 86400); s %= 86400
    long h = (long) (s / 3600);  s %= 3600
    long m = (long) (s / 60)
    if (d > 0) return "${d}d ${h}h"
    if (h > 0) return "${h}h ${m}m"
    return "${m}m"
}

/* ---------------- SRM auth / http ---------------- */

private boolean ensureSession() {
    if (state.sid) return true
    return login()
}

private boolean login() {
    if (!settings.host || !settings.username || !settings.password) {
        log.warn "Router host/username/password not set yet"
        return false
    }
    def params = [
        uri  : baseUri(),
        path : "/webapi/auth.cgi",
        query: [api: "SYNO.API.Auth", method: "Login", version: 2,
                account: settings.username, passwd: settings.password, format: "sid"],
        ignoreSSLIssues: true, timeout: 20
    ]
    try {
        httpGet(params) { resp ->
            def data = asMap(resp)
            if (data?.success && data?.data?.sid) {
                state.sid = data.data.sid
                logDebug "SRM login OK"
            } else {
                log.error "SRM login failed: error=${data?.error}"
            }
        }
    } catch (e) {
        log.error "SRM login exception: ${e.message}"
    }
    return state.sid != null
}

private Map entryParams(String api, String method, Integer version) {
    return [
        uri  : baseUri(),
        path : "/webapi/entry.cgi",
        query: [api: api, method: method, version: version],
        headers: ["Cookie": "id=${state.sid}"],
        ignoreSSLIssues: true, timeout: 25
    ]
}

private String baseUri() {
    String proto = (settings.useHTTPS == false) ? "http" : "https"
    return "${proto}://${settings.host}:${settings.port ?: 8001}"
}

// SRM returns JSON with a non-JSON content-type, so Hubitat may hand back a String/StringReader.
// Sandbox forbids referencing java.io.Reader and getClass(), so duck-type instead.
private Map asMap(resp) {
    def d = resp?.data
    if (d == null) return null
    if (d instanceof Map) return d
    String raw
    if (d instanceof String) { raw = d }
    else { try { raw = d.getText() } catch (ignored) { raw = d.toString() } }
    try { return parseJson(raw) }
    catch (e) { log.error "Could not parse response JSON: ${e.message}"; return null }
}

private void logDebug(msg) { if (settings.logEnable != false) log.debug msg }
