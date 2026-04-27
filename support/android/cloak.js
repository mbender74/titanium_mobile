#!/usr/bin/env node

/**
 * cloak.js — Node.js replacement for the prebuilt ti.cloak module.
 *
 * Encrypts JavaScript files with AES-128-CBC and generates Java source
 * code for AssetCryptImpl.java. The output replaces the ti.cloak
 * native library dependency with pure Java decryption.
 *
 * The AES key is XOR-masked with a random 16-byte key to defeat
 * static extraction from decompiled Java. Asset paths are replaced
 * with djb2 hash values so filenames never appear in the binary.
 *
 * Usage: cloak.js <app_id> <assets_dir> <guid> --salt-hex <hex>
 *
 * Filenames are read from stdin (newline-separated), relative to assets_dir.
 * The app_id and guid arguments are accepted for compatibility but not used.
 * The --salt-hex option provides a pre-generated salt (for testing).
 */

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

// djb2 hash — matches the Java implementation in AssetCryptImpl.java
function djb2(str) {
  let hash = 5381;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) + hash) + str.charCodeAt(i);
  }
  return hash >>> 0; // unsigned 32-bit
}

function formatBytes(buf) {
  return Array.from(buf).map(b => `(byte)0x${b.toString(16).padStart(2, '0')}`).join(', ');
}

const args = process.argv.slice(2);
let saltHex = null;

// Parse --salt-hex flag
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--salt-hex' && i + 1 < args.length) {
    saltHex = args[i + 1];
    args.splice(i, 2);
    break;
  }
}

const [, , appId, assetsDir, guid] = args;

if (!appId || !assetsDir || !guid) {
  console.error('Usage: cloak.js <app_id> <assets_dir> <guid> [--salt-hex <hex>]');
  process.exit(1);
}

// Read filenames from stdin
let stdinData = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => stdinData += chunk);
process.stdin.on('end', () => {
  const filenames = stdinData.split('\n').filter(Boolean);

  if (filenames.length === 0) {
    console.error('No filenames provided on stdin');
    process.exit(1);
  }

  // Generate random AES-128 key, IV (salt), and XOR mask
  const key = crypto.randomBytes(16);
  const salt = saltHex ? Buffer.from(saltHex, 'hex') : crypto.randomBytes(16);
  const xmask = crypto.randomBytes(16);

  // XOR-mask the key for embedding in Java
  const maskedKey = Buffer.alloc(16);
  for (let i = 0; i < 16; i++) {
    maskedKey[i] = key[i] ^ xmask[i % xmask.length];
  }

  // Encrypt each file
  for (const filename of filenames) {
    const filePath = path.join(assetsDir, filename);
    const destPath = path.join(assetsDir, '..', 'app', 'src', 'main', 'assets', 'Resources', filename + '.bin');
    let plaintext;
    try {
      plaintext = fs.readFileSync(filePath);
    } catch (e) {
      console.error(`Unable to read file: ${filePath}`);
      process.exit(1);
    }

    const cipher = crypto.createCipheriv('aes-128-cbc', key, salt);
    const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);

    // Ensure destination directory exists
    fs.mkdirSync(path.dirname(destPath), { recursive: true });
    fs.writeFileSync(destPath, encrypted);
  }

  // Compute djb2 hashes for asset paths
  const assetHashes = filenames.map(f => djb2('Resources/' + f));

  // Output template data as JSON for the build script to consume
  const templateData = {
    appid: appId,
    xmask: formatBytes(xmask),
    maskedKey: formatBytes(maskedKey),
    salt: formatBytes(salt),
    assetHashes: assetHashes.map(h => h + 'L').join(', '),
    assets: filenames.map(f => f.replace(/\\/g, '/'))
  };

  process.stdout.write(JSON.stringify(templateData));
});