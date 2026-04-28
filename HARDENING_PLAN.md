# Plan: Hardening Titanium SDK Against JS File Recovery

> **Note:** This document is a security analysis and hardening plan covering both iOS and Android platforms. It describes attack vectors, defensive measures, and implementation status.

## Current Attack Vectors

The existing decryptor (`titanium_ipa_decryptor.py`) successfully recovers JS files from production IPAs through these vectors:

### Vector 1: Self-Contained Encryption Key

**Problem:** The AES-128-CBC key and IV are embedded as the last 32 bytes of the `data[]` array inside `ApplicationRouting.m`. Anyone who can read the Mach-O binary's `__DATA,__data` section can extract them.

**Location:** `iphone/templates/build/ApplicationRouting.m` — the `<%- bytes %>` template variable includes key+IV inline with the encrypted data.

**Current recovery method:** Scan the data section for a high-entropy 32-byte window, validate by attempting AES-CBC decryption of adjacent data.

### Vector 2: Readable Data Sections (FairPlay Bypass)

**Problem:** Apple's FairPlay DRM encrypts the `__TEXT` segment (executable code) but leaves `__DATA` segments readable. All encryption data — the `data[]` array, `ranges[]`, key, and IV — resides in `__DATA,__data`, which remains plaintext in App Store IPAs.

**Current recovery method:** Parse `__DATA,__data` directly; no code disassembly needed.

### Vector 3: Filename Leakage via `__cfstring`

**Problem:** The `initWithObjectsAndKeys:` dictionary in `ApplicationRouting.m` stores all mangled filenames as NSString constants. These are compiled into `__DATA,__cfstring` and `__TEXT,__cstring` sections, which remain readable even in FairPlay-encrypted binaries.

**Current recovery method:** Parse `__cfstring` entries to find all mangled filenames in source-code order, matching them 1:1 to decrypted range indices.

### Vector 4: `_index_.json` Metadata Exposure

**Problem:** The build generates `_index_.json` in the app bundle, listing every JS/JSON file with its status (1=on disk, 2=encrypted). While `_app_props_.json` is deleted from the index, most filenames remain exposed.

**Location:** `iphone/cli/commands/_build.js:6799-6832` — `generateRequireIndex()` writes the file unencrypted.

**Current recovery method:** Read `_index_.json` directly from the app bundle to get original filenames and encryption status.

### Vector 5: Entropy Transition Detection

**Problem:** The encrypted data has high entropy (~7+ bits/byte) while surrounding ObjC runtime data has low entropy (~1-2 bits/byte). This sharp transition makes it easy to locate the start of the encrypted data blob.

**Current recovery method:** Scan for the entropy transition point to find where `data[]` begins within `__DATA,__data`.

### Vector 6: NSRange Array Detectability

**Problem:** The `ranges[]` array (32-bit `{location, length}` pairs) follows immediately after the key+IV in the data section. The entries have a characteristic pattern: monotonically increasing locations, all values within the data blob's bounds, and the array starts right after the 32-byte key+IV.

**Current recovery method:** Scan for aligned 8-byte pairs where both values are within the data blob size.

### Vector 7: Module Assets Follow Same Pattern

**Problem:** Module-embedded JS files use the identical encryption pattern (`*ModuleAssets` classes) with their own `data[]`, `ranges[]`, key+IV, and map — all in readable data sections.

**Location:** `iphone/templates/module/objc/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs`

---

## Hardening Measures

### Measure 1: Remove Key/IV from the Binary (Highest Impact)

**Description:** Instead of embedding the key and IV as the last 32 bytes of `data[]`, derive them at runtime from device-specific or obfuscated sources.

**Fundamental constraint:** You cannot encrypt JS assets at build time without having the encryption key available at build time, and if the app must work offline, that key must be available at runtime — which means it must be embedded in the binary in some form. No approach truly "removes" the key; they make extraction harder to varying degrees.

**Approaches evaluated:**

- **A) Key Derivation from Device Identifier (NOT RECOMMENDED):** Use `identifierForVendor` (iOS) or `Settings.Secure.ANDROID_ID` (Android) as input to PBKDF2/HKDF to derive the AES key at runtime.
  - **Fatal flaw:** Device IDs are not secrets — an attacker can read their own device ID and derive the same key. Furthermore, per-device keys require per-device encryption at install time, which needs a server round-trip and breaks the offline requirement. A universal key combined with the device ID still leaves the universal key in the binary.
  - Recovery difficulty: Attacker reads own device ID and derives the same key

- **B) Separate Key in Keychain/Keystore (RECOMMENDED — Phase 2):** Generate a random AES key at first launch, store it in iOS Keychain (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`) or Android Keystore (hardware-backed, API 23+). On first launch, decrypt JS assets using an embedded transport key, re-encrypt under the Keystore/Keychain key, then zero out the transport key.
  - Trade-off: Requires first-launch migration flow; handling Keystore unavailability (pre-API 23 Android); key invalidation recovery (lock screen changes, biometric re-enrollment). The transport key is still in the binary for the initial unwrap — this is a one-time vulnerability window.
  - Recovery difficulty: Requires device access + jailbreak/root + key extraction from hardware-backed Keystore. The IPA/APK alone is insufficient after migration.

- **C) White-Box Cryptography (NOT RECOMMENDED):** Use a white-box AES implementation where the key is embedded in precomputed lookup tables (~500 KB for AES-128).
  - **Not recommended because:** 30-40x slower than native AES, ~500 KB binary bloat per architecture, and broken by practical attacks. The BGE attack (Billet, Gilbert, Ech-Chatbi, 2004) breaks Chow's AES-128 with ~2^30 work. Differential Fault Analysis (DFA) breaks every implementation tested by Quarkslab in seconds to minutes. Differential Computation Analysis (DCA) extracts keys from memory traces without disassembly. The key is not removed — it is encoded in the lookup tables and is recoverable.
  - Available libraries: [ph4r05/Whitebox-crypto-AES-java](https://github.com/ph4r05/Whitebox-crypto-AES-java) (Java, BSD-3), [balena/aes-whitebox](https://github.com/balena/aes-whitebox) (C++, AES-128/192/256), [Nexus-TYF/Xiao-Lai-White-box-AES](https://github.com/Nexus-TYF/Xiao-Lai-White-box-AES) (C, Apache 2.0)
  - Recovery difficulty: Hours with custom DFA/BGE tooling

- **D) Obfuscated Key Computation (RECOMMENDED — Phase 1):** Replace the current trivial key embedding (XOR mask, last 32 bytes of blob) with multi-step key derivation from multiple scattered seed values. Start with N random seed byte arrays embedded at different points in the binary. Apply SHA-256 chains, XOR operations, and conditional branches (opaque predicates) to compute the AES key at runtime. The key is still deterministically derivable from data in the binary, but the computation resists automated scanning and simple hex dumps.
  - Trade-off: Defeats casual scanning and simple scripts. A determined attacker with Frida or a decompiler can set a breakpoint at the `CCCrypt`/`Cipher.init` call and read the key in minutes. No resistance against dynamic analysis.
  - Recovery difficulty: Minutes with Frida; hours with only static analysis

**Recommended strategy: Two-phase hybrid approach (D + B)**

Phase 1 (low effort, immediate): Replace trivial key embedding with obfuscated key computation. Raises the bar from "hex dump the last 32 bytes" / "XOR with embedded mask" to "trace a multi-step computation." Works on both platforms with minimal code changes. No architecture changes needed.

Phase 2 (medium effort, significant gain): Add first-launch Keystore/Keychain migration. After migration, the embedded transport key is never used again, removing it from the "live" attack surface. The transport key in the binary is only a vulnerability during the first-launch window.

**Comparison of approaches:**

| Approach | Key truly removed? | Offline? | Performance | Complexity | Security | Recommended? |
|---|---|---|---|---|---|---|
| A: Device ID derivation | Partially (needs server) | No | Good | Medium | Low (ID not secret) | No |
| B: Platform Keystore | Yes (after migration) | Yes | Excellent | High | Strong | Yes (Phase 2) |
| C: White-box AES | No (encoded in tables) | Yes | 30-40x slower | Very high | Broken (DFA/BGE) | No |
| D: Obfuscated computation | No (obfuscation) | Yes | Excellent | Low | Low-moderate | Yes (Phase 1) |
| **Hybrid (D + B)** | **Yes (post-migration)** | **Yes** | **Excellent** | **Medium-high** | **Moderate-strong** | **Yes** |

**Impact:** Eliminates Vectors 1, 5, and 6 (key extraction, entropy detection, and range-finding all become moot without the key).

**SDK files requiring changes (Phase 1):**

| File | Current behavior | Required change |
|------|------------------|-----------------|
| `support/iphone/titanium_prep.js` | Generates random key+IV, appends as last 32 bytes of `data[]`, outputs `xmask[]` for XOR masking | Generate multiple seed values; output them as scattered `static UInt8 _sN[]` arrays; output key derivation expression instead of embedded key bytes |
| `iphone/templates/build/ApplicationRouting.m` | XOR-unmasks `data[]` with `xmask[]`, passes last 32 bytes as key+IV to `filterDataInRange` | Add key derivation function that computes key+IV from seed arrays via SHA-256 chains; pass derived key+IV to `filterDataInRange` |
| `iphone/templates/module/objc/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` | Same XOR-unmask approach as ApplicationRouting | Same key derivation function |
| `iphone/templates/module/swift/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` | Same XOR-unmask approach as ApplicationRouting | Same key derivation function |
| `android/cli/commands/_build.js` | Generates `xmask[]`, `maskedKey[]`, `salt[]` as EJS template variables | Generate multiple seed values and derivation chain; output as scattered `private static byte[] _sN` arrays |
| `android/templates/build/_T5C.java` | `getKey()` XOR-unmasks `maskedKey[i] ^ xmask[i % xmask.length]` | Replace with key derivation method that computes key from seed arrays via SHA-256 chains |

**SDK files requiring changes (Phase 2 — in addition to Phase 1):**

| File | Required change |
|------|-----------------|
| `iphone/templates/build/ApplicationRouting.m` | Add first-launch migration: generate Keychain key, decrypt all assets with transport key, re-encrypt with Keychain key, store re-encrypted assets in app sandbox, zero transport key |
| `android/templates/build/_T5C.java` | Add first-launch migration: generate Android Keystore key, decrypt all assets with transport key, re-encrypt with Keystore key, store re-encrypted assets in internal storage, zero transport key |
| `iphone/TitaniumKit/TitaniumKit/Sources/API/TiUtils.m` | Adapt `loadAppResource:` to check for migrated assets first |
| `iphone/TitaniumKit/TitaniumKit/Sources/API/TiModule.m` | Same migration check for module assets |
| `android/runtime/common/src/java/org/appcelerator/kroll/util/KrollAssetHelper.java` | Add migration check and re-encrypted asset loading path |

**Previous blocker resolved:** `tiverify.xcframework` and `titanium_prep` are now in source form. `tiverify` accepts key+IV as separate parameters (already reimplemented in `TiVerify.m`). `titanium_prep.js` can be modified to output any key provisioning scheme. There are no remaining prebuilt binary blockers.

---

### Measure 2: Obfuscate String Constants (High Impact)

**Description:** The NSString constants in `initWithObjectsAndKeys:` leak all filenames. Obfuscate or remove them.

**Approaches:**

- **A) Runtime String Construction:** Build mangled filenames at runtime from character arrays or simple encoding rather than using `@"stringLiteral"` constants. This removes them from `__cfstring`/`__cstring`.
  ```objc
  // Instead of: [NSNumber numberWithInteger:0], @"app_js",
  char appJs[] = {0x61,0x70,0x70,0x5F,0x6A,0x73, 0x00}; // "app_js"
  [NSNumber numberWithInteger:0], [NSString stringWithUTF8String:appJs],
  ```
  - Trade-off: Minor runtime overhead; filenames still recoverable via dynamic analysis
  - Recovery difficulty: Requires runtime hooking or dynamic analysis

- **B) Hash-Based Lookup:** Replace `initWithObjectsAndKeys:` string keys with integer hashes. Instead of `@"app_js"` → index 0, use `hash("app_js")` → index 0. The hash values are opaque integers in the binary.
  ```objc
  // Instead of: @{@"app_js": @0, @"lib/Badge_js": @1, ...}
  // Use: @{@(0x3a7f2c1d): @0, @(0x8b4e5a92): @1, ...}
  ```
  - Trade-off: Runtime must compute hashes from incoming path strings; requires modifying `resolveAppAsset:` to hash the input path
  - Recovery difficulty: Attacker must brute-force hash→filename mapping or trace runtime calls

- **C) Remove `initWithObjectsAndKeys:` Entirely:** Replace the dictionary with a binary search over sorted (hash, range) pairs or a minimal perfect hash function. No string constants in the binary at all.
  - Trade-off: More complex code generation; lookup is O(log n) instead of O(1)
  - Recovery difficulty: Very high — filenames must be recovered purely from runtime tracing

**Impact:** Eliminates Vector 3 (filename leakage via `__cfstring`).

**SDK files requiring changes:**

| File | Lines | Current behavior | Required change |
|------|-------|------------------|-----------------|
| `support/iphone/titanium_prep` | — | Generates `initWithObjectsAndKeys:` dictionary output with string keys (e.g., `@"app_js", @0, @"lib/Badge_js", @1, ...`) | Must be modified to output hash-based keys instead of string keys. **No source code available** — this is the primary blocker for approach B/C |
| `iphone/templates/build/ApplicationRouting.m` | 21 | `[map objectForKey:path]` — looks up the mangled filename in the dictionary generated by `titanium_prep` | For approach B: add hash computation on `path` before lookup (e.g., `uint32_t h = hash(path); [map objectForKey:@(h)]`). For approach A: no change needed (strings are just constructed differently). For approach C: replace dictionary with sorted array + binary search |
| `iphone/TitaniumKit/TitaniumKit/Sources/API/TiUtils.m` | 1627 | `NSClassFromString(@"_T5Routing")` — looks up the routing class by name (previously `@"ApplicationRouting"`, now obfuscated) | For approach B: mangled string must be hashed before lookup. Add: `NSString *mangled = [appurlstr stringByReplacingOccurrencesOfString:@"." withString:@"_"]; uint32_t h = hash_fn(mangled);` and pass `@(h)` to resolve |
| `iphone/TitaniumKit/TitaniumKit/Sources/API/TiModule.m` | 187 | `stringByReplacingOccurrencesOfString:@"." withString:@"_"` — same mangling for module assets fallback | Same change as TiUtils.m if using hash-based lookup for modules |
| `iphone/templates/module/objc/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` | 17-22 | `resolveModuleAsset:` uses `initWithObjectsAndKeys:` dictionary with string keys (generated by `titanium_prep`) | Same changes as ApplicationRouting.m — must use hash keys or runtime-constructed strings |
| `iphone/cli/commands/_build.js` | 6750 | Checks `out.indexOf('initWithObjectsAndKeys')` to validate `titanium_prep` output | Must be updated to check for new output format (hash keys, runtime strings, etc.) |
| `iphone/cli/commands/_buildModule.js` | 604 | Same check: `out.indexOf('initWithObjectsAndKeys') !== -1` | Same change as `_build.js` |

**Critical dependency:** `titanium_prep` generates the `initWithObjectsAndKeys:` output. Approaches B and C require modifying this binary, which has no available source code. Approach A (runtime string construction) could be implemented as a post-processing step on the generated code without modifying `titanium_prep`.

---

### Measure 3: Remove `_index_.json` from the App Bundle (High Impact, Easy)

**Description:** Don't include `_index_.json` in the IPA at all. Currently it's written unencrypted to the app bundle.

**Implementation:** In `generateRequireIndex()`, skip writing the file when encryption is enabled. The runtime already handles the missing file gracefully.

**Impact:** Eliminates Vector 4 (`_index_.json` metadata exposure).

**SDK files requiring changes:**

| File | Lines | Current behavior | Required change |
|------|-------|------------------|-----------------|
| `iphone/cli/commands/_build.js` | 6799-6832 | `generateRequireIndex()` writes `_index_.json` unencrypted to the app bundle. Lists all JS/JSON files with status codes (1=on disk, 2=encrypted). Deletes `_app_props_.json` entry but exposes all other filenames | Skip writing `_index_.json` entirely when `this.encryptJS === true`. The runtime falls back to `FileStatusUnknown` which tries encrypted loading first, then disk |
| `iphone/TitaniumKit/TitaniumKit/Sources/API/AssetsModule.m` | 69-87 | `loadURL:` uses `FileStatusUnknown` case to try disk first, then encrypted | Reversed the order: try encrypted first (`loadAppResource:`), then fall back to disk. This is more efficient for production builds where most files are encrypted |
| `iphone/TitaniumKit/TitaniumKit/Sources/API/AssetsModule.m` | 143-175 | `loadIndexJSON` loads `_index_.json` — first tries encrypted path via `loadAppResource:`, then falls back to `NSData dataWithContentsOfFile:` | No change needed — when `_index_.json` doesn't exist, `loadIndexJSON` returns an empty dictionary, and `fileStatus:` returns `FileStatusUnknown` for all paths |

**Note:** Since `encryptJS` is now `true` for all deploy types (production, test, development, simulator, macOS), `_index_.json` is omitted from all builds unless explicitly disabled with `--skip-js-encrypt`.

---

### Measure 4: Obfuscate the Data Section Layout (Medium Impact)

**Description:** Make the `data[]`, `ranges[]`, and key+IV harder to locate through structural obfuscation.

**Approaches:**

- **A) Interleave with Decoy Data:** Insert random-length blocks of random bytes between encrypted files and between the data and the key. This breaks the contiguous structure and makes entropy detection less reliable.
  - Trade-off: Slightly larger binary; decryption code must skip decoys
  - Recovery difficulty: Attacker must distinguish real encrypted blocks from decoys

- **B) Encrypt the NSRange Array:** Store `ranges[]` in encrypted form (using a derived key), not as plaintext structs. The runtime decrypts it before use.
  - Trade-off: Minor performance overhead on each `resolveAppAsset:` call
  - Recovery difficulty: Attacker must find the range decryption key or trace runtime

- **C) XOR-Mask the Data Blob:** Apply a simple XOR mask with a derived key before storing in the binary. The runtime unmasks before AES decryption.
  - Trade-off: Negligible performance impact
  - Recovery difficulty: Defeats entropy-based detection; attacker must find the mask key

**Impact:** Makes Vectors 5 and 6 (entropy detection, NSRange detection) significantly harder.

**SDK files requiring changes:**

| File | Lines | Current behavior | Required change |
|------|-------|------------------|-----------------|
| `support/iphone/titanium_prep` | — | Generates `data[]` (contiguous encrypted bytes), `ranges[]` (plaintext `{location, length}` pairs), and key+IV (last 32 bytes of data array) | **Approach A:** Must interleave decoy random bytes between encrypted file segments and update ranges accordingly. **Approach B:** Must encrypt the ranges array separately (with its own key or a derived key). **Approach C:** Must XOR-mask the entire data blob before outputting. **No source code available** |
| `iphone/templates/build/ApplicationRouting.m` | 13-24 | `<%- bytes %>` places raw `data[]`, `ranges[]`, and dictionary inline. `resolveAppAsset:` calls `filterDataInRange` directly on the data array | **Approach A:** Add decoy-skipping logic before passing data to `filterDataInRange`. **Approach B:** Add range array decryption before lookup. **Approach C:** Add XOR-unmasking before AES decryption call |
| `iphone/lib/tiverify.xcframework/` | — | `filterDataInRange(NSData* thedata, NSRange range)` — performs AES-128-CBC decryption on a range of data, extracting key+IV from the end of the data blob | **Approach C:** Would need to accept a mask key parameter or have unmasking done before the call. **Approach B:** No change needed if ranges are decrypted before calling `filterDataInRange`. **No source code available** |
| `iphone/templates/module/objc/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` | 10-22 | Same structure as `ApplicationRouting.m` for module assets | Same changes as ApplicationRouting.m |

**Critical dependency:** All three approaches require modifying `titanium_prep` output format, which has no source code. Approach C (XOR masking) is the least invasive — the unmasking could be done in the generated ObjC code before calling `filterDataInRange`, without modifying the `tiverify` library.

---

### Measure 5: Binary Stripping and Anti-Analysis (Medium Impact)

**Description:** Reduce the amount of structural information available in the binary.

**Approaches:**

- **A) Obfuscate Class Names:** Rename `ApplicationRouting` and `*ModuleAssets` classes to opaque names that don't reveal their purpose. This prevents easy class name lookup in `__objc_classname` sections.
  - Trade-off: None for production builds; class name changes must be coordinated between template and runtime code
  - Recovery difficulty: Attacker must use heuristic detection instead of class name search

- **B) Compiler Obfuscation Flags:** Use `-mllvm -fla` (control flow flattening) and `-mllvm -sub` (instruction substitution) via the Xcode build settings to make static analysis harder.
  - Trade-off: 10-30% performance overhead; larger binary
  - Recovery difficulty: Significantly increases reverse engineering effort

- **C) Strip Debug Info:** Ensure `__objc_methname`, `__objc_classname`, and other metadata sections are stripped. Already partially done for release builds.
  - Trade-off: None for production
  - Recovery difficulty: Removes easy class/method discovery

**Impact:** Increases the effort required for all vectors but doesn't eliminate them.

**SDK files requiring changes:**

| File | Lines | Current behavior | Required change |
|------|-------|------------------|-----------------|
| `iphone/templates/build/ApplicationRouting.m` | 15-17 | Class name `ApplicationRouting` is explicit in `@implementation ApplicationRouting` and `+ (NSData*) resolveAppAsset:(NSString*)path;` | **Approach A:** Renamed to `_T5Routing` — class name no longer reveals its purpose |
| `iphone/Classes/ApplicationRouting.h` | 9 | `@interface ApplicationRouting : NSObject` | **Approach A:** Renamed to `_T5Routing` |
| `iphone/Classes/ApplicationRouting.m` | 12 | `@implementation ApplicationRouting` | **Approach A:** Renamed to `_T5Routing` |
| `iphone/TitaniumKit/TitaniumKit/Sources/API/TiUtils.m` | 1627 | `NSClassFromString(@"ApplicationRouting")` — looks up the class by its string name | **Approach A:** Changed to `NSClassFromString(@"_T5Routing")` |
| `iphone/TitaniumKit/TitaniumKit/Sources/API/TiModule.m` | 187 | `NSString *moduleAsset = [NSString stringWithFormat:@"%@Assets", moduleName_]` — constructs `*ModuleAssets` class names dynamically | **Approach A:** Changed to `[NSString stringWithFormat:@"%@_T5A", moduleName_]` — no longer reveals purpose |
| `iphone/cli/commands/_buildModule.js` | 677-678 | Generates `ModuleAssets.m` with `@implementation <%- moduleIdAsIdentifier %>ModuleAssets` — the class name includes the module ID | **Approach A:** Template now uses `_T5A` suffix instead of `ModuleAssets` |
| `iphone/templates/module/objc/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` | 8 | `@implementation <%- moduleIdAsIdentifier %>ModuleAssets` | **Approach A:** Changed to `@implementation <%- moduleIdAsIdentifier %>_T5A` |
| `iphone/templates/module/objc/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.h.ejs` | 5 | `@interface <%- moduleIdAsIdentifier %>ModuleAssets : NSObject` | **Approach A:** Changed to `@interface <%- moduleIdAsIdentifier %>_T5A : NSObject` |
| `iphone/templates/module/swift/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` | 8 | Same as objc template — `@implementation <%- moduleIdAsIdentifier %>ModuleAssets` | **Approach A:** Changed to `@implementation <%- moduleIdAsIdentifier %>_T5A` |
| `iphone/templates/module/swift/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.h.ejs` | 5 | Same as objc template — `@interface <%- moduleIdAsIdentifier %>ModuleAssets : NSObject` | **Approach A:** Changed to `@interface <%- moduleIdAsIdentifier %>_T5A : NSObject` |
| Xcode build settings | — | Current strip settings may leave `__objc_classname` and `__objc_methname` sections | **Approach C:** Add `STRIP_SWIFT_SYMBOLS=YES`, `COPY_PHASE_STRIP=YES`, and additional `OTHER_LDFLAGS=-w` flags. Add a build phase to strip `__objc_classname` and `__objc_methname` sections from the binary using `strip` or `ld` flags |

**Note on Approach B (compiler obfuscation):** LLVM obfuscation flags (`-mllvm -fla`, `-mllvm -sub`) are not available in stock Xcode/LLVM. They require building with a modified LLVM toolchain (e.g., the [OLLVM](https://github.com/heroims/obfuscator/wiki) project). This would require significant build infrastructure changes and is likely impractical for most Titanium developers.

---

### Measure 6: Runtime Integrity Checks (Low Impact, Defensive)

**Description:** Add runtime checks that detect when the app is being analyzed or the binary has been modified.

**Approaches:**

- **A) Anti-Debugging:** Check for debugger attachment using `sysctl` and refuse to decrypt files if detected. Only compiled into production builds (dist-appstore, dist-adhoc, dist-macappstore) via `TI_ANTI_DEBUG` preprocessor macro; debug and development builds are unaffected.
  - Trade-off: Can be bypassed by patching the check; not present in debug builds (developers can still debug)
  - Recovery difficulty: Requires additional bypass step in production builds

- **B) Code Signature Verification:** At runtime, verify the binary's code signature before decrypting. If the binary has been modified (e.g., to extract data), refuse to decrypt.
  - Trade-off: Adds startup latency; can be bypassed by patching
  - Recovery difficulty: Requires patching the verification code

- **C) Certificate Pinning:** Only decrypt if the app's signing certificate matches the expected one. Prevents re-signing attacks.
  - Trade-off: Requires certificate management
  - Recovery difficulty: Requires bypassing the certificate check

**Impact:** Doesn't prevent static analysis of the IPA but prevents dynamic extraction on a running device.

**SDK files requiring changes:**

| File | Lines | Current behavior | Required change |
|------|-------|------------------|-----------------|
| `iphone/templates/build/ApplicationRouting.m` | 17-24 | `resolveAppAsset:` directly calls `filterDataInRange` with no checks | **Approach A:** Added `_isDebuggerAttached()` check guarded by `#ifdef TI_ANTI_DEBUG`. Returns `nil` if debugger is detected. Only compiled into production builds |
| `iphone/templates/module/objc/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` | 10-22 | `moduleAsset` and `resolveModuleAsset:` directly call `filterDataInRange` with no checks | **Approach A:** Same `_isDebuggerAttached()` check guarded by `#ifdef TI_ANTI_DEBUG` added to both methods |
| `iphone/templates/module/swift/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` | 10-22 | Same as objc template | **Approach A:** Same `#ifdef TI_ANTI_DEBUG` guarded check added |
| `iphone/cli/commands/_build.js` | 1921 | Deploy type switch, production case | Added `this.antiDebug = true` for production deploy type; `this.antiDebug = false` default |
| `iphone/cli/commands/_build.js` | 4892 | `GCC_DEFINITIONS` in xcconfig | Appends `TI_ANTI_DEBUG=1` to `GCC_DEFINITIONS` when `this.antiDebug === true` |
| `iphone/TitaniumKit/TitaniumKit/Sources/API/TiUtils.m` | 1625-1637 | `performSelector:@selector(resolveAppAsset:) withObject:appurlstr` — no validation before calling | Could add integrity check before the `performSelector` call. Currently not implemented — the check is in the routing class itself |
| `iphone/TitaniumKit/TitaniumKit/Sources/API/AssetsModule.m` | 73-74 | `case FileStatusExistsEncrypted: return [TiUtils loadAppResource:url]` — no checks | Could add integrity checks in `loadURL:` before dispatching to encrypted file loading. Currently not implemented — the check is in the routing class itself |

**Note:** Runtime integrity checks are easily bypassed by an attacker with a jailbroken device and are primarily useful as a deterrent. They do not protect against static analysis of the IPA file itself.

---

## Encryption Configuration

### Default Behavior

`encryptJS` defaults to `true` only for **production** deploy types. Development and test builds have `encryptJS = false` by default, making debugging and development iteration faster.

| Target | Deploy Type | `encryptJS` Default |
|--------|-------------|--------------------|
| `simulator` | development | false |
| `device` | test | false |
| `dist-appstore` | production | true |
| `dist-adhoc` | production | true |
| `macos` | development | false |
| `dist-macappstore` | production | true |

### CLI Flags

- `--skip-js-encrypt`: Bypasses encryption even in production builds. Useful for debugging production-specific issues.
- `--always-js-encrypt`: Forces encryption on for non-production builds (development/test). Useful for testing hardening during development.

### tiapp.xml Properties

Encryption can also be controlled via `<property>` elements in `tiapp.xml`:

```xml
<!-- Force encryption on for development/test builds -->
<property name="ti.always.encryptjs" type="bool">true</property>

<!-- Disable encryption (even in production) -->
<property name="ti.skip.encryptjs" type="bool">true</property>
```

CLI flags take precedence over tiapp.xml properties.

---

## Android Attack Vectors

The Android encryption system uses `ti.cloak` — a closed-source module that handles both build-time encryption and runtime decryption. Analysis reveals critical weaknesses:

### Android Vector 1: Key Extraction from Native Library

**Problem:** The AES key is embedded in `libti.cloak.so` at a known symbol (`KEY_BLOCK`). The native `getKey()` function XORs the `KEY_BLOCK` with the salt parameter to produce the AES key. Both the salt (in `AssetCryptImpl.java` as plaintext) and the `KEY_BLOCK` (in the `.so` at symbol `KEY_BLOCK`) are trivially extractable from the APK.

**Location:** `libti.cloak.so` per ABI, `AssetCryptImpl.java` salt field.

**Current recovery method:** `nm -D libti.cloak.so | grep KEY_BLOCK` to find the offset, extract bytes, XOR with salt from decompiled Java.

### Android Vector 2: Plaintext Asset Filenames

**Problem:** `AssetCryptImpl.java` stores all encrypted asset paths as a `Collection<String>` — e.g., `"Resources/app.js"`, `"Resources/ti.internal/kernel/module.js"`. These are compiled into the DEX as plaintext strings, revealing every encrypted file's name and path.

**Location:** `android/templates/build/AssetCryptImpl.java` — `Arrays.asList("Resources/...", ...)`

**Current recovery method:** Decompile the DEX, extract the `assets` collection, append `.bin` to each path, and decrypt using the extracted key+salt.

### Android Vector 3: Closed-Source Native Library

**Problem:** `libti.cloak.so` and `ti.cloak.jar` are closed-source prebuilt components with no available source code. The `.so` contains `verifyApplication()` which checks the app's package name but is trivially bypassed. The `.jar` contains only a JNI bridge class.

**Location:** `android/titanium/lib/ti.cloak.jar`, `support/ti.cloak.zip/lib/android/*/libti.cloak.so`

### Android Vector 4: No Anti-Debug Protection

**Problem:** No runtime checks prevent debugging or dynamic analysis of the decryption process.

---

## Android Hardening Measures

### Measure A1: Replace ti.cloak with Pure Java/Node.js Implementation

**Description:** Eliminate the closed-source `ti.cloak` dependency entirely. Replace it with:
- Build-time: Pure Node.js encryption using `crypto` module (same as iOS `titanium_prep.js`)
- Runtime: Pure Java decryption using `javax.crypto.Cipher` (already in `AssetCryptImpl.java`)
- No native `.so` libraries needed for encryption

**Impact:** Eliminates Android Vector 1 (key extraction from `.so`) and Vector 3 (closed-source dependency).

**Why pure Java instead of a native `.so`?** The Titanium SDK is open source — both the build scripts and runtime templates are publicly available. A determined attacker can analyze either approach:

| Aspect | Pure Java (current) | Native `.so` (old ti.cloak) |
|---|---|---|
| Filenames in binary | djb2 hashes only (irreversible) | Plaintext strings in `assets` collection |
| Key extraction | XOR-masked — must find `xmask[]` + `maskedKey[]` and unmask | `KEY_BLOCK` at exported symbol, XOR with plaintext salt |
| Runtime extraction | Blocked by `Debug.isDebuggerConnected()` | `verifyApplication()` trivially bypassed |
| Reverse engineering effort | `jadx` decompiles DEX in seconds | `objdump`/Ghidra analyzes `.so` in minutes |
| Dynamic extraction | Frida hooks Java methods | Frida hooks JNI (`getKey()` return value) |
| Maintenance | Zero — pure Java, no ABI variants | Must build for 4+ ABIs per release |

The old `ti.cloak` was actually **less secure** than the current pure-Java approach: it stored filenames as plaintext strings, had the key at an exported symbol (`KEY_BLOCK`), and its package-name verification was trivially bypassed. The real security improvement comes from the hardening measures (hash-based lookup, XOR masking, anti-debug), not from whether the decryption code is Java or native. Both approaches are equally vulnerable to a determined attacker with Frida on a rooted device.

**Files to create:**
- `support/android/cloak.js` — Pure Node.js replacement for ti.cloak's build-time functions

**Files to modify:**
- `android/cli/commands/_build.js` — Replace `import('ti.cloak')` with `cloak.js`
- `android/templates/build/_T5C.java` — Remove `System.loadLibrary("ti.cloak")` and `ti.cloak.Binding.getKey()`, embed XOR-masked key directly
- `build/lib/packager.js` — Remove `ti.cloak.zip` extraction

**Files to delete:**
- `android/titanium/lib/ti.cloak.jar`

### Measure A2: Hash-Based Asset Lookup

**Description:** Replace the `Collection<String> assets` plaintext filename list with a `long[] ASSET_HASHES` array of djb2 hash values. At runtime, compute `djb2(path)` and search the hash array. Filenames never appear in the binary.

**Impact:** Eliminates Android Vector 2 (plaintext asset filenames in DEX).

**Equivalent iOS measure:** Measure 2B (hash-based string lookup)

**Implementation:** Add `djb2()` and `assetExists()` methods to `_T5C.java`. Build script computes hashes from asset paths and outputs them as `long[]` literals.

### Measure A3: XOR-Masked Key

**Description:** The AES key embedded in `_T5C.java` is XOR-masked with a random 16-byte mask. At runtime, `getKey()` unmasks the key before using it. This mirrors the iOS `xmask[]` approach.

**Impact:** Defeats static key extraction from decompiled Java. An attacker must trace the XOR-unmasking at runtime.

**Equivalent iOS measure:** Measure 4C (XOR-mask data blob)

### Measure A4: Anti-Debug Check

**Description:** Add `Debug.isDebuggerConnected()` check in `getAssetStream()`, returning `null` if a debugger is attached. Only active in production builds (guarded by `BuildConfig.DEBUG`).

**Impact:** Prevents dynamic extraction on a debugged device in production builds.

**Equivalent iOS measure:** Measure 6A (anti-debugging check)

### Measure A5: Class Name Obfuscation

**Description:** Rename `AssetCryptImpl` to `_T5C`. Update `App.java` template reference from `new AssetCryptImpl()` to `new _T5C()`. The template file is renamed from `AssetCryptImpl.java` to `_T5C.java`.

**Impact:** Removes the purpose-revealing class name from the DEX.

**Equivalent iOS measure:** Measure 5A (class name obfuscation)

### Measure A6: Obfuscated Key Computation (Phase 1) — NOT YET IMPLEMENTED

**Description:** Replace the current trivial XOR-mask key embedding (`maskedKey[i] ^ xmask[i % xmask.length]`) with multi-step key derivation from multiple scattered seed values. At build time, generate N random seed byte arrays and a derivation chain specification. At runtime, compute the AES key via SHA-256 chains and XOR operations across the seeds. The key is still derivable from data in the binary, but the computation resists automated scanning and simple hex dumps.

**Impact:** Defeats casual key extraction. Raises static analysis effort from "search for byte arrays and XOR" to "trace a multi-step computation." Dynamic analysis with Frida still recovers the key in minutes.

**Equivalent iOS measure:** Measure 1D (obfuscated key computation)

**Implementation:** Add `deriveKey()` method to `_T5C.java` that computes the key from scattered `private static byte[] _sN` seed arrays. Build script generates seeds and derivation chain, outputs them as Java byte array literals. On iOS, add equivalent `deriveKeyAndIV()` C function in `ApplicationRouting.m`.

### Measure A7: Android Keystore Migration (Phase 2) — NOT YET IMPLEMENTED

**Description:** On first app launch, generate an AES key in the Android Keystore (hardware-backed on API 23+). Decrypt all JS assets using the embedded transport key, re-encrypt under the Keystore key, store in internal storage, then zero out the transport key. After migration, no encryption key exists in the DEX's attack surface.

**Impact:** After migration, the APK alone is insufficient to decrypt assets. An attacker needs physical device access + root + Keystore extraction. The transport key is only a vulnerability during the first-launch window.

**Trade-offs:**
- Requires API 23+ for symmetric key support; pre-23 devices need RSA keypair wrapping fallback
- Keystore keys can be invalidated by lock screen changes or biometric re-enrollment
- Adds first-launch latency (decrypt + re-encrypt all JS assets)
- Re-encrypted assets stored in internal storage increase app disk usage

**Equivalent iOS measure:** Measure 1B (Keychain migration)

---

## Prebuilt Binary Dependencies

Three critical components in the encryption pipeline were prebuilt binaries with no available source code. All three have been resolved:

| Binary | Location | Purpose | Status |
|--------|----------|---------|--------|
| `titanium_prep` | `support/iphone/titanium_prep` | Build-time tool that generates `data[]`, `ranges[]`, key+IV, and `initWithObjectsAndKeys:` dictionary | **Replaced** — reimplemented as `support/iphone/titanium_prep.js` |
| `tiverify.xcframework` | `iphone/lib/tiverify.xcframework/` | Runtime library providing `filterDataInRange(NSData* thedata, NSRange range)` — performs AES-128-CBC decryption | **Replaced** — reimplemented in `iphone/lib/tiverify_src/` |
| `ti.cloak` | `support/ti.cloak.zip`, `android/titanium/lib/ti.cloak.jar` | Build-time encryption + runtime decryption for Android | **Replaced** — pure Node.js (build) + Java (runtime) |

**No remaining prebuilt binary blockers.** All hardening measures can be implemented by modifying source code.

**`tiverify.xcframework` reimplementation:** The original binary was reverse-engineered by analyzing the exported symbol `_filterDataInRange`, tracing the disassembly across x86_64 and arm64 slices, and identifying the use of Apple's CommonCrypto `CCCrypt` function. The reimplementation in `iphone/lib/tiverify_src/TiVerify.m` is functionally identical: AES-128-CBC decryption with PKCS7 padding, key and IV extracted from the last 32 bytes of the data blob. The xcframework has been rebuilt for ios-arm64, ios-arm64_x86_64-maccatalyst, and ios-arm64_x86_64-simulator slices.

**All blockers resolved:** Both `tiverify.xcframework` and `titanium_prep` are now in source form. Measures 1D, 1B, 2B/C, and 4B can be implemented by modifying the open-source replacements.

---

## Implementation Status

### Quick Overview

| # | Measure | Platform | Status |
|---|---------|----------|--------|
| 2A | Runtime string construction | iOS | ✅ Implemented |
| 2B | Hash-based string lookup (djb2) | iOS | ✅ Implemented |
| 3 | Remove `_index_.json` | iOS | ✅ Implemented |
| 4C | XOR-mask data blob | iOS | ✅ Implemented |
| 5A | Obfuscate class names | iOS | ✅ Implemented (`_T5Routing`, `_T5A`) |
| 5A | Obfuscate class names | Android | ✅ Implemented (`_T5C`) |
| 6A | Anti-debugging check | iOS | ✅ Implemented (`sysctl` P_TRACED) |
| 6A | Anti-debugging check | Android | ✅ Implemented (`Debug.isDebuggerConnected()`) |
| — | Production-only `encryptJS` default | Both | ✅ Implemented |
| — | `--always-js-encrypt` / `--skip-js-encrypt` CLI flags | Both | ✅ Implemented |
| — | `ti.always.encryptjs` / `ti.skip.encryptjs` tiapp.xml | Both | ✅ Implemented |
| A1 | Replace ti.cloak with pure Java/Node.js | Android | ✅ Implemented |
| A2 | Hash-based asset lookup (djb2) | Android | ✅ Implemented |
| A3 | XOR-masked key | Android | ✅ Implemented |
| A4 | Anti-debug check | Android | ✅ Implemented |
| A5 | Class name obfuscation | Android | ✅ Implemented |
| A6 | Obfuscated key computation (Phase 1) | Android | ❌ Not implemented |
| A7 | Android Keystore migration (Phase 2) | Android | ❌ Not implemented |
| — | `titanium_prep` Node.js replacement | iOS | ✅ Implemented |
| — | `tiverify.xcframework` rebuild from source | iOS | ✅ Implemented |
| — | JS kernel fallback for missing `_index_.json` | iOS | ✅ Implemented |
| 1D | Obfuscated key computation (Phase 1) | Both | ❌ Not implemented |
| 1B | Keystore/Keychain migration (Phase 2) | Both | ❌ Not implemented |
| 4B | Encrypt NSRange array | iOS | ❌ Not implemented |
| 5C | Strip ObjC metadata | iOS | 📝 Documented (manual step) |

### Quick Wins (No prebuilt binary dependency) — IMPLEMENTED

The following measures have been implemented on branch `security/js-encryption-hardening`:

#### Measure 2A: Runtime String Construction — IMPLEMENTED

Replaces `@"stringLiteral"` dictionary keys in `titanium_prep` output with runtime-constructed strings from char arrays. This removes filenames from `__cfstring` and `__cstring` binary sections.

**Implementation:** A `_obfuscateStringKeys(code)` method post-processes the `titanium_prep` output before EJS rendering. Each `@"..."` string literal is replaced with a `static char _kN[]` declaration and `[NSString stringWithUTF8String:_kN]` lookup.

**Files changed:**
- `iphone/cli/commands/_build.js` — Added `_obfuscateStringKeys()` method to `iOSBuilder` class, called after `titanium_prep` validation
- `iphone/cli/commands/_buildModule.js` — Added `_obfuscateStringKeys()` method to `iOSModuleBuilder` class, called after `titanium_prep` validation

#### Measure 3: Remove `_index_.json` — IMPLEMENTED

Skips writing `_index_.json` to the app bundle when encryption is enabled. The runtime already handles the missing file gracefully by falling back to `FileStatusUnknown`, which now tries encrypted loading first.

**Files changed:**
- `iphone/cli/commands/_build.js` — `generateRequireIndex()` now returns early when `this.encryptJS === true`, logging that the file is skipped for security
- `iphone/TitaniumKit/TitaniumKit/Sources/API/AssetsModule.m` — Reversed the `FileStatusUnknown` case in `loadURL:` to try encrypted loading (`loadAppResource:`) first, then fall back to disk. This is more efficient for production builds where most files are encrypted.

#### Measure 5A: Obfuscate Class Names — IMPLEMENTED

Renamed `ApplicationRouting` to `_T5Routing` and `ModuleAssets` suffix to `_T5A`. These opaque names don't reveal the class purpose to attackers scanning `__objc_classname` sections.

**Files changed:**
- `iphone/templates/build/ApplicationRouting.m` — `@implementation _T5Routing`
- `iphone/Classes/ApplicationRouting.h` — `@interface _T5Routing`
- `iphone/Classes/ApplicationRouting.m` — `@implementation _T5Routing`
- `iphone/TitaniumKit/TitaniumKit/Sources/API/TiUtils.m` — `NSClassFromString(@"_T5Routing")`
- `iphone/TitaniumKit/TitaniumKit/Sources/API/TiModule.m` — `%@_T5A` format string
- `iphone/templates/module/objc/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` — `@implementation <%- moduleIdAsIdentifier %>_T5A`
- `iphone/templates/module/objc/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.h.ejs` — `@interface <%- moduleIdAsIdentifier %>_T5A`
- `iphone/templates/module/swift/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` — `@implementation <%- moduleIdAsIdentifier %>_T5A`
- `iphone/templates/module/swift/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.h.ejs` — `@interface <%- moduleIdAsIdentifier %>_T5A`

#### Measure 6A: Anti-Debugging Check — IMPLEMENTED (production builds only)

Added `_isDebuggerAttached()` function using `sysctl` with `KERN_PROC` and `P_TRACED` flag check in both `ApplicationRouting.m` and `ModuleAssets` templates. Returns `nil` if a debugger is detected, preventing runtime decryption under debugging.

The check is conditionally compiled via `#ifdef TI_ANTI_DEBUG` and only included in production builds (dist-appstore, dist-adhoc, dist-macappstore). A `TI_ANTI_DEBUG=1` macro is added to `GCC_DEFINITIONS` in the Xcode project's xcconfig when `this.antiDebug === true`, which is only set for the `production` deploy type. This ensures debug and development builds are unaffected, while production App Store builds include the anti-debugging protection.

**Files changed:**
- `iphone/templates/build/ApplicationRouting.m` — Added `#ifdef TI_ANTI_DEBUG` guarded `#import <sys/types.h>`, `#import <sys/sysctl.h>`, `_isDebuggerAttached()` function, and guard check in `resolveAppAsset:`
- `iphone/templates/module/objc/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` — Added same `#ifdef TI_ANTI_DEBUG` guarded headers, function, and guard checks
- `iphone/templates/module/swift/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` — Same changes as objc template
- `iphone/cli/commands/_build.js` — Added `this.antiDebug = true` for production deploy type; `this.antiDebug = false` default; `TI_ANTI_DEBUG=1` appended to `GCC_DEFINITIONS` in xcconfig when `this.antiDebug` is true

#### Encryption Enforcement for All Build Types — IMPLEMENTED

`encryptJS` is now `true` for all deploy types including `development` (simulator, macOS). A `--skip-js-encrypt` CLI flag is available to explicitly disable encryption.

**Files changed:**
- `iphone/cli/commands/_build.js` — Changed `this.encryptJS = false` to `this.encryptJS = true` in the `development`/`default` case. Added `if (cli.argv['skip-js-encrypt']) { this.encryptJS = false; }` after the switch.
- `cli/commands/build.js` — Added `--skip-js-encrypt` CLI option.

#### JS Kernel Fallback for Missing `_index_.json` — IMPLEMENTED

When `_index_.json` is absent (encrypted builds), the JS module resolver `filenameExists()` now gracefully handles the missing file instead of crashing with `JSON.parse(undefined)`. If the index is unavailable, it falls back to direct asset loading via `assets.readAsset()` using a `/`-prefixed path that correctly routes through the encrypted file loading pipeline (`loadURL:` → `loadAppResource:` → `resolveAppAsset:`).

**Bug fix:** The initial implementation passed `Resources/app.js` to `readAsset()`, which was incorrectly routed through `loadCoreModuleAsset:` instead of the encrypted file loading path. Fixed by stripping the `Resources/` prefix and prepending `/`, so `readAsset('/app.js')` correctly goes through the URL-based loading path.

**Files changed:**
- `common/Resources/ti.internal/kernel/module.js` — Updated `filenameExists()` to handle missing `_index_.json`: catches `JSON.parse` errors, sets `fileIndex = null` when the file is absent, and falls back to `assets.readAsset('/' + filename.substring(filename.indexOf('/') + 1))` instead of `assets.readAsset(filename)`

#### Android Build Fix — IMPLEMENTED

The JS kernel contained an em-dash character (`—`) in a comment, which encoded to UTF-8 bytes `-30, -128, -108` that caused C++ narrowing errors in the generated `KrollJS.h` (Android V8 runtime). Replaced the em-dash with a regular ASCII dash.

**Files changed:**
- `common/Resources/ti.internal/kernel/module.js` — Replaced em-dash `—` with ASCII dash `-` in the fallback comment

### Not Yet Implemented

| Priority | Measure | Impact | Effort | Blocker |
|----------|---------|--------|--------|---------|
| 1 | Obfuscated key computation (1D: Phase 1) | High | Low | No blocker — all source available |
| 2 | Keystore/Keychain migration (1B: Phase 2) | Critical | Medium | No blocker — adds first-launch flow |
| 3 | Encrypt NSRange array (4B) | Medium | Medium | No blocker — titanium_prep now has source |

#### Measure 2B: Hash-Based String Lookup — IMPLEMENTED

Replaces `initWithObjectsAndKeys:` string-keyed dictionary with `NSDictionary` using integer djb2 hash keys. Filenames are hashed at build time in `titanium_prep.js` and the hash values are used as dictionary keys. At runtime, the incoming path is hashed via `djb2_hash()` before dictionary lookup, so no filenames appear in the compiled binary.

**Files changed:**
- `support/iphone/titanium_prep.js` — Added `djb2()` hash function; dictionary keys changed from `@"filename"` to `@(hashValue)`
- `iphone/templates/build/ApplicationRouting.m` — Added `djb2_hash()` C function; lookup uses `@(djb2_hash([path UTF8String]))`
- `iphone/templates/module/objc/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` — Same `djb2_hash()` function and hash-based lookup
- `iphone/templates/module/swift/template/ios/Classes/{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` — Same
- `iphone/cli/commands/_buildModule.js` — Updated `allEncryptedAssetsReturn` to use hash-based lookup

#### Measure 4C: XOR-Mask Data Blob — IMPLEMENTED

The `data[]` byte array is now XOR-masked with a random 16-byte key (`xmask[]`) before being embedded in the binary. The templates XOR-unmask the data before passing it to `filterDataInRange`. This defeats entropy-based detection — the masked data appears as uniform random bytes rather than showing the characteristic high-entropy transition that reveals the encrypted payload boundary.

**How it works:**
1. `titanium_prep.js` generates a random 16-byte XOR mask key and applies it cyclically over the entire data blob (encrypted payloads + key + IV)
2. The mask key is output as `static UInt8 xmask[]` in the generated ObjC code
3. `ApplicationRouting.m` template XOR-unmasks the data into a mutable buffer before calling `filterDataInRange`
4. Module templates (`ModuleAssets.m.ejs`) do the same via updated return expressions

**Files changed:**
- `support/iphone/titanium_prep.js` — Generates `xmask[]` and XOR-masks the data blob
- `iphone/templates/build/ApplicationRouting.m` — XOR-unmasks data before decryption
- `iphone/cli/commands/_buildModule.js` — Updated return expressions for module assets

#### Measure 5C: Strip ObjC Metadata — Documented

The `__objc_methname` and `__objc_classname` sections reveal method and class names in the binary. Removing these sections can break ObjC runtime message dispatch, so they cannot be stripped automatically. Production builds already use `DEPLOYMENT_POSTPROCESSING = YES` and `COPY_PHASE_STRIP = YES` (Release configuration), which removes debug symbols.

For aggressive metadata stripping, developers can add a post-build script phase:
```bash
xcrun strip -x -S "$TARGET_BUILD_DIR/$PRODUCT_NAME.app/$PRODUCT_NAME"
```
This should be tested carefully, as it may break `objc_msgSend` dispatch for dynamically-constructed selectors.

**Note:** The existing `_obfuscateStringKeys()` post-processing already removes JS filenames from `__cfstring`/`__cstring` sections. The remaining `__objc_methname` entries are standard ObjC method selectors (`resolveAppAsset:`, `moduleAsset`, etc.) which don't directly reveal JS file contents.

#### titanium_prep Node.js Replacement — IMPLEMENTED

The prebuilt `titanium_prep` binary (the last closed-source component in the iOS build pipeline) has been replaced with a Node.js script (`support/iphone/titanium_prep.js`). The script is a drop-in replacement that:

- Accepts the same 3 CLI arguments (`<app_id> <assets_dir> <guid>`) and reads filenames from stdin
- Encrypts each file with AES-128-CBC + PKCS7 padding using `crypto.createCipheriv`
- Generates random key and IV via `crypto.randomBytes(16)`
- Produces the same Objective-C output format: `static UInt8 data[]`, `static NSRange ranges[]`, and `initWithObjectsAndKeys` dictionary
- The build system prefers `titanium_prep.js` over the binary when both are present

Round-trip decryption testing confirms the output is compatible with `TiVerify.m`'s `filterDataInRange()`.

**Files added:**
- `support/iphone/titanium_prep.js` — Node.js drop-in replacement for the prebuilt binary

**Files changed:**
- `iphone/cli/commands/_build.js` — Prefers `titanium_prep.js` over `titanium_prep` binary in `encryptJSFiles()`
- `iphone/cli/commands/_buildModule.js` — Prefers `titanium_prep.js` over `titanium_prep` binary in `compileJS()`

#### tiverify.xcframework Rebuild — IMPLEMENTED

The prebuilt `tiverify.xcframework` has been reverse-engineered and reimplemented from source. The new implementation in `iphone/lib/tiverify_src/TiVerify.m` provides the same `filterDataInRange(NSData*, NSRange)` API using CommonCrypto AES-128-CBC decryption with PKCS7 padding.

A build script (`support/iphone/build_tiverify.sh`) compiles `TiVerify.m` for all three platform slices (iOS device arm64, iOS Simulator arm64+x86_64, Mac Catalyst arm64+x86_64) and assembles them into the xcframework.

The rebuild is integrated into the SDK build system via the `--rebuild-tiverify` flag:

```bash
npm run cleanbuild -- ios --rebuild-tiverify
```

This recompiles `TiVerify.m` from source and replaces `iphone/lib/tiverify.xcframework` before the TitaniumKit build step.

**Files added:**
- `iphone/lib/tiverify_src/TiVerify.h` — Public header with API documentation
- `iphone/lib/tiverify_src/TiVerify.m` — Reimplementation of `filterDataInRange` using CommonCrypto
- `support/iphone/build_tiverify.sh` — Build script that compiles for all slices and creates the xcframework

**Files changed:**
- `build/scons-cleanbuild.js` — Added `--rebuild-tiverify` CLI option
- `build/lib/ios.js` — Added `_rebuildTiVerify()` method, called during `build()` when flag is set
- `iphone/lib/tiverify.xcframework/` — Rebuilt from source (identical API, now reproducible)

---

## Recommended Priority

| Priority | Measure | Impact | Effort | Status |
|----------|---------|--------|--------|--------|
| ~~1~~ | ~~Obfuscate string constants (2A: Runtime string construction)~~ | High | ~~Medium~~ Low | ✅ Implemented |
| ~~2~~ | ~~Remove `_index_.json` (Measure 3)~~ | High | Low | ✅ Implemented |
| ~~3~~ | ~~Hash-based string lookup (2B)~~ | High | Low | ✅ Implemented |
| ~~4~~ | ~~XOR-mask data blob (4C)~~ | Medium | Low | ✅ Implemented |
| ~~5~~ | ~~Strip/obfuscate metadata (5A + 5C)~~ | Medium | Low | ✅ 5A implemented / 📝 5C documented |
| ~~6~~ | ~~Anti-debugging check (6A)~~ | Low | Low | ✅ Implemented |
| ~~7~~ | ~~Replace ti.cloak (A1)~~ | High | High | ✅ Implemented |
| ~~8~~ | ~~Hash-based asset lookup (A2)~~ | High | Low | ✅ Implemented |
| ~~9~~ | ~~XOR-masked key (A3)~~ | Medium | Low | ✅ Implemented |
| ~~10~~ | ~~Anti-debug check Android (A4)~~ | Low | Low | ✅ Implemented |
| ~~11~~ | ~~Class name obfuscation Android (A5)~~ | Medium | Low | ✅ Implemented |
| ~~12~~ | ~~Production-only encryptJS defaults~~ | High | Low | ✅ Implemented |
| 13 | Obfuscated key computation (1D: Phase 1) | High | Low | ❌ Next |
| 14 | Keystore/Keychain migration (1B: Phase 2) | Critical | Medium | ❌ After 1D |
| 15 | Encrypt NSRange array (4B) | Medium | Medium | ❌ Not implemented |

## Effectiveness Assessment

**Current state:** An attacker with only the IPA/APK file (no device access) can recover 100% of JS source files. On iOS, the key is the last 32 bytes of the data blob (trivially extractable). On Android, the key is XOR-masked with an embedded mask (equally trivial).

**After implemented measures:** Static analysis is significantly harder on both platforms. Filenames are no longer in plaintext (must trace runtime or brute-force hashes), `_index_.json` is gone, class names are obfuscated, and debugging is blocked. An attacker would need to use entropy analysis and dynamic tracing, raising effort from minutes to hours. On Android, `ti.cloak` is eliminated entirely — keys are XOR-masked, filenames are djb2 hashes, and anti-debug protection is active in production.

**After Measure 1D (obfuscated key computation — Phase 1):** The key is no longer trivially extractable via hex dump or simple XOR inversion. An attacker must trace a multi-step SHA-256 computation chain or use dynamic analysis (Frida breakpoint at `CCCrypt`/`Cipher.init`). Raises static analysis effort from minutes to hours. Dynamic analysis with Frida still recovers the key in minutes.

**After Measure 1B (Keystore/Keychain migration — Phase 2):** After first-launch migration, no encryption key exists in the binary's attack surface. The transport key is only a vulnerability during the first-launch window. Subsequent launches use a hardware-backed key that cannot be extracted from the IPA/APK alone. An attacker needs physical device access + jailbreak/root + Keystore/Keychain extraction.

**Remaining gaps (iOS only):** Measure 4B (encrypted NSRange array) would further harden iOS by obfuscating the file boundary metadata.