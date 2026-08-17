# Flight Search — Android client

Native Kotlin/Compose client for the flight-search backend that runs on your PC. It talks to
the same `/api/search` the web UI uses, so both frontends see identical results.

This is a standalone Gradle build — the Spring `settings.gradle.kts` one directory up does not
include it, and building the backend never builds this.

## Build

Needs the Android SDK (`ANDROID_HOME` or `local.properties` → `sdk.dir`) and a JDK 17+.

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Install on a phone plugged in over USB with debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and open it (Android will ask to allow installing from this source).

## Connecting the phone to the backend

The backend binds all interfaces on port 8080, so nothing needs changing on the server side —
only the address you type into the app's settings screen changes.

### Same Wi-Fi

1. On the PC, run `ipconfig` and take the IPv4 address of the Wi-Fi adapter (e.g. `192.168.0.149`).
2. **Try it before touching the firewall.** With the backend under Docker and 8080 published,
   Docker's own inbound rules for `com.docker.backend.exe` often already let LAN traffic
   through. Open `http://<that-ip>:8080` in the phone's browser — if the web UI loads, skip
   step 3 entirely.
3. Only if that fails, add a rule from an **elevated** PowerShell — Win+X → "Terminal (Admin)",
   or Start → type `powershell` → Ctrl+Shift+Enter. Without elevation this fails with
   `Windows System Error 5` (access denied).

   The rule's profile has to match how Windows classifies the network, so check that first:

   ```powershell
   Get-NetConnectionProfile | Select-Object InterfaceAlias, NetworkCategory
   ```

   A home Wi-Fi reported as `Public` is usually just misclassified. Setting it to Private
   (Settings → Network & Internet → Wi-Fi → the network → Private) is safer than opening the
   port on networks Windows treats as untrusted. Then:

   ```powershell
   New-NetFirewallRule -DisplayName "Flight Search 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private
   ```

4. In the app, tap the gear icon and enter `192.168.0.149:8080`.

The LAN IP changes when DHCP reassigns it — if the app stops connecting after a router reboot,
re-check `ipconfig`.

### Anywhere over the internet (Tailscale)

Tailscale puts the phone and the PC on one private network without opening any port to the
public internet, and the address it gives the PC never changes.

1. Install Tailscale on the PC: <https://tailscale.com/download/windows>, sign in.
2. Install Tailscale on the phone from Google Play, sign in with the **same** account.
3. On the PC run `tailscale ip -4` — that prints an address in the `100.x.y.z` range.
4. In the app's settings, enter `100.x.y.z:8080`.

MagicDNS names (`my-pc.tailnet-name.ts.net:8080`) work too and are easier to remember.

With Tailscale connected on the phone, the same address works at home and on mobile data — no
firewall rule and no port forwarding needed, because traffic arrives over the Tailscale
interface rather than the LAN one. If you only ever use Tailscale, the Windows Firewall step
above can be skipped entirely.

### Over the USB cable (quickest way to test)

No Wi-Fi, no Tailscale and no firewall rule needed — adb forwards the port down the cable:

```bash
adb reverse tcp:8080 tcp:8080
```

Then set the server address to `localhost:8080`. The tunnel changes nothing on the phone
itself and disappears when the cable is unplugged; remove it early with
`adb reverse --remove-all`.

Worth knowing: a phone on mobile data has no route to the PC's LAN address at all. Its only
interface is the carrier's (`rmnet_*`, typically a `192.0.0.x/27` NAT address), so
`192.168.x.y:8080` cannot work until Wi-Fi is on or Tailscale is running. Check with
`adb shell ip -4 addr show` — if there is no `wlan0`, that is the problem, not the app.

### Emulator

An emulator reaches the host machine at the special address `10.0.2.2`, so use `10.0.2.2:8080`.

## Why cleartext HTTP is allowed

`res/xml/network_security_config.xml` permits cleartext only for private address ranges
(`192.168/16`, `10/8`, `172.16/12`), Tailscale's CGNAT range (`100.64/10`), and `ts.net`.
Everything else still requires HTTPS. The backend speaks plain HTTP and both paths to it are
already private networks.
