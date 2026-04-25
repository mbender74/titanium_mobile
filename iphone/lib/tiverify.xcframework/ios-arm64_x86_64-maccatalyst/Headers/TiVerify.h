/**
 * Titanium SDK
 * Copyright TiDev, Inc. 04/07/2022-Present. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */

#import <Foundation/Foundation.h>

/**
 * Decrypts a range of data from an encrypted blob.
 *
 * The data blob format is:
 *   [encrypted_payload | 16-byte AES-128 key | 16-byte IV]
 *
 * The last 32 bytes of thedata contain the key and IV used for
 * AES-128-CBC decryption with PKCS7 padding.
 *
 * @param thedata  The encrypted data blob (key+IV are the last 32 bytes)
 * @param range    The range within thedata to decrypt (excluding the trailing key+IV)
 * @return         Decrypted data, or empty NSData on failure
 */
NSData *filterDataInRange(NSData *thedata, NSRange range);