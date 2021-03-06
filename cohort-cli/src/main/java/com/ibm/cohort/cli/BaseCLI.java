/*
 * (C) Copyright IBM Corp. 2020, 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.cohort.cli;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cohort.fhir.client.config.FhirClientBuilder;
import com.ibm.cohort.fhir.client.config.FhirClientBuilderFactory;
import com.ibm.cohort.fhir.client.config.FhirServerConfig;

import ca.uhn.fhir.context.FhirContext;

public class BaseCLI {
	
	protected FhirContext fhirContext = null;
	protected FhirClientBuilder fhirClientBuilder = null;	
	
	protected FhirServerConfig dataServerConfig;
	protected FhirServerConfig terminologyServerConfig;
	protected FhirServerConfig measureServerConfig;

	protected ObjectMapper om = new ObjectMapper();
	
	protected FhirContext getFhirContext() {
		if( fhirContext == null ) {
			fhirContext = FhirContext.forR4();
		}
		return fhirContext;
	}
	
	protected FhirClientBuilder getFhirClientBuilder() {
		if( fhirClientBuilder == null ) {
			FhirClientBuilderFactory factory = FhirClientBuilderFactory.newInstance();
			this.fhirClientBuilder = factory.newFhirClientBuilder(getFhirContext());
		}
		return this.fhirClientBuilder;
	}
	
	protected void readConnectionConfiguration(ConnectionArguments arguments) throws Exception {
		
		readDataServerConfiguration(arguments);

		readTerminologyServerConfiguration(arguments);

		readMeasureServerConfiguration(arguments);
	}

	protected void readDataServerConfiguration(ConnectionArguments arguments)
			throws IOException, JsonParseException, JsonMappingException {
		dataServerConfig = om.readValue(arguments.dataServerConfigFile, FhirServerConfig.class);
	}

	protected void readTerminologyServerConfiguration(ConnectionArguments arguments)
			throws IOException, JsonParseException, JsonMappingException {
		if (arguments.terminologyServerConfigFile != null) {
			terminologyServerConfig = om.readValue(arguments.terminologyServerConfigFile, FhirServerConfig.class);
		} else {
			terminologyServerConfig = dataServerConfig;
		}
	}
	
	protected void readMeasureServerConfiguration(ConnectionArguments arguments)
			throws IOException, JsonParseException, JsonMappingException {
		if (arguments.measureServerConfigFile != null) {
			measureServerConfig = om.readValue(arguments.measureServerConfigFile, FhirServerConfig.class);
		} else { 
			measureServerConfig = dataServerConfig;
		}
	}
}
