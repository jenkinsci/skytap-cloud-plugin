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

public class ChangeContainerStateStep extends SkytapAction {

	private final String containerID;
	private final String containerFile;
	private final String targetContainerAction;

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

	// the runtime environment id will be set one of two ways:
	// either the user has provided just a environment id, so we use it,
	// or the user provided a file, in which case we read the file and extract
	// the
	// id from the json element
	@XStreamOmitField
	private String runtimeContainerID;

	@DataBoundConstructor
	public ChangeContainerStateStep(String containerID,
			String containerFile, String targetContainerAction) {
		super("Change Container State");

		this.containerID = containerID;
		this.containerFile = containerFile;
		this.targetContainerAction = targetContainerAction;

	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Changing Container State");
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		
		if(preFlightSanityChecks()==false){
			return false;
		}
		
		this.globalVars = globalVars;
		this.authCredentials = SkytapUtils.getAuthCredentials(build);

		// reset step parameters with env vars resolved at runtime
		String expContainerFile = SkytapUtils.expandEnvVars(build,
				containerFile);
		
		// if user has provided just a filename with no path, default to
		// place it in their Jenkins workspace

		if (!expContainerFile.equals("")) {
			expContainerFile = SkytapUtils.convertFileNameToFullPath(build,
					expContainerFile);
		}

		// get runtime container id
		try {
			runtimeContainerID = SkytapUtils.getRuntimeId(build, containerID, expContainerFile);
		} catch (FileNotFoundException e) {
			JenkinsLogger.error("Error obtaining runtime container id: " + e.getMessage());
			return false;
		}
		
		String targetContainerState = "";
		if (targetContainerAction.equals("start") || targetContainerAction.equals("unpause")) {
			targetContainerState = "running";
		}
		if (targetContainerAction.equals("pause")) {
			targetContainerState = "paused";

		}
		if (targetContainerAction.equals("stop") || targetContainerAction.equals("kill")) {
			targetContainerState = "exited";
		}

		JenkinsLogger.log("Runtime Container ID: " + runtimeContainerID);
		JenkinsLogger.log("Container File: " + expContainerFile);
		JenkinsLogger.log("Target Container Action: " + this.targetContainerAction);
		JenkinsLogger.log("Target Container Runstate: " + targetContainerState);
		// check current runstate of Skytap environment
		String currentRunState = "";
				
		try {
			currentRunState = getCurrentContainerRunstate(runtimeContainerID);
		} catch (SkytapException e2) {
			JenkinsLogger.error("Error obtaining current container runstate: " + e2.getMessage());
			return false;
		}

		JenkinsLogger.log("Current Container Runstate: " + currentRunState);

		// if its already at our desired runstate, yay we win nothing to do..
		if (targetContainerState.equals(currentRunState)) {
			JenkinsLogger
					.defaultLogMessage("Info: Current container runstate appears to be equal to target container runstate. Skipping this step.");
//					.defaultLogMessage("Current runstate is equal to target. Skipping this step.");
			JenkinsLogger.defaultLogMessage("----------------------------------------");
			return true;
		} else {

			// certain environment state transitions are invalid for skytap so error
			// out if one of those is being attempted
			if (!isContainerStateTransitionValid(currentRunState, targetContainerAction)) {
				JenkinsLogger
						.defaultLogMessage("Skytap will not permit a container \"" + targetContainerAction + "\" action from a \""
								+ currentRunState + "\" state");
				JenkinsLogger.defaultLogMessage("Aborting build step.");
				JenkinsLogger.defaultLogMessage("----------------------------------------");
				return false;
			}

		}

		// execute the initial state change request
		sendStateChangeRequest(runtimeContainerID, targetContainerAction);

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
			JenkinsLogger.log("Checking environment runstate..");
			
			
			try {
				currentRunState = getCurrentContainerRunstate(runtimeContainerID);
			} catch (SkytapException e) {
				JenkinsLogger.error("Error retrieving current container runstate: " + e.getMessage());
			}
			
			
			JenkinsLogger.log("Current Container Runstate=" + currentRunState);

			// did it succeed? if so step succeeds, if not retry the state
			// change again
			if (currentRunState.equals(targetContainerState)) {
				// Sleep for a few seconds to make sure the Config is stable, then we can exit
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e) {
					JenkinsLogger.error("Error: " + e.getMessage());
				}


				JenkinsLogger.defaultLogMessage("Container Runstate transitioned successfully.");
				JenkinsLogger.defaultLogMessage("----------------------------------------");
				return true;
			} else {
				
				// send another request but only if state is not 'busy'
				
				if(!currentRunState.equals("busy")){
				sendStateChangeRequest(runtimeContainerID, targetContainerAction);
				}
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

		// check whether user entered both values for environment id/conf file
		if(!containerID.equals("") && !containerFile.equals("")){
			JenkinsLogger.error("Values were provided for both environment ID and file. Please provide just one or the other.");
			JenkinsLogger.defaultLogMessage("----------------------------------------");
			return false;
		}
		
		// check whether we have neither conf id or file
		if(containerFile.equals("") && containerID.equals("")){
			JenkinsLogger.error("No value was provided for environment ID or file. Please provide either a valid Skytap environment ID, or a valid environment file.");
			JenkinsLogger.defaultLogMessage("----------------------------------------");
			return false;
		}

		return true;
	}
	
	private void sendStateChangeRequest(String containId, String containAction) {

		JenkinsLogger.log("Sending state change request for container id "
				+ containId + ". Target action is " + containAction);

		// build put request url
		String requestURL = buildRequestURL(containId, containAction);

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

	private String buildRequestURL(String containId, String runstate) {

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("v2/containers/");
		sb.append(containId);
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
	private Boolean isContainerStateTransitionValid(String currentState,
			String targetAction) {

		// according to skytap api guide, these transitions are not valid:
		// stopped -> suspended
		// halted -> suspended
		// suspended -> stopped
		if (currentState.equals("exited") && targetAction.equals("pause")) {
			return false;
		}
		if (currentState.equals("paused") && !targetAction.equals("unpause")) {
			return false;
		}
		if (targetAction.equals("unpause") && !currentState.equals("paused")) {
			return false;
		}

		// all other transitions are valid
		return true;
	}

	/**
	 * This method checks the current runstate of the specified Skytap
	 * environment
	 * 
	 * @param configId
	 * @return currentRunstate
	 */
	private String getCurrentContainerRunstate(String skytapContainerId) throws SkytapException{

		JenkinsLogger.log("Retrieving Current Container Runstate ...");

		// build HTTP GET request to check runstate
		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("v2/containers/");
		sb.append(skytapContainerId);
		sb.append(".json");
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
			skytapRunstate = SkytapUtils.getValueFromJsonResponseBody(getResponse, "status");
		} catch (NullPointerException ex) {
			throw new SkytapException("Response was null or empty.");
		}

		return skytapRunstate;

	}

	public String getContainerID() {
		return containerID;
	}

	public String getContainerFile() {
		return containerFile;
	}

	public String getTargetContainerAction() {
		return targetContainerAction;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			ChangeContainerStateStep.class, "Change Container State");

}
