package com.messenger.crisix.transport

import android.content.Context
import com.messenger.crisix.transport.internet.InternetTransport

object TransportInitializer {
    fun initializeTransports(
        transportManager: TransportManager,
        deviceId: String,
        displayName: String,
        context: Context,
        relayUrls: List<String> = listOf("wss://crisix-dns.onrender.com/ws"),
    ) {
        val wifiTransport = WifiTransport(
            deviceId = deviceId,
            deviceName = displayName
        )
        transportManager.registerTransport(wifiTransport)

        val appCtx = context.applicationContext

        val internetTransport = InternetTransport(
            context = appCtx,
            deviceName = displayName
        )
        transportManager.registerTransport(internetTransport)

        val dnsTunnelTransport = DnsTunnelTransport(
            localPeerId = deviceId,
            serverDomain = "crisix-dns.onrender.com",
            useHttpApi = true
        )
        transportManager.registerTransport(dnsTunnelTransport)

        val relayTransport = RelayTransport(
            localPeerId = deviceId,
            relayUrlsArg = relayUrls
        )
        transportManager.registerTransport(relayTransport)

        val bleTransport = BleTransport(
            localPeerId = deviceId,
            appContext = appCtx
        )
        transportManager.registerTransport(bleTransport)

        val wifiAwareTransport = WifiAwareTransport(
            localPeerId = deviceId,
            deviceName = displayName,
            appContext = appCtx
        )
        transportManager.registerTransport(wifiAwareTransport)

        val smsTransport = SmsTransport(
            localPeerId = deviceId,
            appContext = appCtx
        )
        transportManager.registerTransport(smsTransport)
    }
}
