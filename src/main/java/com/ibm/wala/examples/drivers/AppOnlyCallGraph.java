/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.examples.drivers;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IClassLoader;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.examples.util.ExampleUtil;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.CallGraphStats;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.cha.CHACallGraph;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.util.io.CommandLine;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.core.util.strings.StringStuff;
import com.ibm.wala.core.util.warnings.Warnings;

/**
 * Driver that constructs a call graph for an application specified via a scope file.  
 * Useful for getting some code to copy-paste.    
 */
public class AppOnlyCallGraph {

  /**
   * Usage: AppOnlyCallGraph -scopeFile file_path [-entryClass class_name |
   * -mainClass class_name] -out output_file
   * 
   * If given -mainClass, uses main() method of class_name as entrypoint. If
   * given -entryClass, uses all public methods of class_name.
   * 
   * @throws IOException
   * @throws ClassHierarchyException
   * @throws CallGraphBuilderCancelException
   * @throws IllegalArgumentException
   */
  public static void main(String[] args) throws IOException, ClassHierarchyException, IllegalArgumentException,
      CancelException {
    long start = System.currentTimeMillis();
    for(int i = 0; i < args.length; i++)
	System.out.println("ARG " + i + ": \"" + args[i] + "\"");
    Properties p = CommandLine.parse(args);
    String scopeFile = p.getProperty("scopeFile");
    String entryClass = p.getProperty("entryClass");
    String mainClass = p.getProperty("mainClass");
    if (mainClass != null && entryClass != null) {
      throw new IllegalArgumentException("only specify one of mainClass or entryClass");
    }
    
    String outputPath = p.getProperty("out");
    System.out.println("outputPath: " + outputPath);
    FileOutputStream out;
    try {
	File outFile = new File(outputPath);
	if(!outFile.exists()) outFile.createNewFile();
	out = new FileOutputStream(outFile);
    } catch (Exception e) {
	throw new IllegalArgumentException("must specify valid output file as \"-out /path/to/output/file\"");
    }
    
    AnalysisScope scope = AnalysisScopeReader.instance.readJavaScope(scopeFile, null, ScopeFileCallGraph.class.getClassLoader());
    // set exclusions.  we use these exclusions as standard for handling JDK 8
    ExampleUtil.addDefaultExclusions(scope);
    IClassHierarchy cha = ClassHierarchyFactory.make(scope);
    System.out.println(cha.getNumberOfClasses() + " classes");
    System.out.println(Warnings.asString());
    Warnings.clear();
    // AnalysisOptions options = new AnalysisOptions();
    // String classString = StringStuff.deployment2CanonicalTypeString(entryClass != null ? entryClass : mainClass);
    // Iterable<Entrypoint> entrypoints =
	// entryClass != null
	// ? makePublicEntrypoints(scope, cha, classString)
	// : Util.makeMainEntrypoints(scope, cha, classString);
    // options.setEntrypoints(entrypoints);
    // you can dial down reflection handling if you like
    // options.setReflectionOptions(AnalysisOptions.ReflectionOptions.NONE);
    AnalysisCache cache = new AnalysisCacheImpl();
    // other builders can be constructed with different Util methods
    // TODO(benno): Manu says to use CHACallGraph if we run into scaling issues with 0-1-container-CFA? doesn't need entrypoints and scales much better
    // CallGraphBuilder builder = Util.makeNCFABuilder(0, options, cache, cha, scope);
    //    CallGraphBuilder builder = Util.makeVanillaNCFABuilder(2, options, cache, cha, scope);
    //    CallGraphBuilder builder = Util.makeZeroOneContainerCFABuilder(options, cache, cha, scope);
    System.out.println("building call graph...");
        // CallGraph cg = builder.makeCallGraph(options, null);
    CHACallGraph cg = new CHACallGraph(cha);
    cg.init(new AllApplicationEntrypoints(scope, cha));
    for (CGNode n : cg) {
	if(n.getMethod().getDeclaringClass().getClassLoader().getReference().equals(ClassLoaderReference.Application)) {
	    java.util.Iterator<CGNode> callees = cg.getSuccNodes(n);
	    Collection<CGNode> appCallees = new ArrayList<CGNode>();
	    while (callees.hasNext()) {
		CGNode callee = callees.next();
		if(callee.getMethod().getDeclaringClass().getClassLoader().getReference().equals(ClassLoaderReference.Application))
		    appCallees.add(callee);
	    }
	    if(appCallees.isEmpty()) continue;
	    out.write("CALLER: ".getBytes());
	    serializeCGNode(n, out);
	    for(CGNode callee : appCallees){
		if(callee.getMethod().getDeclaringClass().getClassLoader().getReference().equals(ClassLoaderReference.Application)) {
		    out.write("\tCALLEE: ".getBytes());
		    serializeCGNode(callee, out);
		}
	    }
	    out.flush();
	}
    }
    out.close();
    long end = System.currentTimeMillis();
    System.out.println("done");
    System.out.println("took " + (end-start) + "ms");
    System.out.println(CallGraphStats.getStats(cg));
  }

  private static Iterable<Entrypoint> makePublicEntrypoints(AnalysisScope scope, IClassHierarchy cha, String entryClass) {
    Collection<Entrypoint> result = new ArrayList<Entrypoint>();
    IClass klass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, entryClass));
    if (klass == null) return result;
    for (IMethod m : klass.getDeclaredMethods()) {
      if (m.isPublic()) {
        result.add(new DefaultEntrypoint(m, cha));
      }
    }
    return result;
  }
    
    /* Convert a WALA CGNode into a string containing only that info needed to recover the corresponding DAI Method_id.t
       Produces output in the following grammar, i.e. something like "com.example.ClassName#methodName(fully.qualified.Arg1Type, fully.qualified.Arg2Type, ...)"
:

       output ::= <static_opt> <package>.<class_name>#<method_name>(<arg_types>)

       static_opt ::= static | ε

       package ::= lowercase_ident | <package>.lowercase_ident

       class_name ::= uppercase_ident

       method_name ::= lowercase_ident

       arg_types ::= <type> | <arg_types>, <type>

       type ::= <type>[] | <package>.uppercase_ident | primitive_type

       (where `primitive_type` and `(upper|lower)case_ident` are as in Java source-language syntax)

     */
    private static void serializeCGNode(CGNode n, FileOutputStream out) throws IOException {
	StringBuilder builder = new StringBuilder(128); // Initial buffer size of 128 characters
	
	// First append "static " if needed, then the declaring class' TypeName followed by a "#" separator
	if (n.getMethod().isStatic())
	    builder.append("static ");
	IClass klass = n.getMethod().getDeclaringClass();
	builder.append(StringStuff.jvmToReadableType(klass.getName().toString()));
	builder.append("#");

	// Next, add the method name followed by an open-parenthesis
	MethodReference meth = n.getMethod().getReference();
	builder.append(meth.getName());
	builder.append("(");

	//Then (comma-separated) argument types, followed by a close-parenthesis and newline
	int numParams = meth.getNumberOfParameters();
	for(int i = 0; i < numParams; i++) {
	    TypeReference paramType = meth.getParameterType(i);
	    builder.append(StringStuff.jvmToReadableType(paramType.getName().toString()));
	    if( i + 1 < numParams ) builder.append(",");
	    }
	builder.append(")\n");

	//Write the full serialized CGNode to the output stream
	byte[] serialized =  builder.toString().getBytes();
	out.write(serialized);
    }
}
