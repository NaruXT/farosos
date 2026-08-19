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
 * Secuencia al conectar: lee la clave pública efímera de la víctima →
 * genera la propia → escribe la propia en la misma característica (dispara
 * que la víctima calcule su versión de la clave de sesión y notifique el
 * historial) → calcula la clave de sesión de este lado → habilita
 * notificaciones. Ambos lados llegan a la misma clave de forma
 * independiente, sin que ninguno tenga que esperar al otro más que lo que
 * ya exige leer antes de escribir.
 */
class ChatGattClient(private val context: Context) {
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onMessagesReceived: ((List<ChatMessage>) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var ownKeyPair: EphemeralKeyExchange.KeyPair? = null
    private var sessionKey: ByteArray? = null

    @SuppressLint("MissingPermission") // el caller garantiza BLUETOOTH_CONNECT antes de llamar
    fun connect(device: BluetoothDevice) {
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
        val key = sessionKey ?: return
        val characteristic = gatt?.getService(DirectChatGattService.SERVICE_UUID)
            ?.getCharacteristic(DirectChatGattService.MESSAGE_WRITE_CHARACTERISTIC_UUID) ?: return
        val message = ChatMessage(fromVictim = false, text = text, sentAtEpochSeconds = System.currentTimeMillis() / 1000)
        val payload = ChatCrypto.encrypt(key, ChatMessageWireFormat.encode(listOf(message)).toByteArray(Charsets.UTF_8))
        characteristic.value = payload
        gatt?.writeCharacteristic(characteristic)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onError?.invoke("Error de conexión al chat (código $status)")
                onDisconnected?.invoke()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> onDisconnected?.invoke()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(DirectChatGattService.SERVICE_UUID)
                ?.getCharacteristic(DirectChatGattService.EPHEMERAL_PUBLIC_KEY_CHARACTERISTIC_UUID)
            if (characteristic == null) {
                onError?.invoke("La víctima no expone el servicio de chat")
                return
            }
            gatt.readCharacteristic(characteristic)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // minSdk 26 no tiene la variante de 4 parámetros, agregada en API 33 — mismo criterio que BleScanner
        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
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
                onError?.invoke("Clave pública efímera inválida recibida de la víctima")
                return
            }
            ownKeyPair = ownPair
            sessionKey = key

            characteristic.value = ownPair.publicKey
            gatt.writeCharacteristic(characteristic)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != DirectChatGattService.EPHEMERAL_PUBLIC_KEY_CHARACTERISTIC_UUID) return
            enableNotifications(gatt)
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(gatt: BluetoothGatt) {
            val characteristic = gatt.getService(DirectChatGattService.SERVICE_UUID)
                ?.getCharacteristic(DirectChatGattService.MESSAGE_NOTIFY_CHARACTERISTIC_UUID) ?: return
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(DirectChatGattService.CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == DirectChatGattService.CLIENT_CHARACTERISTIC_CONFIG_UUID) onConnected?.invoke()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != DirectChatGattService.MESSAGE_NOTIFY_CHARACTERISTIC_UUID) return
            val key = sessionKey ?: return
            val payload = characteristic.value ?: return
            val plaintext = runCatching { ChatCrypto.decrypt(key, payload) }.getOrNull() ?: return
            onMessagesReceived?.invoke(ChatMessageWireFormat.decode(String(plaintext, Charsets.UTF_8)))
        }
    }
}
