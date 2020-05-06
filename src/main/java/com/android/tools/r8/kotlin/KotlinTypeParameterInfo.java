// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.kotlin;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexDefinitionSupplier;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.Reporter;
import com.google.common.collect.ImmutableList;
import java.util.List;
import kotlinx.metadata.KmType;
import kotlinx.metadata.KmTypeParameter;
import kotlinx.metadata.KmTypeParameterVisitor;
import kotlinx.metadata.KmVariance;
import kotlinx.metadata.jvm.JvmExtensionsKt;
import kotlinx.metadata.jvm.JvmTypeParameterExtensionVisitor;

// Provides access to Kotlin information about a type-parameter.
public class KotlinTypeParameterInfo {

  private static final List<KotlinTypeParameterInfo> EMPTY_TYPE_PARAMETERS = ImmutableList.of();
  private static final List<KotlinTypeInfo> EMPTY_UPPER_BOUNDS = ImmutableList.of();

  private final int flags;
  private final int id;
  private final String name;
  private final KmVariance variance;
  private final List<KotlinTypeInfo> originalUpperBounds;
  private final List<KotlinAnnotationInfo> annotations;

  private KotlinTypeParameterInfo(
      int flags,
      int id,
      String name,
      KmVariance variance,
      List<KotlinTypeInfo> originalUpperBounds,
      List<KotlinAnnotationInfo> annotations) {
    this.flags = flags;
    this.id = id;
    this.name = name;
    this.variance = variance;
    this.originalUpperBounds = originalUpperBounds;
    this.annotations = annotations;
  }

  private static KotlinTypeParameterInfo create(
      KmTypeParameter kmTypeParameter,
      DexDefinitionSupplier definitionSupplier,
      Reporter reporter) {
    return new KotlinTypeParameterInfo(
        kmTypeParameter.getFlags(),
        kmTypeParameter.getId(),
        kmTypeParameter.getName(),
        kmTypeParameter.getVariance(),
        getUpperBounds(kmTypeParameter.getUpperBounds(), definitionSupplier, reporter),
        KotlinAnnotationInfo.create(
            JvmExtensionsKt.getAnnotations(kmTypeParameter), definitionSupplier));
  }

  static List<KotlinTypeParameterInfo> create(
      List<KmTypeParameter> kmTypeParameters,
      DexDefinitionSupplier definitionSupplier,
      Reporter reporter) {
    if (kmTypeParameters.isEmpty()) {
      return EMPTY_TYPE_PARAMETERS;
    }
    ImmutableList.Builder<KotlinTypeParameterInfo> builder = ImmutableList.builder();
    for (KmTypeParameter kmTypeParameter : kmTypeParameters) {
      builder.add(create(kmTypeParameter, definitionSupplier, reporter));
    }
    return builder.build();
  }

  private static List<KotlinTypeInfo> getUpperBounds(
      List<KmType> upperBounds, DexDefinitionSupplier definitionSupplier, Reporter reporter) {
    if (upperBounds.isEmpty()) {
      return EMPTY_UPPER_BOUNDS;
    }
    ImmutableList.Builder<KotlinTypeInfo> builder = ImmutableList.builder();
    for (KmType upperBound : upperBounds) {
      builder.add(KotlinTypeInfo.create(upperBound, definitionSupplier, reporter));
    }
    return builder.build();
  }

  void rewrite(
      KmVisitorProviders.KmTypeParameterVisitorProvider visitorProvider,
      AppView<AppInfoWithLiveness> appView,
      NamingLens namingLens) {
    KmTypeParameterVisitor kmTypeParameterVisitor = visitorProvider.get(flags, name, id, variance);
    for (KotlinTypeInfo originalUpperBound : originalUpperBounds) {
      originalUpperBound.rewrite(kmTypeParameterVisitor::visitUpperBound, appView, namingLens);
    }
    if (annotations.isEmpty()) {
      return;
    }
    JvmTypeParameterExtensionVisitor extensionVisitor =
        (JvmTypeParameterExtensionVisitor)
            kmTypeParameterVisitor.visitExtensions(JvmTypeParameterExtensionVisitor.TYPE);
    for (KotlinAnnotationInfo annotation : annotations) {
      annotation.rewrite(extensionVisitor::visitAnnotation, appView, namingLens);
    }
  }
}
