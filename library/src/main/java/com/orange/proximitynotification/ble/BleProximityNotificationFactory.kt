/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Authors
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 * Created by Orange / Date - 2020/04/27 - for the STOP-COVID project
 */

package com.orange.proximitynotification.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import com.orange.proximitynotification.ble.advertiser.BleAdvertiserImpl
import com.orange.proximitynotification.ble.gatt.BleGattClientProviderImpl
import com.orange.proximitynotification.ble.gatt.BleGattManagerImpl
import com.orange.proximitynotification.ble.scanner.BleScannerImpl
import kotlinx.coroutines.CoroutineScope
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat

object BleProximityNotificationFactory {

    fun build(
        context: Context,
        settings: BleSettings,
        coroutineScope: CoroutineScope
    ): BleProximityNotification? {

        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        val bleGattClientProvider = BleGattClientProviderImpl(context)
        val bleAdvertiser =
            bluetoothAdapter.bluetoothLeAdvertiser?.let { BleAdvertiserImpl(settings, it) }
        val bleScanner = BleScannerImpl(settings, BluetoothLeScannerCompat.getScanner())
        val bleGattManager = BleGattManagerImpl(
            settings,
            context,
            bluetoothManager,
            bleGattClientProvider,
            coroutineScope
        )

        return BleProximityNotification(
            bleScanner,
            bleAdvertiser,
            bleGattManager,
            settings,
            coroutineScope
        )
    }
}
