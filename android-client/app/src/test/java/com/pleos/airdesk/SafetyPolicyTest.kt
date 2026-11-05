package com.pleos.airdesk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {
    @Test fun defaultIsLocked() {
        assertFalse(RemoteGrant().canRemoteControl)
    }
    @Test fun unknownNeverGrants() {
        assertFalse(RemoteGrant(safety = VehicleSafety.UNKNOWN,
            foreground = true, uxAllowed = true, authenticated = true,
            pcUserApproved = true, heartbeatFresh = true).canRemoteControl)
    }
    @Test fun drivingBlocks() {
        assertFalse(RemoteGrant(safety = VehicleSafety.DRIVING,
            foreground = true, uxAllowed = true, authenticated = true,
            pcUserApproved = true, heartbeatFresh = true).canRemoteControl)
    }
    @Test fun onlyAllVerifiedCanGrant() {
        assertTrue(RemoteGrant(safety = VehicleSafety.PARKED_CONFIRMED,
            foreground = true, uxAllowed = true, authenticated = true,
            pcUserApproved = true, heartbeatFresh = true).canRemoteControl)
    }
}
