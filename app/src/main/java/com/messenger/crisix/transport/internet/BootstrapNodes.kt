package com.messenger.crisix.transport.internet

import android.util.Log
import java.net.InetAddress

/**
 * DNS-Seeds für das Crisix-P2P-Netzwerk.
 *
 * ## Warum DNS-Seeds statt fester IPs?
 * Feste Bootstrap-IPs werden schnell veraltet (Server gehen offline, IPs ändern sich).
 * DNS-Seeds lösen dieses Problem, indem sie immer aktuelle Knoten-IPs liefern.
 *
 * ## Funktionsweise
 * 1. Die DNS-Seeds werden aufgelöst (z.B. "mainline.krrd.org" -> "188.166.254.123")
 * 2. Die erhaltenen IPs werden als Bootstrap-Knoten verwendet
 * 3. Fallback: Falls DNS fehlschlägt, werden die zuletzt bekannten IPs verwendet
 *
 * ## Quellen für DNS-Seeds
 * - Mainline DHT (BitTorrent): mainline.krrd.org, router.bittorrent.com
 * - Hyperswarm: Keine offiziellen DNS-Seeds, daher Fallback auf Mainline DHT
 *
 * ## Port
 * Standard-Hyperswarm-Port: 49737 (UDP)
 */
object BootstrapNodes {

    private const val TAG = "BootstrapNodes"

    /**
     * DNS-Seeds, die aktuelle Bootstrap-Knoten-IPs liefern.
     *
     * Diese Seeds werden bei jedem App-Start aufgelöst, um die
     * aktuellsten Knoten-IPs zu erhalten.
     */
    private val DNS_SEEDS = listOf(
        "mainline.krrd.org",
        "router.bittorrent.com",
        "router.utorrent.com",
        "dht.transmissionbt.com"
    )

    /**
     * Fallback-IPs, falls DNS-Auflösung fehlschlägt.
     *
     * Diese IPs sind die zuletzt bekannten Adressen der DNS-Seeds.
     * Sie werden nur verwendet, wenn die DNS-Auflösung komplett fehlschlägt.
     *
     * Wichtig: Mainline-DHT-Knoten verwenden Port 6881 (Standard),
     * aber viele laufen auch auf anderen Ports. Der MainlineDhtClient
     * versucht beide Ports.
     */
    private val FALLBACK_NODES: List<String> = listOf(
        "188.166.254.123:6881",
        "167.99.239.114:6881",
        "138.68.14.104:6881",
        "188.166.254.123:49737",
        "167.99.239.114:49737",
        "138.68.14.104:49737"
    )

    /**
     * Löst die DNS-Seeds auf und gibt die Bootstrap-Knoten zurück.
     *
     * @return Liste der Bootstrap-Knoten im Format "host:port"
     */
    suspend fun getNodes(): List<String> {
        val resolvedNodes = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (seed in DNS_SEEDS) {
            try {
                Log.d(TAG, "Löse DNS-Seed auf: $seed")
                val addresses = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    InetAddress.getAllByName(seed)
                }
                for (addr in addresses) {
                    val host = addr.hostAddress ?: continue
                    // Nur IPv4 (Hyperswarm unterstützt nur IPv4)
                    if (host.contains(":")) continue
                    val node = "$host:49737"
                    if (node !in resolvedNodes) {
                        resolvedNodes.add(node)
                        Log.d(TAG, "  -> $node (von $seed)")
                    }
                }
            } catch (e: Exception) {
                errors.add("$seed: ${e.message}")
                Log.w(TAG, "DNS-Auflösung fehlgeschlagen für $seed: ${e.message}")
            }
        }

        // Wenn DNS erfolgreich war, resolvedNodes zurückgeben
        if (resolvedNodes.isNotEmpty()) {
            Log.i(TAG, "DNS-Seeds aufgelöst: ${resolvedNodes.size} Knoten gefunden")
            return resolvedNodes
        }

        // Fallback: Zuletzt bekannte IPs verwenden
        Log.w(TAG, "DNS-Auflösung komplett fehlgeschlagen ($errors), verwende Fallback-IPs")
        return FALLBACK_NODES
    }

    /**
     * Gibt die DNS-Seeds zurück (für Debugging).
     */
    fun getDnsSeeds(): List<String> = DNS_SEEDS

    /**
     * Gibt die Fallback-IPs zurück (für Debugging).
     */
    fun getFallbackNodes(): List<String> = FALLBACK_NODES
}
