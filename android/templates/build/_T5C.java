package <%- appid %>;

import android.os.Debug;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.appcelerator.kroll.common.Log;
import org.appcelerator.kroll.util.KrollAssetHelper;

@SuppressWarnings("unchecked")
public class _T5C implements KrollAssetHelper.AssetCrypt
{
	private static final String TAG = "_T5C";

	private static final String BIN_EXT = ".bin";

<% if (antiDebug) { %>
	private static boolean isDebuggerAttached()
	{
		return Debug.isDebuggerConnected();
	}
<% } %>

	// Seed arrays for key derivation: key = SHA256(_s0 XOR _s1)[0:16], iv = SHA256(_s2 XOR _s3)[0:16]
	private static byte[] _s0 = {
		<%- s0 %>
	};
	private static byte[] _s1 = {
		<%- s1 %>
	};
	private static byte[] _s2 = {
		<%- s2 %>
	};
	private static byte[] _s3 = {
		<%- s3 %>
	};

	// Cached derived key and IV
	private static byte[] cachedKey = null;
	private static byte[] cachedIV = null;

	// djb2 hashes of asset paths (no plaintext filenames in the binary)
	private static final long[] ASSET_HASHES = {
		<%- assetHashes %>
	};

	private static void deriveKeyAndIV()
	{
		if (cachedKey != null) return;

		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

			// key = SHA256(_s0 XOR _s1)[0:16]
			byte[] xor01 = new byte[32];
			for (int i = 0; i < 32; i++) {
				xor01[i] = (byte)(_s0[i] ^ _s1[i]);
			}
			byte[] hash01 = sha256.digest(xor01);
			cachedKey = Arrays.copyOf(hash01, 16);

			// iv = SHA256(_s2 XOR _s3)[0:16]
			byte[] xor23 = new byte[32];
			for (int i = 0; i < 32; i++) {
				xor23[i] = (byte)(_s2[i] ^ _s3[i]);
			}
			byte[] hash23 = sha256.digest(xor23);
			cachedIV = Arrays.copyOf(hash23, 16);
		} catch (Exception e) {
			Log.e(TAG, "Key derivation failed: " + e.toString());
		}
	}

	private static byte[] getKey()
	{
		deriveKeyAndIV();
		return cachedKey;
	}

	private static byte[] getIV()
	{
		deriveKeyAndIV();
		return cachedIV;
	}

	private static long djb2(String str)
	{
		long hash = 5381;
		for (int i = 0; i < str.length(); i++) {
			hash = ((hash << 5) + hash) + str.charAt(i);
		}
		return hash & 0xFFFFFFFFL;
	}

	private static boolean hashExists(String path)
	{
		long h = djb2(path);
		for (long ah : ASSET_HASHES) {
			if (ah == h) return true;
		}
		return false;
	}

	@Override
	public boolean assetExists(String path)
	{
		return hashExists(path);
	}

	@Override
	public InputStream openAsset(String path)
	{
		return getAssetStream(path);
	}

	@Override
	public String readAsset(String path)
	{
		byte[] bytes = getAssetBytes(path);
		if (bytes != null) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		return null;
	}

	@Override
	public java.util.Collection<String> getAssetPaths()
	{
		// Return empty collection — asset paths are not exposed as plaintext
		return java.util.Collections.emptyList();
	}

	private static InputStream getAssetStream(String path)
	{
<% if (antiDebug) { %>
		if (isDebuggerAttached()) {
			return null;
		}
<% } %>
		if (!hashExists(path)) {
			return null;
		}
		if (!path.endsWith(BIN_EXT)) {
			path = path + BIN_EXT;
		}
		try {
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(getKey(), "AES"), new IvParameterSpec(getIV()));
			return new CipherInputStream(KrollAssetHelper.getAssetManager().open(path), cipher);
		} catch (Exception e) {
			Log.e(TAG, "Could not decrypt '" + path + "'");
			Log.e(TAG, e.toString());
		}
		return null;
	}

	private static byte[] getAssetBytes(String path)
	{
		try {
			InputStream in = getAssetStream(path);
			if (in != null) {
				return KrollAssetHelper.readInputStream(in).toByteArray();
			}
		} catch (Exception e) {
			Log.e(TAG, "Could not decrypt '" + path + "'");
			Log.e(TAG, e.toString());
		}
		return null;
	}
}