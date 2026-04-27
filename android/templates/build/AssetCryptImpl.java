package <%- appid %>;

import android.os.Debug;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.appcelerator.kroll.common.Log;
import org.appcelerator.kroll.util.KrollAssetHelper;

@SuppressWarnings("unchecked")
public class AssetCryptImpl implements KrollAssetHelper.AssetCrypt
{
	private static final String TAG = "AssetCryptImpl";

	private static final String BIN_EXT = ".bin";

<% if (antiDebug) { %>
	private static boolean isDebuggerAttached()
	{
		return Debug.isDebuggerConnected();
	}
<% } %>

	// XOR-masked key: real key = maskedKey[i] ^ xmask[i % xmask.length]
	private static byte[] xmask = {
		<%- xmask %>
	};
	private static byte[] maskedKey = {
		<%- maskedKey %>
	};

	// IV (salt) for AES-128-CBC
	private static byte[] salt = {
		<%- salt %>
	};

	// djb2 hashes of asset paths (no plaintext filenames in the binary)
	private static final long[] ASSET_HASHES = {
		<%- assetHashes %>
	};

	private static byte[] getKey()
	{
		byte[] key = new byte[16];
		for (int i = 0; i < 16; i++) {
			key[i] = (byte)(maskedKey[i] ^ xmask[i % xmask.length]);
		}
		return key;
	}

	private static long djb2(String str)
	{
		long hash = 5381;
		for (int i = 0; i < str.length(); i++) {
			hash = ((hash << 5) + hash) + str.charAt(i);
		}
		return hash & 0xFFFFFFFFL;
	}

	private static boolean assetExists(String path)
	{
		long h = djb2(path);
		for (long ah : ASSET_HASHES) {
			if (ah == h) return true;
		}
		return false;
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
		if (!assetExists(path)) {
			return null;
		}
		if (!path.endsWith(BIN_EXT)) {
			path = path + BIN_EXT;
		}
		try {
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(getKey(), "AES"), new IvParameterSpec(salt));
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