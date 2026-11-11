package me.kavishdevar.librepods.core.bluetooth.aacp

import android.util.Log
import com.google.protobuf.ByteString
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RTBuddyPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorServiceSetting
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorServiceType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.SensorDataWxBuddyPayload
import me.kavishdevar.librepods.core.devices.PacketDestination
import kotlin.time.Duration

class RTBuddyManager(
    private val sendPacket: (AACPPacket) -> Boolean,
) {
    companion object {
        private const val TAG = "RTBuddyManager"

        /**
         * AirPods Max 2 (A3454, firmware 8E258) ignore the static DEVMOTION6 ID (16): the firmware
         * advertises devmotion6 as a dynamically assigned RTBuddy service with ID 6.
         * Found by https://github.com/FIocker/aap-head-tracker.
         */
        const val MAX_2_DEVMOTION6_SERVICE_ID = 6
    }

    private var sequence = 0

    private fun nextSequence(): Int {
        val next = sequence
        sequence = (sequence + 1) and 0x7FFFFFFF
        return next
    }

    private fun observeSequence(data: SensorDataWX) {
        Log.d(TAG, "Received SensorDataWX seq=${data.seq}")
    }

    fun handlePacket(packet: RTBuddyPacket) {
        when (val payload = packet.rtBuddyPayload) {
            is SensorDataWxBuddyPayload -> {
                val data = payload.data
                observeSequence(data)
            }

            else -> {
                Log.d(
                    TAG,
                    "Unhandled RTBuddy payload: ${payload.descriptor}"
                )
            }
        }
    }

    fun sendSensorData(
        data: SensorDataWX,
    ): Boolean {
        val dataWithSequence = data.toBuilder()
            .setSeq(nextSequence())
            .build()

        val packet = RTBuddyPacket.create(
            rtBuddyPayload = SensorDataWxBuddyPayload(
                data = dataWithSequence,
            ),
            destination = PacketDestination.DEVICE,
        )

        return sendPacket(packet)
    }

    private fun sendSensorServiceSetting(
        serviceId: Int,
        configuration: ByteArray,
    ): Boolean {
        val data = SensorDataWX.newBuilder()
            .setServiceSettings(
                SensorServiceSetting.newBuilder()
                    .setServiceValue(serviceId)
                    .setSetting(2)
                    .setConfiguration(
                        ByteString.copyFrom(configuration)
                    )
            )
            .build()

        return sendSensorData(data)
    }

    fun setSensorServiceReportInterval(sensorServiceType: SensorServiceType, interval: Duration): Boolean =
        setSensorServiceReportInterval(sensorServiceType.number, interval)

    /** For services whose ID is not in the static [SensorServiceType] enum. */
    fun setSensorServiceReportInterval(serviceId: Int, interval: Duration): Boolean = sendSensorServiceSetting(
        serviceId = serviceId,
        configuration = byteArrayOf(0x01) + interval.toMicrosUINT32LE()
    )
}

private fun Duration.toMicrosUINT32LE(): ByteArray {
    val microseconds = this.inWholeMicroseconds
    return byteArrayOf(
        microseconds.toByte(),
        (microseconds shr 8).toByte(),
        (microseconds shr 16).toByte(),
        (microseconds shr 24).toByte()
    )
}
