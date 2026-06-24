/**
 *  Synology SRM Router  (parent health device)
 *
 *  Overall router health + reachability, with one child "Synology SRM Node" device per mesh node.
 *  Presence = router reachable (last health poll succeeded). Attributes summarise the primary node.
 *
 *  Created automatically by the "Synology SRM Integration" app. Requires the companion drivers
 *  "Synology SRM Node" (for the per-node children).
 *
 *  Reboot: the reboot() command asks the app to send SYNO.Core.System reboot. It only works if
 *  "Allow Reboot" is enabled in the app settings, and it reboots the whole router — use with care.
 *
 *  Author:  Derek Osborn
 *  Version: 1.1.1  (2026-06-17)
 */

metadata {
    definition(name: "Synology SRM Router", namespace: "dJOS", author: "Derek Osborn",
        importUrl: "https://raw.githubusercontent.com/dJOS1475/Synology-SRM-Integration/refs/heads/main/drivers/SynologySRMRouter.groovy") {
        capability "Refresh"
        capability "PresenceSensor"
        capability "Sensor"

        attribute "cpu",         "number"   // % (primary node)
        attribute "memory",      "number"   // % (primary node)
        attribute "firmware",    "string"
        attribute "model",       "string"
        attribute "uptime",      "string"
        attribute "nodeCount",   "number"
        attribute "nodesOnline", "number"
        attribute "internet",        "enum", ["up", "down", "unknown"]   // WAN/internet reachability
        attribute "publicIP",        "string"
        attribute "updateAvailable", "enum", ["yes", "no", "unknown"]    // firmware update pending
        attribute "availableVersion","string"                            // e.g. "SRM 1.3.2-9366 Update 2"
        attribute "lastPoll",        "string"
        attribute "htmlTile",        "string"   // dashboard summary tile

        command "reboot"
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def installed() { sendEvent(name: "presence", value: "not present") }
def updated()   { }

def refresh() { parent?.pollHealth() }
def reboot()  { parent?.rebootRouter() }

// Relay a per-node reboot from a node child up to the app.
def rebootNode(nodeId) { parent?.rebootNode(nodeId) }

// Called by the app each health poll with merged data:
//   [cpu, memory, firmware, model, uptime, nodeCount, nodes:[[nodeId,model,firmware,cpu,memory,uptime,role,name,online], ...]]
def updateHealth(Map h) {
    if (device.currentValue("presence") != "present") {
        sendEvent(name: "presence", value: "present", descriptionText: "${device.displayName} is reachable")
    }
    updateAttr("cpu",       h.cpu)
    updateAttr("memory",    h.memory)
    updateAttr("firmware",  h.firmware)
    updateAttr("model",     h.model)
    updateAttr("uptime",    h.uptime)
    updateAttr("nodeCount", h.nodeCount)
    if (h.containsKey("internet")) updateAttr("internet", h.internet)
    if (h.containsKey("publicIP")) updateAttr("publicIP", h.publicIP)
    if (h.containsKey("updateAvailable"))  updateAttr("updateAvailable", h.updateAvailable)
    if (h.containsKey("availableVersion")) updateAttr("availableVersion", h.availableVersion)

    def nodes = h.nodes ?: []
    updateAttr("nodesOnline", nodes.count { it.online })
    updateAttr("lastPoll", new Date().format("yyyy-MM-dd HH:mm:ss"))

    // sync per-node children
    def seenIds = []
    nodes.each { n ->
        seenIds << n.nodeId?.toString()
        String dni = nodeDni(n.nodeId)
        def child = getChildDevice(dni)
        if (!child) {
            try {
                child = addChildDevice("dJOS", "Synology SRM Node", dni,
                        [name: nodeLabel(n), label: nodeLabel(n), isComponent: false])
            } catch (e) {
                log.error "Could not create node child ${dni}: ${e.message}. Is the 'Synology SRM Node' driver installed?"
            }
        }
        child?.setNode(n)   // n.online reflects the router's reported network_status
    }
    // any previously-known node missing from this poll -> offline
    getChildDevices().each { c ->
        String id = c.deviceNetworkId.replace("SRMNODE-", "")
        if (!seenIds.contains(id)) c.setOffline()
    }
    updateTile()
}

def markUnreachable() {
    if (device.currentValue("presence") != "not present") {
        sendEvent(name: "presence", value: "not present", descriptionText: "${device.displayName} is unreachable")
    }
    getChildDevices().each { it.setOffline() }
    updateTile()
}

private String nodeDni(nodeId) { "SRMNODE-${nodeId}" }

private String nodeLabel(Map n) {
    String who = n.name ?: n.model ?: "node ${n.nodeId}"
    String role = (n.role == "primary") ? "Primary" : "Node"
    return "${who} (${role}) - SRM"
}

private updateAttr(String name, value) {
    if (value != null && device.currentValue(name)?.toString() != value.toString()) {
        sendEvent(name: name, value: value)
    }
}

/* ---- dashboard HTML tile ---- */

private void updateTile() {
    boolean reachable = device.currentValue("presence") == "present"
    StringBuilder h = new StringBuilder()
    h << "<div style='line-height:1.7; font-size:0.85em; text-align:left;'>"
    h << "<b>${device.currentValue('model') ?: 'SRM Router'}</b><br>"
    if (!reachable) {
        h << "🔴 unreachable</div>"
        updateTileAttr("htmlTile", h.toString()); return
    }
    def net = device.currentValue("internet")
    h << "Internet: ${net == 'up' ? '🟢 up' : net == 'down' ? '🔴 down' : net}"
    def ip = device.currentValue("publicIP"); if (ip) h << " &nbsp;${ip}"
    h << "<br>"
    h << "CPU ${device.currentValue('cpu')}% • Mem ${device.currentValue('memory')}%<br>"
    h << "Nodes ${device.currentValue('nodesOnline')}/${device.currentValue('nodeCount')} online<br>"
    def up = device.currentValue("uptime"); if (up) h << "Uptime ${up}<br>"
    if (device.currentValue("updateAvailable") == "yes")
        h << "⚠ Update: ${device.currentValue('availableVersion')}"
    else
        h << "<span style='font-size:0.85em;'>${device.currentValue('firmware')}</span>"
    h << "</div>"
    updateTileAttr("htmlTile", h.toString())
}

private void updateTileAttr(String name, String val) {
    if (val.length() > 1024) log.warn "${name} is ${val.length()} chars (>1024) — dashboard tile may be truncated"
    if (device.currentValue(name) != val) sendEvent(name: name, value: val)
}
