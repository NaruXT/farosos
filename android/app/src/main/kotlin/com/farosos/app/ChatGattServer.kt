package com.farosos.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import com.farosos.beaconradio.BEACON_COMPANY_ID
import com.farosos.beaconradio.DirectChatGattService
import com.farosos.directchat.ChatCrypto
import com.farosos.directchat.ChatHostSession
import com.farosos.directchat.ChatMessage
import com.farosos.directchat.ChatMessageWireFormat
import com.farosos.directchat.EphemeralKeyExchange

/**
 * Rol víctima (host) del canal de chat directo (#61/#63) — primer
 * `BluetoothGattServer` real del proyecto en Android (hasta ahora Android
 * solo tenía rol de cliente GATT, ver `BleScanner`). Activo únicamente
 * mientras el propio estado sea `AYUDA_SOLICITADA`/`SILENCIO_TIMEOUT`
 * (`start()`/`stop()` los llama `EmergencyViewModel` en cada transición,
 * decisión de batería de la sesión de `/grilling` de #61). Acepta una sola
 * conexión a la vez — [ChatHostSession.acceptConnection] es la única fuente
 * de esa regla, este tipo solo la aplica desde el callback de conexión.
 *
 * El intercambio de clave efímera y el cifrado en tránsito viven acá (no en
 * `ChatHostSession`, que es puro y no sabe de BLE/cripto real) — la sesión
 * solo recibe/expone texto plano ya descifrado.
 */
class ChatGattServer(
    private val context: Context,
    private val session: ChatHostSession,
    private val ownDeviceIdHash: ByteArray
) {
    var onError: ((String) -> Unit)? = null
    /** Ver el comentario de `BleAdvertiser.pause()` - el llamador pausa/reanuda el beacon general con esto. */
    var onGuestConnected: (() -> Unit)? = null
    var onGuestDisconnected: (() -> Unit)? = null

    private val bluetoothManager: BluetoothManager?
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private var gattServer: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var connectedDevice: BluetoothDevice? = null
    private var ownEphemeralKeyPair: EphemeralKeyExchange.KeyPair? = null
    private var sessionKey: ByteArray? = null

    @SuppressLint("MissingPermission") // el caller garantiza BLUETOOTH_CONNECT/ADVERTISE antes de llamar
    fun start() {
        val manager = bluetoothManager ?: run {
            onError?.invoke("Este dispositivo no tiene BluetoothManager disponible")
            return
        }
        val server = manager.openGattServer(context, gattServerCallback)
        if (server == null) {
            onError?.invoke("No se pudo abrir el servidor GATT del chat")
            return
        }
        gattServer = server
        server.addService(buildService())
        startAdvertising(manager)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopAdvertising()
        connectedDevice?.let { gattServer?.cancelConnection(it) }
        gattServer?.close()
        gattServer = null
        resetSession()
    }

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(DirectChatGattService.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val hostPublicKeyCharacteristic = BluetoothGattCharacteristic(
            DirectChatGattService.HOST_PUBLIC_KEY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val guestPublicKeyCharacteristic = BluetoothGattCharacteristic(
            DirectChatGattService.GUEST_PUBLIC_KEY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val messageCharacteristic = BluetoothGattCharacteristic(
            DirectChatGattService.MESSAGE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        messageCharacteristic.addDescriptor(
            BluetoothGattDescriptor(
                DirectChatGattService.CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        service.addCharacteristic(hostPublicKeyCharacteristic)
        service.addCharacteristic(guestPublicKeyCharacteristic)
        service.addCharacteristic(messageCharacteristic)
        return service
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(manager: BluetoothManager) {
        val advertiser = manager.adapter?.bluetoothLeAdvertiser ?: run {
            onError?.invoke("Este dispositivo no tiene BluetoothLeAdvertiser disponible")
            return
        }
        // Hallazgo de campo real (#64): LOW_LATENCY + TX_POWER_HIGH (muy
        // agresivo) coincide con conexiones que se caen cada 1-3s en este
        // chipset (Samsung A10) al conectarse un central iOS - nunca llega
        // a completar el descubrimiento de servicios. BALANCED + MEDIUM es
        // la configuración recomendada de Android para chipsets de gama
        // baja cuando hay una conexión activa que sostener, no solo
        // visibilidad rápida.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(android.os.ParcelUuid(DirectChatGattService.SERVICE_UUID))
            // Mismo Company ID que el beacon (#61) — se distingue por venir
            // junto al Service UUID del chat, nunca se confunde con un
            // BeaconPacket real (BleScanner.onScanResult revisa el Service
            // UUID primero). Permite que un rescatista resuelva a qué caso
            // conocido corresponde este BluetoothDevice antes de conectarse.
            .addManufacturerData(BEACON_COMPANY_ID, ownDeviceIdHash)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                onError?.invoke("Error al anunciar el servicio de chat (código $errorCode)")
            }
        }
        advertiseCallback = callback
        advertiser.startAdvertising(settings, data, callback)
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val callback = advertiseCallback ?: return
        bluetoothManager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
        advertiseCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun resetSession() {
        val hadGuest = connectedDevice != null
        session.connectionClosed()
        connectedDevice = null
        ownEphemeralKeyPair = null
        sessionKey = null
        if (hadGuest) {
            onGuestDisconnected?.invoke()
            // Ver el comentario de `onConnectionStateChange` (#64) - se
            // reanuda para que otro rescatista pueda descubrir y conectarse
            // después de que este se fue, siempre que `gattServer` siga
            // abierto (si venimos de `stop()`, ya es `null` y esto es no-op).
            bluetoothManager?.let { manager -> gattServer?.let { startAdvertising(manager) } }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            android.util.Log.d("FarososDiag", "server onServiceAdded: status=$status uuid=${service.uuid} chars=${service.characteristics.size}")
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            android.util.Log.d("FarososDiag", "server onConnectionStateChange: status=$status newState=$newState device=${device.address}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!session.acceptConnection()) {
                        // Ya hay un rescatista conectado — rechaza al segundo (#63, AC "una sola conexión a la vez").
                        android.util.Log.d("FarososDiag", "server: acceptConnection()=false, cancelando")
                        gattServer?.cancelConnection(device)
                        return
                    }
                    connectedDevice = device
                    ownEphemeralKeyPair = EphemeralKeyExchange.generateKeyPair()
                    android.util.Log.d("FarososDiag", "server: conexión aceptada, keyPair generado")
                    // Hallazgo de campo real (#64): el advertising del chat
                    // (LOW_LATENCY + TX_POWER_HIGH) seguía transmitiendo sin
                    // parar incluso con un central ya conectado - solo se
                    // apagaba al salir de `AYUDA_SOLICITADA` por completo.
                    // Esa contención de radio (anunciar agresivo + sostener
                    // una conexión GATT) tumbaba la conexión cada 1-3s en
                    // este chipset (Samsung A10), sin que el central
                    // llegara nunca a leer/escribir ninguna característica.
                    // Un periférico BLE normal deja de anunciarse al
                    // conectarse - acá además solo se admite una conexión
                    // a la vez (#63), así que no hace falta seguir
                    // anunciando mientras ya hay alguien.
                    stopAdvertising()
                    onGuestConnected?.invoke()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device.address == connectedDevice?.address) resetSession()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            // Ignora escrituras de un dispositivo que no es el aceptado —
            // por si Android entrega un callback tardío de una conexión ya
            // rechazada por `acceptConnection()` antes de que termine de
            // cerrarse (#63, "una sola conexión a la vez").
            android.util.Log.d("FarososDiag", "server onCharacteristicWriteRequest: uuid=${characteristic.uuid} device=${device.address} accepted=${device.address == connectedDevice?.address}")
            if (device.address != connectedDevice?.address) return
            when (characteristic.uuid) {
                DirectChatGattService.GUEST_PUBLIC_KEY_CHARACTERISTIC_UUID -> handlePeerPublicKey(value)
                DirectChatGattService.MESSAGE_CHARACTERISTIC_UUID -> handleIncomingMessage(value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value = if (characteristic.uuid == DirectChatGattService.HOST_PUBLIC_KEY_CHARACTERISTIC_UUID) {
                ownEphemeralKeyPair?.publicKey
            } else {
                null
            }
            android.util.Log.d("FarososDiag", "server onCharacteristicReadRequest: uuid=${characteristic.uuid} device=${device.address} value=${value != null}")
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        // Hallazgo de campo real (#64): sin este override, la escritura del
        // descriptor CCCD (suscripción a notificaciones) que hace todo
        // central al conectar nunca recibe respuesta ATT - el central espera
        // el timeout de 30s de la transacción GATT y la conexión completa
        // cae, aunque el resto del handshake (lectura/escritura de claves)
        // ya haya terminado bien. La implementación por defecto de
        // `BluetoothGattServerCallback` no manda ninguna respuesta.
        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            android.util.Log.d("FarososDiag", "server onDescriptorWriteRequest: uuid=${descriptor.uuid} device=${device.address}")
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    /**
     * El rescatista escribe su clave pública efímera — con la propia ya
     * generada al conectar, ya se puede derivar la clave de sesión y mandar
     * el historial (#61). `runCatching`: `peerPublicKey` viene de un
     * dispositivo externo sin validar — un valor que no sea una clave
     * X25519 válida (largo incorrecto, punto inválido de la curva) no debe
     * tirar una excepción no capturada en el callback del framework.
     */
    private fun handlePeerPublicKey(peerPublicKey: ByteArray) {
        val ownKeyPair = ownEphemeralKeyPair ?: return
        val key = runCatching {
            ChatCrypto.deriveSessionKey(EphemeralKeyExchange.agree(ownKeyPair.privateKey, peerPublicKey))
        }.getOrNull() ?: run {
            onError?.invoke("Clave pública efímera inválida recibida del rescatista")
            return
        }
        sessionKey = key
        android.util.Log.d("FarososDiag", "server: sessionKey derivado, notificando historial")
        sendEncrypted(key, session.historySnapshot())
    }

    private fun handleIncomingMessage(encryptedPayload: ByteArray) {
        val key = sessionKey ?: return
        val plaintext = runCatching { ChatCrypto.decrypt(key, encryptedPayload) }.getOrNull() ?: return
        val message = ChatMessageWireFormat.decode(String(plaintext, Charsets.UTF_8)).singleOrNull() ?: return
        session.receiveMessage(message.text, message.sentAtEpochSeconds)
    }

    /** Notifica un mensaje propio nuevo al rescatista conectado, si hay uno. */
    fun sendOwnMessage(text: String) {
        val timestamp = System.currentTimeMillis() / 1000
        session.sendMessage(text, timestamp)
        val key = sessionKey ?: return
        sendEncrypted(key, listOf(ChatMessage(fromVictim = true, text = text, sentAtEpochSeconds = timestamp)))
    }

    @SuppressLint("MissingPermission")
    private fun sendEncrypted(key: ByteArray, messages: List<ChatMessage>) {
        val device = connectedDevice ?: return
        val server = gattServer ?: return
        val service = server.getService(DirectChatGattService.SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(DirectChatGattService.MESSAGE_CHARACTERISTIC_UUID) ?: return
        val payload = sealFitting(key, messages) ?: return
        characteristic.value = payload
        val notified = server.notifyCharacteristicChanged(device, characteristic, false)
        android.util.Log.d("FarososDiag", "server: notifyCharacteristicChanged=$notified bytes=${payload.size}")
    }

    /**
     * Una notificación GATT nunca se fragmenta a nivel de protocolo - tiene
     * que caber entera en un solo paquete ATT. Con el MTU de 517 bytes que
     * este proyecto negocia, el máximo utilizable es 514; este valor se
     * queda por debajo con margen de sobra (mismo criterio que el lado iOS,
     * `ChatHostSession.maxSealedHistoryBytes`). Hallazgo de campo (#64): el
     * historial acumulado de una conversación real supera esto fácilmente,
     * y una notificación más grande que el paquete se trunca en silencio -
     * el tag de AES-GCM nunca calza y el receptor descarta todo el
     * historial, no solo lo que sobraba. Descarta los mensajes más viejos,
     * uno por uno, hasta que el blob cifrado entre. `null` solo si ni el
     * mensaje más reciente por sí solo entra (mensaje individual demasiado
     * largo).
     */
    private fun sealFitting(key: ByteArray, messages: List<ChatMessage>): ByteArray? {
        var candidate = messages
        while (true) {
            val sealed = ChatCrypto.encrypt(key, ChatMessageWireFormat.encode(candidate).toByteArray(Charsets.UTF_8))
            if (sealed.size <= MAX_SEALED_HISTORY_BYTES) return sealed
            if (candidate.isEmpty()) return null
            candidate = candidate.drop(1)
        }
    }

    private companion object {
        const val MAX_SEALED_HISTORY_BYTES = 480
    }
}
