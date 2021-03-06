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
 * Contact: mind@ow2.org
 *
 * Authors: Stephane Seyvoz, Assystem (for Schneider-Electric)
 * Contributors: 
 */

package org.ow2.mind.adl;

import static org.ow2.mind.PathHelper.fullyQualifiedNameToPath;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.antlr.stringtemplate.StringTemplate;
import org.objectweb.fractal.adl.ADLException;
import org.objectweb.fractal.adl.CompilerError;
import org.objectweb.fractal.adl.Definition;
import org.objectweb.fractal.adl.interfaces.Interface;
import org.objectweb.fractal.adl.interfaces.InterfaceContainer;
import org.objectweb.fractal.adl.types.TypeInterface;
import org.ow2.mind.SourceFileWriter;
import org.ow2.mind.adl.graph.ComponentGraph;
import org.ow2.mind.adl.membrane.ast.InternalInterfaceContainer;
import org.ow2.mind.idl.IDLLoader;
import org.ow2.mind.idl.ast.IDL;
import org.ow2.mind.idl.ast.InterfaceDefinition;
import org.ow2.mind.io.IOErrors;

import com.google.inject.Inject;
import com.google.inject.name.Named;

/**
 * {@link DefinitionSourceGenerator} component that generated {@value #FILE_EXT}
 * files using the {@value #DEFAULT_TEMPLATE} template.
 */
public class OptimizedDefinitionHeaderSourceGenerator extends AbstractSourceGenerator implements DefinitionSourceGenerator {

	/** The name to be used to inject the templateGroupName used by this class. */
	public static final String    TEMPLATE_NAME    = "optim.definitions.header";

	/** The default templateGroupName used by this class. */
	public static final String    DEFAULT_TEMPLATE = "st.optim.definitions.header.Component";

	protected final static String FILE_EXT         = ".adl.h";

	@Inject
	protected IDLLoader idlLoaderItf;

	@Inject
	protected OptimizedDefinitionHeaderSourceGenerator(
			@Named(TEMPLATE_NAME) final String templateGroupName) {
		super(templateGroupName);
	}

	/**
	 * A static method that returns the name of the file that is generated by this
	 * component for the given {@link Definition};
	 * 
	 * @param definition a {@link Definition} node.
	 * @return the name of the file that is generated by this component for the
	 *         given {@link Definition};
	 */
	public static String getHeaderFileName(final Definition definition) {
		return fullyQualifiedNameToPath(definition.getName(), FILE_EXT);
	}

	// ---------------------------------------------------------------------------
	// Implementation of the DefinitionSourceGenerator interface
	// ---------------------------------------------------------------------------

	public void visit(final Definition definition,
			final Map<Object, Object> context) throws ADLException {
		final File outputFile = outputFileLocatorItf.getCSourceOutputFile(
				getHeaderFileName(definition), context);

		if (regenerate(outputFile, definition, context)) {

			decorateClientInterfacesWithAccordingInterfaceDefinition(definition,
					context);
			decorateInternalClientInterfacesWithAccordingInterfaceDefinition(definition,
					context);

			final StringTemplate st = getInstanceOf("ComponentDefinitionHeader");
			st.setAttribute("definition", definition);

			// SSZ : BEGIN MODIFICATION

			//ComponentGraph topLevelGraph = (ComponentGraph) context.get("topLevelGraph");
			ComponentGraph instanceGraph = (ComponentGraph) context.get("currentInstanceGraph");

			// /!\ 	HERE WE CONSIDER AS A PRE-CONDITION THAT WE WILL HAVE
			// 		ONLY ONE AND ONLY ONE INSTANCE PER DEFINITION 			/!\
			st.setAttribute("instance", instanceGraph);

			//topLevelGraph = null;
			//instanceGraph = null;

			// Very important detail here : If we found an unbound interface, it's binding will be "null" and would make the "existsNonStaticBinding" test
			// crash with NullPointerException, but the test WON'T even be entered if there's an unbound client interface so it WILL NOT crash.
			//
			// The same goes for collection binding because if ONE element of the collection is not bound, a binding with no destination would be used : NPE.
			/*			if (!(existsOptionalUnboundClientInterface(instanceGraph) || existsCollectionInterface(instanceGraph) || existsNonStaticBinding(instanceGraph))) {
				System.out.println("Current component has all his optional interfaces bound and all bindings are static");
				setAllowTypeDataRemovalDecoration(instanceGraph);
			} else { 
				System.out.println("Current component has at least an unbound optional client interface or a non-static binding");
			}*/
			// SSZ : END MODIFICATION

			try {
				SourceFileWriter.writeToFile(outputFile, st.toString());
			} catch (final IOException e) {
				throw new CompilerError(IOErrors.WRITE_ERROR, e,
						outputFile.getAbsolutePath());
			}
		}
	}

	/**
	 * This utility method allows us to find the method definitions to be used
	 * for collections optimization, to allow defining function pointers arrays.
	 * 
	 * @param definition
	 * @param context
	 * @throws ADLException when a server interface signature can't be loaded
	 */
	private void decorateClientInterfacesWithAccordingInterfaceDefinition(
			final Definition definition, final Map<Object, Object> context)
					throws ADLException {

		// defensive
		if (!(definition instanceof InterfaceContainer)) return;

		final InterfaceContainer itfContainer = (InterfaceContainer) definition;

		for (final Interface currItf : itfContainer.getInterfaces()) {
			if (!(currItf instanceof TypeInterface)) continue;
			final TypeInterface currTypeItf = (TypeInterface) currItf;

			// found one
			if (currTypeItf.getRole().equals(TypeInterface.CLIENT_ROLE)) {

				// load according InterfaceDefinition
				final IDL currItfIDL = idlLoaderItf.load(currTypeItf.getSignature(),
						context);

				if (currItfIDL instanceof InterfaceDefinition) {
					final InterfaceDefinition currItfItfDef = (InterfaceDefinition) currItfIDL;

					// decorate our instance
					currItf.astSetDecoration("interfaceDefinition", currItfItfDef);
				}
			}
		}
	}
	
	/**
	 * This utility method allows us to find the method definitions to be used
	 * for collections optimization, to allow defining function pointers arrays.
	 * This method is the same as decorateClientInterfacesWithAccordingInterfaceDefinition
	 * except that it's used for Composites internal client interfaces (server
	 * interfaces duals for delegation).
	 * 
	 * 
	 * @param definition
	 * @param context
	 * @throws ADLException when a server interface signature can't be loaded
	 */
	private void decorateInternalClientInterfacesWithAccordingInterfaceDefinition(
			final Definition definition, final Map<Object, Object> context)
					throws ADLException {

		// defensive
		if (!(definition instanceof InternalInterfaceContainer)) return;

		final InternalInterfaceContainer itfContainer = (InternalInterfaceContainer) definition;

		for (final Interface currItf : itfContainer.getInternalInterfaces()) {
			if (!(currItf instanceof TypeInterface)) continue;
			final TypeInterface currTypeItf = (TypeInterface) currItf;

			// found one
			if (currTypeItf.getRole().equals(TypeInterface.CLIENT_ROLE)) {

				// load according InterfaceDefinition
				final IDL currItfIDL = idlLoaderItf.load(currTypeItf.getSignature(),
						context);

				if (currItfIDL instanceof InterfaceDefinition) {
					final InterfaceDefinition currItfItfDef = (InterfaceDefinition) currItfIDL;

					// decorate our instance
					currItf.astSetDecoration("interfaceDefinition", currItfItfDef);
				}
			}
		}
	}
}
