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

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;

import org.apache.commons.httpclient.Header;
import org.apache.http.HttpEntity;


import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.BasicHttpEntity;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapAction;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapActionDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

public class ChangeVMContainerHostStatus extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;
	private final String vmID;
	private final String vmName;
	private final String containerHostStatus;

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
	private String runtimeConfigurationID;

	@XStreamOmitField
	private String runtimeVMID;

	@DataBoundConstructor
	public ChangeVMContainerHostStatus(String configurationID,
			String configurationFile, String vmID, String vmName, String containerHostStatus) {
		super("Change VM Container Host Status");

		this.configurationID = configurationID;
		this.configurationFile = configurationFile;
		this.vmID = vmID;
		this.vmName = vmName;
		this.containerHostStatus = containerHostStatus;

	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Changing VM Container Host Status");
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

		// get runtime environment id
		try {
			runtimeConfigurationID = SkytapUtils.getRuntimeId(build, configurationID, expConfigurationFile);
		} catch (FileNotFoundException e) {
			JenkinsLogger.error("Error obtaining runtime id: " + e.getMessage());
			return false;
		}

		// if the user provided a vm name,
		// get the vm id.. queries that use the vm name return different types of responses
		
		if(!vmName.isEmpty()){
		
			try {
				this.runtimeVMID = SkytapUtils.getVMIDFromName(runtimeConfigurationID, vmName, authCredentials);
			} catch (SkytapException e) {
				JenkinsLogger.error(e.getMessage());
				return false;
			}
		
		} else { runtimeVMID = vmID; }

		
		JenkinsLogger.log("Environment ID: " + runtimeConfigurationID);
		JenkinsLogger.log("Environment File: " + expConfigurationFile);
		JenkinsLogger.log("VM ID: " + runtimeVMID);
		JenkinsLogger.log("VM Name: " + vmName);
		JenkinsLogger.log("Target Container Host State: " + this.containerHostStatus);

		// execute the initial state change request
		sendStatusChangeRequest(runtimeConfigurationID, runtimeVMID, containerHostStatus);

		// poll and retry until correct state is achieved
		// for (int i = 1; i <= this.NUMBER_OF_RETRIES; i++) {

			// sleep to give Skytap time to change VM runstate
		// 	try {

		// 		int sleepTime = BASE_RETRY_INTERVAL_SECONDS * i;

		// 		JenkinsLogger.log("Sleeping for " + sleepTime + " seconds.");
		// 		Thread.sleep(sleepTime * 1000);
		// 	} catch (InterruptedException e1) {
		// 		JenkinsLogger.error(e1.getMessage());
		// 	}

			// retrieve the Container Host Status
			// JenkinsLogger.log("Checking container host status ..");
			
			
			// try {
			// 	currentRunState = getCurrentConfigurationRunstate(runtimeConfigurationID);
			// } catch (SkytapException e) {
			// 	JenkinsLogger.error("Error retrieving current VM host status: " + e.getMessage());
			// }
			
			
			// JenkinsLogger.log("Current VM host status=" + currentRunState);

			// did it succeed? if so step succeeds, if not retry the state
			// change again
			// if (currentRunState.equals(containerHostStatus)) {
				// Sleep for a few seconds to make sure the Config is stable, then we can exit
			// 	try {
			// 		Thread.sleep(5000);
			// 	} catch (InterruptedException e) {
			// 		JenkinsLogger.error("Error: " + e.getMessage());
			// 	}


			// 	JenkinsLogger.defaultLogMessage("VM container host status transitioned successfully.");
			// 	JenkinsLogger.defaultLogMessage("----------------------------------------");
			// 	return true;
			// } else {
				
				// send another request but only if state is not 'busy'
				
			// 	if(!currentRunState.equals("busy")){
			// 	sendStatusChangeRequest(runtimeConfigurationID, vmID, containerHostStatus);
			// 	}
			// }
		// }

		JenkinsLogger.defaultLogMessage("----------------------------------------");
		// if we've made it to here without returning true fail the step
		return true;
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
		if(!this.configurationID.equals("") && !this.configurationFile.equals("")){
			JenkinsLogger.error("Values were provided for both environment ID and file. Please provide just one or the other.");
			JenkinsLogger.defaultLogMessage("----------------------------------------");
			return false;
		}
		
		// check whether we have neither conf id or file
		if(this.configurationFile.equals("") && this.configurationID.equals("")){
			JenkinsLogger.error("No value was provided for environment ID or file. Please provide either a valid Skytap environment ID, or a valid environment file.");
			JenkinsLogger.defaultLogMessage("----------------------------------------");
			return false;
		}
				
		// check whether user omitted vmID or name
		if (this.vmID.isEmpty() && this.vmName.isEmpty()) {
			JenkinsLogger
					.error("No value was provided for VM ID or name. Please provide either a valid Skytap VM ID or name.");
			return false;
		}

		// check whether user provided both vm id and name
		if (!this.vmID.equals("") && !this.vmName.equals("")) {
			JenkinsLogger
					.error("Values were provided for both VM ID and name. Please provide just one or the other.");
			return false;
		}


		return true;
	}
	
	private void sendStatusChangeRequest(String confId, String vmId, String tgtStatus) {

		JenkinsLogger.log("Sending state change request for environment id "
				+ confId + " VM id " + vmId +". Target container host status is " + tgtStatus);

		// build put request url
		String requestURL = buildRequestURL(confId, vmId, tgtStatus);

		// create request for Skytap API
		HttpPut hp = SkytapUtils.buildHttpPutRequest(requestURL,
				this.authCredentials);

		// add content to request - vpn identifier
		BasicHttpEntity he = new BasicHttpEntity();
		he.setContentEncoding("gzip");
		he.setContentType("application/json");

		String jsonString = "{\"container_host\" :false}";
		// json string for connected attribute
		if (tgtStatus.equals("enabled")) {
			jsonString = "{\"container_host\" :true}";
		}

		InputStream stream;
		try {
			stream = new ByteArrayInputStream(jsonString.getBytes("UTF-8"));
			Integer len = jsonString.getBytes("UTF-8").length;
			long llen = len.longValue();

			he.setContent(stream);
			he.setContentLength(llen);

		} catch (UnsupportedEncodingException e) {
			JenkinsLogger
					.error("Error encoding json string for connected attribute: "
							+ e.getMessage());

		}
		hp.setEntity(he);

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

	private String buildRequestURL(String configId, String vmId, String newStatus) {

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(configId);
		sb.append("/vms/");
		sb.append(vmId);
		sb.append(".json");
		return sb.toString();
	}

	/**
	 * This method checks the current runstate of the specified Skytap
	 * environment
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

	public String getvmID() {
		return vmID;
	}

	public String getvmName() {
		return vmName;
	}

	public String getContainerHostStatus() {
		return containerHostStatus;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			ChangeVMContainerHostStatus.class, "Change VM Container Host Status");

}
