/**
 * Titanium SDK
 * Copyright TiDev, Inc. 04/07/2022-Present
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */
package org.appcelerator.titanium.util;

import android.graphics.Bitmap;
import android.util.LruCache;
import org.appcelerator.kroll.KrollRuntime;
import org.appcelerator.titanium.view.TiDrawableReference;
import org.appcelerator.titanium.util.TiExifOrientation;

/**
 * In-memory bitmap cache using LruCache instead of SoftReference.
 *
 * SoftReference-based caches are too aggressive under GC pressure, causing
 * frequent cache misses and redundant re-decoding of images. This is especially
 * problematic for scrolling lists (TableView/ListView) where many images are
 * needed quickly. LruCache provides predictable eviction based on memory size
 * rather than GC whims.
 */
public final class TiImageCache
{
	private static final int DEFAULT_CACHE_SIZE = 8 * 1024 * 1024; // 8MB default
	private static final LruCache<TiDrawableReference.Key, Bitmap> bitmapCache;
	private static final LruCache<TiDrawableReference.Key, TiExifOrientation> orientationCache;

	static
	{
		// Use 1/8 of available memory for the bitmap cache, with a minimum of DEFAULT_CACHE_SIZE
		final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
		final int cacheSize = Math.max(DEFAULT_CACHE_SIZE / 1024, maxMemory / 8);

		bitmapCache = new LruCache<TiDrawableReference.Key, Bitmap>(cacheSize)
		{
			@Override
			protected int sizeOf(TiDrawableReference.Key key, Bitmap bitmap)
			{
				// Size in kilobytes
				if (bitmap == null || bitmap.isRecycled()) {
					return 0;
				}
				return bitmap.getByteCount() / 1024;
			}

			@Override
			protected void entryRemoved(boolean evicted, TiDrawableReference.Key key, Bitmap oldValue,
				Bitmap newValue)
			{
				// Don't recycle the bitmap here - it might still be in use by a View
				// Let GC handle it naturally
			}
		};

		orientationCache = new LruCache<TiDrawableReference.Key, TiExifOrientation>(cacheSize);

		KrollRuntime.addOnDisposingListener((KrollRuntime runtime) -> {
			clear();
		});
	}

	private TiImageCache()
	{
	}

	public static synchronized void add(TiImageInfo imageInfo)
	{
		if ((imageInfo != null) && (imageInfo.getKey() != null) && (imageInfo.getBitmap() != null)) {
			Bitmap bitmap = imageInfo.getBitmap();
			if ((bitmap != null) && !bitmap.isRecycled()) {
				bitmapCache.put(imageInfo.getKey(), bitmap);
				orientationCache.put(imageInfo.getKey(), imageInfo.getOrientation());
			}
		}
	}

	public static synchronized Bitmap getBitmap(TiDrawableReference.Key key)
	{
		Bitmap bitmap = bitmapCache.get(key);
		if (bitmap != null && !bitmap.isRecycled()) {
			return bitmap;
		}
		// Remove stale entries
		if (bitmap != null && bitmap.isRecycled()) {
			bitmapCache.remove(key);
			orientationCache.remove(key);
		}
		return null;
	}

	public static synchronized TiExifOrientation getOrientation(TiDrawableReference.Key key)
	{
		return orientationCache.get(key);
	}

	public static synchronized void clear()
	{
		bitmapCache.evictAll();
		orientationCache.evictAll();
	}

	static synchronized void remove(TiDrawableReference.Key key)
	{
		bitmapCache.remove(key);
		orientationCache.remove(key);
	}
}
