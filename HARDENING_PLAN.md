# Plan: Hardening Titanium iOS IPA Against JS File Recovery

> **Note:** This document is a security analysis and hardening plan. It describes theoretical attack vectors and defensive measures. No changes to the SDK are being implemented — this serves as a reference for understanding and improving the protection of JS files in production IPAs.

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

**Approaches:**

- **A) Key Derivation from Device Identifier:** Use `identifierForVendor` or keychain-stored identifier as input to PBKDF2/HKDF to derive the AES key at runtime. The IPA would no longer contain the key.
  - Trade-off: App must phone home on first launch or embed an obfuscated seed value
  - Recovery difficulty: Attacker must extract the seed from obfuscated runtime code (hard but not impossible)

- **B) Separate Key in Keychain:** Store the key in the iOS Keychain with `kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly`. The IPA alone is insufficient; the device is needed.
  - Trade-off: Requires key provisioning step; app won't work on first launch without network
  - Recovery difficulty: Requires access to the device + jailbreak

- **C) White-Box Cryptography:** Use a white-box AES implementation where the key is embedded in precomputed lookup tables, making static extraction infeasible.
  - Trade-off: ~2-10x performance overhead; significantly larger binary; not foolproof (DFA attacks exist)
  - Recovery difficulty: Requires dynamic analysis or side-channel attacks

**Impact:** Eliminates Vectors 1, 5, and 6 (key extraction, entropy detection, and range-finding all become moot without the key).

**SDK files requiring changes:**

| File | Lines | Current behavior | Required change |
|------|-------|------------------|-----------------|
| `iphone/templates/build/ApplicationRouting.m` | 13-24 | `<%- bytes %>` embeds `data[]` with key+IV as last 32 bytes, `ranges[]` as plaintext, `initWithObjectsAndKeys:` dictionary with string keys | Remove key+IV from data array; add key derivation/lookup call before `filterDataInRange`; replace `initWithObjectsAndKeys:` with hash-based lookup (see Measure 2) |
| `iphone/lib/tiverify.xcframework/` | — | Prebuilt static library containing `filterDataInRange(NSData* thedata, NSRange range)` which performs AES-128-CBC decryption using key+IV extracted from the end of `thedata` | Must be rebuilt to accept key+IV as separate parameters, or to perform key derivation internally. **No source code is available** — this would need to be reverse-engineered and rewritten, or replaced with a new implementation |
| `support/iphone/titanium_prep` | — | Prebuilt binary that generates the `data[]` array, `ranges[]`, key+IV, and `initWithObjectsAndKeys:` dictionary at build time. Reads `/dev/urandom` for key generation | Must be modified to either omit key+IV from output (for derived-key approach) or embed key in white-box tables. **No source code is available** — would need to be reverse-engineered and rewritten |
| `iphone/cli/commands/_build.js` | 6667-6797 | `encryptJSFiles()` spawns `titanium_prep`, reads its `initWithObjectsAndKeys:` output, renders `ApplicationRouting.m` via EJS template | Must be updated to handle new key provisioning logic (e.g., passing derived key seed, or omitting key from generated code) |
| `iphone/cli/commands/_buildModule.js` | 546-707 | `compileJS()` spawns `titanium_prep` for module encryption, renders `{{ModuleIdAsIdentifier}}ModuleAssets.m.ejs` | Same changes as `_build.js` — must handle new key provisioning for module assets |
| `iphone/TitaniumKit/TitaniumKit/Sources/API/TiUtils.m` | 1577-1641 | `loadAppResource:` calls `[AppRouter performSelector:@selector(resolveAppAsset:) withObject:appurlstr]` | No change needed if `resolveAppAsset:` internally handles key derivation; if key is passed separately, this call site must provide it |
| `iphone/TitaniumKit/TitaniumKit/Sources/API/TiModule.m` | 205-219 | `loadModuleAsset:` calls `[moduleAssets performSelector:@selector(resolveModuleAsset:) withObject:fromPath]` with mangling fallback | Same as TiUtils — must adapt if module key provisioning changes |

**Critical dependency:** `tiverify.xcframework` and `titanium_prep` are prebuilt binaries with no available source code. Any measure that changes how keys are handled requires either:
1. Reverse-engineering and rewriting these binaries, or
2. Replacing `filterDataInRange` with a new implementation (e.g., CommonCrypto calls directly in `ApplicationRouting.m`)

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

## Encryption Enforcement for All Build Types

Originally, `encryptJS` was only set to `true` for `production` and `test` deploy types. Development, simulator, and macOS debug builds had `encryptJS = false`, meaning JS files were stored unencrypted and all hardening measures were bypassed.

**Change:** `encryptJS` is now `true` for all deploy types including `development` (simulator, macOS). This ensures all hardening measures apply universally regardless of build target.

| Target | Deploy Type | `encryptJS` Before | `encryptJS` After |
|--------|-------------|--------------------|-------------------|
| `simulator` | development | false | **true** |
| `device` | test | true | true |
| `dist-appstore` | production | true | true |
| `dist-adhoc` | production | true | true |
| `macos` | development | false | **true** |
| `dist-macappstore` | production | true | true |

A new CLI flag `--skip-js-encrypt` is available to explicitly disable encryption when needed (e.g., for local development iteration where faster builds are preferred over security).

**SDK files requiring changes:**

| File | Change |
|------|--------|
| `iphone/cli/commands/_build.js` | Changed `this.encryptJS = false` to `this.encryptJS = true` in the `development` case. Added `--skip-js-encrypt` flag handling that sets `this.encryptJS = false` when present. |
| `cli/commands/build.js` | Added `--skip-js-encrypt` CLI option with `default: false` and description. |

---

## Prebuilt Binary Dependencies

Two critical components in the encryption pipeline were prebuilt binaries with no available source code. `tiverify.xcframework` has been reverse-engineered and reimplemented; `titanium_prep` remains a prebuilt binary:

| Binary | Location | Purpose | Source available? |
|--------|----------|---------|-------------------|
| `titanium_prep` | `support/iphone/titanium_prep` | Build-time tool that generates `data[]`, `ranges[]`, key+IV, and `initWithObjectsAndKeys:` dictionary. Reads `/dev/urandom` for key generation. | No |
| `tiverify.xcframework` | `iphone/lib/tiverify.xcframework/` | Runtime library providing `filterDataInRange(NSData* thedata, NSRange range)` — performs AES-128-CBC decryption extracting key+IV from end of data | **Yes** — reimplemented from scratch in `iphone/lib/tiverify_src/` |

**Implications:**
- **Measure 1** (key removal): `tiverify.xcframework` can now be modified to accept key+IV as separate parameters or implement key derivation internally. `titanium_prep` still needs to be reverse-engineered or reimplemented.
- **Measure 2** (string obfuscation) approaches B/C: Still require rewriting `titanium_prep` to output hash keys instead of string keys.
- **Measure 4** (data obfuscation): `tiverify.xcframework` can now be modified (e.g., to accept XOR mask keys). `titanium_prep` still needs modification.
- **Measure 5** (stripping) and **Measure 6** (runtime checks): Can be implemented without touching these binaries.

**`tiverify.xcframework` reimplementation:** The original binary was reverse-engineered by analyzing the exported symbol `_filterDataInRange`, tracing the disassembly across x86_64 and arm64 slices, and identifying the use of Apple's CommonCrypto `CCCrypt` function. The reimplementation in `iphone/lib/tiverify_src/TiVerify.m` is functionally identical: AES-128-CBC decryption with PKCS7 padding, key and IV extracted from the last 32 bytes of the data blob. The xcframework has been rebuilt for ios-arm64, ios-arm64_x86_64-maccatalyst, and ios-arm64_x86_64-simulator slices.

**Remaining blocker:** `titanium_prep` must still be reverse-engineered or reimplemented before Measures 1, 2B/C, and 4 can be implemented.

---

## Implementation Status

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

### Not Yet Implemented (Previously blocked on titanium_prep source)

| Priority | Measure | Impact | Effort | Blocker |
|----------|---------|--------|--------|---------|
| 1 | Remove key/IV from binary (1C: White-box AES) | Critical | Medium | No blocker — titanium_prep now has source |
| 2 | Hash-based string lookup (2B/2C) | High | Medium | No blocker — titanium_prep now has source |
| 4 | Encrypt NSRange array (4B) | Medium | Medium | No blocker — titanium_prep now has source |
| ~~4~~ | ~~XOR-mask data blob (4C)~~ | Medium | ~~Low~~ | ~~No blocker~~ Implemented |
| ~~5C~~ | ~~Strip ObjC metadata sections~~ | Medium | ~~Low~~ | ~~No blocker~~ Documented as manual step |

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

| Priority | Measure | Impact | Effort | Blocker |
|----------|---------|--------|--------|---------|
| ~~1~~ | ~~Remove key/IV from binary (1C: White-box AES)~~ | Critical | ~~High~~ Medium | ~~Requires tiverify + titanium_prep rewrite~~ No blocker — both now have source |
| ~~2~~ | ~~Obfuscate string constants (2A: Runtime string construction)~~ | High | ~~Medium~~ Low | ~~Requires `titanium_prep` rewrite~~ No blocker — implemented as post-processing |
| ~~3~~ | ~~Remove `_index_.json` (Measure 3)~~ | High | ~~Low~~ | ~~No blocker — pure JS/ObjC changes~~ Implemented |
| 4 | Encrypt NSRange array (4B) | Medium | Medium | No blocker — titanium_prep now has source |
| ~~5~~ | ~~XOR-mask data blob (4C)~~ | Medium | ~~Low~~ | ~~No blocker~~ Implemented |
| ~~6~~ | ~~Strip/obfuscate metadata (5A + 5C)~~ | Medium | ~~Low~~ | ~~No blocker~~ Implemented (5A) / Documented (5C) |
| ~~7~~ | ~~Anti-debugging check (6A)~~ | Low | ~~Low~~ | ~~No blocker — ObjC code changes~~ Implemented |

## Effectiveness Assessment

**Current state:** An attacker with only the IPA file (no device access) can recover 100% of JS source files in under 30 seconds using `titanium_ipa_decryptor.py`.

**After quick wins (Measures 2A, 3, 5A, 6A — now implemented):** Static analysis is significantly harder. Filenames are no longer in `__cfstring` as plain strings (must trace runtime), `_index_.json` is gone, class names are obfuscated, and debugging is blocked. An attacker would need to use entropy analysis and dynamic tracing, raising effort from minutes to hours. All hardening now applies to every build type (production, test, development, simulator, macOS).

**After Measures 1-3 (full implementation):** Static analysis of the IPA alone is no longer sufficient. An attacker needs dynamic analysis (runtime tracing on a jailbroken device) or significant reverse engineering effort (weeks, not minutes). With `titanium_prep` now in source form, implementing white-box AES, hash-based lookups, and data obfuscation is unblocked.

**After Measures 1-5 (full implementation):** Even dynamic analysis becomes challenging. Recovering filenames requires tracing every `resolveAppAsset:` call or brute-forcing hash mappings. Combined with anti-debugging, the effort approaches the cost of rewriting the app from scratch.

**No remaining prebuilt binary blockers:** Both `tiverify.xcframework` and `titanium_prep` are now in source form. All hardening measures can be implemented by modifying the source code.

**Practical recommendation:** The implemented quick wins eliminate the most exploitable vectors with minimal effort. The remaining measures (1, 2B/C, 4) require reimplementing `titanium_prep` and `tiverify.xcframework` with available source code. Until those binaries are replaced, the current implementation provides the best achievable protection without prebuilt binary modifications.