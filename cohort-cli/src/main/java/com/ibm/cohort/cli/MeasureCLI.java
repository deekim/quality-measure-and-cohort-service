/*
 * (C) Copyright IBM Corp. 2020, 2021
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.cohort.cli;

import java.io.File;
import java.io.PrintStream;
import java.util.List;
import java.util.zip.ZipFile;

import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Measure;
import org.hl7.fhir.r4.model.MeasureReport;
import org.opencds.cqf.common.providers.LibraryResolutionProvider;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.internal.Console;
import com.beust.jcommander.internal.DefaultConsole;
import com.ibm.cohort.cli.input.MeasureContextProvider;
import com.ibm.cohort.cli.input.NoSplittingSplitter;
import com.ibm.cohort.engine.DirectoryResourceResolutionProvider;
import com.ibm.cohort.engine.helpers.FileHelpers;
import com.ibm.cohort.engine.measure.MeasureContext;
import com.ibm.cohort.engine.measure.MeasureEvaluator;
import com.ibm.cohort.engine.measure.MeasureResolutionProvider;
import com.ibm.cohort.engine.measure.ResourceResolutionProvider;
import com.ibm.cohort.engine.measure.RestFhirLibraryResolutionProvider;
import com.ibm.cohort.engine.measure.RestFhirMeasureResolutionProvider;
import com.ibm.cohort.engine.measure.ZipResourceResolutionProvider;
import com.ibm.cohort.engine.measure.evidence.MeasureEvidenceOptions;
import com.ibm.cohort.fhir.client.config.FhirClientBuilder;

import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.client.api.IGenericClient;

public class MeasureCLI extends BaseCLI {

	private static enum ReportFormat { TEXT, JSON }
	
	/**
	 * Command line argument definitions
	 */
	private static final class Arguments extends ConnectionArguments {

		@Parameter(names = { "-c",
				"--context-id" }, description = "FHIR resource ID for one or more patients to evaluate.", required = true)
		private List<String> contextIds;
		
		@Parameter(names = { "-h", "--help" }, description = "Display this help", required = false, help = true)
		private boolean isDisplayHelp;
		
		@Parameter(names = { "-f", "--format" }, description = "Output format of the report (JSON|TEXT*)" ) 
		private ReportFormat reportFormat = ReportFormat.TEXT;

		@Parameter(names = { "-j",
				"--json-measure-configurations" }, description = "JSON File containing measure resource ids and optional parameters. Cannot be specified if -r option is used")
		private File measureConfigurationFile;

		@Parameter(names = { "-p",
				"--parameters" }, description = "Parameter value(s) in format name:type:value where value can contain additional parameterized elements separated by comma. Multiple parameters must be specified as multiple -p options", splitter = NoSplittingSplitter.class, required = false)
		private List<String> parameters;

		@Parameter(names = { "-r",
				"--resource" }, description = "FHIR Resource ID or canonical URL for the measure resource to be evaluated. Cannot be specified if -j option is used")
		private String resourceId;
		
		@Parameter(names = { "--filter" }, description = "Filter information for resource loader if the resource loader supports filtering")
		private List<String> filters;
		
		@Parameter(names = { "-e",
				"--include-evaluated-resources" }, description = "Include evaluated resources on measure report. Defaults to false.")
		private boolean includeEvaluatedResources = false;
		
		@Parameter(names = { "-i",
				"--include-define-results" }, description = "Include results for evaluated define statements on measure report. Defaults to false.")
		private boolean includeDefineResults = false;

		public void validate() {
			boolean resourceSpecified = resourceId != null;
			boolean measureConfigurationSpecified = measureConfigurationFile != null;

			if (resourceSpecified ==  measureConfigurationSpecified) {
				throw new IllegalArgumentException("Must specify exactly one of -r or -j options");
			}

			if (measureConfigurationSpecified && !measureConfigurationFile.exists()) {
				throw new IllegalArgumentException("Measure configuration file does not exist: " + measureConfigurationFile.getPath());
			}
		}
	}
	
	public MeasureEvaluator runWithArgs(String[] args, PrintStream out) throws Exception {
		MeasureEvaluator evaluator = null;

		Arguments arguments = new Arguments();
		Console console = new DefaultConsole(out);
		JCommander jc = JCommander.newBuilder().programName("measure-engine").console(console).addObject(arguments).build();
		jc.parse(args);

		if( arguments.isDisplayHelp ) {
			jc.usage();
		} else {
			arguments.validate();

			readDataServerConfiguration(arguments);
			readTerminologyServerConfiguration(arguments);
			
			FhirClientBuilder builder = getFhirClientBuilder();
			
			IGenericClient dataServerClient = builder.createFhirClient(dataServerConfig);
			IGenericClient terminologyServerClient = builder.createFhirClient(terminologyServerConfig);
			
			LibraryResolutionProvider<Library> libraryProvider;
			MeasureResolutionProvider<Measure> measureProvider;

			IParser parser = getFhirContext().newJsonParser().setPrettyPrint(true);
			String [] filters = (arguments.filters != null) ? arguments.filters.toArray(new String[arguments.filters.size()]) : null;
			
			if( arguments.measureServerConfigFile != null && FileHelpers.isZip(arguments.measureServerConfigFile)) {
				ZipFile zipFile = new ZipFile( arguments.measureServerConfigFile );
				
				ResourceResolutionProvider resourceProvider = new ZipResourceResolutionProvider(zipFile, parser, filters);
				
				libraryProvider = resourceProvider;
				measureProvider = resourceProvider;
				
			} else if( arguments.measureServerConfigFile != null && arguments.measureServerConfigFile.isDirectory() ) {
				
				ResourceResolutionProvider resourceProvider = new DirectoryResourceResolutionProvider(arguments.measureServerConfigFile, parser, filters);
				
				libraryProvider = resourceProvider;
				measureProvider = resourceProvider;
				
			} else {
				readMeasureServerConfiguration( arguments );
				IGenericClient measureServerClient = builder.createFhirClient(measureServerConfig);
				
				libraryProvider = new RestFhirLibraryResolutionProvider( measureServerClient );
				measureProvider = new RestFhirMeasureResolutionProvider( measureServerClient );
			}
			
			List<MeasureContext> measureContexts;

			if (arguments.measureConfigurationFile != null) {
				measureContexts = MeasureContextProvider.getMeasureContexts(arguments.measureConfigurationFile);
			} else {
				measureContexts = MeasureContextProvider.getMeasureContexts(arguments.resourceId,  arguments.parameters);
			}
			
			evaluator = new MeasureEvaluator(dataServerClient, terminologyServerClient);
			evaluator.setMeasureResolutionProvider(measureProvider);
			evaluator.setLibraryResolutionProvider(libraryProvider);
			
			for( String contextId : arguments.contextIds ) {
				out.println("Evaluating: " + contextId);
				// Reports only returned for measures where patient is in initial population
				List<MeasureReport> reports = evaluator.evaluatePatientMeasures(contextId, measureContexts, new MeasureEvidenceOptions(arguments.includeEvaluatedResources, arguments.includeDefineResults));

				for (MeasureReport report : reports) {
					if (arguments.reportFormat == ReportFormat.TEXT) {
						out.println("Result for " + report.getMeasure());
						for (MeasureReport.MeasureReportGroupComponent group : report.getGroup()) {
							for (MeasureReport.MeasureReportGroupPopulationComponent pop : group.getPopulation()) {
								String popCode = pop.getCode().getCodingFirstRep().getCode();
								if (pop.getId() != null) {
									popCode += "(" + pop.getId() + ")";
								}
								out.println(String.format("Population: %s = %d", popCode, pop.getCount()));
							}
						}
					} else {
						out.println(parser.encodeResourceToString(report));
					}
					out.println("---");
				}
				if (reports.isEmpty()) {
					out.println("---");
				}
			}
		}
		return evaluator;
	}
	
	public static void main(String[] args) throws Exception {
		MeasureCLI cli = new MeasureCLI();
		cli.runWithArgs( args, System.out );
	}
}
