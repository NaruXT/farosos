package com.farosos.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.farosos.beaconradio.DirectChatGattService
import com.farosos.directchat.ChatCrypto
import com.farosos.directchat.ChatMessage
import com.farosos.directchat.ChatMessageWireFormat
import com.farosos.directchat.EphemeralKeyExchange

/**
 * Rol rescatista (cliente) del canal de chat directo (#61/#63) — extiende
 * el patrón de conexión ya existente en `BleScanner` (`connectGatt`), pero
 * a diferencia de esa lectura de una sola vez (leer y desconectar), acá la
 * conexión se mantiene abierta mientras dure la conversación.
 *
 * Secuencia al conectar: lee la clave pública efímera de la víctima (host)
 * → genera la propia → escribe la propia en la característica *separada*
 * del rescatista (guest) - nunca en la misma que se leyó, cada una tiene un
 * único sentido (dispara que la víctima calcule su versión de la clave de
 * sesión y notifique el historial) → calcula la clave de sesión de este
 * lado → habilita notificaciones en la característica de mensajes (una
 * sola, escritura+notificación combinadas). Ambos lados llegan a la misma
 * clave de forma independiente, sin que ninguno tenga que esperar al otro
 * más que lo que ya exige leer antes de escribir. Mismo protocolo exacto
 * que el lado iOS (#62) - reescrito durante la verificación de campo de
 * #64 para que ambas plataformas coincidan (ver el comentario de
 * `DirectChatGattService`).
 */
class ChatGattClient(private val context: Context) {
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onMessagesReceived: ((List<ChatMessage>) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var ownKeyPair: EphemeralKeyExchange.KeyPair? = null
    private var sessionKey: ByteArray? = null

    /**
     * No-op si ya hay una conexión en curso - hallazgo de campo (#64): un
     * doble toque en "Abrir chat" (el botón no daba feedback visual
     * inmediato) llamaba a esto dos veces, creando dos `BluetoothGatt`
     * compitiendo por el mismo peer y confundiendo su lado del handshake lo
     * suficiente como para que terminara la conexión real a los ~30s.
     */
    @SuppressLint("MissingPermission") // el caller garantiza BLUETOOTH_CONNECT antes de llamar
    fun connect(device: BluetoothDevice) {
        android.util.Log.d("FarososDiag", "client: connect() llamado device=${device.address} yaHabiaGatt=${gatt != null}")
        if (gatt != null) return
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        sessionKey = null
        ownKeyPair = null
    }

    @SuppressLint("MissingPermission")
    fun sendMessage(text: String) {
        android.util.Log.d("FarososDiag", "sendMessage: gatt=${gatt != null} sessionKey=${sessionKey != null}")
        val key = sessionKey ?: return
        val characteristic = gatt?.getService(DirectChatGattService.SERVICE_UUID)
            ?.getCharacteristic(DirectChatGattService.MESSAGE_CHARACTERISTIC_UUID)
        android.util.Log.d("FarososDiag", "sendMessage: characteristic=${characteristic != null}")
        if (characteristic == null) return
        val message = ChatMessage(fromVictim = false, text = text, sentAtEpochSeconds = System.currentTimeMillis() / 1000)
        val payload = ChatCrypto.encrypt(key, ChatMessageWireFormat.encode(listOf(message)).toByteArray(Charsets.UTF_8))
        characteristic.value = payload
        val started = gatt?.writeCharacteristic(characteristic)
        android.util.Log.d("FarososDiag", "sendMessage: writeCharacteristic iniciado=$started")
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            android.util.Log.d("FarososDiag", "client onConnectionStateChange: status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onError?.invoke("Error de conexión al chat (código $status)")
                onDisconnected?.invoke()
                return
            }
            when (newState) {
                // Hallazgo de campo (#64): las notificaciones GATT (a
                // diferencia de las escrituras, que el propio protocolo ATT
                // fragmenta automáticamente vía "prepare/execute write") se
                // truncan al ATT_MTU vigente sin avisar. Con el MTU por
                // defecto (23 bytes, 20 útiles) cualquier mensaje cifrado con
                // AES-GCM llega cortado y la desencriptación siempre falla -
                // hay que negociar un MTU mayor antes de descubrir servicios.
                BluetoothProfile.STATE_CONNECTED -> {
                    val started = gatt.requestMtu(517)
                    android.util.Log.d("FarososDiag", "client: requestMtu(517) iniciado=$started")
                }
                BluetoothProfile.STATE_DISCONNECTED -> onDisconnected?.invoke()
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            android.util.Log.d("FarososDiag", "onMtuChanged: mtu=$mtu status=$status")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(DirectChatGattService.SERVICE_UUID)
                ?.getCharacteristic(DirectChatGattService.HOST_PUBLIC_KEY_CHARACTERISTIC_UUID)
            android.util.Log.d("FarososDiag", "onServicesDiscovered: status=$status hostKeyChar=${characteristic != null}")
            if (characteristic == null) {
                onError?.invoke("La víctima no expone el servicio de chat")
                return
            }
            val started = gatt.readCharacteristic(characteristic)
            android.util.Log.d("FarososDiag", "readCharacteristic(hostKey) iniciado=$started")
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // minSdk 26 no tiene la variante de 4 parámetros, agregada en API 33 — mismo criterio que BleScanner
        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            android.util.Log.d("FarososDiag", "onCharacteristicRead: uuid=${characteristic.uuid} status=$status bytes=${characteristic.value?.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val victimPublicKey = characteristic.value ?: return
            val ownPair = EphemeralKeyExchange.generateKeyPair()
            // `runCatching`: la clave leída viene del otro dispositivo sin
            // validar — un valor que no sea una clave X25519 válida no debe
            // tirar una excepción no capturada en el callback del framework.
            val key = runCatching {
                ChatCrypto.deriveSessionKey(EphemeralKeyExchange.agree(ownPair.privateKey, victimPublicKey))
            }.getOrNull()
            if (key == null) {
                android.util.Log.d("FarososDiag", "derivación de clave falló, bytes de la clave del host=${victimPublicKey.size}")
                onError?.invoke("Clave pública efímera inválida recibida de la víctima")
                return
            }
            ownKeyPair = ownPair
            sessionKey = key

            val guestCharacteristic = gatt.getService(DirectChatGattService.SERVICE_UUID)
                ?.getCharacteristic(DirectChatGattService.GUEST_PUBLIC_KEY_CHARACTERISTIC_UUID) ?: return
            guestCharacteristic.value = ownPair.publicKey
            val started = gatt.writeCharacteristic(guestCharacteristic)
            android.util.Log.d("FarososDiag", "writeCharacteristic(guestKey) iniciado=$started")
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            android.util.Log.d("FarososDiag", "onCharacteristicWrite: uuid=${characteristic.uuid} status=$status")
            if (characteristic.uuid != DirectChatGattService.GUEST_PUBLIC_KEY_CHARACTERISTIC_UUID) return
            enableNotifications(gatt)
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(gatt: BluetoothGatt) {
            val characteristic = gatt.getService(DirectChatGattService.SERVICE_UUID)
                ?.getCharacteristic(DirectChatGattService.MESSAGE_CHARACTERISTIC_UUID) ?: return
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(DirectChatGattService.CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            android.util.Log.d("FarososDiag", "writeDescriptor(CCCD) preparado")
            gatt.writeDescriptor(descriptor)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            android.util.Log.d("FarososDiag", "onDescriptorWrite: uuid=${descriptor.uuid} status=$status")
            if (descriptor.uuid == DirectChatGattService.CLIENT_CHARACTERISTIC_CONFIG_UUID) onConnected?.invoke()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            android.util.Log.d("FarososDiag", "onCharacteristicChanged: uuid=${characteristic.uuid} bytes=${characteristic.value?.size}")
            if (characteristic.uuid != DirectChatGattService.MESSAGE_CHARACTERISTIC_UUID) return
            val key = sessionKey ?: return
            val payload = characteristic.value ?: return
            val plaintext = runCatching { ChatCrypto.decrypt(key, payload) }.getOrNull()
            android.util.Log.d("FarososDiag", "onCharacteristicChanged: decrypt exitoso=${plaintext != null}")
            if (plaintext == null) return
            onMessagesReceived?.invoke(ChatMessageWireFormat.decode(String(plaintext, Charsets.UTF_8)))
        }
    }
}
