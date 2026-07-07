/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* vim: set ts=8 sts=2 et sw=2 tw=80: */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "CpuAffinity.h"

#include "nsString.h"

#if defined(__linux__)
#  include <dirent.h>
#  include <sched.h>
#  include <stdlib.h>
#  include <string.h>
#  include <sys/types.h>
#endif

#if defined(__ANDROID__)
#  include <android/log.h>
#  define CPUAFF_LOG(fmt, ...)                                            \
    __android_log_print(ANDROID_LOG_INFO, "GeckoCpuAffinity", fmt,       \
                        ##__VA_ARGS__)
#else
#  include <cstdio>
#  define CPUAFF_LOG(fmt, ...) fprintf(stderr, "GeckoCpuAffinity: " fmt "\n", \
                                       ##__VA_ARGS__)
#endif

namespace mozilla {

#if defined(__linux__)

// Parse a core list/range spec ("4-7", "4,5,6,7", "0,4-7") into aOutSet.
// Returns the number of distinct cores selected, or 0 if the spec named none
// (empty/whitespace/garbage). Out-of-range or reversed ranges are skipped.
static int ParseCoreSpec(const nsACString& aSpec, cpu_set_t* aOutSet) {
  CPU_ZERO(aOutSet);

  const long maxCore = CPU_SETSIZE;
  int selected = 0;

  // Copy to a NUL-terminated buffer we can walk with strtol.
  nsAutoCString spec(aSpec);
  const char* p = spec.get();

  while (*p) {
    // Skip separators and whitespace.
    while (*p == ',' || *p == ' ' || *p == '\t') {
      ++p;
    }
    if (!*p) {
      break;
    }

    char* end = nullptr;
    long lo = strtol(p, &end, 10);
    if (end == p) {
      // Not a number where one was expected; skip one char to make progress.
      ++p;
      continue;
    }
    p = end;

    long hi = lo;
    if (*p == '-') {
      ++p;
      hi = strtol(p, &end, 10);
      if (end == p) {
        // Dangling '-' with no upper bound; ignore this token.
        continue;
      }
      p = end;
    }

    if (lo < 0 || hi < lo) {
      continue;
    }
    for (long c = lo; c <= hi && c < maxCore; ++c) {
      if (!CPU_ISSET(c, aOutSet)) {
        CPU_SET(c, aOutSet);
        ++selected;
      }
    }
  }

  return selected;
}

// Human-readable "4-7" style rendering of the set, for logging.
static nsCString DescribeSet(const cpu_set_t* aSet) {
  nsCString out;
  int runStart = -1;
  for (int c = 0; c <= CPU_SETSIZE; ++c) {
    bool set = (c < CPU_SETSIZE) && CPU_ISSET(c, aSet);
    if (set && runStart < 0) {
      runStart = c;
    } else if (!set && runStart >= 0) {
      if (!out.IsEmpty()) {
        out.Append(',');
      }
      if (c - 1 == runStart) {
        out.AppendInt(runStart);
      } else {
        out.AppendInt(runStart);
        out.Append('-');
        out.AppendInt(c - 1);
      }
      runStart = -1;
    }
  }
  if (out.IsEmpty()) {
    out.AssignLiteral("(none)");
  }
  return out;
}

#endif  // __linux__

void ApplyCpuAffinityToSelf(const nsACString& aCoreSpec) {
  if (aCoreSpec.IsEmpty()) {
    return;  // Feature disabled: no behavior change.
  }

#if defined(__linux__)
  cpu_set_t set;
  int selected = ParseCoreSpec(aCoreSpec, &set);
  if (selected <= 0) {
    CPUAFF_LOG("ignoring malformed cpu-affinity spec \"%s\"",
               nsAutoCString(aCoreSpec).get());
    return;
  }

  nsCString desc = DescribeSet(&set);

  // Iterate our own threads. /proc/self/task is always readable for our own
  // process regardless of hidepid. Pin every existing tid; threads spawned
  // after this by the calling thread inherit the mask via pthread_create.
  DIR* dir = opendir("/proc/self/task");
  int pinned = 0;
  int failed = 0;
  if (dir) {
    struct dirent* ent;
    while ((ent = readdir(dir))) {
      if (ent->d_name[0] < '0' || ent->d_name[0] > '9') {
        continue;  // "." / ".."
      }
      pid_t tid = static_cast<pid_t>(atoi(ent->d_name));
      if (tid <= 0) {
        continue;
      }
      if (sched_setaffinity(tid, sizeof(set), &set) == 0) {
        ++pinned;
      } else {
        ++failed;
      }
    }
    closedir(dir);
  } else {
    // Fall back to pinning just the calling thread (tid 0 == self).
    if (sched_setaffinity(0, sizeof(set), &set) == 0) {
      ++pinned;
    } else {
      ++failed;
    }
  }

  CPUAFF_LOG("pinned %d thread(s) to cores %s (spec \"%s\"), %d failed", pinned,
             desc.get(), nsAutoCString(aCoreSpec).get(), failed);
#else
  // Not supported off Linux; no-op.
  CPUAFF_LOG("cpu-affinity requested (\"%s\") but unsupported on this platform",
             nsAutoCString(aCoreSpec).get());
#endif
}

}  // namespace mozilla
