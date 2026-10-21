# SpectreFi — bug audit

**Commit audited:** `4d85d27` ("Improved database recovery") · branch `arena/aec45c7a-wifi-frankenstein`
**Scope:** `app/src/main/**` — ~140 Kotlin files read in full, the rest scanned by pattern. No Gradle/JDK in this
sandbox, so nothing was compiled or run on a device. Every finding below is a *static* finding; the two
pure-logic bugs (S9, S14) were confirmed by re-implementing the exact algorithm in Python and running it.

Legend: **S1** = security-critical, **S2** = data loss / wrong output, **S3** = crash or lifecycle,
**S4** = cosmetic / correctness nit.

---

## Summary

| # | Severity | Area | One-liner |
|---|----------|------|-----------|
| 1 | S1 | `util/SslHelper.kt` | TLS certificate **and** hostname verification disabled (15 call sites) |
| 2 | S1 | `network/NetworkClient.kt` | Second trust-all OkHttp client (+ hardcoded GitHub IPs) used for updates and DB downloads |
| 3 | S1 | `service/DownloadService.kt` | Trust-all + redirect to any host + remote-controlled file name + no hash check on APKs |
| 4 | S1 | `AndroidManifest.xml` | `usesCleartextTraffic="true"` and a base-config that permits cleartext globally |
| 5 | S2 | `util/PcapParser.kt`, `util/HandshakeParser.kt` | Nanosecond-pcap magic numbers are invented values — `.pcap` files with ns timestamps are rejected |
| 6 | S2 | `util/HandshakeParser.kt` | `WPA*03` lines are parsed as `WPA*01` and re-emitted losing the EAPOL half |
| 7 | S2 | `util/HandshakeParser.kt` | hccapx ESSID is read as 32 bytes including zero padding → corrupt `.22000` conversions |
| 8 | S2 | `util/HandshakeParser.kt` | Multi-record `.hccapx` imports only the first handshake |
| 9 | S2 | `util/MaskCracker.kt` | Mask generator scrambles any mask containing a literal character (**verified**) |
| 10 | S2 | `util/PcapParser.kt` | RSN-IE PMKID parser is shifted 2 bytes → PMKID from (re)association frames is wrong |
| 11 | S2 | `util/HandshakeParser.kt` | `verifyPassword()` returns `false` for every EAPOL handshake |
| 12 | S2 | `util/HandshakeParser.kt` | `.16800` / `.pmkid` colon-format text is detected as UNKNOWN and never imported |
| 13 | S3 | `util/HandshakeParser.kt` | `toHccapBytes()` copies an unbounded EAPOL over a fixed 392-byte buffer |
| 14 | S2 | `util/QuadkeyUtils.kt` | `quadkeyToTileXY()` is not the inverse of `latLonToQuadkey()` (**verified**) |
| 15 | S2 | `ui/wifianalysis/WiFiChannelAnalyzer.kt` | 6 GHz band unreachable, 6 GHz channel numbers wrong, ch 144 missing, co-channel ignored as interference |
| 16 | S2 | `ui/updates/UpdateChecker.kt` | Any version difference (including a *downgrade*) is offered as an update |
| 17 | S2 | `ui/wpagenerator/WpaAlgorithmsHelper.kt` | `LIKELY_SUPPORTED` and `UNLIKELY_SUPPORTED` are both `1` |
| 18 | S3 | `service/DownloadService.kt` | `context.startService()` from a companion object on API 26+ crashes when backgrounded |
| 19 | S3 | `ui/airodump/AirodumpViewModel.kt:2288` | `GlobalScope.launch` — work outlives the ViewModel |
| 20 | S3 | `util/HandshakeParser.kt:423,470` | Full file is read into memory just to sniff 4 bytes / 200 chars |
| 21 | S4 | `util/DistanceCalculator.kt:12` | Hard-coded English `"%.2f meters"`, default locale, NaN on 0 MHz |
| 22 | S4 | `ui/maclocation/MacLocationViewModel.kt:925` | `SSSSSS` pattern repeats milliseconds; Microsoft request timestamp is wrong |
| 23 | S4 | `util/HandshakeParser.kt:531` | `hexToBytes()` silently *deletes* non-hex characters instead of rejecting input |

---

---

## Fix status

Every finding below has been fixed on `arena/aec45c7a-wifi-frankenstein`, one commit per finding (or per
closely-related group), on top of `4d85d27`. Nothing could be compiled or run here (no JDK/Gradle/Android SDK),
so each fix was verified statically and, where the bug was pure logic, by re-implementing the algorithm in
Python.

| Findings | Commit | Subject |
|---|---|---|
| 1 | `211815e` | remove trust-all TLS helper (`util/SslHelper.kt` deleted, 15 call sites) |
| 2 | `9937263` | `NetworkClient`: drop trust-all OkHttp client and hardcoded GitHub IPs |
| 3 | `c906c93` | `DownloadService`: https-only, redirect/destination hygiene, sha256, atomic `.part` rename; per-client trust-all removed from 6 helpers, settings switch gone |
| 4 | `361dac1` | `network_security_config.xml`: credential/update/RIR hosts are https-only |
| 5, 6, 7, 8, 11, 12, 13, 20, 23 | `9357d53` | `HandshakeParser`: `.22000` type codes, hccapx ESSID length, multi-record hccapx, PMKID text, bounded sniffing, strict hex, tri-state `verifyPassword()` with real EAPOL MIC check |
| 10 | `d0be396` | `PcapParser`: nanosecond magics, RSN PMKID at the correct offset, 2.4/5/6 GHz channel mapping |
| 9, 16, 17, 19, 21, 22 | `4371bfe` | mask literal ordering, version comparison, `LIKELY_SUPPORTED` value + string, distance localisation/NaN, `GlobalScope`, `SSS` timestamp |
| 14 | `c450eea` | `QuadkeyUtils.quadkeyToTileXY()` is now the inverse of `latLonToQuadkey()` |
| 15 | `a53c46d` | `WiFiChannelAnalyzer`: 6 GHz band and numbering, channel 144, co-channel interference |
| 18 | `9eb60a8` | every companion helper uses `startForegroundService()` and each service posts its notification before dispatch |

## S1 — Security

### 1. TLS verification is disabled app-wide (`util/SslHelper.kt:15-35`)

```kotlin
override fun checkServerTrusted(certs: Array<X509Certificate>?, authType: String?) { }   // :23
connection.hostnameVerifier = HostnameVerifier { _, _ -> true }                           // :33
```

`SslHelper.configure()` replaces the platform trust manager with one that accepts every certificate and
disables hostname checking. It is used on the calls that carry the most sensitive data in the app:

| Call site | What crosses the wire |
|---|---|
| `ui/dbsetup/API3WiFiHelper.kt:92,119,246,297,350` | user's 3WiFi API key |
| `ui/routerscan/RouterScanViewModel.kt:504`, `uploadrouterscan/UploadRouterScanViewModel.kt:261` | scan results + credentials |
| `util/ThreeWiFiUploader.kt:89` | uploaded AP records |
| `WpsGeneratorActivity.kt:821`, `ui/wpsgenerator/WpsGeneratorFragment.kt:677` | PIN lookups |
| `ui/dbsetup/DbSetupFragment.kt:1793`, `ApiServerHelper.kt:23`, `ThreeWifiDevMapHelper.kt` | database/AP data |

Any attacker on the same Wi-Fi (or a rogue AP, which is precisely the threat model of a wardriving app) can
present a self-signed certificate, answer for `3wifi.stascorp.com`, and read the API key or inject AP records.

**Fix:** delete `SslHelper` entirely and let the platform validate; if a specific host really fails, pin that
host's public key instead of trusting everything.

### 2. A second trust-all client (`network/NetworkClient.kt:37-82`)

Same problem in OkHttp form: `sslSocketFactory(trustAllCerts…)` at `:74` and `hostnameVerifier { _, _ -> true }`
at `:75`. This is the client used by `UpdateChecker` (fetches `updates.json`) and by `DatabaseDownloadManager`,
so **the list of files/APKs to download is fetched over an unauthenticated channel**. Combined with #3 this is a
complete update-chain compromise.

`:43-63` additionally hard-codes fallback IPs for `github.com` (140.82.112.3/4) and
`raw.githubusercontent.com` (185.199.108.133/109.133). Those addresses rotate; after a rotation every user
without working DNS for those names gets a timeout instead of a download. And `:96` silently rewrites any
`github.com/...` URL to `raw.githubusercontent.com/...`, which changes the meaning of URLs that rely on GitHub's
API/redirect behaviour.

### 3. `DownloadService` — unauthenticated download of app-private files (`service/DownloadService.kt:147-200`)

```kotlin
val file = File(filesDir, fileName)                       // :152  fileName comes from updates.json
connection.sslSocketFactory = sslContext.socketFactory    // :176  trust-all, rebuilt on every redirect hop
connection.hostnameVerifier = HostnameVerifier { _, _ -> true }  // :177
...
url = URL(url, redirectUrl)                               // :185  no host/scheme allow-list
```

* `fileName` is taken verbatim from the remote `updates.json` (`UpdateChecker.checkFileUpdates`). `../` in that
  field escapes `filesDir` — the app writes attacker-chosen content anywhere its private storage can reach,
  including overwriting its own SQLite databases.
* Redirects are followed manually with no allow-list, so a `https://github.com/...` URL can bounce to `http://`
  (the app permits cleartext, see #4) and then to any host.
* Nothing is hashed or signature-checked after download. `AppApkInstaller.installApk()` hands the file straight
  to `ACTION_VIEW`. Android itself will refuse to *update* the app with a differently-signed APK, which limits
  the damage, but a driver/helper package can still be offered to the user.

**Fix:** `File(filesDir, File(fileName).name)`, verify a SHA-256 from `updates.json` (over a properly validated
TLS connection), restrict redirects to `https` + an expected host set, and only accept `.dic/.db/.apk` names.

### 4. Cleartext allowed everywhere (`AndroidManifest.xml:69`, `res/xml/network_security_config.xml`)

`android:usesCleartextTraffic="true"` plus `<base-config cleartextTrafficPermitted="true"/>` means every HTTP
URL — RSS feeds, Mega mirrors, 3WiFi mirrors — is allowed to be intercepted *and* is accepted silently. The
config file exists but is the weakest possible one. Set `cleartextTrafficPermitted="false"` and add explicit
`domain-config` exceptions only for hosts that genuinely lack TLS.

---

## S2 — Wrong output / data loss

### 5. Nanosecond pcap magic numbers don't exist (`util/PcapParser.kt:77-78`, `util/HandshakeParser.kt:521-523`)

```kotlin
private const val PCAP_MAGIC_NANO = 0x1a2b3c4dL          // real value: 0xa1b23c4d
private const val PCAP_MAGIC_NANO_SWAPPED = 0x4d3c2b1aL  // real value: 0x4d3cb2a1

private val pcapNanoMagic = byteArrayOf(0x4d, 0x3c, 0x2b, 0x1a)          // should be 4d 3c b2 a1
private val pcapNanoBigEndianMagic = byteArrayOf(0x1a, 0x2b, 0x3c, 0x4d) // should be a1 b2 3c 4d
```

`.pcap` files written with nanosecond resolution (`tcpdump -j nano`, Wireshark "nanosecond" captures, anything
that uses `pcap_open_dead_with_tstamp_precision`) start with `a1 b2 3c 4d` / `4d 3c b2 a1`. Because both
constants are wrong, `canParse()` (`:206`) rejects the file and `extractHandshakes()` (`:228-238`) logs
"unknown magic" and returns an empty list — the user just sees "0 handshakes found" on a perfectly good capture.
Same magic check is duplicated in `HandshakeParser.detectFileFormat` (`:445-455`), so the import path agrees the
file is garbage.

**Fix:** correct the four constants to `0xa1b23c4d`, `0x4d3cb2a1`, and byte arrays `4d 3c b2 a1` / `a1 b2 3c 4d`.

### 6. `WPA*03` lines are silently downgraded to `WPA*01` (`util/HandshakeParser.kt:200-204, 28-48`)

```kotlin
val type = when (typeCode) {
    1, 3 -> HandshakeType.PMKID     // :202 — 03 is PMKID+EAPOL, not PMKID
    2 -> HandshakeType.EAPOL
```
`to22000Line()` then re-emits `WPA*01` for that object (`:33`), so `anonce`, `eapol` and `message_pair` are
dropped the moment a `WPA*03` hash is loaded. Hash converter, handshake storage and the crack export path all go
through `parse22000Line`, so **every load → save round-trip destroys the EAPOL half of a `03` line**. The
enum value `HandshakeType.PMKID_EAPOL` exists, `to22000Line` has a `03` branch (`:42`), and three other modules
explicitly handle it (`util/WpaCrackSelector.kt:68,95` → hashcat mode 3, `ui/handshakeconverter/…Engine.kt:57,68`,
`ui/handshakecapture/HandshakeStorageViewModel.kt:1245`) — but because the parser never emits it, **the entire
WPA*03 path is dead code** and `-m 22000` mode-3 hashes cannot be cracked, converted or stored by the app.

**Fix:** `3 -> HandshakeType.PMKID_EAPOL`, and keep the trailing fields.

### 7. hccapx ESSID includes zero padding (`util/HandshakeParser.kt:269-273`)

```kotlin
val essidLen = data[9].toInt() and 0xFF
val essidHex = bytesToHex(data.copyOfRange(10, 10 + 32))   // :271  ignores essidLen
```
`essidHex` is 64 hex chars — the real SSID plus `00` bytes for the unused part of the 32-byte field. Since
`to22000Line()` prefers `essidHex` whenever it is non-blank (`:29-31`), converting a `.hccapx` to `.22000`
writes `ESSID=486f6d65…0000000000` instead of `486f6d65`. Hashcat then derives the PMK from the padded string, so
**every converted hash is wrong** — and a 33+-character padded ESSID also breaks the 22000 field length.

**Fix:** `data.copyOfRange(10, 10 + essidLen.coerceAtMost(32))` — the correct `essid` is already computed two
lines later; just derive `essidHex` from `essidBytes`.

### 8. Multi-record `.hccapx` drops all but the first handshake (`util/HandshakeParser.kt:584-595`)

`parseFile` calls `parseHccapx(data)` (single record) rather than iterating `0 until data.size step 393`, even
though `extractAllFromHccap()` does exactly that for the older `.hccap` format (`:413-418`). A `.hccapx` produced
by `hcxpcapngtool` for a whole survey loses every handshake but the first, silently.

### 9. Mask generator breaks on any literal character (`util/MaskCracker.kt:65-82`) — **verified**

```kotlin
for (pos in parsed.positions.asReversed()) {
    val size = pos.size
    if (size <= 1) { sb.append(pos.firstOrNull() ?: '?'); continue }  // :75 append, not insert(0,…)
    val digit = (remaining % size).toInt(); remaining /= size
    sb.insert(0, pos[digit])                                          // :81
}
```
Single-character positions are appended to the *end* while everything else is inserted at the front. Because the
loop walks the positions backwards, every literal in the mask ends up after the wildcards and in reverse order.
Reimplementing the exact algorithm:

```
mask "ab?d"  →  "0ba", "1ba", "2ba", …       (expected "ab0", "ab1", "ab2")
mask "Pass?d?d" → "21ssaP", "32ssaP", …     (expected "Pass00", "Pass01")
```

The real password is **never generated**, and the same wrong strings are reported to the user as candidates. Masks
with only wildcards (`?u?l?l?l?d?d?d?d`) happen to work, which is why this probably went unnoticed. Reachable
from `ui/bruteforce/PskWordlistSourcePicker.kt`, `ui/wpacracker/WpaCrackerViewModel.kt:788` and
`util/PskOfflineBruteForceRunner.kt:302-333`.

**Fix:** `sb.insert(0, pos[0])` in the `size <= 1` branch (and `continue` without consuming the index, as now).
Also fix `require(index < parsed.totalCombinations || parsed.totalCombinations == Long.MAX_VALUE)` (`:67`) — the
saturated branch lets any index through, so `> Long.MAX_VALUE` masks silently repeat candidates.

### 10. RSN-IE PMKID parsing is offset by 2 bytes (`util/PcapParser.kt:1300-1330`)

```kotlin
val rsn = data.copyOfRange(off + 2, off + 2 + len)   // rsn[0..1] = version, rsn[2..5] = group cipher
var rOff = 4                                         // :1301 — should be 2
val gcip = rsn.copyOfRange(rOff, rOff + 4)           // :1302 — reads version+cipher mix
```
The RSN IE body starts with a 2-byte version, so every subsequent field is read two bytes late: the "group
cipher" swallows part of the version, the pairwise count is read from the middle of the group cipher, and the
PMKID-count check at the end compares garbage. Result: PMKIDs carried inside (re)association-request RSN IEs are
usually missed or mis-sliced. `rsn.copyOfRange(rOff, rOff + 4)` also throws `IndexOutOfBoundsException` for any
RSN IE shorter than 8 bytes (only `len` is validated), and the caller's catch then aborts the whole packet.

**Fix:** `var rOff = 2` and guard every `copyOfRange` with `rOff + n <= rsn.size`.

### 11. `verifyPassword()` can never return `true` for an EAPOL handshake (`util/HandshakeParser.kt:145-148`)

```kotlin
if (type != HandshakeType.PMKID) return false
```
Only PMKID verification is implemented, but the API returns a plain `Boolean`, so "this password is wrong" and
"this handshake type can't be verified" are indistinguishable. Any UI that offers "check password" on a captured
4-way handshake reports *wrong password* for the correct one.

**Fix:** return `Boolean?` (null = unsupported) or implement the EAPOL path (PMK → PBKDF2 → PTK → MIC compare).

### 12. `.16800` / `.pmkid` colon format is unreachable (`util/HandshakeParser.kt:364-401`, `:420-500`)

```kotlin
if (!trimmed.contains("*")) return null   // :366
```
The 16800 format is `pmkid:mac_ap:mac_sta:essid` — no asterisk — so `parsePmkidText` bails before its own
fallback (`:378-383`, which would itself be wrong: it expects the PMKID in field 4 instead of field 1). And
`detectFileFormat` (`:481`) only classifies text as `PMKID` when it literally contains `"PMKID"` and `"*"`.
Net effect: `hashcat -m 16800` wordlist files and plain `.pmkid` lists import as zero hashes.

### 14. `quadkeyToTileXY()` is not the inverse of `latLonToQuadkey()` (`util/QuadkeyUtils.kt:9-52`) — **verified**

Encoding writes the level-1 bit into the **most** significant position (so keys match Bing/`latLonToQuadkeyString`,
which is what the SQL prefix queries assume — good), but decoding rebuilds the tile by shifting left on each
level read from the LSB, i.e. in the opposite order. Reimplementing both exactly:

```
(0, 0)              encodes to tile (4194304, 4194304) → decodes to (1, 1)
(28.6139, 77.2090)  encodes to (5993404, 3502254)      → decodes to (2025325, 3835734)
```

`getQuadkeyBounds()` (`:81`) builds its `BoundingBox` from these values, so it returns a box near the antipode.
Today the only caller is `getQuadkeyBounds` itself — nothing else uses it, so this is a **latent** bug that will
bite the moment someone wires it up. `getTileRangeBounds()`/`getQuadkeyRangeForTileRange()` take tile numbers
directly and are fine.

**Fix:** in the decoder, read 2 bits at a time from the top: `for (i in 0 until zoom) { val shift = 2*(zoom-1-i); … }`,
or simply `tileX = (quadkey shr 1).toInt()…` per level; easiest is to re-derive via `latLonToQuadkeyString` and
parse the base-4 digits.

### 15. Channel analysis gets 6 GHz and part of 5 GHz wrong (`ui/wifianalysis/WiFiChannelAnalyzer.kt`)

* `:227-231` — band detection tests `frequency in 5000..5999` **before** `5955..7000`, so every 6 GHz channel
  from 5955 MHz up to 5999 MHz is classified as 5 GHz and then fails channel decoding → dropped from the scan.
* `:249-251` — 6 GHz channel numbers step by **4** (1, 5, 9, … 233), because 20 MHz channels are numbered with
  80 MHz groups. `((f - 5955) / 20) + 1` yields 2, 3, 4 for the networks that should be 5, 9, 13. The inverse at
  `:267` (`5955 + (channel - 1) * 20`) is wrong for the same reason, and `getAvailableChannelsForBand` (`:271-302`)
  offers `1..29`, while the 6 GHz suggestion list (`:186`) asks for 1, 5, 9, 13… so the analysis and the
  recommendations describe two different channel numbering schemes.
* `:242-247` — 5 GHz decoding has no branch for 5720 MHz (channel 144), although 144 is in the available-channel
  list and `getFrequencyForChannel` happily encodes it as `5500 + 44*5 = 5720` (`:261`). Any AP on ch 144
  disappears from the analysis while still being counted in `totalNetworksCount`.
* `:105-124` — `findInterferingNetworks` filters `network.channel != targetChannel`, i.e. **co-channel APs are not
  counted as interference**. A channel occupied by six strong APs is reported as `NoInterference`
  (`determineRecommendationReason`, `:203-206`) as long as nothing sits on the neighbours, and can be recommended as
  the best channel.

**Fix:** test `5945..7125` for 6 GHz first; use the 6 GHz formula `channel = 1 + 4 * ((f - 5955) / 20)`;
add `frequency in 5720..5720` (or extend the 5500..5720 range) to 5 GHz; count co-channel networks in the
interference set (usually weighted higher than adjacent-channel).

### 16. Version comparison is `!=` (`ui/updates/UpdateChecker.kt:84-95`)

```kotlin
if (currentVersion != newVersion) { … AppUpdateInfo(…) }
```
`compareVersions()` already exists in the same file (`:194`) and is used for the dictionary files, but the app
itself uses string inequality. Consequences: a user on a locally-patched or beta build is nagged forever, and if
the server ever publishes an older version the app offers a **downgrade** as an update. Use
`compareVersions(currentVersion, newVersion) < 0`.

### 17. `LIKELY_SUPPORTED == UNLIKELY_SUPPORTED == 1` (`ui/wpagenerator/WpaAlgorithmsHelper.kt:25-26, 68`)

```kotlin
const val SUPPORTED = 2
const val LIKELY_SUPPORTED = 1
const val UNLIKELY_SUPPORTED = 1     // :26  copy-paste
```
`NetworksAdapter.kt:163-167` switches on these values. With the two constants equal, the `LIKELY_SUPPORTED` branch
is unreachable (Kotlin only warns about the duplicate label), so every algorithm the code marks "likely supported"
is displayed in orange as "Unlikely". `strings.xml` has no `support_state_likely` entry at all, which is why the
mislabelling was easy to miss. The same duplicate pair exists again at `:68`.

**Fix:** give them distinct values (e.g. `LIKELY = 2, UNLIKELY = 1, UNSUPPORTED = 0`) and add the missing string.

---

## S3 — Crashes and lifecycle

### 13. `toHccapBytes()` can write past the end of its buffer (`util/HandshakeParser.kt:100-140`)

```kotlin
val eapolBytes = hexToBytes(eapolData)      // :119  no clamp
eapolBytes.copyInto(buffer, 112)            // :120  392 - 112 = 280 bytes of room
```
The `.hccap` EAPOL field is 256 bytes (`eapol_size` at `:368` is clamped to 256, but the copy is not). A 22000
record whose EAPOL is longer than 256 bytes — legal, e.g. M4 with a large key-data blob — throws
`ArrayIndexOutOfBoundsException` from `copyInto` and kills the conversion. The `.hccapx` writer a few lines above
does clamp (`eapolData.take(512)`), so the two paths behave differently.

**Fix:** `hexToBytes(eapolData.take(512)).copyInto(buffer, 112)` and derive `eapolSize` from the clamped array.

### 18. `DownloadService` is started with `startService()` (`service/DownloadService.kt:86, 94, 101`)

The companion's helpers call `context.startService(...)` for a service declared as `foregroundServiceType="dataSync"`
that never calls `startForeground()`. On API 26+ this throws
`IllegalStateException: Not allowed to start service Intent … app is in background` whenever the app is not in the
foreground (e.g. a retry triggered from a broadcast or after rotation with the activity stopped). On API 26+ the
correct call is `startForegroundService()` **plus** an immediate `startForeground()` with the progress
notification the service already builds.

### 19. `GlobalScope` in a ViewModel (`ui/airodump/AirodumpViewModel.kt:2288`)

```kotlin
kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { … }
```
The only `GlobalScope` in the codebase. The job keeps running after the ViewModel is cleared and after the
fragment is destroyed — it can write to a closed database handle or emit into a completed LiveData. Use
`viewModelScope.launch` (add `Dispatchers.IO` via `withContext`).

### 20. Whole-file reads for header sniffing (`util/HandshakeParser.kt:423, 470`)

```kotlin
file.inputStream().use { it.readBytes().take(4).toByteArray() }
…
file.readText(Charsets.ISO_8859_1).take(200)
```
Both load the entire capture into memory before taking a few bytes. With 100–300 MB wardriving captures on a
`largeHeap` device this is an easy OOM before the real parser (which correctly streams) ever starts. Read 4 bytes
with `readNBytes(4)` and 200 chars with a bounded reader.

---

## S4 — Nits worth fixing

* `util/DistanceCalculator.kt:12` — `String.format("%.2f meters", distance)` is untranslated, ignores the locale
  (`@SuppressLint("DefaultLocale")` is hiding the bug) and returns `"0.00 meters"` / `NaN` when `frequency` is 0
  (`log10(0) = -Infinity`). Move the unit into `strings.xml` and guard `frequency > 0`.
* `ui/maclocation/MacLocationViewModel.kt:925` — `"yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'"`: `S` is *milliseconds*, so six
  of them repeat the millisecond field (`…:00.123123`) instead of emitting microseconds. Use
  `…SS'Z'` or `java.time` with `DateTimeFormatter`.
* `util/HandshakeParser.kt:531` — `hex.filter { it.isDigit() || it.lowercase() in "abcdef" }` repairs malformed
  input by deleting characters instead of rejecting it (and `it.isDigit()` accepts non-ASCII digits), so a
  truncated PMKID silently becomes a shorter, valid-looking one. Validate length/parity and return `null`.
* `util/ArchiveExtractor.kt:27-30, 110` — the zip-slip guard is good; `extractGzip` for a `.tgz` only gunzips to a
  `.tar`, which is then returned as an extractable "handshake" candidate and never unpacked.
* `ui/dbsetup/localappdb/PersonalMapDbHelper.kt:1878-1886` — `parseWigleTime` uses a lenient `SimpleDateFormat`,
  so `"1700000000"` (an epoch value some exporters write) parses as a nonsense date instead of falling through to
  `toLongOrNull()` (that branch is only reachable from the `catch`).
* `SslHelper.configure()` allocates a fresh `SecureRandom` and `SSLContext` per connection (`:29-31`); even if the
  trust-all is removed, that should be a single shared context.

---

## What I could not check here

* No Gradle/JDK/Android SDK in this environment, so the project was not compiled and no lint/unit tests were run.
  Build-level issues (unused imports, `when` exhaustiveness warnings, R8 rules) are therefore unverified.
* Everything runtime-related (ForegroundService restrictions, `WifiManager.startScan` behaviour on API 33+,
  libsu/root paths, native `libcracker` self-test) needs a device.
* The C++ layer under `app/src/main/cpp` and the JNI bridges (`NativeWifiBinaries`, `NativeArpSpoof`) were not
  reviewed.

## Suggested regression tests (cheap, catch the S2 items)

1. `MaskCracker.parse("ab?d")` → `generateAt(…)` round-trip against an expected list.
2. `QuadkeyUtils` encode/decode round-trip for a table of lat/lon at zooms 1..23.
3. `HandshakeParser`: `WPA*03` → `to22000Line()` keeps type 03 and the EAPOL field.
4. `parseHccapx` on a fixture with a 10-char SSID → `essidHex` must be 20 chars, not 64.
5. `PcapParser.canParse` on a 4-byte nanosecond-pcap header (`4d 3c b2 a1`).
6. `WiFiChannelAnalyzer.determineFrequencyBand(5975)` == `GHZ_6` and its channel == 5.
