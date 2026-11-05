package com.pleos.airdesk

enum class VehicleSafety { UNKNOWN, PARKED_CONFIRMED, DRIVING, UX_RESTRICTED, UNSUPPORTED }

data class RemoteGrant(
    val safety: VehicleSafety = VehicleSafety.UNKNOWN,
    val foreground: Boolean = false,
    val uxAllowed: Boolean = false,
    val authenticated: Boolean = false,
    val pcUserApproved: Boolean = false,
    val heartbeatFresh: Boolean = false,
) {
    val canRemoteControl: Boolean
        get() = safety == VehicleSafety.PARKED_CONFIRMED &&
            foreground && uxAllowed && authenticated && pcUserApproved && heartbeatFresh
}

/** No vehicle SDK implementation in the initial diagnostic project.
 * Production integrations MUST provide verified source + lifecycle and fail closed. */
interface VehicleSafetyGateway {
    val current: VehicleSafety
}
class UnavailableVehicleSafetyGateway : VehicleSafetyGateway {
    override val current: VehicleSafety = VehicleSafety.UNKNOWN
}
