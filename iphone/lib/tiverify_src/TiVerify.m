/**
 * Titanium SDK
 * Copyright TiDev, Inc. 04/07/2022-Present. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */

#import "TiVerify.h"
#import <CommonCrypto/CommonCryptor.h>

NSData *filterDataInRange(NSData *thedata, NSRange range)
{
  if (!thedata) {
    return nil;
  }

  NSUInteger length = [thedata length];
  if (length < 32) {
    NSLog(@"[ERROR] Error retrieving data");
    return [NSData data];
  }

  // Key is 16 bytes at offset (length - 32), IV is 16 bytes at offset (length - 16)
  const void *key = [thedata bytes] + length - 32;
  const void *iv = [thedata bytes] + length - 16;

  // Data to decrypt starts at range.location
  const void *dataIn = [thedata bytes] + range.location;
  NSUInteger dataInLength = range.length;

  NSMutableData *decryptedData = [NSMutableData dataWithLength:dataInLength + kCCBlockSizeAES128];
  size_t outLength = 0;

  CCCryptorStatus result = CCCrypt(
      kCCDecrypt,
      kCCAlgorithmAES128,
      kCCOptionPKCS7Padding,
      key,
      kCCKeySizeAES128,
      iv,
      dataIn,
      dataInLength,
      [decryptedData mutableBytes],
      [decryptedData length],
      &outLength);

  if (result == kCCSuccess) {
    [decryptedData setLength:outLength];
    return decryptedData;
  }

  NSLog(@"[ERROR] Error retrieving data");
  return [NSData data];
}