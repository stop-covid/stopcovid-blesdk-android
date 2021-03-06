/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Authors
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 * Created by Orange / Date - 2020/04/27 - for the STOP-COVID project
 */

package com.orange.proximitynotification.ble.gatt

import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.orange.proximitynotification.ble.BleSettings
import com.orange.proximitynotification.tools.CoroutineContextProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.receiveOrNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

internal class BleGattManagerImpl(
    override val settings: BleSettings,
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val gattClientProvider: BleGattClientProvider,
    private val coroutineScope: CoroutineScope,
    private val coroutineContextProvider: CoroutineContextProvider = CoroutineContextProvider.Default()
) : BleGattManager {

    private lateinit var executionChannel: Channel<suspend () -> Unit>
    private var executionJob: Job? = null

    private var bluetoothGattServer: BluetoothGattServer? = null

    // Characteristic used to read payload
    private val payloadCharacteristic = BluetoothGattCharacteristic(
        settings.servicePayloadCharacteristicUuid,
        BluetoothGattCharacteristic.PROPERTY_WRITE,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    )

    override fun start(callback: BleGattManager.Callback) {
        Timber.d("Starting GATT server")

        stop()

        bluetoothGattServer =
            bluetoothManager.openGattServer(context, GattServerCallback(callback)).apply {
                clearServices()
                addService(buildGattService())
            }

        executionJob = executionJob()
    }

    override fun stop() {
        Timber.d("Stopping GATT server")

        executionJob?.cancel()
        executionJob = null

        bluetoothGattServer?.apply {
            clearServices()
            close()
        }
        bluetoothGattServer = null
    }

    override suspend fun requestRemoteRssi(device: BluetoothDevice): Int? {

        val rssiChannel = Channel<Int>()

        execute {
            val client = gattClientProvider.fromDevice(device)

            try {
                withTimeout(settings.connectionTimeout) {
                    client.open()
                }

                val rssi = client.readRemoteRssi()
                rssiChannel.send(rssi)
            } catch (e: TimeoutCancellationException) {
                Timber.d(e,"Request remote rssi failed. Connection timeout")
                rssiChannel.close()
            } catch (t: Throwable) {
                Timber.d(t,"Request remote rssi failed with exception")
                rssiChannel.close()
            } finally {
                client.close()
            }
        }

        return rssiChannel.receiveOrNull()
    }

    private fun buildGattService(): BluetoothGattService {
        return BluetoothGattService(
            settings.serviceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply {
            addCharacteristic(payloadCharacteristic)
        }
    }

    private fun executionJob() = coroutineScope.launch {
        executionChannel = Channel(UNLIMITED)
        executionChannel
            .receiveAsFlow()
            .flowOn(coroutineContextProvider.io)
            .collect {
                it()
            }
    }.apply { invokeOnCompletion { executionChannel.close(it) } }

    private fun execute(block: suspend () -> Unit) {
        runCatching {
            executionChannel.offer(block)
        }
    }

    private inner class GattServerCallback(private val callback: BleGattManager.Callback) :
        BluetoothGattServerCallback() {

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) = execute {
            super.onCharacteristicWriteRequest(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )

            Timber.d("onCharacteristicWriteRequest")

            val result = when (characteristic.uuid) {
                payloadCharacteristic.uuid -> {
                    if (offset != 0) {
                        BluetoothGatt.GATT_INVALID_OFFSET
                    } else {
                        value?.let { callback.onWritePayloadRequest(device, it) }
                        BluetoothGatt.GATT_SUCCESS
                    }
                }
                else -> BluetoothGatt.GATT_FAILURE

            }

            Timber.d("onCharacteristicWriteRequest result=$result")
            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(device, requestId, result, offset, null)
            }
        }

    }


}