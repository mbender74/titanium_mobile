#!/usr/bin/env node

/**
 * titanium_prep.js — Node.js replacement for the prebuilt titanium_prep binary.
 *
 * Encrypts JavaScript files with AES-128-CBC and generates Objective-C source
 * code that embeds the encrypted data as static byte arrays. The output is
 * consumed by the EJS templates for ApplicationRouting.m and ModuleAssets.m.
 *
 * Key derivation: The AES key and IV are derived at runtime from four seed
 * arrays using SHA-256. This defeats static extraction — the key is no longer
 * stored contiguously or at a fixed offset.
 *
 *   key = SHA256(_s0 XOR _s1)[0:16]
 *   iv  = SHA256(_s2 XOR _s3)[0:16]
 *
 * The data blob is XOR-masked with a random 16-byte key to defeat entropy-based
 * detection. Range entries are XOR-masked with a separate 16-byte key to prevent
 * plaintext file boundary information.
 *
 * Usage: titanium_prep.js <app_id> <assets_dir> <guid>
 *
 * Filenames are read from stdin (newline-separated), relative to assets_dir.
 * The app_id and guid arguments are accepted for compatibility but not used.
 */

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

// djb2 hash — matches the C implementation in ApplicationRouting.m / ModuleAssets.m.ejs
function djb2(str) {
  let hash = 5381;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) + hash) + str.charCodeAt(i);
  }
  return hash >>> 0; // unsigned 32-bit
}

const [, , appId, assetsDir, guid] = process.argv;

if (!appId || !assetsDir || !guid) {
  console.error('Usage: titanium_prep.js <app_id> <assets_dir> <guid>');
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

  // Generate four random 32-byte seed arrays for key derivation
  const seed0 = crypto.randomBytes(32);
  const seed1 = crypto.randomBytes(32);
  const seed2 = crypto.randomBytes(32);
  const seed3 = crypto.randomBytes(32);

  // Derive AES key and IV from seeds using SHA-256
  const xor01 = Buffer.alloc(32);
  const xor23 = Buffer.alloc(32);
  for (let i = 0; i < 32; i++) {
    xor01[i] = seed0[i] ^ seed1[i];
    xor23[i] = seed2[i] ^ seed3[i];
  }
  const key = crypto.createHash('sha256').update(xor01).digest().subarray(0, 16);
  const iv = crypto.createHash('sha256').update(xor23).digest().subarray(0, 16);

  // Encrypt each file
  const encryptedPieces = [];
  const ranges = [];
  let offset = 0;

  for (const filename of filenames) {
    const filePath = path.join(assetsDir, filename);
    let plaintext;
    try {
      plaintext = fs.readFileSync(filePath);
    } catch (e) {
      console.error(`Unable to read file: ${filePath}`);
      process.exit(1);
    }

    const cipher = crypto.createCipheriv('aes-128-cbc', key, iv);
    const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);

    encryptedPieces.push(encrypted);
    ranges.push({ location: offset, length: encrypted.length });
    offset += encrypted.length;
  }

  // Build data buffer: encrypted payloads only (no key/IV embedded)
  const dataBuffer = Buffer.concat(encryptedPieces);

  // Generate XOR mask key for the data blob
  const xmask = crypto.randomBytes(16);
  const maskedData = Buffer.alloc(dataBuffer.length);
  for (let i = 0; i < dataBuffer.length; i++) {
    maskedData[i] = dataBuffer[i] ^ xmask[i % 16];
  }

  // Generate XOR mask key for the ranges array (4 bytes per NSRange entry)
  const rmask = crypto.randomBytes(16);
  const rangeBuffer = Buffer.alloc(ranges.length * 8); // each NSRange is {location: UInt32, length: UInt32}
  for (let i = 0; i < ranges.length; i++) {
    rangeBuffer.writeUInt32LE(ranges[i].location, i * 8);
    rangeBuffer.writeUInt32LE(ranges[i].length, i * 8 + 4);
  }
  const maskedRanges = Buffer.alloc(rangeBuffer.length);
  for (let i = 0; i < rangeBuffer.length; i++) {
    maskedRanges[i] = rangeBuffer[i] ^ rmask[i % 16];
  }

  // Generate Objective-C output
  let output = '';

  // Seed arrays for key derivation
  const formatBytes = (buf, name) => {
    output += `static UInt8 ${name}[] = {\n`;
    const hex = [];
    for (let i = 0; i < buf.length; i++) {
      hex.push(`0x${buf[i].toString(16).padStart(2, '0')}`);
    }
    output += `\t\t${hex.join(',')},\n`;
    output += '\t};\n';
  };

  formatBytes(seed0, '_s0');
  formatBytes(seed1, '_s1');
  formatBytes(seed2, '_s2');
  formatBytes(seed3, '_s3');
  formatBytes(xmask, 'xmask');
  formatBytes(rmask, 'rmask');

  output += 'static UInt8 data[] = {\n';
  const hexBytes = [];
  for (let i = 0; i < maskedData.length; i++) {
    hexBytes.push(`0x${maskedData[i].toString(16).padStart(2, '0')}`);
  }
  output += `\t\t${hexBytes.join(',')},\n`;
  output += '\t};\n';

  output += 'static UInt8 masked_ranges[] = {\n';
  const rangeHexBytes = [];
  for (let i = 0; i < maskedRanges.length; i++) {
    rangeHexBytes.push(`0x${maskedRanges[i].toString(16).padStart(2, '0')}`);
  }
  output += `\t\t${rangeHexBytes.join(',')},\n`;
  output += '\t};\n';

  output += 'static NSUInteger range_count = ' + ranges.length + ';\n';

  output += 'static NSDictionary *map = nil;\n';
  output += '\tif (map == nil) {\n';
  output += '\t\tmap = [[NSDictionary alloc] initWithObjectsAndKeys:\n';
  const mapEntries = filenames.map((name, idx) => `[NSNumber numberWithInteger:${idx}], @(${djb2(name)})`);
  output += `\t\t${mapEntries.join(',\n\t\t')},\n`;
  output += '\t\tnil];\n';
  output += '\t}\n';

  process.stdout.write(output);
});