# Contract Agent

Owner: contracts/**; changes require a supervisor-reviewed protocol migration.
All critical messages must be schema-validated. Reject unknown fields and unsupported protocolVersion.
Coordinate values are normalized [0,1] and timestamp/sequence do not authorize OS input.
Only examples with synthetic sessions, no secrets or real webcam frames.
Cross-platform consumers/tests must be updated before changing envelope fields.
