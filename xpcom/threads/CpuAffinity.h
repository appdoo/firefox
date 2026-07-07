/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* vim: set ts=8 sts=2 et sw=2 tw=80: */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#ifndef mozilla_CpuAffinity_h
#define mozilla_CpuAffinity_h

#include "nsStringFwd.h"

namespace mozilla {

// Pin the *calling* process's threads to the CPU core set named by aCoreSpec.
//
// aCoreSpec is a core list/range string, e.g. "4-7" or "4,5,6,7". Empty or
// malformed input is a clean no-op. The mask is applied to:
//   (a) the calling thread, and
//   (b) every existing tid under /proc/self/task.
//
// A thread inherits its creator's affinity mask at pthread_create, so calling
// this on the main thread *before* later threads spawn (e.g. the GPU process's
// render/compositor threads) makes those threads inherit the mask too.
//
// This exists so a process can pin its OWN threads (reading /proc/self/task and
// sched_setaffinity on your own tids is always permitted), which the parent app
// cannot do for a sibling process on a non-debuggable Android build (hidepid +
// SELinux). Fork-only; see bug 1549943.
//
// Never crashes on failure: affinity is best-effort, failures are logged (to
// logcat on Android) and execution continues. No-op on non-Linux platforms.
void ApplyCpuAffinityToSelf(const nsACString& aCoreSpec);

}  // namespace mozilla

#endif  // mozilla_CpuAffinity_h
