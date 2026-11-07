# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

- **Household viewers (primary on the TV):** the owner's family — including older and younger members — watching live TV on the living-room television from the sofa, about 3 m away, using only a D-pad remote. Their job: turn it on, see something playing, change channels, find a channel by group or number. They never configure anything.
- **The household admin (the owner):** sets up the box once and maintains it — adds M3U subscriptions, EPG, multicast/udpxy, decoder mode — mostly from a phone or PC browser on the same LAN via the built-in web admin, occasionally from the TV settings panel.

## Product Purpose

WiTV is a self-hosted IPTV player for Android TV boxes (developed and tested on a Phicomm N1, Android 7.1). It plays the owner's own M3U subscriptions as a living-room TV experience: it boots straight into playback (it can act as the launcher), zaps channels with the remote, and quietly survives unstable sources. Success means the family watches TV without asking the admin for help, and the admin can fix things from the phone without touching the remote.

## Positioning

- Setup happens on the phone, not with the remote: the empty state shows a QR code and LAN address for the embedded web admin (port 9979).
- Each channel can have several playback addresses; WiTV tries them in order, switches on timeout, and recovers from stalls — unreliable sources are the normal case, not the exception.
- Channel menu is three columns — group / channel / today's programme guide — built for remote navigation.
- Built for weak, old boxes: hardware decoding first, with honest per-device decoder options.

## Operating Context

- Living room, TV at 1920×1080, viewed from ~3 m; remote with D-pad, OK, Back, Menu, number keys; no touch, no pointer.
- Video plays full screen underneath every overlay; overlays must read over arbitrary, often bright, video.
- The web admin is opened from a phone (QR code) or desktop browser on the LAN; there is no internet-hosted service and no account system.
- Low-end ARM boxes (N1 class, 2 GB RAM, Android 7.1): CPU and GPU budget is small while 1080p/4K video is decoding.

## Capabilities and Constraints

- TV: full-screen player; bottom info overlay (channel, now/next programme, signal info); three-column channel menu; settings panel (address management, stream switching, timeout, EPG, playback options, multicast/UDP, decoder mode, help); empty state with QR code; buffering indicator; playback-failed card (retry / next channel / channel list); number-key channel input; favourites; exit dialog (settings / switch app / rest); optional load-speed overlay; can run as the default launcher.
- Web admin (plain HTML/CSS/JS served by NanoHTTPD from `app/src/main/assets/web/`): add / switch / reload / delete M3U sources, browse channels and favourites, per-channel EPG, EPG URL, udpxy proxy setting.
- Stack: Java, Android Views (XML layouts), Media3/ExoPlayer, Room; minSdk 23, compileSdk 36. No Compose.
- The channel menu's three-column structure and panel width are intentional and must be kept, as is hiding the menu after idle even while it has focus.
- UI language is Simplified Chinese.

## Brand Commitments

- Name **WiTV** / `witv` and the existing `app_banner` logo stay unchanged.

## Evidence on Hand

- Real channel data comes from the owner's M3U subscriptions (e.g. a Beijing Unicom multicast list) and an XMLTV EPG; logos come from the playlist.
- User guide with screenshots: `docs/user-guide.md`. Development write-up: `docs/witv-ai.md`.
- No testimonials, user counts, or store presence exist; do not invent any.

## Product Principles

1. The person on the sofa never configures anything: watching needs only arrows, OK, and Back.
2. Configure on the phone; the TV shows the way there (QR code, address) instead of asking for typing.
3. Failure is normal: every stall or dead source says what happened and offers the next step.
4. Readable from the sofa over any picture, on the weakest supported box.
5. The focused element is always unmistakable.

## Accessibility & Inclusion

- Older family members are primary viewers: large type, high contrast over video, unambiguous focus, plain words instead of technical terms on viewer-facing screens.
- Web admin must work by touch on a phone and by keyboard on desktop (WCAG AA contrast, 44 px targets, visible focus).
