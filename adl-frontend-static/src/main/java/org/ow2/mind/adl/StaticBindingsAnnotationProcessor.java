/**
 * Copyright (C) 2012 Schneider-Electric
 *
 * This file is part of "Mind Compiler" is free software: you can redistribute 
 * it and/or modify it under the terms of the GNU Lesser General Public License 
 * as published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT 
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Contact: mind@ow2.org, sseyvoz@assystem.com
 *
 * Authors: Stephane Seyvoz, Assystem (for Schneider-Electric)
 * Contributors: 
 */

package org.ow2.mind.adl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.objectweb.fractal.adl.ADLException;
import org.objectweb.fractal.adl.Definition;
import org.objectweb.fractal.adl.Node;
import org.objectweb.fractal.adl.interfaces.Interface;
import org.objectweb.fractal.adl.interfaces.InterfaceContainer;
import org.ow2.mind.adl.annotation.ADLLoaderPhase;
import org.ow2.mind.adl.annotation.AbstractADLLoaderAnnotationProcessor;
import org.ow2.mind.adl.annotation.predefined.Static;
import org.ow2.mind.adl.annotation.predefined.StaticBindings;
import org.ow2.mind.adl.ast.ASTHelper;
import org.ow2.mind.adl.ast.Binding;
import org.ow2.mind.adl.ast.BindingContainer;
import org.ow2.mind.adl.ast.Component;
import org.ow2.mind.adl.ast.ComponentContainer;
import org.ow2.mind.adl.ast.OptimASTHelper;
import org.ow2.mind.adl.membrane.ast.Controller;
import org.ow2.mind.adl.membrane.ast.ControllerContainer;
import org.ow2.mind.adl.membrane.ast.ControllerInterface;
import org.ow2.mind.annotation.Annotation;
import org.ow2.mind.annotation.AnnotationHelper;
import org.ow2.mind.cli.OptimOptionHandler;

public class StaticBindingsAnnotationProcessor extends
AbstractADLLoaderAnnotationProcessor {

	/**
	 * The current binding client component.
	 */
	private Component currentClientCpt = null;

	/**
	 * The current binding server component.
	 */
	private Component currentServerCpt = null;

	/**
	 * The current binding client component definition. 
	 */
	private Definition currentClientDef = null;

	/**
	 * The current binding server component definition.
	 */
	private Definition currentServerDef = null;
	

	public Definition processAnnotation(Annotation annotation, Node node,
			Definition definition, ADLLoaderPhase phase,
			Map<Object, Object> context) throws ADLException {

		/* Preventing from use of @StaticBindings on composites */
		if (!(definition instanceof ComponentContainer)) {
			errorManagerItf.logError(
					OptimADLErrors.INVALID_STATICBINDINGS_NOT_A_COMPOSITE, definition);
			return null;
		}
		
		if ((phase == ADLLoaderPhase.AFTER_CHECKING) || (phase == ADLLoaderPhase.AFTER_TEMPLATE_INSTANTIATE)) {
			
			if (!OptimOptionHandler.isOptimizationBackendEnabled(context))
				logger.warning("@StaticBindings found in '" + definition.getName() + "' definition, but --optimize is missing ! Add it to enable optimizations.");
			
			if (!OptimASTHelper.isSingleton(definition)) {
				errorManagerItf.logError(
						OptimADLErrors.INVALID_STATICBINDINGS_NOT_SINGLETON, definition);
				//logger.warning("Invalid @StaticBindings annotation, definition " + definition.getName() + " must be a @Singleton");
				return null;
			}

			// TODO: Add a check to throw a big Error to the user when using @StaticBindings on
			// a composite containing components hosting a BindingController !
			if (definition instanceof ComponentContainer
					&&	hasStaticBindingsVSBindingControllerIncompatibilities(definition, context, ((StaticBindings) annotation).recursive)) {
				return null;
			}

			// Decorating for StringTemplate generation
			// Used to remove interface structure declarations and its references from the glue
			OptimASTHelper.setStaticBindingsDecoration((ComponentContainer) node);

			// Propagate the Static annotation to all inner bindings
			// The Static annotation will be propagated as Bindings will be treated after Definitions/Components.
			for (final Binding binding : ((BindingContainer) node).getBindings()) {

				/*
				 * For the CPL (ADL backend)
				 * If Binding is not already tagged static, both for simple or collection bindings.
				 * (generated code will use a different strategy though)
				 */
				if (!OptimASTHelper.isStatic(binding)) {
					// This method may throw an exception when there's an error and stop the compilation
					checkSourceDestinationSingletons(definition, binding, context);

					AnnotationHelper.addAnnotation(binding, new Static());
				}

				// We also need to protect "self" bindings (from a component client interfaces to its own server interfaces)
				// (talking about definitions, not instances)
				// since the server methods prototypes are already defined in the .inc file, we do not need to redefine them
				// when handling optimized clients (still in the .inc / implementations/Component.stc) when writing their
				// target function prototype (= redundant declaration = compilation failure)
				// We also filter to check redundant prototype declarations in .inc files
				Definition fromCompDef = null;
				if (!binding.getFromComponent().equals(Binding.THIS_COMPONENT)) {
					Component fromComponent = ASTHelper.getComponent((ComponentContainer) definition, binding.getFromComponent());
					fromCompDef = ASTHelper.getResolvedComponentDefinition(fromComponent, loaderItf, context);
				} else
					fromCompDef = definition;
				
				Definition toCompDef = null;
				if (!binding.getToComponent().equals(Binding.THIS_COMPONENT)) {
					Component toComponent = ASTHelper.getComponent((ComponentContainer) definition, binding.getToComponent());
					toCompDef = ASTHelper.getResolvedComponentDefinition(toComponent, loaderItf, context);
				}
				
				if (fromCompDef.equals(toCompDef))
					binding.astSetDecoration("self-definition-binding-detected", "true");
				
				// Useful to avoid redundant prototypes declarations in .inc files
				Interface toInterfaceInstance = ASTHelper.getInterface((InterfaceContainer) toCompDef, binding.getToInterface());
				Integer toInterfaceIndex = ASTHelper.getToInterfaceNumber(binding);
				DefinitionInterfaceIndexTuple targetTuple = new DefinitionInterfaceIndexTuple(toCompDef, toInterfaceInstance, toInterfaceIndex);
				
				// Should work for collections
				decorateBindingIfTargetAlreadyMetForSourceDefinition(fromCompDef, binding, targetTuple);
			}

			// Please note that the propagation DOES NOT work for sub-components at this stage.
			// Even if we Annotate sub-components, they have ALREADY been taken care of, so we cannot hope for them
			// to be treated if we did that. So we just recursively Annotate Bindings instead of composite
			// sub-components instead.
			// Additional note : recursive is for decorating COMPOSITE sub-components. We decorate primitive sub-components with StaticBindings
			// in any case : it's useful in StringTemplates.
			if (node instanceof Definition && node instanceof ComponentContainer)
				recursiveStaticDecorationPropagation((Definition) node, context, ((StaticBindings) annotation).recursive);

		}

		return definition;
	}

	/*
	 * Check if the current binding has both source and destination as @Singleton, in the current container definition
	 */
	private void checkSourceDestinationSingletons(Definition definition, Binding binding, Map<Object, Object> context) throws ADLException {

		String fromComponent = binding.getFromComponent();
		String toComponent = binding.getToComponent();

		// We don't want to check "this.<myItfName>" internal interfaces as we already know the host definition is Singleton (previously checked in this class)
		if (!fromComponent.equals("this")) {
			//get the client component instance
			currentClientCpt = OptimASTHelper.getComponent(definition, fromComponent);
			//get the client component definition
			currentClientDef = OptimASTHelper.getResolvedComponentDefinition(currentClientCpt, loaderItf, context);

			if (!OptimASTHelper.isSingleton(currentClientDef)) {
				errorManagerItf.logFatal(
						OptimADLErrors.INVALID_STATICBINDINGS_SOURCE_NOT_SINGLETON, binding);
				//logger.warning("Invalid @Static annotation, source of the binding ("+ currentClientCpt.getName() +") must be a @Singleton");
			}
		}
		if (!toComponent.equals("this")) {
			//get the server component instance
			currentServerCpt = OptimASTHelper.getComponent(definition, toComponent);
			//get the server component definition
			currentServerDef = OptimASTHelper.getResolvedComponentDefinition(currentServerCpt, loaderItf, context);

			if (!OptimASTHelper.isSingleton(currentServerDef)){
				errorManagerItf.logFatal(
						OptimADLErrors.INVALID_STATICBINDINGS_DESTINATION_NOT_SINGLETON, binding);
				//logger.warning("Invalid @Static annotation, destination of the binding (" + currentServerCpt.getName() + ") must be a @Singleton");
			}
		}
	}

	/*
	 * We will propagate the Static decoration, not the StaticBinding annotation.
	 * Propagation both is about Bindings and Interfaces : Bindings are used for CALL(s) in the CPL
	 * and Interfaces are used in IDL-Backend (we will remove structures there)
	 */
	private void recursiveStaticDecorationPropagation(Definition definition, Map<Object, Object> context, boolean recursive) throws ADLException{

		Definition subCompDef;

		// For all sub components of our composite
		for (final Component subComp : ((ComponentContainer) definition).getComponents()) {

			// We want to work on the definition, not instance, of the current component.
			// Otherwise we wouldn't know the primitive/composite nature and possible sub-components information
			subCompDef = OptimASTHelper.getResolvedComponentDefinition(subComp, loaderItf, context);

			// For StringTemplates optimization : It's a Decoration, not an Annotation
			if (!OptimASTHelper.hasParentStaticBindings(subCompDef))
				OptimASTHelper.setParentStaticBindingsDecoration(subCompDef);

			// If the component is a composite and the StaticBindings annotation had not been set manually by the user
			if (recursive && (subCompDef instanceof ComponentContainer)){

				if (!OptimASTHelper.hasStaticBindings(subCompDef))
					OptimASTHelper.setStaticBindingsDecoration(subCompDef);

				// Let's propagate the "Static" property to all inner bindings
				// Note : For StringTemplates again, not propagation of Annotation !!
				// (Annotation propagation wouldn't work because of the execution flow)
				for (final Binding binding : ((BindingContainer) subCompDef).getBindings()) {	

					// Both for collection and simple bindings

					// Check if the binding was already static
					if (!OptimASTHelper.isStatic(binding)) {
						// This method may throw an exception when there's an error and stop the compilation
						checkSourceDestinationSingletons(subCompDef, binding, context);
						/*
						 * For CPL optimization
						 */
						OptimASTHelper.setStaticDecoration(binding);
					}

					// We also need to protect "self" bindings (from a component client interfaces to its own server interfaces)
					// (talking about definitions, not instances)
					// since the server methods prototypes are already defined in the .inc file, we do not need to redefine them
					// when handling optimized clients (still in the .inc / implementations/Component.stc) when writing their
					// target function prototype (= redundant declaration = compilation failure)
					// We also filter to check redundant prototype declarations in .inc files
					Definition fromCompDef = null;
					if (!binding.getFromComponent().equals(Binding.THIS_COMPONENT)) {
						Component fromComponent = ASTHelper.getComponent((ComponentContainer) subCompDef, binding.getFromComponent());
						fromCompDef = ASTHelper.getResolvedComponentDefinition(fromComponent, loaderItf, context);
					} else
						fromCompDef = subCompDef;
					
					Definition toCompDef = null;
					if (!binding.getToComponent().equals(Binding.THIS_COMPONENT)) {
						Component toComponent = ASTHelper.getComponent((ComponentContainer) subCompDef, binding.getToComponent());
						toCompDef = ASTHelper.getResolvedComponentDefinition(toComponent, loaderItf, context);
					}
					
					if (fromCompDef.equals(toCompDef))
						binding.astSetDecoration("self-definition-binding-detected", "true");
					
					// Useful to avoid redundant prototypes declarations in .inc files
					Interface toInterfaceInstance = ASTHelper.getInterface((InterfaceContainer) toCompDef, binding.getToInterface());
					Integer toInterfaceIndex = ASTHelper.getToInterfaceNumber(binding);
					DefinitionInterfaceIndexTuple targetTuple = new DefinitionInterfaceIndexTuple(toCompDef, toInterfaceInstance, toInterfaceIndex);
					
					// Should work for collections
					decorateBindingIfTargetAlreadyMetForSourceDefinition(fromCompDef, binding, targetTuple);
				}

				// Now take care of other sub-components in the architecture tree
				recursiveStaticDecorationPropagation(subCompDef, context, recursive);
			}
		}
	}

	private void decorateBindingIfTargetAlreadyMetForSourceDefinition(Definition sourceDefinition, Binding binding, DefinitionInterfaceIndexTuple targetTuple) {
		
		List<DefinitionInterfaceIndexTuple> allTuplesForDefinition = (List<DefinitionInterfaceIndexTuple>) sourceDefinition.astGetDecoration("already-met-target-definition-interface-pairs");
		if (allTuplesForDefinition == null) {
			allTuplesForDefinition = new ArrayList<DefinitionInterfaceIndexTuple>();
			sourceDefinition.astSetDecoration("already-met-target-definition-interface-pairs", allTuplesForDefinition); // track the referenced object
			allTuplesForDefinition.add(targetTuple);
			return;
		}
		
		if (allTuplesForDefinition.contains(targetTuple))
			binding.astSetDecoration("skip-redundant-declaration", true);
	}

	private boolean hasStaticBindingsVSBindingControllerIncompatibilities(Definition definition, Map<Object, Object> context, boolean recursive) throws ADLException{

		Definition subCompDef;
		boolean result = false;

		// For all sub components of our composite
		for (final Component subComp : ((ComponentContainer) definition).getComponents()) {

			// We want to work on the definition, not instance, of the current component.
			// Otherwise we wouldn't know the primitive/composite nature and possible sub-components information
			subCompDef = OptimASTHelper.getResolvedComponentDefinition(subComp, loaderItf, context);

			if (subCompDef instanceof ControllerContainer) {
				ControllerContainer ctrlContainer = (ControllerContainer) subCompDef;
				Controller subHostedCtrlrs[] = ctrlContainer.getControllers();

				for (Controller currentCtrlr : subHostedCtrlrs) {
					ControllerInterface ctrlrItfs[] = currentCtrlr.getControllerInterfaces();
					for (ControllerInterface currentCtrlrItf : ctrlrItfs){
						if (currentCtrlrItf.getName().equals("bindingController")) {
							errorManagerItf.logError(OptimADLErrors.INVALID_STATICBINDINGS_HOSTING_BINDINGCONTROLLER, subCompDef);
							result = true;
						}
					}
				}
			}

			// Now take care of current sub-component sub-components in the architecture tree
			if (recursive && (subCompDef instanceof ComponentContainer))
				hasStaticBindingsVSBindingControllerIncompatibilities(subCompDef, context, recursive);
		}
		return result;
	}
	
}
