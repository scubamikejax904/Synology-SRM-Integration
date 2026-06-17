/**
 *  Synology SRM Device  (child driver)
 *
 *  One instance per tracked WiFi/network device. Presence and switch move in lockstep:
 *     connected    -> present + switch on
 *     disconnected -> not present + switch off
 *
 *  The switch is a mirror of connectivity, not a control: on()/off() are no-ops. State is
 *  driven entirely by the parent app's poll of the Synology router.
 *
 *  Optional "child device" mode: enable "This is a Child" and pick a Safe Access profile to expose
 *  internet pause/resume control (pauseInternet/resumeInternet commands + internetAccess attribute)
 *  for that profile, straight from this device.
 *
 *  Installed automatically by the "Synology SRM Integration" parent app — do not create manually.
 *
 *  Author:  Derek Osborn
 *  Version: 1.1.0  (2026-06-17)
 */

metadata {
    definition(name: "Synology SRM Device", namespace: "derekosborn", author: "Derek Osborn",
        importUrl: "https://raw.githubusercontent.com/dJOS1475/Synology-SRM-Integration/refs/heads/main/drivers/SynologySRMDevice.groovy") {
        capability "PresenceSensor"
        capability "Switch"
        capability "Refresh"
        capability "Sensor"
        capability "Actuator"

        attribute "mac",            "string"
        attribute "ipAddress",      "string"
        attribute "hostname",       "string"
        attribute "band",           "string"
        attribute "signal",         "number"
        attribute "linkRate",       "number"
        attribute "meshNode",       "string"
        attribute "ssid",           "string"
        attribute "connection",     "string"
        attribute "lastSeen",       "string"
        attribute "internetAccess",    "enum", ["allowed", "paused", "n/a"]  // Safe Access (Child mode)
        attribute "screenTime",        "string"   // e.g. "1h 23m" online today (Child mode)
        attribute "screenTimeMinutes", "number"   // minutes online today (Child mode)
        attribute "htmlTile",          "string"   // dashboard summary tile

        command "pauseInternet"
        command "resumeInternet"
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "isChild",   type: "bool", title: "This is a Child (enable Safe Access internet control)", defaultValue: false
        if (isChild) {
            input name: "safeAccessGroup", type: "enum", title: "Safe Access profile to control",
                  options: (parent?.getSafeAccessProfileOptions() ?: [:]), required: false
        }
    }
}

def installed() { sendEvent(name: "presence", value: "not present"); sendEvent(name: "switch", value: "off") }
def updated()   { }

// Switch commands are intentional no-ops — connectivity is reported by the router, not set here.
def on()  { if (txtEnable) log.info "${device.displayName}: on() ignored (presence is router-driven)" }
def off() { if (txtEnable) log.info "${device.displayName}: off() ignored (presence is router-driven)" }

// Ask the parent app to poll the router immediately.
def refresh() { parent?.pollDevices() }

/* ---- Safe Access (Child mode) internet control ---- */

def pauseInternet()  { internetControl(true) }
def resumeInternet() { internetControl(false) }

private void internetControl(boolean pause) {
    if (settings.isChild != true) {
        log.warn "${device.displayName}: not configured as a Child device (enable 'This is a Child')"
        return
    }
    if (settings.safeAccessGroup == null) {
        log.warn "${device.displayName}: no Safe Access profile selected"
        return
    }
    parent?.setSafeAccessPause(settings.safeAccessGroup as int, pause)
}

// Called by the app each poll with the selected profile's pause state (or null if not a Child).
def setInternetAccess(paused) {
    String v = (paused == null) ? "n/a" : (paused ? "paused" : "allowed")
    if (device.currentValue("internetAccess") != v) {
        sendEvent(name: "internetAccess", value: v)
        if (txtEnable && paused != null) log.info "${device.displayName} internet ${v}"
    }
    updateTile()
}

// Called by the app with the selected profile's minutes online today.
def setScreenTime(mins) {
    if (mins == null) return
    int m = mins as int
    updateAttr("screenTimeMinutes", m)
    updateAttr("screenTime", (m >= 60) ? "${(int)(m / 60)}h ${m % 60}m" : "${m}m")
    updateTile()
}

/**
 * Called by the parent app on each poll.
 * @param online  effective online state (after the app applies the grace period)
 * @param d       the raw NSM.Device map for this MAC, or null if absent from the list
 */
def setOnline(online, d) {
    Boolean isOn = (online == true)
    String presence = isOn ? "present" : "not present"
    String sw       = isOn ? "on" : "off"

    if (device.currentValue("presence") != presence) {
        sendEvent(name: "presence", value: presence,
                  descriptionText: "${device.displayName} is ${presence}")
        if (txtEnable) log.info "${device.displayName} is ${presence}"
    }
    if (device.currentValue("switch") != sw) {
        sendEvent(name: "switch", value: sw,
                  descriptionText: "${device.displayName} switch ${sw}")
    }

    if (d != null) {
        updateAttr("mac",        d.mac)
        updateAttr("ipAddress",  d.ip_addr)
        updateAttr("hostname",   d.hostname)
        updateAttr("band",       d.band ?: "")
        updateAttr("signal",     (d.signalstrength != null) ? d.signalstrength : 0)
        updateAttr("linkRate",   (d.current_rate != null) ? d.current_rate : 0)
        updateAttr("meshNode",   d.mesh_node_name ?: "")
        updateAttr("ssid",       d.wifi_ssid ?: "")
        updateAttr("connection", d.connection ?: (d.is_wireless ? "wifi" : "ethernet"))
    }
    if (isOn) updateAttr("lastSeen", new Date().format("yyyy-MM-dd HH:mm:ss"))
    updateTile()
}

private updateAttr(String name, value) {
    if (value != null && device.currentValue(name)?.toString() != value.toString()) {
        sendEvent(name: name, value: value)
    }
}

/* ---- dashboard HTML tile ---- */

private void updateTile() {
    boolean online = device.currentValue("presence") == "present"
    StringBuilder h = new StringBuilder()
    h << "<div style='line-height:1.7; font-size:0.85em; text-align:left;'>"
    h << "<b>${device.displayName}</b><br>"
    h << (online ? "🟢 online" : "🔴 offline")
    def ip = device.currentValue("ipAddress"); if (ip) h << " &nbsp;${ip}"
    h << "<br>"
    def conn = device.currentValue("connection"); def band = device.currentValue("band"); def sig = device.currentValue("signal")
    if (conn) h << "${conn}${band ? ' ' + band : ''}${(sig != null && sig != 0) ? ' • ' + sig + '%' : ''}<br>"
    def node = device.currentValue("meshNode"); if (node) h << "via ${node}<br>"
    def ia = device.currentValue("internetAccess")
    if (ia && ia != "n/a") {
        h << "Internet: ${ia == 'paused' ? '⛔ paused' : '✅ allowed'}"
        def st = device.currentValue("screenTime"); if (st) h << " • ${st} today"
        h << "<br>"
    }
    def ls = device.currentValue("lastSeen"); if (ls) h << "<span style='font-size:0.85em;'>seen ${ls}</span>"
    h << "</div>"
    updateTileAttr("htmlTile", h.toString())
}

private void updateTileAttr(String name, String val) {
    if (val.length() > 1024) log.warn "${name} is ${val.length()} chars (>1024) — dashboard tile may be truncated"
    if (device.currentValue(name) != val) sendEvent(name: name, value: val)
}
