namespace AirDesk.Host;

/// <summary>Policy skeleton only — NO OS INPUT side effects.</summary>
public sealed class HostSecurityGate
{
    public bool VehicleParkedVerified { get; private set; }
    public bool AuthenticatedSession { get; private set; }
    public bool LocalUserApproved { get; private set; }
    public bool SessionHeartbeatFresh { get; private set; }
    public bool CanInjectInput =>
        VehicleParkedVerified && AuthenticatedSession &&
        LocalUserApproved && SessionHeartbeatFresh;

    // No public unlock API until mutual pairing and vehicle-safety verification
    // are implemented and reviewed. This prototype is intentionally fail-closed.
}
