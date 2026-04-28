/**
 * Titanium SDK
 * Copyright TiDev, Inc. 04/07/2022-Present. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 *
 * WARNING: This is generated code. Do not modify. Your changes *will* be lost.
 */

#import "ApplicationRouting.h"
#import <CommonCrypto/CommonDigest.h>
#import <Foundation/Foundation.h>
#ifdef TI_ANTI_DEBUG
#import <sys/sysctl.h>
#import <sys/types.h>
#endif

extern NSData *filterDataInRange(NSData *thedata, NSRange range, const void *key, const void *iv);

static unsigned int djb2_hash(const char *str)
{
  unsigned int hash = 5381;
  int c;
  while ((c = *str++))
    hash = ((hash << 5) + hash) + c;
  return hash;
}

#ifdef TI_ANTI_DEBUG
static BOOL _isDebuggerAttached(void)
{
  int name[4] = { CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid() };
  struct kinfo_proc info;
  size_t size = sizeof(info);
  memset(&info, 0, size);
  if (sysctl(name, 4, &info, &size, NULL, 0) == -1) {
    return NO;
  }
  return (info.kp_proc.p_flag & P_TRACED) != 0;
}
#endif

static void deriveKeyAndIV(UInt8 *keyOut, UInt8 *ivOut)
{
  // key = SHA256(_s0 XOR _s1)[0:16]
  UInt8 tmp[32];
  for (int i = 0; i < 32; i++) {
    tmp[i] = _s0[i] ^ _s1[i];
  }
  unsigned char hash[CC_SHA256_DIGEST_LENGTH];
  CC_SHA256(tmp, 32, hash);
  memcpy(keyOut, hash, 16);

  // iv = SHA256(_s2 XOR _s3)[0:16]
  for (int i = 0; i < 32; i++) {
    tmp[i] = _s2[i] ^ _s3[i];
  }
  CC_SHA256(tmp, 32, hash);
  memcpy(ivOut, hash, 16);
}

@implementation _T5Routing

+ (NSData *)resolveAppAsset:(NSString *)path;
{
#ifdef TI_ANTI_DEBUG
  if (_isDebuggerAttached()) {
    return nil;
  }
#endif
  // clang-format off
	<%- bytes %>
  // clang-format on

  NSNumber *index
      = [map objectForKey:@(djb2_hash([path UTF8String]))];
  if (index == nil) {
    return nil;
  }

  // XOR-unmask the data blob
  NSUInteger dataLen = sizeof(data);
  NSMutableData *unmasked = [NSMutableData dataWithLength:dataLen];
  UInt8 *outBytes = [unmasked mutableBytes];
  for (NSUInteger i = 0; i < dataLen; i++) {
    outBytes[i] = data[i] ^ xmask[i % sizeof(xmask)];
  }

  // Unmask the ranges
  NSUInteger rangeBytesLen = range_count * sizeof(NSRange);
  NSRange *unmaskedRanges = (NSRange *)malloc(rangeBytesLen);
  for (NSUInteger i = 0; i < rangeBytesLen; i++) {
    ((UInt8 *)unmaskedRanges)[i] = masked_ranges[i] ^ rmask[i % sizeof(rmask)];
  }

  // Derive key and IV from seed arrays
  UInt8 derivedKey[16];
  UInt8 derivedIV[16];
  deriveKeyAndIV(derivedKey, derivedIV);

  NSRange range = unmaskedRanges[index.integerValue];
  NSData *result = filterDataInRange(unmasked, range, derivedKey, derivedIV);

  // Securely zero derived key/IV
  memset(derivedKey, 0, sizeof(derivedKey));
  memset(derivedIV, 0, sizeof(derivedIV));
  free(unmaskedRanges);

  return result;
}

@end