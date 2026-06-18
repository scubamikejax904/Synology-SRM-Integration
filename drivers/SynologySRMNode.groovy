/**
 *  Synology SRM Node  (child of the SRM Router device)
 *
 *  One per mesh node (e.g. RT6600ax primary, RT2600ac node). Shows the node's online status as
 *  presence plus per-node health (CPU, memory, uptime, model, firmware).
 *
 *  Created automatically by the "Synology SRM Router" device — do not create manually.
 *
 *  Author:  Derek Osborn
 *  Version: 1.1.0  (2026-06-17)
 */

metadata {
    definition(name: "Synology SRM Node", namespace: "dJOS", author: "Derek Osborn",
        importUrl: "https://raw.githubusercontent.com/dJOS1475/Synology-SRM-Integration/refs/heads/main/drivers/SynologySRMNode.groovy") {
        capability "PresenceSensor"
        capability "Sensor"

        attribute "model",            "string"
        attribute "firmware",         "string"
        attribute "cpu",              "number"   // %
        attribute "memory",           "number"   // %
        attribute "uptime",           "string"
        attribute "role",             "string"   // primary | node
        attribute "nodeId",           "number"
        attribute "connectedDevices", "number"
        attribute "nodeStatus",       "string"   // good | ...
        attribute "connectionType",   "string"   // wired | wireless
        attribute "portsUp",          "number"   // ethernet ports with link
        attribute "lastUpdate",       "string"
        attribute "htmlTile",         "string"   // dashboard summary tile

        command "reboot"
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def installed() { sendEvent(name: "presence", value: "not present") }
def updated()   { }

// Reboot just this mesh node (gated by the app's "Allow Reboot" setting).
def reboot() { parent?.rebootNode(device.currentValue("nodeId")) }

// Called by the parent SRM Router device with a merged per-node health map.
def setNode(Map n) {
    String presence = (n.online ? "present" : "not present")
    if (device.currentValue("presence") != presence) {
        sendEvent(name: "presence", value: presence, descriptionText: "${device.displayName} is ${presence}")
        if (txtEnable) log.info "${device.displayName} is ${presence}"
    }
    updateAttr("model",            n.model)
    updateAttr("firmware",         n.firmware)
    updateAttr("cpu",              n.cpu)
    updateAttr("memory",           n.memory)
    updateAttr("uptime",           n.uptime)
    updateAttr("role",             n.role)
    updateAttr("nodeId",           n.nodeId)
    updateAttr("connectedDevices", n.connectedDevices)
    updateAttr("nodeStatus",       n.nodeStatus)
    updateAttr("connectionType",   n.connectionType)
    updateAttr("portsUp",          n.portsUp)
    updateAttr("lastUpdate", new Date().format("yyyy-MM-dd HH:mm:ss"))
    updateTile()
}

def setOffline() {
    if (device.currentValue("presence") != "not present") {
        sendEvent(name: "presence", value: "not present", descriptionText: "${device.displayName} is not present")
        if (txtEnable) log.info "${device.displayName} is not present"
    }
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
    def ct = device.currentValue("connectionType"); if (ct) h << " • ${ct}"
    h << "<br>"
    if (online) {
        h << "CPU ${device.currentValue('cpu')}% • Mem ${device.currentValue('memory')}%<br>"
        def cd = device.currentValue("connectedDevices"); def pu = device.currentValue("portsUp")
        if (cd != null) h << "${cd} device${cd == 1 ? '' : 's'}"
        if (pu != null) h << "${cd != null ? ' • ' : ''}${pu} port${pu == 1 ? '' : 's'} up"
        h << "<br>"
        def up = device.currentValue("uptime"); if (up) h << "Uptime ${up}"
    }
    h << "</div>"
    updateTileAttr("htmlTile", h.toString())
}

private void updateTileAttr(String name, String val) {
    if (val.length() > 1024) log.warn "${name} is ${val.length()} chars (>1024) — dashboard tile may be truncated"
    if (device.currentValue(name) != val) sendEvent(name: name, value: val)
}
