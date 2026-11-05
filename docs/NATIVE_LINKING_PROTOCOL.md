# Native Linking transport findings

Research snapshot: 2026-10-17. These are protocol facts from static inspection of the installed Partner desktop program (2.5.24+260), `SupernoteLauncher.apk` (1.01.076), and the active `keyboard.apk` (1.00.276) on the user's Nomad. The APKs were pulled as code only. No app data, account credentials, notes, or clipboard contents were inspected. Live requests and user-visible behavior are separate validation steps; source inspection alone does not establish interoperability.

A bounded physical-device pair request on this firmware subsequently returned the native 47-byte HTTP response with body `code:201` (refused). This verifies that the request reached the pair decision path and that refusal can be recognized. It does not establish the approval path, keyboard/clipboard compatibility, or whether the dialog visually matches desktop Partner; those checks remain pending.

This document describes behavior, not copied vendor source. The native path is specific to the inspected firmware and Partner versions; ports and message formats can change.

## Discovery and user approval

`DeviceSearchResponse.SearchRespThread` in Launcher listens on UDP 8100 when `WifiP2PService` is active. An exact UTF-8 `Supernote_Partner_Peer_Rquest` datagram receives a comma-separated `deviceName,serialNumber,2` reply to the sender. The serial number is device-identifying data; a client should use it internally and avoid logs, screenshots, and analytics. Discovery is not pairing or authorization. Restrict requests to the currently connected private IPv4 Wi-Fi network and cap response size and wait time.

Launcher `ReceiverManager` listens on TCP 60002 while that service is active. Its header parser reads GBK lines until a CRLF blank line. The first request line is ignored. A token-free, ASCII pair request uses:

```text
GET / HTTP/1.0\r\n
DeviceName: SuperScanner-<unique session suffix>\r\n
Status: 1\r\n
PairCode: <six random digits>\r\n
\r\n
```

`WifiP2PService` displays the supplied device name and pair code in its native pair-confirm dialog. The user must compare the code shown on the phone with the code shown on the Nomad before approving. The held TCP connection receives an HTTP/1.0 `200 OK` status line for **both** outcomes; the body has **unquoted** `code:200` for approval or `code:201` when approval did not occur. `code:201` can mean the user tapped the negative action, but `P2PDialogController` also produces it after its 60-second decision timeout, missing overlay permission, overlay-show failure, or service destruction. UI wording should say “Supernote did not approve the request,” without attributing the cause to the user. The device writes this response using `PrintStream.println`, so a parser must accept LF line endings (and optionally CRLF), then parse the bounded body rather than the HTTP status line. A timeout or socket close does not by itself remove the pending dialog. A second short request with the same unique `DeviceName`, `Status: 2`, and a CRLF blank line cancels that pending device-name session. Use a unique name per attempt to avoid affecting another prompt.

Do not send `pairing-token` or `pairing-devices` headers for a keyboard/clipboard-only session: Launcher writes those values into its INBOX file-transfer cache **before** the user approves. The receiver's subsequent token validation is for file uploads, not evidence that keyboard packets are authenticated. This pair dialog offers a genuine on-device user decision that our client can use as a local session gate. Static inspection does not show that the approval cryptographically binds later UDP/TCP keyboard or clipboard traffic to the approved phone.

## Keyboard and clipboard service

The built-in Pinyin IME unconditionally auto-binds `com.android.inputmethod.keyboard.flutter.UDPSS` when the IME starts. `UDPSS` listens on UDP 12345 and TCP 12346. Listener presence therefore does not establish Partner pairing or that Keyboard Sharing is enabled. On startup, `linkStatus` is initialized from whether localhost TCP 60002 is open; Launcher Wi-Fi-transfer START/STOP broadcasts later toggle it. This is a service-availability gate, not a peer identity check.

`UDPSS` overwrites its return `clientAddress` and `clientPort` with the source of every received UDP datagram before parsing it. Its receive path has no per-packet token, peer fingerprint, or authenticated session check. A received datagram that is not recognized as a key/control command falls through to setting the Android primary clipboard to that text. The service also registers a local clipboard-change listener: after a local clipboard change, it sends text to the most recently recorded UDP peer, unless suppressing an echo of a remotely written clip. These facts make transport traffic exposed to other hosts on the same LAN. The phone app must make this limitation clear and must not silently treat the native pair dialog as transport security.

Recognized keyboard datagrams are ASCII text in the form `KEY_DOWN_EVENT|<packedCode>|<metaState>` and `KEY_UP_EVENT|<packedCode>|<metaState>`. All numeric fields are decimal integers. The protocol mode bits are `0x4000` for down and `0x8000` for up; the Android keycode is `packedCode & ~0xC000`. The third number maps Android KeyEvent meta flags (shift `1`, alt `2`, sym `4`, ctrl `0x1000`, meta `0x10000`, caps `0x100000`, num `0x200000`, scroll `0x400000`). The device injects key events only while its Pinyin IME reports a visible/shown keyboard. UDP provides no delivery acknowledgement or sequence number, so a client must avoid automatic replay of uncertain key events.

The inspected receiver's control strings are `OPEN|49152|0`, `CLOSE|49152|0`, and `REMOVE_PAIR|0|0`. `OPEN` broadcasts `keyboard_status=true` and schedules one keyboard-visible/hidden notification after about one second. The status handler returns without rescheduling; key-down processing and keyboard show/hide broadcasts also report status. These are events, not a periodic visibility lease. A quiet three-second interval therefore does not imply a hidden keyboard. The client retains the latest explicit status only within an active keyboard session, resetting it on OPEN, CLOSE, session close, or route loss. UDP loss can still hide a state change; the protocol has no stronger visibility acknowledgement.

`CLOSE` broadcasts false and cancels pending status notifications; `REMOVE_PAIR` also resets its heartbeat schedule. None clears the stored return peer address. Clipboard-only receive registers the approved phone's UDP return address with the known CLOSE frame, leaving keyboard sharing off. The tablet sends `HeartBeat` to that peer on its own 15-second timer; `HeartBeat` is **not** an inbound request and would be treated as clipboard text if sent to the tablet. Do not use a guessed command as a liveness probe because unrecognized text mutates the device clipboard.

The desktop Partner AOT bundle contains keyboard-state/token-related method names (`getKeyboardState2`, `consumeKeyboardToken`, `sendKeyBoard`) and keyboard/clipboard client names, but those literal method names do not appear in either inspected device APK. Their call direction, complete state schema, and role in the desktop client remain unverified.

## Clipboard frames and user action

`UDPSS` forwards a newly changed tablet clipboard to the most recent UDP sender. For short text (up to 1,000 Java characters), the datagram is `CLIP|<text>`. For larger text, it sends `CLIP_TCP_NOTIFY|<UTF-8 byte count>`, holds that text for up to 30 seconds, and the client may open TCP 12346 and send `CLIP_TCP_FETCH\n`. The one-shot response is `CLIP_TCP_CONTENT|<UTF-8 byte count>|<raw UTF-8 bytes>\n`. The byte count, rather than character count, determines the read boundary. A fetch does not reveal an older/current clipboard value if no new change was announced. The client should arm a bounded receive only after an explicit user action and then ask the user to copy the desired text on the Nomad.

An explicit phone-to-tablet send opens TCP 12346 and writes `TCP_STRING|<UTF-8 byte count>|<raw UTF-8 bytes>`. The inspected receiver sets the Android primary clipboard from this message. It sends no positive delivery acknowledgement, so successful local socket write must not be described as confirmed tablet paste. The library caps each clipboard text at 64 KiB of strict UTF-8 and rejects whole oversized or malformed values. It neither polls nor reads either system clipboard automatically.

The native keyboard and clipboard frames are plaintext and carry no source authentication. The client's approval result only gates its **own** outbound actions; it cannot secure the tablet's listener against another LAN host. Connected UDP sockets restrict which sender's packets the app accepts, and pinned Wi-Fi routing prevents accidental cellular/VPN fallback, but neither creates encrypted or authenticated native transport.

## Client boundary and validation still needed

The Android client separates discovery, approval, and transport: `discover()` returns bounded private-Wi-Fi candidates; `preparePair(device)` creates a one-time random comparison code before sending; only the native dialog's `code:200` returns an `Approved` session. `openKeyboard()` and explicit clipboard send/receive actions require that in-memory session. Closing it, changing the selected Wi-Fi network/address, or ending the app session clears the local approval. It never borrows desktop Partner's cached identity or account state. It pins response IP/port where the protocol permits, caps bytes and deadlines, and does not automatically retry uncertain keyboard or clipboard sends.

Before implementation is called interoperable, Luna device checks must confirm: the discovery reply on this firmware; that the pair dialog matches the user's normal Linking experience; rejection/timeout cancellation; exact key-code behavior in a focused text field; clipboard framing and Unicode/size behavior in both directions; and whether leaving Keyboard Sharing or disabling Wi-Fi changes the tablet's actual behavior. The current inspection does not prove a private or authenticated LAN channel, nor a direct read of the tablet's current clipboard without a new copy event.
