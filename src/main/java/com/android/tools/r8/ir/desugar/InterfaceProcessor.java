// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.cf.code.CfInstruction;
import com.android.tools.r8.cf.code.CfInvoke;
import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.code.InvokeSuper;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.errors.Unimplemented;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.ClassAccessFlags;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexLibraryClass;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProgramClass.ChecksumSupplier;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.GenericSignature.ClassSignature;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.graph.GraphLens.NestedGraphLens;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.graph.ParameterAnnotationsList;
import com.android.tools.r8.ir.code.Invoke.Type;
import com.android.tools.r8.ir.synthetic.ForwardMethodBuilder;
import com.android.tools.r8.origin.SynthesizedOrigin;
import com.android.tools.r8.utils.Pair;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Default and static method interface desugaring processor for interfaces.
//
// Makes default interface methods abstract, moves their implementation to
// a companion class. Removes bridge default methods.
//
// Also moves static interface methods into a companion class.
public final class InterfaceProcessor {

  private final AppView<?> appView;
  private final InterfaceMethodRewriter rewriter;

  // All created companion and dispatch classes indexed by interface type.
  final Map<DexClass, DexProgramClass> syntheticClasses = new IdentityHashMap<>();

  InterfaceProcessor(
      AppView<?> appView, InterfaceMethodRewriter rewriter) {
    this.appView = appView;
    this.rewriter = rewriter;
  }

  void process(DexProgramClass iface, InterfaceProcessorNestedGraphLens.Builder graphLensBuilder) {
    assert iface.isInterface();
    // The list of methods to be created in companion class.
    List<DexEncodedMethod> companionMethods = new ArrayList<>();

    // Process virtual interface methods first.
    List<DexEncodedMethod> remainingMethods = new ArrayList<>();
    for (DexEncodedMethod virtual : iface.virtualMethods()) {
      if (rewriter.isDefaultMethod(virtual)) {
        if (!canMoveToCompanionClass(virtual)) {
          throw new CompilationError("One or more instruction is preventing default interface "
              + "method from being desugared: " + virtual.method.toSourceString(), iface.origin);
        }

        // Create a new method in a companion class to represent default method implementation.
        DexMethod companionMethod = rewriter.defaultAsMethodOfCompanionClass(virtual.method);

        Code code = virtual.getCode();
        if (code == null) {
          throw new CompilationError("Code is missing for default "
              + "interface method: " + virtual.method.toSourceString(), iface.origin);
        }

        MethodAccessFlags newFlags = virtual.accessFlags.copy();
        newFlags.unsetBridge();
        newFlags.promoteToStatic();
        DexEncodedMethod.setDebugInfoWithFakeThisParameter(
            code, companionMethod.getArity(), appView);
        DexEncodedMethod implMethod =
            new DexEncodedMethod(
                companionMethod,
                newFlags,
                virtual.annotations(),
                virtual.parameterAnnotationsList,
                code,
                true);
        implMethod.copyMetadata(virtual);
        virtual.setDefaultInterfaceMethodImplementation(implMethod);
        companionMethods.add(implMethod);
        graphLensBuilder.recordOrigin(implMethod.method, virtual.method);
      }

      // Remove bridge methods.
      if (interfaceMethodRemovalChangesApi(virtual, iface)) {
        remainingMethods.add(virtual);
      }
    }

    // If at least one bridge method was removed then update the table.
    if (remainingMethods.size() < iface.getMethodCollection().numberOfVirtualMethods()) {
      iface.setVirtualMethods(remainingMethods.toArray(DexEncodedMethod.EMPTY_ARRAY));
    }
    remainingMethods.clear();

    // Process static and private methods, move them into companion class as well,
    // make private instance methods public static.
    for (DexEncodedMethod direct : iface.directMethods()) {
      MethodAccessFlags originalFlags = direct.accessFlags;
      MethodAccessFlags newFlags = originalFlags.copy();
      if (originalFlags.isPrivate()) {
        newFlags.promoteToPublic();
      }
      DexMethod oldMethod = direct.method;
      if (isStaticMethod(direct)) {
        assert originalFlags.isPrivate() || originalFlags.isPublic()
            : "Static interface method " + direct.toSourceString() + " is expected to "
            + "either be public or private in " + iface.origin;
        DexMethod companionMethod = rewriter.staticAsMethodOfCompanionClass(oldMethod);
        DexEncodedMethod implMethod =
            new DexEncodedMethod(
                companionMethod,
                newFlags,
                direct.annotations(),
                direct.parameterAnnotationsList,
                direct.getCode(),
                true);
        implMethod.copyMetadata(direct);
        companionMethods.add(implMethod);
        graphLensBuilder.move(oldMethod, companionMethod);
      } else {
        if (originalFlags.isPrivate()) {
          assert !rewriter.factory.isClassConstructor(oldMethod)
              : "Unexpected private constructor " + direct.toSourceString()
              + " in " + iface.origin;
          newFlags.promoteToStatic();

          DexMethod companionMethod = rewriter.privateAsMethodOfCompanionClass(oldMethod);

          Code code = direct.getCode();
          if (code == null) {
            throw new CompilationError("Code is missing for private instance "
                + "interface method: " + oldMethod.toSourceString(), iface.origin);
          }
          DexEncodedMethod.setDebugInfoWithFakeThisParameter(
              code, companionMethod.getArity(), appView);
          DexEncodedMethod implMethod =
              new DexEncodedMethod(
                  companionMethod,
                  newFlags,
                  direct.annotations(),
                  direct.parameterAnnotationsList,
                  code,
                  true);
          implMethod.copyMetadata(direct);
          companionMethods.add(implMethod);
          graphLensBuilder.move(oldMethod, companionMethod);
        } else {
          // Since there are no interface constructors at this point,
          // this should only be class constructor.
          assert rewriter.factory.isClassConstructor(oldMethod);
          remainingMethods.add(direct);
        }
      }
    }
    if (remainingMethods.size() < iface.getMethodCollection().numberOfDirectMethods()) {
      iface.setDirectMethods(remainingMethods.toArray(DexEncodedMethod.EMPTY_ARRAY));
    }

    if (companionMethods.isEmpty()) {
      return; // No methods to create, companion class not needed.
    }

    ClassAccessFlags companionClassFlags = iface.accessFlags.copy();
    companionClassFlags.unsetAbstract();
    companionClassFlags.unsetInterface();
    companionClassFlags.unsetAnnotation();
    companionClassFlags.setFinal();
    companionClassFlags.setSynthetic();
    // Companion class must be public so moved methods can be called from anywhere.
    companionClassFlags.setPublic();

    // Create companion class.
    DexType companionClassType = rewriter.getCompanionClassType(iface.type);
    DexProgramClass companionClass =
        new DexProgramClass(
            companionClassType,
            null,
            new SynthesizedOrigin("interface desugaring", getClass()),
            companionClassFlags,
            rewriter.factory.objectType,
            DexTypeList.empty(),
            iface.sourceFile,
            null,
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            ClassSignature.NO_CLASS_SIGNATURE,
            DexAnnotationSet.empty(),
            DexEncodedField.EMPTY_ARRAY,
            DexEncodedField.EMPTY_ARRAY,
            companionMethods.toArray(DexEncodedMethod.EMPTY_ARRAY),
            DexEncodedMethod.EMPTY_ARRAY,
            rewriter.factory.getSkipNameValidationForTesting(),
            getChecksumSupplier(iface),
            Collections.singletonList(iface));
    syntheticClasses.put(iface, companionClass);
  }

  private ChecksumSupplier getChecksumSupplier(DexProgramClass iface) {
    if (!appView.options().encodeChecksums) {
      return DexProgramClass::invalidChecksumRequest;
    }
    long checksum = iface.getChecksum();
    return c -> 7 * checksum;
  }

  DexProgramClass process(DexLibraryClass iface, Set<DexProgramClass> callers) {
    assert iface.isInterface();

    // The list of methods to be created in dispatch class.
    // NOTE: we do NOT check static methods being actually called on the interface against
    //       static methods actually existing in that interface. It is essential for supporting
    //       D8 desugaring when each class may be dexed separately since it allows us to assume
    //       that all synthesized dispatch classes have the same set of methods so we don't
    //       need to merge them.
    List<DexEncodedMethod> dispatchMethods = new ArrayList<>();

    // Process public static methods, for each of them create a method dispatching the call to it.
    for (DexEncodedMethod direct : iface.directMethods()) {
      MethodAccessFlags originalAccessFlags = direct.accessFlags;
      if (!originalAccessFlags.isStatic() || !originalAccessFlags.isPublic()) {
        // We assume only public static methods of library interfaces can be called
        // from program classes, since there should not be protected or package private
        // static methods on interfaces.
        assert !originalAccessFlags.isStatic() || originalAccessFlags.isPrivate();
        continue;
      }

      assert !rewriter.factory.isClassConstructor(direct.method);

      DexMethod origMethod = direct.method;
      DexMethod newMethod = rewriter.staticAsMethodOfDispatchClass(origMethod);
      // Create a forwarding method to the library static interface method. The method is added
      // to the dispatch class, however, the targeted method is still on the interface, so the
      // interface bit should be set to true.
      ForwardMethodBuilder forwardMethodBuilder =
          ForwardMethodBuilder.builder(appView.dexItemFactory())
              .setStaticSource(newMethod)
              .setStaticTarget(origMethod, true);
      DexEncodedMethod newEncodedMethod =
          new DexEncodedMethod(
              newMethod,
              MethodAccessFlags.fromSharedAccessFlags(
                  Constants.ACC_PUBLIC | Constants.ACC_STATIC | Constants.ACC_SYNTHETIC, false),
              DexAnnotationSet.empty(),
              ParameterAnnotationsList.empty(),
              forwardMethodBuilder.build(),
              true);
      newEncodedMethod.getMutableOptimizationInfo().markNeverInline();
      dispatchMethods.add(newEncodedMethod);
    }

    ClassAccessFlags dispatchClassFlags =
        ClassAccessFlags.fromSharedAccessFlags(
            Constants.ACC_PUBLIC | Constants.ACC_FINAL | Constants.ACC_SYNTHETIC);

    // Create dispatch class.
    DexType dispatchClassType = rewriter.getDispatchClassType(iface.type);
    DexProgramClass dispatchClass =
        new DexProgramClass(
            dispatchClassType,
            null,
            new SynthesizedOrigin("interface dispatch", getClass()),
            dispatchClassFlags,
            rewriter.factory.objectType,
            DexTypeList.empty(),
            iface.sourceFile,
            null,
            Collections.emptyList(),
            null,
            Collections.emptyList(),
            ClassSignature.NO_CLASS_SIGNATURE,
            DexAnnotationSet.empty(),
            DexEncodedField.EMPTY_ARRAY,
            DexEncodedField.EMPTY_ARRAY,
            dispatchMethods.toArray(DexEncodedMethod.EMPTY_ARRAY),
            DexEncodedMethod.EMPTY_ARRAY,
            rewriter.factory.getSkipNameValidationForTesting(),
            DexProgramClass::checksumFromType,
            callers);
    syntheticClasses.put(iface, dispatchClass);
    return dispatchClass;
  }

  private boolean canMoveToCompanionClass(DexEncodedMethod method) {
    Code code = method.getCode();
    assert code != null;
    if (code.isDexCode()) {
      for (Instruction insn : code.asDexCode().instructions) {
        if (insn instanceof InvokeSuper) {
          return false;
        }
      }
    } else {
      assert code.isCfCode();
      for (CfInstruction insn : code.asCfCode().getInstructions()) {
        if (insn instanceof CfInvoke && ((CfInvoke) insn).isInvokeSuper(method.holder())) {
          return false;
        }
      }
    }
    return true;
  }

  private DexClass definitionForDependency(DexType dependency, DexClass dependent) {
    return dependent.isProgramClass()
        ? appView.appInfo().definitionForDesugarDependency(dependent.asProgramClass(), dependency)
        : appView.definitionFor(dependency);
  }

  // Returns true if the given interface method must be kept on [iface] after moving its
  // implementation to the companion class of [iface]. This is always the case for non-bridge
  // methods. Bridge methods that does not override an implementation in a super-interface must
  // also be kept (such a situation can happen if the vertical class merger merges two interfaces).
  private boolean interfaceMethodRemovalChangesApi(DexEncodedMethod method, DexClass iface) {
    if (appView.enableWholeProgramOptimizations()) {
      if (appView.appInfo().withLiveness().isPinned(method.method)) {
        return true;
      }
    }
    if (method.accessFlags.isBridge()) {
      Deque<Pair<DexClass, DexType>> worklist = new ArrayDeque<>();
      Set<DexType> seenBefore = new HashSet<>();
      addSuperTypes(iface, worklist);
      while (!worklist.isEmpty()) {
        Pair<DexClass, DexType> item = worklist.pop();
        DexClass clazz = definitionForDependency(item.getSecond(), item.getFirst());
        if (clazz == null || !seenBefore.add(clazz.type)) {
          continue;
        }
        if (clazz.lookupVirtualMethod(method.method) != null) {
          return false;
        }
        addSuperTypes(clazz, worklist);
      }
    }
    return true;
  }

  private static void addSuperTypes(DexClass clazz, Deque<Pair<DexClass, DexType>> worklist) {
    if (clazz.superType != null) {
      worklist.add(new Pair<>(clazz, clazz.superType));
    }
    for (DexType iface : clazz.interfaces.values) {
      worklist.add(new Pair<>(clazz, iface));
    }
  }

  private boolean isStaticMethod(DexEncodedMethod method) {
    if (method.accessFlags.isNative()) {
      throw new Unimplemented("Native interface methods are not yet supported.");
    }
    return method.accessFlags.isStatic() && !rewriter.factory.isClassConstructor(method.method);
  }

  // Specific lens which remaps invocation types to static since all rewrites performed here
  // are to static companion methods.
  public static class InterfaceProcessorNestedGraphLens extends NestedGraphLens {

    private BiMap<DexMethod, DexMethod> extraOriginalMethodSignatures;

    public InterfaceProcessorNestedGraphLens(
        Map<DexType, DexType> typeMap,
        Map<DexMethod, DexMethod> methodMap,
        Map<DexField, DexField> fieldMap,
        BiMap<DexField, DexField> originalFieldSignatures,
        BiMap<DexMethod, DexMethod> originalMethodSignatures,
        BiMap<DexMethod, DexMethod> extraOriginalMethodSignatures,
        GraphLens previousLens,
        DexItemFactory dexItemFactory) {
      super(
          typeMap,
          methodMap,
          fieldMap,
          originalFieldSignatures,
          originalMethodSignatures,
          previousLens,
          dexItemFactory);
      this.extraOriginalMethodSignatures = extraOriginalMethodSignatures;
    }

    public static InterfaceProcessorNestedGraphLens find(GraphLens lens) {
      if (lens.isInterfaceProcessorLens()) {
        return lens.asInterfaceProcessorLens();
      }
      if (lens.isIdentityLens()) {
        return null;
      }
      if (lens.isNonIdentityLens()) {
        return find(lens.asNonIdentityLens().getPrevious());
      }
      assert false;
      return null;
    }

    public void toggleMappingToExtraMethods() {
      BiMap<DexMethod, DexMethod> tmp = originalMethodSignatures;
      this.originalMethodSignatures = extraOriginalMethodSignatures;
      this.extraOriginalMethodSignatures = tmp;
    }

    public BiMap<DexMethod, DexMethod> getExtraOriginalMethodSignatures() {
      return extraOriginalMethodSignatures;
    }

    @Override
    public boolean isInterfaceProcessorLens() {
      return true;
    }

    @Override
    public InterfaceProcessorNestedGraphLens asInterfaceProcessorLens() {
      return this;
    }

    @Override
    public boolean isLegitimateToHaveEmptyMappings() {
      return true;
    }

    @Override
    protected DexMethod internalGetPreviousMethodSignature(DexMethod method) {
      return extraOriginalMethodSignatures.getOrDefault(
          method, originalMethodSignatures.getOrDefault(method, method));
    }

    @Override
    protected DexMethod internalGetNextMethodSignature(DexMethod method) {
      return originalMethodSignatures
          .inverse()
          .getOrDefault(
              method, extraOriginalMethodSignatures.inverse().getOrDefault(method, method));
    }

    @Override
    protected Type mapInvocationType(DexMethod newMethod, DexMethod originalMethod, Type type) {
      return Type.STATIC;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static class Builder extends NestedGraphLens.Builder {

      private final BiMap<DexMethod, DexMethod> extraOriginalMethodSignatures = HashBiMap.create();

      public void recordOrigin(DexMethod method, DexMethod origin) {
        if (method == origin) {
          return;
        }
        extraOriginalMethodSignatures.put(method, origin);
      }

      @Override
      public InterfaceProcessorNestedGraphLens build(
          DexItemFactory dexItemFactory, GraphLens previousLens) {
        if (originalFieldSignatures.isEmpty()
            && originalMethodSignatures.isEmpty()
            && extraOriginalMethodSignatures.isEmpty()) {
          return null;
        }
        return new InterfaceProcessorNestedGraphLens(
            typeMap,
            methodMap,
            fieldMap,
            originalFieldSignatures,
            originalMethodSignatures,
            extraOriginalMethodSignatures,
            previousLens,
            dexItemFactory);
      }
    }
  }
}
