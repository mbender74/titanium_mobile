/**
 * Titanium SDK
 * Copyright TiDev, Inc. 04/07/2022-Present. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 *
 * WARNING: This is generated code. Do not modify. Your changes *will* be lost.
 */

#import "ApplicationRouting.h"
#import <Foundation/Foundation.h>
#ifdef TI_ANTI_DEBUG
#import <sys/sysctl.h>
#import <sys/types.h>
#endif

extern NSData *filterDataInRange(NSData *thedata, NSRange range);

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
  // XOR-unmask the data blob before decryption
  NSUInteger dataLen = sizeof(data);
  NSMutableData *unmasked = [NSMutableData dataWithLength:dataLen];
  UInt8 *outBytes = [unmasked mutableBytes];
  for (NSUInteger i = 0; i < dataLen; i++) {
    outBytes[i] = data[i] ^ xmask[i % sizeof(xmask)];
  }
  return filterDataInRange(unmasked, ranges[index.integerValue]);
}

@end