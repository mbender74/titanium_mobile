import JavaScriptObfuscator from 'javascript-obfuscator';
import fs from 'fs-extra';
import path from 'node:path';
import pLimit from 'p-limit';

const MAX_SIMULTANEOUS_FILES = 64;
const limit = pLimit(MAX_SIMULTANEOUS_FILES);

const LEVEL_PRESETS = {
	low: 'low-obfuscation',
	medium: 'medium-obfuscation',
	high: 'high-obfuscation'
};

const DEFAULT_OPTIONS = {
	target: 'browser',
	ignoreImports: true,
	selfDefending: false,
	debugProtection: false,
	disableConsoleOutput: false,
	renameGlobals: false,
	renameProperties: false,
	reservedNames: [
		'^Ti$', '^Titanium$', '^require$', '^module$', '^exports$', '^global$', '^kroll$', '^globalThis$'
	],
	reservedStrings: [
		'^\\./', '^\\.\\./', '^/', 'app$', '^Ti/', '^Titanium/'
	],
	sourceMap: false,
	compact: true
};

/**
 * Obfuscates JavaScript files in place using javascript-obfuscator.
 *
 * @param {Object} options
 * @param {Object} options.logger Logger instance
 * @param {String[]} options.jsFiles Array of relative file paths to obfuscate
 * @param {String} options.baseDir Base directory where the JS files reside
 * @param {String} options.sdkCommonFolder Path to SDK common JS files (skipped from obfuscation)
 * @param {String} options.level Obfuscation level: 'low', 'medium', or 'high'
 */
export async function obfuscateJsFiles(options) {
	const { logger, jsFiles, baseDir, sdkCommonFolder, level = 'low' } = options;

	const preset = LEVEL_PRESETS[level] || LEVEL_PRESETS.low;
	logger.info(`Obfuscating JavaScript files (level: ${level})...`);

	const obfuscatorOptions = {
		...DEFAULT_OPTIONS,
		optionsPreset: preset
	};

	const tasks = jsFiles.map(relPath => limit(async () => {
		const filePath = path.join(baseDir, relPath);

		// Skip non-JS files (JSON, etc.) — the obfuscator can't parse them
		const ext = path.extname(relPath).toLowerCase();
		if (ext !== '.js' && ext !== '.cjs' && ext !== '.mjs') {
			logger.trace(`Skipping non-JS file: ${relPath.cyan}`);
			return;
		}

		// Skip SDK common files
		if (sdkCommonFolder && filePath.startsWith(sdkCommonFolder)) {
			logger.trace(`Skipping SDK file: ${relPath.cyan}`);
			return;
		}

		if (!fs.existsSync(filePath)) {
			logger.trace(`Skipping missing file: ${relPath.cyan}`);
			return;
		}

		const source = await fs.readFile(filePath, 'utf8');
		const result = JavaScriptObfuscator.obfuscate(source, obfuscatorOptions);
		await fs.writeFile(filePath, result.getObfuscatedCode());
		logger.debug(`Obfuscated: ${relPath.cyan}`);
	}));

	await Promise.all(tasks);
	logger.info('JavaScript obfuscation complete');
}