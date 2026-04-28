# JavaScript Obfuscation Integration Plan

## Overview

Integrate [javascript-obfuscator](https://github.com/javascript-obfuscator/javascript-obfuscator) into the Titanium SDK build pipeline to provide an additional layer of source code protection. When combined with the existing AES encryption, an attacker who extracts the encryption key still faces heavily obfuscated code that is difficult to reverse back to readable JavaScript.

## Pipeline Position

Obfuscation runs **between minification (`ProcessJsTask`) and encryption (`encryptJSFiles()`)**:

```
Transpile (Babel) → Minify (babel-preset-minify) → Obfuscate (javascript-obfuscator) → Encrypt (AES-128-CBC)
```

This ensures obfuscation operates on the final transpiled+minified code before it gets encrypted.

## Critical Safeguards

These options are **mandatory** — without them the app will break at runtime:

| Option | Value | Why |
|--------|-------|-----|
| `ignoreImports` | `true` | Preserves `require()` string paths used for CommonJS module resolution |
| `renameGlobals` | `false` | Preserves `Ti`, `Titanium`, `require` globals |
| `renameProperties` | `false` | Preserves Titanium API property chains (`Ti.UI.createView` etc.) |
| `selfDefending` | `false` | Conflicts with encryption — code is read as bytes and re-encrypted |
| `debugProtection` | `false` | Would freeze mobile apps (4s interval DevTools check) |
| `disableConsoleOutput` | `false` | Titanium apps use `Ti.API.info`/`warn`/`error` for logging |
| SDK kernel/common files | **Skip** | Same exclusion as minification — these are framework bootstrap files |

**Reserved names** (prevent renaming critical identifiers):

```js
reservedNames: [
    '^Ti$', '^Titanium$', '^require$', '^module$', '^exports$',
    '^global$', '^kroll$', '^globalThis$'
]
```

**Reserved strings** (prevent encoding of critical string literals):

```js
reservedStrings: [
    '^\\./', '^\\.\\./', '^/', 'app$',
    '^Ti/', '^Titanium/'
]
```

## CLI Integration

### CLI Flag

```
--js-obfuscate <level>
```

Values: `low`, `medium`, `high` (or `true` which defaults to `low`)

```bash
ti build -p ios --target dist-appstore --js-obfuscate low
ti build -p android --target dist-appstore --js-obfuscate medium
```

### tiapp.xml Property

```xml
<property name="ti.js.obfuscate" type="string">low</property>
```

Values: `false` (default), `true` (maps to `low`), `low`, `medium`, `high`

### Priority

CLI flag overrides tiapp.xml property (same pattern as `--skip-js-encrypt` / `ti.skip.encryptjs`).

### Default Behavior

Obfuscation is **OFF by default** — even for production builds. Reasons:
- Significant build time increase
- Runtime performance degradation
- Risk of silent breakage if safeguards are misconfigured
- Existing encryption already provides substantial protection

## Obfuscation Levels

| Level | Runtime Impact | Size Increase | Build Time | Transforms |
|-------|---------------|---------------|------------|------------|
| Low | ~15% slower | ~10-20% larger | +10-30s | Identifier renaming (hexadecimal), string array extraction, string array rotation, string array shuffle |
| Medium | ~30-50% slower | ~50-100% larger | +30-90s | Low + control flow flattening, dead code injection, numbers to expressions, split strings (chunk 10), string array encoding (base64), transform object keys |
| High | ~50-80% slower | ~100-200% larger | +1-5min | Medium + max thresholds, string array encoding (RC4), split strings (chunk 5), debug protection intervals |

**Recommendation**: Default to `low` when obfuscation is enabled. `medium` and `high` carry significant runtime and build time costs.

## Obfuscation Options (Full Configuration)

```js
{
    optionsPreset: level,           // 'low-obfuscation', 'medium-obfuscation', 'high-obfuscation'
    target: 'browser',
    ignoreImports: true,            // PRESERVE: require() paths
    selfDefending: false,           // DISABLE: conflicts with encryption
    debugProtection: false,         // DISABLE: crashes mobile apps
    disableConsoleOutput: false,    // DISABLE: Titanium apps use Ti.API logging
    renameGlobals: false,           // PRESERVE: Ti, Titanium, require
    renameProperties: false,        // PRESERVE: API property names
    reservedNames: [
        '^Ti$', '^Titanium$', '^require$', '^module$', '^exports$',
        '^global$', '^kroll$', '^globalThis$'
    ],
    reservedStrings: [
        '^\\./', '^\\.\\./', '^/', 'app$',
        '^Ti/', '^Titanium/'
    ],
    sourceMap: false,               // production builds don't use source maps
    compact: true                   // already minified
}
```

## Files to Create

| File | Purpose |
|------|---------|
| `cli/lib/tasks/obfuscate-js-task.js` | `IncrementalFileTask` that obfuscates JS files (follows `ProcessJsTask` pattern) |

The task should:
- Read minified JS files from their intermediate destination
- Run `JavaScriptObfuscator.obfuscate(source, options)` per file
- Write obfuscated output back, overwriting the minified version
- Cache results for incremental builds (content hash comparison)
- Skip files from `sdkCommonFolder` (kernel/bootstrap files)
- Use `pLimit()` for parallel processing

## Files to Modify

| File | Change |
|------|--------|
| `cli/commands/build.js` | Add `--js-obfuscate` CLI option (around line 77, alongside existing flags) |
| `iphone/cli/commands/_build.js` | Read flag/property, invoke `ObfuscateJsTask` after `ProcessJsTask` and before `encryptJSFiles()` |
| `android/cli/commands/_build.js` | Same: read flag/property, invoke task between processJSFiles and encryptJSFiles |
| `package.json` | Add `javascript-obfuscator` dependency |

## Security Impact

Obfuscation + encryption is **stronger than either alone**:

| Attack Scenario | Encryption Only | Obfuscation Only | Encryption + Obfuscation |
|---|---|---|---|
| Key extracted from binary | Recovers **100% original source** | N/A | Recovers obfuscated code — must also de-obfuscate |
| Bytecode decompilation | N/A | Recovers ~85-95% (no variable names) | N/A |
| Frida runtime hooking | Intercepts decrypted source | Intercepts obfuscated source | Intercepted source is still obfuscated |
| Automated scanner | Finds key patterns in binary | Finds obfuscation patterns | Must extract key AND de-obfuscate |

The combination raises the bar significantly: even if an attacker extracts the AES key and decrypts all JS files, they face identifier-renamed, control-flow-flattened, string-encoded code rather than the original readable source.

## Risks and Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| `require()` paths corrupted | Critical | `ignoreImports: true` + `reservedStrings` patterns |
| Titanium API property names renamed | Critical | `renameProperties: false`, `renameGlobals: false`, `reservedNames` |
| Runtime performance degradation | Medium | Default to `low-obfuscation`; document impact per level |
| Build time increase | Medium | Incremental caching in `ObfuscateJsTask`; only run for production |
| `selfDefending` breaks encryption | Medium | Explicitly disable `selfDefending` |
| `debugProtection` freezes app | High | Explicitly disable `debugProtection` |
| Kernel/bootstrap files corrupted | High | Skip obfuscation for `sdkCommonFolder` files |
| V8/JSC JIT compiler issues | Low | `low` level avoids heavy transforms; test on both platforms |

## Build Examples

### Production build with obfuscation (low)

```bash
ti build -p ios --target dist-appstore --js-obfuscate low
ti build -p android --target dist-appstore --js-obfuscate low
```

### Production build with medium obfuscation

```bash
ti build -p ios --target dist-appstore --js-obfuscate medium
```

### Combine with existing flags

```bash
ti build -p ios --target dist-appstore --js-obfuscate low --skip-js-minify
ti build -p android --target dist-appstore --js-obfuscate medium
```

### Force obfuscation via tiapp.xml

```xml
<property name="ti.js.obfuscate" type="string">low</property>
```