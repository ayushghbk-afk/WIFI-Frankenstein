# SpectreFi Rebrand Summary

## Overview
This document summarizes the complete rebrand of **WIFI Frankenstein** to **SpectreFi** as requested.

## Changes Made

### 1. Package & Application ID
- **Old:** `com.lsd.wififrankenstein`
- **New:** `com.spectrefi.app`
- All Java/Kotlin source files moved to new package structure
- `app/build.gradle.kts` updated with new namespace and applicationId

### 2. JNI / Native Code
- `app/src/main/cpp/cracker.c`: All 5 JNI function symbols renamed:
  - `Java_com_lsd_wififrankenstein_util_NativeCracker_*` → `Java_com_spectrefi_app_util_NativeCracker_*`
- Functions affected: `benchmarkPbkdf2`, `debugPbkdf2Hex`, `tryPasswordHex`, `crackBatchHex`, `crackBatchMultiHex`

### 3. Brand Identity & Strings
- App name updated to **SpectreFi** in all contexts
- Localized strings updated in 4 locales: `en`, `ru`, `uk`, `zh-rCN`
- Key strings: `app_name`, `nav_header_title`, `app_update_title`, `welcome_title`, `about_app_description`, etc.
- Upstream GitHub link retained at `https://github.com/LowSkillDeveloper/WIFI-Frankenstein` (intentional)

### 4. Visual Design (Dark Stealth Theme)

#### Color Palette
| Name | Hex | Usage |
|------|-----|-------|
| Brand Ink | `#0B0F12` | Primary background |
| Brand Surface | `#131C21` | Card/element backgrounds |
| Brand Raised | `#1A252B` | Elevated surfaces |
| Brand Accent | `#00E5A0` | Primary accent (mint green) |
| Brand Accent Deep | `#00B37E` | Accent variant |
| Brand Soft | `#7FF3D0` | Soft accent |
| Brand Cyan | `#22D3EE` | Secondary accent |
| Brand Violet | `#A78BFA` | Tertiary accent |
| On-Accent | `#062018` | Text on accent |

#### Themes
- `Theme.SpectreFi.Base` / `.Night`
- `Theme.SpectreFi.Purple` / `.Night`
- `Theme.SpectreFi.Green` / `.Night`
- `Theme.SpectreFi.Blue` / `.Night`
- `Theme.SpectreFi.AppBarOverlay`
- `Theme.SpectreFi.PopupOverlay`
- All themes updated with dark status bar, navigation bar, and window background

### 5. Launcher Icons

#### Design
- Square icon with Wi-Fi signal arcs (3 concentric arcs) on the left
- Arcs transition into a spectral waveform / signal decay line on the right
- Colors: Electric mint green (`#00E5A0`) with cyan (`#22D3EE`) accent
- Background: Dark charcoal near-black (`#0B0F12`)
- Clean, minimal, high-contrast design with generous safe margins

#### Files Created
All densities for main app and 4 flavor variants:

**Main App Icons:**
- `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` (48/72/96/144/192 × same)
- `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_round.png` (48/72/96/144/192 × same)
- `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png` (108/162/216/324/432 × same)
- `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_monochrome.png` (48/72/96/144/192 × same)
- `mipmap-anydpi-v26/ic_launcher.xml` (adaptive icon)
- `mipmap-anydpi-v26/ic_launcher_round.xml` (adaptive icon)

**Flavor Icons (3wifi, anti3wifi, p3wifi, p3wifi_pixel):**
- `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_{flavor}.png`
- `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_{flavor}_foreground.png`
- `mipmap-anydpi-v26/ic_launcher_{flavor}.xml`

**Background Colors (for adaptive icons):**
- `values/ic_launcher_background.xml` → `#0B0F12`
- `values/ic_launcher_3wifi_background.xml` → `#0B0F12`
- `values/ic_launcher_anti3wifi_background.xml` → `#0B0F12`
- `values/ic_launcher_p3wifi_background.xml` → `#0B0F12`
- `values/ic_launcher_p3wifi_pixel_background.xml` → `#0B0F12`

### 6. Storage Paths & Identifiers
All internal paths updated to use **spectrefi** instead of **wififrankenstein**:
- `/data/local/spectrefi/`
- `/data/vendor/wifi/wpa/spectrefi/`
- `/data/misc/wifi/spectrefi/`
- Log directory: `SpectreFi_Logs`
- Detailed log files: `spectrefi_detailed_log_*`
- Self-test password: `spectrefi-selftest-pw`
- Control directory: `"spectrefi"`

### 7. Documentation Updates
- `README.md`: Updated with new name, badges, and lineage section crediting upstream
- `BUG_REPORT.md`: References updated to reflect new package structure
- `SmartLinkDB-Example/README.md`: References updated

### 8. Manifest & Navigation
- `AndroidManifest.xml`: Package name and theme references updated
- Activity aliases for flavors updated with new icon references
- `navigation/mobile_navigation.xml`: FQCNs updated
- All layout XML files: Custom view FQCNs updated

## Files Changed
- **520 files changed** (2181 insertions, 2152 deletions)
- All source files moved from `com/lsd/wififrankenstein/` to `com/spectrefi/app/`
- All string resources updated across 4 locales
- All color and theme resources updated
- All launcher icon assets replaced

## Commit History
```
abbf824 Rebrand: WIFI Frankenstein -> SpectreFi
7dbf35d docs: map every audit finding to its fix commit
9eb60a8 fix(services): start foreground services the way the platform requires
a53c46d fix(analysis): correct 6 GHz band/channel maths, ch 144 and co-channel interference
c450eea fix(quadkey): decode quadkeys in the same bit order they are encoded
...
```

## Verification Notes
- **No build verification**: No JDK/Gradle/Android SDK available in sandbox
- All changes are **statically verified only**
- No old package references remain (`com.lsd` or `wififrankenstein`)
- All JNI symbols match the new package structure
- All icon sizes are correct for their respective densities
- All flavor variants have proper icon assets

## Build Instructions (for local verification)
```bash
# Update local.properties if needed
# Then build:
./gradlew assembleDebug

# Or for release:
./gradlew assembleRelease
```

## Credits
SpectreFi is a rebranded derivative of [WIFI Frankenstein](https://github.com/LowSkillDeveloper/WIFI-Frankenstein) by LowSkillDeveloper, published under the GNU GPL v3. The original authors retain all copyright in their code; this fork maintains the same license, preserves the upstream history, and adds a security/robustness pass plus a new visual identity.
