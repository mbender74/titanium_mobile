#!/usr/bin/env node

/**
 * titanium_prep.js — Node.js replacement for the prebuilt titanium_prep binary.
 *
 * Encrypts JavaScript files with AES-128-CBC and generates Objective-C source
 * code that embeds the encrypted data as static byte arrays. The output is
 * consumed by the EJS templates for ApplicationRouting.m and ModuleAssets.m.
 *
 * The data blob is XOR-masked with a random 16-byte key to defeat entropy-based
 * detection of the encrypted payload. The mask key is embedded as a separate
 * static array and the templates apply XOR-unmasking before decryption.
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

	// Generate random AES-128 key and IV
	const key = crypto.randomBytes(16);
	const iv = crypto.randomBytes(16);

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

	// Build complete data buffer: encrypted payloads + key + IV
	const dataBuffer = Buffer.concat([...encryptedPieces, key, iv]);

	// Generate XOR mask key and apply it to the data blob
	const xmask = crypto.randomBytes(16);
	const maskedData = Buffer.alloc(dataBuffer.length);
	for (let i = 0; i < dataBuffer.length; i++) {
		maskedData[i] = dataBuffer[i] ^ xmask[i % 16];
	}

	// Generate Objective-C output
	let output = 'static UInt8 xmask[] = {\n';
	const maskHex = [];
	for (let i = 0; i < xmask.length; i++) {
		maskHex.push(`0x${xmask[i].toString(16).padStart(2, '0')}`);
	}
	output += `\t\t${maskHex.join(',')},\n`;
	output += '\t};\n';

	output += 'static UInt8 data[] = {\n';
	const hexBytes = [];
	for (let i = 0; i < maskedData.length; i++) {
		hexBytes.push(`0x${maskedData[i].toString(16).padStart(2, '0')}`);
	}
	output += `\t\t${hexBytes.join(',')},\n`;
	output += '\t};\n';

	output += 'static NSRange ranges[] = {\n';
	const rangeEntries = ranges.map(r => `{${r.location},${r.length}}`);
	output += `\t\t${rangeEntries.join(',')},\n`;
	output += '\t};\n';

	output += 'static NSDictionary *map = nil;\n';
	output += '\tif (map == nil) {\n';
	output += '\t\tmap = [[NSDictionary alloc] initWithObjectsAndKeys:\n';
	const mapEntries = filenames.map((name, idx) => `[NSNumber numberWithInteger:${idx}], @(${djb2(name)})`);
	output += `\t\t${mapEntries.join(',\n\t\t')},\n`;
	output += '\t\tnil];\n';
	output += '\t}\n';

	process.stdout.write(output);
});