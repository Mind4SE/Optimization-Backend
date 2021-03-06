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

import static org.ow2.mind.PathHelper.replaceExtension;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.objectweb.fractal.adl.ADLException;
import org.objectweb.fractal.adl.CompilerError;
import org.objectweb.fractal.adl.error.GenericErrors;
import org.ow2.mind.adl.ast.OptimASTHelper;
import org.ow2.mind.adl.ast.ImplementationContainer;
import org.ow2.mind.adl.ast.Source;
import org.ow2.mind.compilation.CompilationCommand;
import org.ow2.mind.compilation.CompilerCommand;
import org.ow2.mind.compilation.PreprocessorCommand;
import org.ow2.mind.preproc.MPPCommand;

public class OptimizedInstanceCompiler extends BasicInstanceCompiler {

	// ---------------------------------------------------------------------------
	// Implementation of the Visitor interface
	// ---------------------------------------------------------------------------

	@Override
	public Collection<CompilationCommand> visit(
			final InstancesDescriptor instanceDesc, final Map<Object, Object> context)
					throws ADLException {

		instanceSourceGeneratorItf.visit(instanceDesc, context);
		final String instancesFileName = BasicInstanceSourceGenerator
				.getInstancesFileName(instanceDesc);

		final File srcFile = outputFileLocatorItf.getCSourceOutputFile(
				instancesFileName, context);
		if (!srcFile.exists()) {
			throw new CompilerError(GenericErrors.INTERNAL_ERROR,
					"Can't find source file \"" + instancesFileName + "\"");
		}

		Collection<File> dependencies = null;
		if (instanceDesc.instanceDefinition instanceof ImplementationContainer) {
			final Source sources[] = ((ImplementationContainer) instanceDesc.instanceDefinition)
					.getSources();
			if (sources.length == 1) {
				dependencies = new ArrayList<File>();
				dependencies.add(outputFileLocatorItf.getCSourceTemporaryOutputFile(
						ImplementationHeaderSourceGenerator
						.getImplHeaderFileName(instanceDesc.instanceDefinition),
						context));
			} else if (sources.length > 1) {
				dependencies = new ArrayList<File>();
				for (int i = 0; i < sources.length; i++) {
					final Source src = sources[i];

					// SSZ
					Boolean raw = (Boolean) src.astGetDecoration("raw");
					raw = ((raw != null) && raw); // null or false => false

					if (!OptimASTHelper.isPreCompiled(src) && !OptimASTHelper.isAssembly(src) && (!raw)) {
						dependencies.add(outputFileLocatorItf
								.getCSourceTemporaryOutputFile(
										ImplementationHeaderSourceGenerator.getImplHeaderFileName(
												instanceDesc.instanceDefinition, i), context));
					}
				}
			}
		}

		final File cppFile = outputFileLocatorItf.getCSourceTemporaryOutputFile(
				replaceExtension(instancesFileName, ".i"), context);
		final File mppFile = outputFileLocatorItf.getCSourceTemporaryOutputFile(
				replaceExtension(instancesFileName, ".mpp.c"), context);
		final File objectFile = outputFileLocatorItf.getCCompiledOutputFile(
				replaceExtension(instancesFileName, ".mpp.o"), context);
		final File depFile = outputFileLocatorItf.getCCompiledOutputFile(
				replaceExtension(instancesFileName, ".d"), context);

		final PreprocessorCommand cppCommand = compilationCommandFactory
				.newPreprocessorCommand(instanceDesc.instanceDefinition, null, srcFile,
						dependencies, depFile, cppFile, context);
		final MPPCommand mppCommand = compilationCommandFactory.newMPPCommand(
				instanceDesc.instanceDefinition, null, cppFile, mppFile, null, context);

		final CompilerCommand gccCommand = compilationCommandFactory
				.newCompilerCommand(instanceDesc.instanceDefinition, null, mppFile,
						true, null, null, objectFile, context);

		// SSZ : BEGIN MODIFICATION
		//	    gccCommand.addIncludeFile(outputFileLocatorItf.getCSourceOutputFile(
		//	            DefinitionMacroSourceGenerator
		//	                .getMacroFileName(instanceDesc.instanceDefinition), context));

		gccCommand.addIncludeFile(outputFileLocatorItf.getCSourceOutputFile(
				InstanceMacroSourceGenerator
				.getMacroFileName(instanceDesc), context));
		// SSZ : END MODIFICATION
		gccCommand.setAllDependenciesManaged(true);

		final List<CompilationCommand> compilationTasks = new ArrayList<CompilationCommand>();
		compilationTasks.add(cppCommand);
		compilationTasks.add(mppCommand);
		compilationTasks.add(gccCommand);

		return compilationTasks;
	}

}
