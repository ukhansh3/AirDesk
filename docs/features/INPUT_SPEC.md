# Remote input specification (DRAFT / DISABLED)

Allowed candidate commands: pointer.move/down/up/scroll, keyboard.down/up/text and input.release_all.
A production input command needs: mutually authenticated connection, host approval, explicit active consent, fresh sequence, bounded normalized coordinates, and parked/UX-unrestricted vehicle safety.
Host must independently reject input after 500–1500ms heartbeat expiry (choose after testing) and release held buttons/keys.
Windows SendInput has UIPI constraints; do not elevate, inject to secure desktop, bypass UAC or capture passwords.
Gesture hints must NEVER directly trigger OS input without authorization. The initial demo intentionally has no enabled input receiver.
