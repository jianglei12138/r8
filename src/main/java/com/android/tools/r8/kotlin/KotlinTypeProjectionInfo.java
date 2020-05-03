// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.kotlin;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import kotlinx.metadata.KmTypeProjection;
import kotlinx.metadata.KmVariance;

// Provides access to Kotlin information about the type projection of a type (arguments).
public class KotlinTypeProjectionInfo {

  final KmVariance variance;
  final KotlinTypeInfo typeInfo;

  private KotlinTypeProjectionInfo(KmVariance variance, KotlinTypeInfo typeInfo) {
    this.variance = variance;
    this.typeInfo = typeInfo;
  }

  static KotlinTypeProjectionInfo create(KmTypeProjection kmTypeProjection, AppView<?> appView) {
    return new KotlinTypeProjectionInfo(
        kmTypeProjection.getVariance(), KotlinTypeInfo.create(kmTypeProjection.getType(), appView));
  }

  private boolean isStarProjection() {
    return variance == null && typeInfo == null;
  }

  public void rewrite(
      KmVisitorProviders.KmTypeProjectionVisitorProvider visitorProvider,
      KmVisitorProviders.KmTypeStarProjectionVisitorProvider starProjectionProvider,
      AppView<AppInfoWithLiveness> appView,
      NamingLens namingLens) {
    if (isStarProjection()) {
      starProjectionProvider.get();
    } else {
      typeInfo.rewrite(flags -> visitorProvider.get(flags, variance), appView, namingLens);
    }
  }
}
