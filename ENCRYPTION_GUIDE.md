# JS Encryption Hardening — User Guide

## Overview

The Titanium SDK encrypts JavaScript files in production builds to prevent easy extraction from the app binary. Starting with SDK 13.3.0, the encryption system has been significantly hardened on both iOS and Android:

- **Hash-based asset lookup**: Filenames are replaced with djb2 hash values — no plaintext filenames appear in the compiled binary
- **XOR-masked encryption keys**: The AES key is XOR-masked with a random mask, making static extraction infeasible
- **Anti-debug protection**: Production builds refuse to decrypt when a debugger is attached
- **Class name obfuscation**: Internal routing classes use opaque names (`_T5Routing`, `_T5A`) instead of revealing names
- **No `_index_.json`**: The file index is omitted from encrypted builds to prevent filename leakage

## Encryption by Deploy Type

### Default Behavior

| Target | Platform | Deploy Type | `encryptJS` Default |
|--------|----------|-------------|---------------------|
| `simulator` | iOS | development | **false** |
| `device` | iOS | test | **false** |
| `dist-appstore` | iOS | production | **true** |
| `dist-adhoc` | iOS | production | **true** |
| `macos` | iOS/macOS | development | **false** |
| `dist-macappstore` | iOS/macOS | production | **true** |
| `device` | Android | test | **false** |
| `dist-appstore` | Android | production | **true** |

**Key change from previous versions**: `encryptJS` previously defaulted to `true` for **all** deploy types including development and test. It now defaults to `true` only for production, making debugging and development significantly easier.

### What happens when `encryptJS = false`

- JS files are included unencrypted in the app bundle
- `_index_.json` is written (file index for module resolution)
- No `ApplicationRouting.m` / `AssetCryptImpl.java` encryption code is generated
- Source maps and breakpoints work normally

### What happens when `encryptJS = true`

- JS files are encrypted with AES-128-CBC and stored as `.bin` files
- `_index_.json` is **not** written (prevents filename leakage)
- Filenames are replaced with djb2 hash values in the lookup dictionary
- Encryption keys are XOR-masked before embedding
- Anti-debug checks are active in production builds (iOS: `sysctl` P_TRACED check; Android: `Debug.isDebuggerConnected()`)
- On iOS: class names are obfuscated (`_T5Routing` instead of `ApplicationRouting`)

## CLI Options

### `--skip-js-encrypt`

Bypasses JS encryption even in production builds.

```bash
ti build -p ios --target dist-appstore --skip-js-encrypt
ti build -p android --target dist-appstore --skip-js-encrypt
```

Use this when:
- Debugging production-specific issues and you need to inspect JS files
- Running automated tests against a production-like build
- You want to reduce build time for a non-release distribution build

### `--always-js-encrypt`

Forces JS encryption on for non-production builds (development and test).

```bash
ti build -p ios --target simulator --always-js-encrypt
ti build -p android --target device --always-js-encrypt
```

Use this when:
- Testing the encryption pipeline during development
- Verifying that encrypted assets load correctly
- Validating anti-debug behavior in a debuggable environment

### `--skip-js-minify`

Bypasses JS minification. Available on both platforms.

```bash
ti build -p ios --target dist-appstore --skip-js-minify
```

Note: Simulator builds never minify JS regardless of this flag.

## tiapp.xml Properties

You can also control encryption via `<property>` elements in `tiapp.xml`. These are evaluated after the deploy-type defaults, so they override the default behavior.

### Force encryption on for all builds

```xml
<property name="ti.always.encryptjs" type="bool">true</property>
```

This is equivalent to `--always-js-encrypt` on every build. Useful when you want all builds (including development) to use encryption.

### Disable encryption for all builds

```xml
<property name="ti.skip.encryptjs" type="bool">true</property>
```

This is equivalent to `--skip-js-encrypt` on every build. Useful for rapid development iteration where build speed matters more than security.

### Priority order

CLI flags take precedence over tiapp.xml properties:

```
--always-js-encrypt  >  ti.always.encryptjs  →  encryptJS = true
--skip-js-encrypt    >  ti.skip.encryptjs     →  encryptJS = false
```

If both `--always-js-encrypt` and `--skip-js-encrypt` are specified, `--skip-js-encrypt` wins (it is evaluated second).

## Platform-Specific Details

### iOS

When `encryptJS = true`:

1. JS files are encrypted by `titanium_prep.js` (Node.js, replaces the old prebuilt binary)
2. Encrypted data is XOR-masked and embedded in `_T5Routing` (renamed from `ApplicationRouting`)
3. Dictionary keys are djb2 hash integers — no filenames in the binary
4. The `_index_.json` file is not written
5. Anti-debug check uses `sysctl(KERN_PROC)` with `P_TRACED` flag (production only)
6. Module JS files use the same encryption via `_T5A` class (renamed from `ModuleAssets`)

When `encryptJS = false`:

1. JS files are copied as-is to the app bundle
2. `_index_.json` is written for module resolution
3. No encryption-related code is generated

### Android

When `encryptJS = true`:

1. JS files are encrypted with AES-128-CBC by the build system (pure Node.js `crypto`, replaces `ti.cloak`)
2. The `_T5C` class (renamed from `AssetCryptImpl`) handles decryption at runtime
3. The AES key is XOR-masked before embedding in Java — not stored as plaintext
4. Asset paths are stored as djb2 hash values — no filenames in the DEX
5. Anti-debug check uses `Debug.isDebuggerConnected()` (production only)
6. Class name is obfuscated (`_T5C` instead of `AssetCryptImpl`)
7. No native `.so` library is needed (the old `libti.cloak.so` has been replaced)

When `encryptJS = false`:

1. JS files are copied as-is to the APK assets directory
2. No `_T5C` class is generated
3. The app loads JS directly from `AssetManager`

### Anti-Debug (Production Only)

Anti-debug checks are only active in production builds:

| Platform | Deploy Type | Anti-Debug Active |
|----------|-------------|-----------------|
| iOS | `dist-appstore` | Yes |
| iOS | `dist-adhoc` | Yes |
| iOS | `dist-macappstore` | Yes |
| iOS | `simulator`, `device`, `macos` | No |
| Android | `dist-appstore` | Yes |
| Android | `device` (test) | No |

On iOS, the anti-debug check uses `sysctl()` with `KERN_PROC` and `P_TRACED` to detect debugger attachment. On Android, it uses `Debug.isDebuggerConnected()`.

When a debugger is detected, `resolveAppAsset:` (iOS) or `getAssetStream()` (Android) returns `nil`/`null`, preventing decryption of any JS files.

## Build Examples

### Production App Store build (encryption ON by default)

```bash
ti build -p ios --target dist-appstore
ti build -p android --target dist-appstore
```

JS files are encrypted, hashed, and XOR-masked. Anti-debug is active.

### Development/Simulator build (encryption OFF by default)

```bash
ti build -p ios --target simulator
ti build -p android --target device
```

JS files are unencrypted for easy debugging. Source maps work normally.

### Test encryption during development

```bash
ti build -p ios --target simulator --always-js-encrypt
ti build -p android --target device --always-js-encrypt
```

Forces encryption on in a development build so you can verify that encrypted assets load correctly.

### Debug a production issue without encryption

```bash
ti build -p ios --target dist-appstore --skip-js-encrypt
ti build -p android --target dist-appstore --skip-js-encrypt
```

Builds a production-optimized app but without JS encryption. Useful for inspecting JS files in a production-like build.

### Force encryption via tiapp.xml

In your `tiapp.xml`:

```xml
<!-- Always encrypt JS, even in development -->
<property name="ti.always.encryptjs" type="bool">true</property>

<!-- Or: never encrypt JS, even in production -->
<property name="ti.skip.encryptjs" type="bool">true</property>
```

## What Changed from Previous Versions

| Feature | Before | After |
|---------|--------|-------|
| `encryptJS` default | `true` for all deploy types | `true` for production only |
| Asset filenames | Plaintext `@"filename"` strings in ObjC dictionary | djb2 hash integers `@(hashValue)` |
| Encryption key | Last 32 bytes of data blob (iOS) / XOR with plaintext salt (Android) | XOR-masked with random 16-byte key |
| `_index_.json` | Written in all builds | Omitted when `encryptJS = true` |
| Class names | `ApplicationRouting`, `ModuleAssets`, `AssetCryptImpl` | `_T5Routing`, `_T5A`, `_T5C` |
| Anti-debug | Not implemented | `sysctl` P_TRACED check (iOS), `Debug.isDebuggerConnected()` (Android) |
| `--always-js-encrypt` | Not available | New CLI flag |
| `--skip-js-encrypt` | Available, but less needed | Available, now more useful |
| `ti.always.encryptjs` | Not available | New tiapp.xml property |
| `ti.skip.encryptjs` | Not available | New tiapp.xml property |
| Android `ti.cloak` | Closed-source native `.so` + `.jar` | Replaced with pure Java + Node.js |
| Android asset lookup | Plaintext `Collection<String>` of filenames | djb2 `long[]` hash array |