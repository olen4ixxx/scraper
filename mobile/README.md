# Flight Search — Android client

Native Kotlin/Compose client for the flight-search backend. It talks to the same `/api/search`
the web UI uses, so both frontends see identical results - pointed either at the deployed site
or at a backend running on your own machine.

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

Set the address on the settings screen (gear icon). Any of these work.

### The deployed site (simplest)

```
https://azair.onrender.com
```

Works from anywhere with nothing running at home, and needs no firewall rule, no Tailscale
and no cable. It is the same instance the website serves, reading the same Neon database the
scheduled collection writes to.

Two things to know about it. It is on a free plan, so it sleeps when idle and the first
request after a quiet spell takes a while to answer — the app's read timeout is generous
enough to sit through that. And collection is off there (`COLLECTOR_ENABLED=false` in
`render.yaml`), so what you see is whatever the scheduled runs have gathered.

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

`res/xml/network_security_config.xml` permits cleartext for every host. That looks lax, and
the narrower version that came before it was simply broken: Android's `<domain>` rules accept
hostnames and exact IPs only, so the CIDR entries it listed (`192.168.0.0/16` and friends)
silently matched nothing and every LAN address was refused.

There is no list that would work, because the server address is typed in at runtime and can be
any private LAN address, any Tailscale address, or `localhost` behind an `adb reverse` tunnel.
The deployed site is reached over HTTPS regardless — permitting cleartext does not weaken it.

## Airlines and missing times

Two details the client has to match the backend on, both learned the hard way:

- **The airline filter is omitted when every airline is ticked.** Naming them all explicitly
  would exclude any airline the backend gains later, which is exactly how this client came to
  hide Transavia results after collection for it started.
- **Some airlines publish a date and a price but no clock times.** Those results carry
  `timesPublished: false`, and the app shows `--:--` and "Time n/a" rather than the placeholder
  times in the payload. They also sort last under the time-based orders, so a zero-length
  placeholder duration cannot sweep them to the top of "Shortest".
