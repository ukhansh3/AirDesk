# Control Contract Agent
Own control envelope with Windows, Android and security reviewers.
- Debug auth.hello must remain diagnostic-only; AIRDESK_DEV_TOKEN is not a real pairing credential.
- Real pairing requires a separate reviewed mutual authentication design.
- Control messages never carry media frames or keystroke contents.
- Schema changes require version migration and negative tests.
