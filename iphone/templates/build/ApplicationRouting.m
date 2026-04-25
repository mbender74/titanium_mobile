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
  <% -bytes %>

  NSNumber *index
      = [map objectForKey:path];
  if (index == nil) {
    return nil;
  }
  return filterDataInRange([NSData dataWithBytesNoCopy:data length:sizeof(data) freeWhenDone:NO], ranges[index.integerValue]);
}

@end