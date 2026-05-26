package com.messenger.crisix.transport.internet

/**
 * Liste öffentlicher Bootstrap-Knoten für das Crisix-P2P-Netzwerk.
 *
 * ## Zweck
 * Bootstrap-Knoten sind die Einstiegspunkte in das dezentrale P2P-Netzwerk.
 * Sie helfen neuen Peers, andere Peers zu finden, indem sie ihre
 * Routing-Tabellen teilen. Sobald ein Peer mit dem Netzwerk verbunden ist,
 * kann er andere Peers direkt über die DHT finden.
 *
 * ## Dezentralität
 * Die Bootstrap-Knoten sind NICHT zentral. Sie sind nur Einstiegspunkte.
 * Jeder Peer, der einmal verbunden war, kann selbst als Bootstrap-Knoten
 * für andere Peers dienen. Die Liste kann von jedem erweitert werden.
 *
 * ## Öffentliche Bootstrap-Knoten
 * Wir nutzen die öffentlichen Hyperswarm-Bootstrap-Knoten, die von der
 * Hyperswarm-Community betrieben werden. Diese Knoten sind:
 * - Öffentlich und kostenlos nutzbar
 * - Dezentral (gehören niemandem spezifisch)
 * - Stabil (werden von der Community betrieben)
 * - Keine Crisix-eigenen Server!
 *
 * ## Format
 * Die Knoten werden als "host:port" Strings gespeichert, kompatibel
 * mit dem Hyperswarm-Protokoll auf UDP-Port 49737.
 *
 * ## Wie man selbst ein Bootstrap-Knoten wird
 * 1. Crisix auf einem Server mit öffentlicher IP installieren
 * 2. Port 49737 (UDP) in der Firewall freigeben
 * 3. Die eigene Adresse zu dieser Liste hinzufügen
 * 4. Die App mit der aktualisierten Liste neu verteilen
 */
object BootstrapNodes {

    /**
     * Öffentliche Hyperswarm-Bootstrap-Knoten.
     *
     * Diese Knoten werden von der Hyperswarm-Community betrieben und
     * sind öffentlich dokumentiert. Sie dienen nur als Einstiegspunkt
     * in das DHT-Netzwerk und speichern keine Nachrichten.
     *
     * Quellen:
     * - https://github.com/hyperswarm/bootstrap
     * - https://docs.holepunch.to/building-apps/hyperswarm
     *
     * Format: "host:port"
     * - host: IP-Adresse oder Domain
     * - port: UDP-Port (Standard: 49737)
     */
    private val DEFAULT_BOOTSTRAP_NODES: List<String> = listOf(
        // Öffentliche Hyperswarm-Bootstrap-Knoten (Port 49737)
        "35.245.27.113:49737",
        "35.229.25.58:49737",
        "35.233.198.178:49737",
        "34.77.139.153:49737",
        "35.205.16.45:49737",
        "35.205.16.45:49738",
        "35.205.16.45:49739",
        // Weitere öffentliche DHT-Knoten
        "188.166.254.123:49737",
        "167.99.239.114:49737",
        "138.68.14.104:49737"
    )

    /**
     * Gibt die Liste der Bootstrap-Knoten zurück.
     *
     * @return Liste der Bootstrap-Knoten im Format "host:port"
     */
    fun getNodes(): List<String> {
        return DEFAULT_BOOTSTRAP_NODES.toList()
    }

    /**
     * Gibt die Liste der Bootstrap-Knoten mit benutzerdefinierten Knoten zurück.
     *
     * @param customNodes Optionale benutzerdefinierte Knoten
     * @return Die kombinierte Liste
     */
    fun getNodes(customNodes: List<String>? = null): List<String> {
        val nodes = mutableListOf<String>()
        nodes.addAll(DEFAULT_BOOTSTRAP_NODES)
        if (customNodes != null) {
            nodes.addAll(customNodes)
        }
        return nodes.distinct()
    }
}
