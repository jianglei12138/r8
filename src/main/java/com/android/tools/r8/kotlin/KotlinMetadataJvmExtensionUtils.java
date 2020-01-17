// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.kotlin;

import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import kotlinx.metadata.KmConstructor;
import kotlinx.metadata.KmConstructorExtensionVisitor;
import kotlinx.metadata.KmConstructorVisitor;
import kotlinx.metadata.KmExtensionType;
import kotlinx.metadata.KmFunction;
import kotlinx.metadata.KmFunctionExtensionVisitor;
import kotlinx.metadata.KmFunctionVisitor;
import kotlinx.metadata.KmProperty;
import kotlinx.metadata.KmPropertyExtensionVisitor;
import kotlinx.metadata.KmPropertyVisitor;
import kotlinx.metadata.jvm.JvmConstructorExtensionVisitor;
import kotlinx.metadata.jvm.JvmFieldSignature;
import kotlinx.metadata.jvm.JvmFunctionExtensionVisitor;
import kotlinx.metadata.jvm.JvmMethodSignature;
import kotlinx.metadata.jvm.JvmPropertyExtensionVisitor;

class KotlinMetadataJvmExtensionUtils {

  static JvmFieldSignature toJvmFieldSignature(DexField field) {
    return new JvmFieldSignature(field.name.toString(), field.type.toDescriptorString());
  }

  static JvmMethodSignature toJvmMethodSignature(DexMethod method) {
    StringBuilder descBuilder = new StringBuilder();
    descBuilder.append("(");
    for (DexType argType : method.proto.parameters.values) {
      descBuilder.append(argType.toDescriptorString());
    }
    descBuilder.append(")");
    descBuilder.append(method.proto.returnType.toDescriptorString());
    return new JvmMethodSignature(method.name.toString(), descBuilder.toString());
  }

  static class KmConstructorProcessor {
    private JvmMethodSignature signature = null;

    KmConstructorProcessor(KmConstructor kmConstructor) {
      kmConstructor.accept(new KmConstructorVisitor() {
        @Override
        public KmConstructorExtensionVisitor visitExtensions(KmExtensionType type) {
          if (type != JvmConstructorExtensionVisitor.TYPE) {
            return null;
          }
          return new JvmConstructorExtensionVisitor() {
            @Override
            public void visit(JvmMethodSignature desc) {
              assert signature == null : signature.asString();
              signature = desc;
            }
          };
        }
      });
    }

    JvmMethodSignature signature() {
      return signature;
    }
  }

  static class KmFunctionProcessor {
    // Custom name via @JvmName("..."). Otherwise, null.
    private JvmMethodSignature signature = null;

    KmFunctionProcessor(KmFunction kmFunction) {
      kmFunction.accept(new KmFunctionVisitor() {
        @Override
        public KmFunctionExtensionVisitor visitExtensions(KmExtensionType type) {
          if (type != JvmFunctionExtensionVisitor.TYPE) {
            return null;
          }
          return new JvmFunctionExtensionVisitor() {
            @Override
            public void visit(JvmMethodSignature desc) {
              assert signature == null : signature.asString();
              signature = desc;
            }
          };
        }
      });
    }

    JvmMethodSignature signature() {
      return signature;
    }
  }

  static class KmPropertyProcessor {
    private JvmFieldSignature fieldSignature = null;
    // Custom getter via @get:JvmName("..."). Otherwise, null.
    private JvmMethodSignature getterSignature = null;
    // Custom getter via @set:JvmName("..."). Otherwise, null.
    private JvmMethodSignature setterSignature = null;

    KmPropertyProcessor(KmProperty kmProperty) {
      kmProperty.accept(new KmPropertyVisitor() {
        @Override
        public KmPropertyExtensionVisitor visitExtensions(KmExtensionType type) {
          if (type != JvmPropertyExtensionVisitor.TYPE) {
            return null;
          }
          return new JvmPropertyExtensionVisitor() {
            @Override
            public void visit(
                int flags,
                JvmFieldSignature fieldDesc,
                JvmMethodSignature getterDesc,
                JvmMethodSignature setterDesc) {
              assert fieldSignature == null : fieldSignature.asString();
              fieldSignature = fieldDesc;
              assert getterSignature == null : getterSignature.asString();
              getterSignature = getterDesc;
              assert setterSignature == null : setterSignature.asString();
              setterSignature = setterDesc;
            }
          };
        }
      });
    }

    JvmFieldSignature fieldSignature() {
      return fieldSignature;
    }

    JvmMethodSignature getterSignature() {
      return getterSignature;
    }

    JvmMethodSignature setterSignature() {
      return setterSignature;
    }
  }
}
