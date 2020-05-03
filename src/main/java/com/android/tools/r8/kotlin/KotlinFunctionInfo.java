// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.kotlin;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.DescriptorUtils;
import java.util.List;
import kotlinx.metadata.KmFunction;
import kotlinx.metadata.KmFunctionVisitor;
import kotlinx.metadata.jvm.JvmExtensionsKt;
import kotlinx.metadata.jvm.JvmFunctionExtensionVisitor;

// Holds information about KmFunction
public final class KotlinFunctionInfo implements KotlinMethodLevelInfo {
  // Original flags
  private final int flags;
  // Original name;
  private final String name;
  // Information from original KmValueParameter(s) if available.
  private final List<KotlinValueParameterInfo> valueParameters;
  // Information from original KmFunction.returnType. Null if this is from a KmConstructor.
  public final KotlinTypeInfo returnType;
  // Information from original KmFunction.receiverType. Null if this is from a KmConstructor.
  private final KotlinTypeInfo receiverParameterType;
  // Information about original type parameters. Null if this is from a KmConstructor.
  private final List<KotlinTypeParameterInfo> typeParameters;
  // Information about the signature
  private final KotlinJvmMethodSignatureInfo signature;
  // Information about the lambdaClassOrigin.
  private final DexType lambdaClassOrigin;

  private KotlinFunctionInfo(
      int flags,
      String name,
      KotlinTypeInfo returnType,
      KotlinTypeInfo receiverParameterType,
      List<KotlinValueParameterInfo> valueParameters,
      List<KotlinTypeParameterInfo> typeParameters,
      KotlinJvmMethodSignatureInfo signature,
      DexType lambdaClassOrigin) {
    this.flags = flags;
    this.name = name;
    this.returnType = returnType;
    this.receiverParameterType = receiverParameterType;
    this.valueParameters = valueParameters;
    this.typeParameters = typeParameters;
    this.signature = signature;
    this.lambdaClassOrigin = lambdaClassOrigin;
  }

  static KotlinFunctionInfo create(KmFunction kmFunction, AppView<?> appView) {
    return new KotlinFunctionInfo(
        kmFunction.getFlags(),
        kmFunction.getName(),
        KotlinTypeInfo.create(kmFunction.getReturnType(), appView),
        KotlinTypeInfo.create(kmFunction.getReceiverParameterType(), appView),
        KotlinValueParameterInfo.create(kmFunction.getValueParameters(), appView),
        KotlinTypeParameterInfo.create(kmFunction.getTypeParameters(), appView),
        KotlinJvmMethodSignatureInfo.create(JvmExtensionsKt.getSignature(kmFunction), appView),
        getlambdaClassOrigin(kmFunction, appView));
  }

  private static DexType getlambdaClassOrigin(KmFunction kmFunction, AppView<?> appView) {
    String lambdaClassOriginName = JvmExtensionsKt.getLambdaClassOriginName(kmFunction);
    if (lambdaClassOriginName != null) {
      return appView
          .dexItemFactory()
          .createType(DescriptorUtils.getDescriptorFromClassBinaryName(lambdaClassOriginName));
    }
    return null;
  }

  public void rewrite(
      KmVisitorProviders.KmFunctionVisitorProvider visitorProvider,
      DexEncodedMethod method,
      AppView<AppInfoWithLiveness> appView,
      NamingLens namingLens) {
    // TODO(b/154348683): Check method for flags to pass in.
    String finalName = this.name;
    if (method != null) {
      String methodName = method.method.name.toString();
      String rewrittenName = namingLens.lookupName(method.method).toString();
      if (!methodName.equals(rewrittenName)) {
        finalName = rewrittenName;
      }
    }
    KmFunctionVisitor kmFunction = visitorProvider.get(flags, finalName);
    // TODO(b/154348149): ReturnType could have been merged to a subtype.
    returnType.rewrite(kmFunction::visitReturnType, appView, namingLens);
    for (KotlinValueParameterInfo valueParameterInfo : valueParameters) {
      valueParameterInfo.rewrite(kmFunction::visitValueParameter, appView, namingLens);
    }
    for (KotlinTypeParameterInfo typeParameterInfo : typeParameters) {
      typeParameterInfo.rewrite(kmFunction::visitTypeParameter, appView, namingLens);
    }
    if (receiverParameterType != null) {
      receiverParameterType.rewrite(kmFunction::visitReceiverParameterType, appView, namingLens);
    }
    JvmFunctionExtensionVisitor extensionVisitor =
        (JvmFunctionExtensionVisitor) kmFunction.visitExtensions(JvmFunctionExtensionVisitor.TYPE);
    if (signature != null && extensionVisitor != null) {
      extensionVisitor.visit(signature.rewrite(method, appView, namingLens));
    }
    if (lambdaClassOrigin != null && extensionVisitor != null) {
      extensionVisitor.visitLambdaClassOriginName(
          KotlinMetadataUtils.kotlinNameFromDescriptor(lambdaClassOrigin.descriptor));
    }
  }

  @Override
  public boolean isFunction() {
    return true;
  }

  @Override
  public KotlinFunctionInfo asFunction() {
    return this;
  }

  public boolean isExtensionFunction() {
    return receiverParameterType != null;
  }
}
