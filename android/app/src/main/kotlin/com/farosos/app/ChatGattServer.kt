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

        val ephemeralPublicKeyCharacteristic = BluetoothGattCharacteristic(
            DirectChatGattService.EPHEMERAL_PUBLIC_KEY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val messageWriteCharacteristic = BluetoothGattCharacteristic(
            DirectChatGattService.MESSAGE_WRITE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val messageNotifyCharacteristic = BluetoothGattCharacteristic(
            DirectChatGattService.MESSAGE_NOTIFY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        )
        messageNotifyCharacteristic.addDescriptor(
            BluetoothGattDescriptor(
                DirectChatGattService.CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        service.addCharacteristic(ephemeralPublicKeyCharacteristic)
        service.addCharacteristic(messageWriteCharacteristic)
        service.addCharacteristic(messageNotifyCharacteristic)
        return service
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(manager: BluetoothManager) {
        val advertiser = manager.adapter?.bluetoothLeAdvertiser ?: run {
            onError?.invoke("Este dispositivo no tiene BluetoothLeAdvertiser disponible")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
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

    private fun resetSession() {
        session.connectionClosed()
        connectedDevice = null
        ownEphemeralKeyPair = null
        sessionKey = null
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!session.acceptConnection()) {
                        // Ya hay un rescatista conectado — rechaza al segundo (#63, AC "una sola conexión a la vez").
                        gattServer?.cancelConnection(device)
                        return
                    }
                    connectedDevice = device
                    ownEphemeralKeyPair = EphemeralKeyExchange.generateKeyPair()
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
            if (device.address != connectedDevice?.address) return
            when (characteristic.uuid) {
                DirectChatGattService.EPHEMERAL_PUBLIC_KEY_CHARACTERISTIC_UUID -> handlePeerPublicKey(value)
                DirectChatGattService.MESSAGE_WRITE_CHARACTERISTIC_UUID -> handleIncomingMessage(value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value = if (characteristic.uuid == DirectChatGattService.EPHEMERAL_PUBLIC_KEY_CHARACTERISTIC_UUID) {
                ownEphemeralKeyPair?.publicKey
            } else {
                null
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
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
        val characteristic = service.getCharacteristic(DirectChatGattService.MESSAGE_NOTIFY_CHARACTERISTIC_UUID) ?: return
        val payload = ChatCrypto.encrypt(key, ChatMessageWireFormat.encode(messages).toByteArray(Charsets.UTF_8))
        characteristic.value = payload
        server.notifyCharacteristicChanged(device, characteristic, false)
    }
}
