//
// Copyright (c) 2013, Skytap, Inc
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the "Software"),
// to deal in the Software without restriction, including without limitation
// the rights to use, copy, modify, merge, publish, distribute, sublicense,
// and/or sell copies of the Software, and to permit persons to whom the
// Software is furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.
//                        
package org.jenkinsci.plugins.skytap;

import java.io.FileNotFoundException;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapAction;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapActionDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

public class ChangeConfigurationStateStep extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;
	private final String targetRunState;
	private final Boolean haltOnFailedShutdown;

	// number of times it will poll Skytap to see if correct runstate has been
	// reached
	private static final int NUMBER_OF_RETRIES = 5;

	// base retry interval - on every retry it will double the current value,
	// backing off
	private static final int BASE_RETRY_INTERVAL_SECONDS = 20;

	// these vars will be initialized when the step is run

	@XStreamOmitField
	private SkytapGlobalVariables globalVars;
	
	@XStreamOmitField
	private String authCredentials;

	// the runtime config id will be set one of two ways:
	// either the user has provided just a config id, so we use it,
	// or the user provided a file, in which case we read the file and extract
	// the
	// id from the json element
	@XStreamOmitField
	private String runtimeConfigurationID;

	@DataBoundConstructor
	public ChangeConfigurationStateStep(String configurationID,
			String configurationFile, String targetRunState,
			Boolean haltOnFailedShutdown) {
		super("Change Configuration State");

		this.configurationID = configurationID;
		this.configurationFile = configurationFile;
		this.targetRunState = targetRunState;
		this.haltOnFailedShutdown = haltOnFailedShutdown;

	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Changing Configuration State");
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		
		if(preFlightSanityChecks()==false){
			return false;
		}
		
		this.globalVars = globalVars;
		this.authCredentials = SkytapUtils.getAuthCredentials(build);

		// reset step parameters with env vars resolved at runtime
		String expConfigurationFile = SkytapUtils.expandEnvVars(build,
				configurationFile);
		
		// if user has provided just a filename with no path, default to
		// place it in their Jenkins workspace

		if (!expConfigurationFile.equals("")) {
			expConfigurationFile = SkytapUtils.convertFileNameToFullPath(build,
					expConfigurationFile);
		}

		// get runtime config id
		try {
			runtimeConfigurationID = SkytapUtils.getRuntimeId(configurationID, expConfigurationFile);
		} catch (FileNotFoundException e) {
			JenkinsLogger.error("Error obtaining runtime id: " + e.getMessage());
			return false;
		}
		
		// check current runstate of Skytap configuration
		String currentRunState = "";
				
		try {
			currentRunState = getCurrentConfigurationRunstate(runtimeConfigurationID);
		} catch (SkytapException e2) {
			JenkinsLogger.error("Error obtaining current runstate: " + e2.getMessage());
			return false;
		}

		JenkinsLogger.log("Configuration ID: " + runtimeConfigurationID);
		JenkinsLogger.log("Configuration File: " + expConfigurationFile);
		JenkinsLogger.log("Target runstate: " + this.targetRunState);
		JenkinsLogger.log("Current runstate: " + currentRunState);

		// if its already at our desired runstate, yay we win nothing to do..
		if (this.targetRunState.equals(currentRunState)) {
			JenkinsLogger
					.defaultLogMessage("Current runstate is equal to target. Skipping this step.");
			JenkinsLogger.defaultLogMessage("----------------------------------------");
			return true;
		} else {

			// certain config state transitions are invalid for skytap so error
			// out if one of those is being attempted
			if (!isConfigStateTransitionValid(currentRunState, targetRunState)) {
				JenkinsLogger
						.defaultLogMessage("Skytap will not permit a transition between "
								+ currentRunState + " and " + targetRunState);
				JenkinsLogger.defaultLogMessage("Aborting build step.");
				JenkinsLogger.defaultLogMessage("----------------------------------------");
				return false;
			}

		}

		// execute the initial state change request
		sendStateChangeRequest(runtimeConfigurationID, targetRunState);

		// poll and retry until correct state is achieved
		for (int i = 1; i <= this.NUMBER_OF_RETRIES; i++) {

			// sleep to give Skytap time to change VM runstate
			try {

				int sleepTime = BASE_RETRY_INTERVAL_SECONDS * i;

				JenkinsLogger.log("Sleeping for " + sleepTime + " seconds.");
				Thread.sleep(sleepTime * 1000);
			} catch (InterruptedException e1) {
				JenkinsLogger.error(e1.getMessage());
			}

			// retrieve the runstate
			JenkinsLogger.log("Checking config runstate..");
			
			
			try {
				currentRunState = getCurrentConfigurationRunstate(runtimeConfigurationID);
			} catch (SkytapException e) {
				JenkinsLogger.error("Error retrieving current runstate: " + e.getMessage());
			}
			
			
			JenkinsLogger.log("Current runstate=" + currentRunState);

			// did it succeed? if so step succeeds, if not retry the state
			// change again
			if (currentRunState.equals(targetRunState)) {
				JenkinsLogger.defaultLogMessage("Runstate transitioned successfully.");
				JenkinsLogger.defaultLogMessage("----------------------------------------");
				return true;
			} else {
				
				// send another request but only if state is not 'busy'
				
				if(!currentRunState.equals("busy")){
				sendStateChangeRequest(runtimeConfigurationID, targetRunState);
				}
			}
		}

		// if our target runstate was stopped and the VM did not shutdown
		// gracefully, and if the user selected that checkbox option,
		// power it down using 'halted' state
		if (haltOnFailedShutdown && targetRunState.equals("stopped")) {

			JenkinsLogger.defaultLogMessage("Shutdown has failed. Attempting to halt VM.");

			// Sleep for a minute to make sure the VM is stable, then we can issue the state change request
			try {
				Thread.sleep(60000);
			} catch (InterruptedException e) {
				JenkinsLogger.error("Error: " + e.getMessage());
			}

			sendStateChangeRequest(runtimeConfigurationID, "halted");

			JenkinsLogger.log("Sleeping for 60 seconds.");
			try {
				Thread.sleep(60000);
			} catch (InterruptedException e) {
				JenkinsLogger.error("Error: " + e.getMessage());
			}
			JenkinsLogger.log("Checking if VM is powered down ...");

			// check one more time
			String currentState = "";
			
			try {
				currentState = getCurrentConfigurationRunstate(runtimeConfigurationID);
			} catch (SkytapException e) {
				JenkinsLogger.error("Error getting runstate: " + e.getMessage());
			}
			
			JenkinsLogger.log("Current state: " + currentState);
			if (currentState.equals("stopped")) {
				JenkinsLogger.defaultLogMessage("VM powered down successfully.");
				JenkinsLogger.defaultLogMessage("----------------------------------------");
				return true;
			} else {
				JenkinsLogger.error("Failed to power down VM");
				return false;
			}

		}

		JenkinsLogger.defaultLogMessage("----------------------------------------");
		// if we've made it to here without returning true fail the step
		return false;
	}
	
	/**
	 * This method is a final check to ensure that user inputs are legitimate.
	 * Any situation where the user has entered both inputs in an either/or scenario 
	 * will fail the build. If the user has left both blank where we need one, it will
	 * also fail.
	 * 
	 * @return Boolean sanityCheckPassed
	 */
	private Boolean preFlightSanityChecks(){

		// check whether user entered both values for conf id/conf file
		if(!this.configurationID.equals("") && !this.configurationFile.equals("")){
			JenkinsLogger.error("Values were provided for both configuration ID and file. Please provide just one or the other.");
			JenkinsLogger.defaultLogMessage("----------------------------------------");
			return false;
		}
		
		// check whether we have neither conf id or file
		if(this.configurationFile.equals("") && this.configurationID.equals("")){
			JenkinsLogger.error("No value was provided for configuration ID or file. Please provide either a valid Skytap configuration ID, or a valid configuration file.");
			JenkinsLogger.defaultLogMessage("----------------------------------------");
			return false;
		}
				
		return true;
	}
	
	private void sendStateChangeRequest(String confId, String tgtState) {

		JenkinsLogger.log("Sending state change request for configuration id "
				+ confId + ". Target runstate is " + tgtState);

		// build put request url
		String requestURL = buildRequestURL(confId, tgtState);

		// create request for Skytap API
		HttpPut hp = SkytapUtils.buildHttpPutRequest(requestURL,
				this.authCredentials);

		// execute request
		String httpRespBody = "";
		
		try {
			SkytapUtils.executeHttpRequest(hp);
		} catch (SkytapException e1) {
			JenkinsLogger.error("Skytap Error: " + e1.getMessage());
		}

		// check response for skytap errors
		try {
			SkytapUtils.checkResponseForErrors(httpRespBody);
		} catch (SkytapException e) {
			JenkinsLogger.error("Skytap Error: " + e.getError());
		}

	}

	private String buildRequestURL(String configId, String runstate) {

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(configId);
		sb.append("?runstate=");
		sb.append(runstate);

		return sb.toString();
	}

	/**
	 * Utility method to ensure that Skytap will permit you to change between
	 * the current state and your target state.
	 * 
	 * @param desiredState
	 * @param targetState
	 * @return
	 */
	private Boolean isConfigStateTransitionValid(String currentState,
			String targetState) {

		// according to skytap api guide, these transitions are not valid:
		// stopped -> suspended
		// halted -> suspended
		// suspended -> stopped
		if (currentState.equals("stopped") && targetState.equals("suspended")) {
			return false;
		}
		if (currentState.equals("halted") && targetState.equals("suspended")) {
			return false;
		}
		if (currentState.equals("suspended") && targetState.equals("stopped")) {
			return false;
		}

		// all other transitions are valid
		return true;
	}

	/**
	 * This method checks the current runstate of the specified Skytap
	 * configuration
	 * 
	 * @param configId
	 * @return currentRunstate
	 */
	private String getCurrentConfigurationRunstate(String skytapConfigId) throws SkytapException{

		JenkinsLogger.log("Retrieving Current Runstate ...");

		// build HTTP GET request to check runstate
		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(skytapConfigId);
		String getRequest = sb.toString();

		// build http request
		HttpGet hg = SkytapUtils.buildHttpGetRequest(getRequest,
				this.authCredentials);

		// execute HTTP GET request
		String getResponse = "";
		
		
		try {
			getResponse = SkytapUtils.executeHttpRequest(hg);
		} catch (Exception e) {
			throw new SkytapException(e.getMessage());
		}
		
		String skytapRunstate = "";
				
		try {
			skytapRunstate = SkytapUtils.getValueFromJsonResponseBody(getResponse, "runstate");
		} catch (NullPointerException ex) {
			throw new SkytapException("Response was null or empty.");
		}

		return skytapRunstate;

	}

	public String getConfigurationID() {
		return configurationID;
	}

	public String getConfigurationFile() {
		return configurationFile;
	}

	public String getTargetRunState() {
		return targetRunState;
	}

	public Boolean getHaltOnFailedShutdown() {
		return haltOnFailedShutdown;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			ChangeConfigurationStateStep.class, "Change Configuration State");

}
