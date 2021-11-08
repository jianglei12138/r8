// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import com.android.tools.r8.utils.structural.Ordered;

public enum MapVersion implements Ordered<MapVersion> {
  MAP_VERSION_NONE("none"),
  MAP_VERSION_1_0("1.0"),
  MAP_VERSION_2_0("2.0"),
  MAP_VERSION_EXPERIMENTAL("experimental"),
  MAP_VERSION_UNKNOWN("unknown");

  public static final MapVersion STABLE = MAP_VERSION_2_0;

  private final String name;

  MapVersion(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public static MapVersion fromName(String name) {
    for (MapVersion version : MapVersion.values()) {
      if (version.getName().equals(name)) {
        return version;
      }
    }
    return null;
  }

  public boolean isUnknown() {
    return this == MAP_VERSION_UNKNOWN;
  }
}
