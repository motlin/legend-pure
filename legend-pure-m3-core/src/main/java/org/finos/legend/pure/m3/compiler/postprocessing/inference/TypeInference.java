// Copyright 2020 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.m3.compiler.postprocessing.inference;

import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.block.procedure.Procedure;
import org.eclipse.collections.api.block.procedure.Procedure2;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.factory.Maps;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;
import org.eclipse.collections.impl.tuple.Tuples;
import org.finos.legend.pure.m3.navigation.M3Paths;
import org.finos.legend.pure.m3.exception.PureExecutionException;
import org.finos.legend.pure.m3.compiler.postprocessing.ProcessorState;
import org.finos.legend.pure.m3.compiler.postprocessing.processor.FunctionDefinitionProcessor;
import org.finos.legend.pure.m3.compiler.postprocessing.processor.LambdaFunctionProcessor;
import org.finos.legend.pure.m3.compiler.postprocessing.processor.valuespecification.InstanceValueProcessor;
import org.finos.legend.pure.m3.navigation.importstub.ImportStub;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.function.Function;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.function.LambdaFunction;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.function.property.QualifiedProperty;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.multiplicity.Multiplicity;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.FunctionType;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.Type;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.generics.TypeParameter;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.valuespecification.FunctionExpression;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.valuespecification.InstanceValue;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.valuespecification.VariableExpression;
import org.finos.legend.pure.m3.navigation.ProcessorSupport;
import org.finos.legend.pure.m3.tools.ListHelper;
import org.finos.legend.pure.m3.tools.matcher.Matcher;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.ModelRepository;
import org.finos.legend.pure.m4.exception.PureCompilationException;

import java.util.Objects;
import java.util.Stack;

public class TypeInference
{
    public static boolean canProcessLambda(FunctionDefinition lambda, ProcessorState processorState, ProcessorSupport processorSupport)
    {
        FunctionType functionType = lambda._classifierGenericType()._typeArguments().getFirst() != null ? (FunctionType)ImportStub.withImportStubByPass(lambda._classifierGenericType()._typeArguments().getFirst()._rawTypeCoreInstance(), processorSupport) : null;
        if (functionType != null)
        {
            for (VariableExpression parameter : functionType._parameters())
            {
                GenericType genericType = parameter._genericType();
                if (genericType == null || (!org.finos.legend.pure.m3.navigation.generictype.GenericType.isGenericTypeConcrete(genericType, processorSupport) && !processorState.getTypeInferenceContext().isTypeParameterResolved(genericType)))
                {
                    return false;
                }
            }
        }
        return true;
    }

    public static void storeInferredTypeParametersInFunctionExpression(FunctionExpression functionExpression, ProcessorState state, ProcessorSupport processorSupport, Function foundFunction) throws PureCompilationException
    {
        // Store the inferred params in the FunctionExpression
        if (!(foundFunction instanceof QualifiedProperty))
        {
            FunctionType functionType = (FunctionType)processorSupport.function_getFunctionType(foundFunction);
            for (TypeParameter typeParameter : functionType._typeParameters())
            {
                if (state.getTypeInferenceContext().getTypeParameterToGenericType().get(typeParameter._name()) == null ) {
                    if (state.getTypeInferenceContext().getParent() == null ) {
                        throw new PureCompilationException(functionExpression.getSourceInformation(), "The type parameter " + typeParameter._name() + " was not resolved (" + foundFunction._functionName() + " / " + org.finos.legend.pure.m3.navigation.function.FunctionType.print(functionType, processorSupport) + ")!");
                   }
                }
                else
                {
                    functionExpression._resolvedTypeParametersAdd((GenericType)state.getTypeInferenceContext().getTypeParameterToGenericType().get(typeParameter._name()));
                }
            }
            for (InstanceValue multiplicityParameter : ((FunctionType)processorSupport.function_getFunctionType(foundFunction))._multiplicityParameters())
            {
                if (state.getTypeInferenceContext().getMultiplicityParameterToMultiplicity().get(multiplicityParameter._valuesCoreInstance().getFirst().getName()) == null)
                {
                    throw new PureCompilationException(functionExpression.getSourceInformation(), "The multiplicity parameter "+multiplicityParameter._valuesCoreInstance().getFirst().getName()+" was not resolved!");
                }
                else
                {
                    functionExpression._resolvedMultiplicityParametersAdd((Multiplicity) state.getTypeInferenceContext().getMultiplicityParameterToMultiplicity().get(multiplicityParameter._valuesCoreInstance().getFirst().getName()));
                }
            }
        }
    }

    public static boolean processParamTypesOfLambdaUsedAsAFunctionExpressionParamValue(ValueSpecification instanceValueContainer, LambdaFunction lambdaFunction, VariableExpression templateToMatchLambdaTo, Matcher matcher, ProcessorState state, ModelRepository repository, ProcessorSupport processorSupport) throws PureCompilationException
    {
        GenericType templateGenericType = templateToMatchLambdaTo._genericType();
        Type templateFunctionType = templateGenericType._typeArguments().getFirst() != null ? (Type)ImportStub.withImportStubByPass(templateGenericType._typeArguments().getFirst()._rawTypeCoreInstance(), processorSupport) : null;
        FunctionType lambdaFunctionType = (FunctionType)ImportStub.withImportStubByPass(lambdaFunction._classifierGenericType()._typeArguments().getFirst()._rawTypeCoreInstance(), processorSupport);

        if (org.finos.legend.pure.m3.navigation.generictype.GenericType.isGenericTypeConcrete(templateGenericType, processorSupport) && org.finos.legend.pure.m3.navigation.type.Type.subTypeOf(ImportStub.withImportStubByPass(templateGenericType._rawTypeCoreInstance(), processorSupport), processorSupport.package_getByUserPath(M3Paths.Function), processorSupport))
        {
            ListIterable<? extends VariableExpression> parameters = lambdaFunctionType._parameters().toList();
            for (int j = 0 ; j < parameters.size(); j++)
            {
                VariableExpression param = parameters.get(j);
                if (param._genericType() == null)
                {
                    if (org.finos.legend.pure.m3.navigation.type.Type.isBottomType(templateFunctionType, processorSupport) || org.finos.legend.pure.m3.navigation.type.Type.isTopType(templateFunctionType, processorSupport))
                    {
                        throw new PureCompilationException(lambdaFunction.getSourceInformation(), "Can't infer the parameters' types for the lambda. Please specify it in the signature.");
                    }
                    VariableExpression templateParam = ((FunctionType) Objects.requireNonNull(templateFunctionType))._parameters().toList().get(j);
                    CoreInstance genericType = org.finos.legend.pure.m3.navigation.generictype.GenericType.makeTypeArgumentAsConcreteAsPossible(templateParam._genericType(), state.getTypeInferenceContext().getTypeParameterToGenericType(), state.getTypeInferenceContext().getMultiplicityParameterToMultiplicity(), processorSupport);
                    if (state.getTypeInferenceContext().isTypeParameterResolved(genericType))
                    {
                        genericType = state.getTypeInferenceContext().resolve(genericType);
                    }
                    else
                    {
                        return true;
                    }
                    CoreInstance multiplicity = org.finos.legend.pure.m3.navigation.multiplicity.Multiplicity.makeMultiplicityAsConcreteAsPossible(templateParam._multiplicity(), state.getTypeInferenceContext().getMultiplicityParameterToMultiplicity());
                    param._genericType((GenericType)org.finos.legend.pure.m3.navigation.generictype.GenericType.copyGenericTypeAsInferredGenericType(genericType, param.getSourceInformation(), processorSupport));
                    param._multiplicity((Multiplicity)org.finos.legend.pure.m3.navigation.multiplicity.Multiplicity.copyMultiplicity(multiplicity, param.getSourceInformation(), processorSupport));
                }
            }
            state.pushVariableContext();
            FunctionDefinitionProcessor.process(lambdaFunction, state, matcher, repository);
            LambdaFunctionProcessor.process(lambdaFunction, state, matcher, repository);
            state.popVariableContext();
        }
        else
        {
            throw new PureCompilationException(lambdaFunction.getSourceInformation(), "Can't infer the parameters' types for the lambda. Please specify it in the signature.");
        }

        instanceValueContainer._genericTypeRemove();
        InstanceValueProcessor.updateInstanceValue(instanceValueContainer, processorSupport);
        return false;
    }

    public static void potentiallyUpdateParentTypeParamForInstanceValueWithManyElements(InstanceValue instance, final TypeInferenceContext typeInferenceContext, final ProcessorState state, ProcessorSupport processorSupport)
    {
        MutableList<TypeInferenceContextState> set = typeInferenceContext.drop(instance._values().size());

        if (typeInferenceContext.getParent() != null)
        {
            final TypeInferenceContextState nonInstanceSpecificState = set.get(0);
            RichIterable<TypeInferenceContextState> instanceStates = ListHelper.tail(set);

            // Accumulate changes so as not to modify during iteration
            MutableMap<GenericType, GenericType> toRegisterTypes = Maps.mutable.empty();

            for (String typeParam : nonInstanceSpecificState.getTypeParameters())
            {
                CoreInstance possibleParentTypeParam = nonInstanceSpecificState.getTypeParameterValue(typeParam);
                if (!org.finos.legend.pure.m3.navigation.generictype.GenericType.isGenericTypeConcrete(possibleParentTypeParam, processorSupport))
                {
                    MutableList<CoreInstance> allGenericTypes = FastList.newList();
                    for (TypeInferenceContextState v : instanceStates)
                    {
                        allGenericTypes.add(v.getTypeParameterValue(typeParam));
                    }
                    CoreInstance res = org.finos.legend.pure.m3.navigation.generictype.GenericType.findBestCommonGenericType(allGenericTypes, org.finos.legend.pure.m3.navigation.typeparameter.TypeParameter.isCovariant(possibleParentTypeParam), false, processorSupport);
                    toRegisterTypes.put((GenericType)possibleParentTypeParam, (GenericType)res);
                }
            }

            // Accumulate changes so as not to modify during iteration
            MutableMap<Multiplicity, Multiplicity> toRegisterMultiplicities = Maps.mutable.empty();

            for (String multiplicityParam : nonInstanceSpecificState.getMultiplicityParameters())
            {
                CoreInstance possibleParentMultiplicityTypeParam = nonInstanceSpecificState.getMultiplicityParameterValue(multiplicityParam);
                if (!org.finos.legend.pure.m3.navigation.multiplicity.Multiplicity.isMultiplicityConcrete(possibleParentMultiplicityTypeParam) && !instanceStates.isEmpty())
                {
                    CoreInstance res = instanceStates.getFirst().getMultiplicityParameterValue(multiplicityParam);
                    for (TypeInferenceContextState v : instanceStates)
                    {
                        res = org.finos.legend.pure.m3.navigation.multiplicity.Multiplicity.minSubsumingMultiplicity(res, v.getMultiplicityParameterValue(multiplicityParam), processorSupport);
                    }
                    toRegisterMultiplicities.put((Multiplicity)possibleParentMultiplicityTypeParam, (Multiplicity)res);
                }
            }

            toRegisterTypes.forEachKeyValue(new Procedure2<GenericType, GenericType>()
            {
                @Override
                public void value(GenericType from, GenericType to)
                {
                    typeInferenceContext.getParent().register(from, to, typeInferenceContext.getParent(), state.getObserver());
                }
            });

            toRegisterMultiplicities.forEachKeyValue(new Procedure2<Multiplicity, Multiplicity>()
            {
                @Override
                public void value(Multiplicity from, Multiplicity to)
                {
                    typeInferenceContext.getParent().registerMul(from, to, typeInferenceContext.getParent(), state.getObserver());
                }
            });
        }
    }

    public static void mapSpecToInstance(ListIterable<? extends VariableExpression> parameters, ListIterable<? extends ValueSpecification> wrappedParameterValues, Stack<MutableMap<String, CoreInstance>> resolvedTypeParameters, Stack<MutableMap<String, CoreInstance>> resolvedMultiplicityParameters, final ProcessorSupport processorSupport)
    {
        ListIterable<? extends Pair<? extends VariableExpression, ? extends ValueSpecification>> zipped = parameters.zip(wrappedParameterValues);
        ListIterable<Pair<GenericType, GenericType>> genericTypes = zipped.collect(new org.eclipse.collections.api.block.function.Function<Pair<? extends VariableExpression, ? extends ValueSpecification>, Pair<GenericType, GenericType>>()
        {

            @Override
            public Pair<GenericType, GenericType> valueOf(Pair<? extends VariableExpression, ? extends ValueSpecification> pair)
            {
                return Tuples.pair(pair.getOne()._genericType(), pair.getTwo()._genericType());
            }
        });
        ListIterable<Pair<Multiplicity, Multiplicity>> multiplicities = zipped.collect(new org.eclipse.collections.api.block.function.Function<Pair<? extends VariableExpression, ? extends ValueSpecification>, Pair<Multiplicity, Multiplicity>>()
        {

            @Override
            public Pair<Multiplicity, Multiplicity> valueOf(Pair<? extends VariableExpression, ? extends ValueSpecification> pair)
            {
                return Tuples.pair(pair.getOne()._multiplicity(), pair.getTwo()._multiplicity());
            }
        });

        mapSpecToInstanceSub(genericTypes, multiplicities, resolvedTypeParameters, resolvedMultiplicityParameters, processorSupport);
    }

    public static void mapSpecToInstanceSub(ListIterable<Pair<GenericType, GenericType>> types, ListIterable<Pair<Multiplicity, Multiplicity>> multiplicities, final Stack<MutableMap<String, CoreInstance>> resolvedTypeParameters, final Stack<MutableMap<String, CoreInstance>> resolvedMultiplicityParameters, final ProcessorSupport processorSupport)
    {
        final MutableMap<String, CoreInstance> rtypes = UnifiedMap.newMap();
        final MutableMap<String, CoreInstance> rmultiplicities = UnifiedMap.newMap();
        types.forEach(new Procedure<Pair<GenericType, GenericType>>()
        {
            @Override
            public void value(Pair<GenericType, GenericType> pair)
            {
                resolveTypeParameters(pair.getOne(), pair.getTwo(), rtypes, rmultiplicities, resolvedTypeParameters.peek(), resolvedMultiplicityParameters.peek(), processorSupport);
            }
        });
        multiplicities.forEach(new Procedure<Pair<Multiplicity, Multiplicity>>()
        {
            @Override
            public void value(Pair<Multiplicity, Multiplicity> pair)
            {
                resolveMultiplicityParameters(pair.getOne(), pair.getTwo(), rmultiplicities, resolvedMultiplicityParameters.peek(), processorSupport);
            }
        });
        resolvedTypeParameters.push(rtypes.asUnmodifiable());
        resolvedMultiplicityParameters.push(rmultiplicities.asUnmodifiable());
    }

    private static void resolveTypeParameters(GenericType template, GenericType fromInstance, final MutableMap<String, CoreInstance> rtypes, final MutableMap<String, CoreInstance> rmultiplicities, final MutableMap<String, CoreInstance> types, final MutableMap<String, CoreInstance> multiplicities, final ProcessorSupport processorSupport)
    {
        GenericType g = (GenericType)org.finos.legend.pure.m3.navigation.generictype.GenericType.makeTypeArgumentAsConcreteAsPossible(org.finos.legend.pure.m3.navigation.generictype.GenericType.copyGenericType(fromInstance, false, processorSupport), types, multiplicities, processorSupport);
        Type rawType = template._rawType();
        if (rawType != null)
        {
            if (org.finos.legend.pure.m3.navigation.type.Type.subTypeOf(rawType, processorSupport.package_getByUserPath(M3Paths.Function), processorSupport))
            {
                if (template._typeArguments().getFirst() != null && g._typeArguments().getFirst() != null)
                {
                    CoreInstance fTypR = template._typeArguments().getFirst()._rawType();
                    CoreInstance sTypeR = g._typeArguments().getFirst()._rawType();
                    if (fTypR instanceof FunctionType &&  sTypeR instanceof FunctionType)
                    {
                        FunctionType fType = (FunctionType)fTypR;
                        FunctionType sType = (FunctionType)sTypeR;
                        resolveTypeParameters(fType._returnType(), sType._returnType(), rtypes, rmultiplicities, types, multiplicities, processorSupport);
                        resolveMultiplicityParameters(fType._returnMultiplicity(), sType._returnMultiplicity(), rmultiplicities, multiplicities, processorSupport);
                        fType._parameters().zip(sType._parameters()).forEach(new Procedure<Pair<? extends VariableExpression, ? extends VariableExpression>>()
                        {
                            @Override
                            public void value(Pair<? extends VariableExpression, ? extends VariableExpression> pair)
                            {
                                resolveTypeParameters(pair.getOne()._genericType(), pair.getTwo()._genericType(), rtypes, rmultiplicities, types, multiplicities, processorSupport);
                                resolveMultiplicityParameters(pair.getOne()._multiplicity(), pair.getTwo()._multiplicity(), rmultiplicities, multiplicities, processorSupport);
                            }
                        });
                    }
                }
            }
        }
        else
        {
            rtypes.put(template._typeParameter()._name(), g);
        }
        template._typeArguments().zip(g._typeArguments()).forEach(new Procedure<Pair<? extends GenericType, ? extends GenericType>>()
        {
            @Override
            public void value(Pair<? extends GenericType, ? extends GenericType> pair)
            {
                resolveTypeParameters(pair.getOne(), pair.getTwo(), rtypes, rmultiplicities, types, multiplicities, processorSupport);
            }
        });
        template._multiplicityArguments().zip(g._multiplicityArguments()).forEach(new Procedure<Pair<? extends Multiplicity, ? extends Multiplicity>>()
        {
            @Override
            public void value(Pair<? extends Multiplicity, ? extends Multiplicity> pair)
            {
                resolveMultiplicityParameters(pair.getOne(), pair.getTwo(), rmultiplicities, multiplicities, processorSupport);
            }
        });
    }

    private static void resolveMultiplicityParameters(Multiplicity template, Multiplicity fromInstance, MutableMap<String, CoreInstance> rmultiplicities, final MutableMap<String, CoreInstance> multiplicities, final ProcessorSupport processorSupport)
    {
        Multiplicity newMul;
        if (!org.finos.legend.pure.m3.navigation.multiplicity.Multiplicity.isMultiplicityConcrete(fromInstance))
        {
            newMul = (Multiplicity)multiplicities.get(org.finos.legend.pure.m3.navigation.multiplicity.Multiplicity.getMultiplicityParameter(fromInstance));
            if (fromInstance == null)
            {
                throw new PureExecutionException("Cannot resolve multiplicity parameter: ");
            }
        }
        else
        {
            newMul = fromInstance;
        }
        if (template._multiplicityParameter() != null)
        {
            rmultiplicities.put(template._multiplicityParameter(), newMul);
        }
    }

    public static String print(Stack<MutableMap<String, CoreInstance>> s, final ProcessorSupport processorSupport)
    {
        return FastList.newList(s).collect(new org.eclipse.collections.api.block.function.Function<MutableMap<String, CoreInstance>, String>()
        {
            @Override
            public String valueOf(MutableMap<String, CoreInstance> coreInstances)
            {
                return print(coreInstances, processorSupport);
            }
        }).makeString("[", ",", "]");

    }

    public static String print(MutableMap<String, CoreInstance> v, final ProcessorSupport processorSupport)
    {

        return v.keyValuesView().collect(new org.eclipse.collections.api.block.function.Function<Pair<String, CoreInstance>, String>()
        {
            @Override
            public String valueOf(Pair<String, CoreInstance> stringCoreInstancePair)
            {
                return stringCoreInstancePair.getOne() + " -> " + (stringCoreInstancePair.getTwo() instanceof org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.generics.GenericType ? org.finos.legend.pure.m3.navigation.generictype.GenericType.print(stringCoreInstancePair.getTwo(), processorSupport) :
                        org.finos.legend.pure.m3.navigation.multiplicity.Multiplicity.print(stringCoreInstancePair.getTwo()));
            }
        }).makeString("(", ",", ")");

    }
}
