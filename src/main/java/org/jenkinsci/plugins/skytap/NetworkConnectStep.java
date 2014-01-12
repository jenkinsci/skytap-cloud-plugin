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

import hudson.Extension;
import hudson.model.AbstractBuild;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapAction;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapActionDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

public class NetworkConnectStep extends SkytapAction {

	private final String sourceNetworkConfigurationID;
	private final String targetNetworkConfigurationID;

	private final String sourceNetworkConfigurationFile;
	private final String targetNetworkConfigurationFile;

	private final String sourceNetworkName;
	private final String targetNetworkName;

	// number of times it will poll Skytap to see if template is busy
	private static final int NUMBER_OF_RETRIES = 18;
	private static final int RETRY_INTERVAL_SECONDS = 10;

	// these vars will be initialized when the step is run
	@XStreamOmitField
	private SkytapGlobalVariables globalVars;

	@XStreamOmitField
	private String authCredentials;

	// runtime source network config ID
	@XStreamOmitField
	private String runtimeSourceNetworkConfigurationID;

	// runtime target network config ID
	@XStreamOmitField
	private String runtimeTargetNetworkConfigurationID;

	@DataBoundConstructor
	public NetworkConnectStep(String sourceNetworkConfigurationID,
			String targetNetworkConfigurationID,
			String sourceNetworkConfigurationFile,
			String targetNetworkConfigurationFile, String sourceNetworkName,
			String targetNetworkName) {
		super("Connect to Network in another Configuration (ICNR)");

		this.sourceNetworkConfigurationID = sourceNetworkConfigurationID;
		this.targetNetworkConfigurationID = targetNetworkConfigurationID;
		this.sourceNetworkConfigurationFile = sourceNetworkConfigurationFile;
		this.targetNetworkConfigurationFile = targetNetworkConfigurationFile;
		this.sourceNetworkName = sourceNetworkName;
		this.targetNetworkName = targetNetworkName;

	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		JenkinsLogger
				.defaultLogMessage("Connecting to Network in another Configuration");
		JenkinsLogger
				.defaultLogMessage("----------------------------------------");

		if (preFlightSanityChecks() == false) {
			return false;
		}

		this.globalVars = globalVars;
		this.authCredentials = SkytapUtils.getAuthCredentials(build);

		// reset step parameters with env vars resolved at runtime
		String expSourceNetworkConfigurationFile = SkytapUtils.expandEnvVars(
				build, sourceNetworkConfigurationFile);

		// if user has provided just a source network filename with no path,
		// default to
		// place it in their Jenkins workspace

		if (!expSourceNetworkConfigurationFile.equals("")) {
			expSourceNetworkConfigurationFile = SkytapUtils
					.convertFileNameToFullPath(build,
							expSourceNetworkConfigurationFile);
		}

		String expTargetNetworkConfigurationFile = SkytapUtils.expandEnvVars(
				build, targetNetworkConfigurationFile);

		// if user has provided just a target network filename with no path,
		// default to
		// place it in their Jenkins workspace

		if (!expTargetNetworkConfigurationFile.equals("")) {
			expTargetNetworkConfigurationFile = SkytapUtils
					.convertFileNameToFullPath(build,
							expTargetNetworkConfigurationFile);
		}

		// get runtime config ids for source and target network configurations
		try {

			runtimeSourceNetworkConfigurationID = SkytapUtils.getRuntimeId(
					sourceNetworkConfigurationID,
					expSourceNetworkConfigurationFile);
			runtimeTargetNetworkConfigurationID = SkytapUtils.getRuntimeId(
					targetNetworkConfigurationID,
					expTargetNetworkConfigurationFile);

		} catch (FileNotFoundException e) {
			JenkinsLogger
					.error("Error obtaining runtime id: " + e.getMessage());
			return false;
		}

		// get network ids for source and target network names
		String runtimeSourceNetworkID = "";
		String runtimeTargetNetworkID = "";

		try {
			runtimeSourceNetworkID = SkytapUtils.getNetworkIdFromName(
					runtimeSourceNetworkConfigurationID, sourceNetworkName,
					this.authCredentials);
		} catch (SkytapException e1) {
			JenkinsLogger.error(e1.getError());
			return false;
		}

		try {
			runtimeTargetNetworkID = SkytapUtils.getNetworkIdFromName(
					runtimeTargetNetworkConfigurationID, targetNetworkName,
					this.authCredentials);
		} catch (SkytapException e1) {
			JenkinsLogger.error(e1.getError());
			return false;
		}

		// if one or both network ids could not be obtained, error out and fail
		// build
		if (runtimeSourceNetworkID.equals("")
				|| runtimeTargetNetworkID.equals("")) {
			JenkinsLogger.error("Unable to obtain network identifiers.");
			return false;
		}

		// verify that the target network is not busy.
		// if busy, enter wait/retry loop
		// if the check returns false after multiple waits/retries, fail the
		// build step
		// if(!checkIsTargetNetworkAvailable(runtimeTargetNetworkConfigurationID,
		// runtimeTargetNetworkID)){
		// JenkinsLogger.error("Target network has not become available. Failing build step.");
		// return false;
		// }

		// connect the two networks
		try {
			sendNetConnectRequest(runtimeSourceNetworkID,
					runtimeTargetNetworkID);
		} catch (SkytapException e) {

			// if error indicates that networks were already connected,
			// indicate that in the message and return true			
			if (e.getMessage()
					.contains("networks are already connected")) {
				JenkinsLogger
						.error("The source and target networks were already connected. Passing build step.");
				return true;
			}

			JenkinsLogger.error("Skytap Error: " + e.getMessage());
			return false;
		}

		JenkinsLogger.defaultLogMessage("Networks " + this.sourceNetworkName
				+ " and " + this.targetNetworkName
				+ " have been connected successfully.");
		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		return true;
	}

	private void sendNetConnectRequest(String sourceNetId, String targetNetId)
			throws SkytapException {

		JenkinsLogger.log("Sending network connection request for source: "
				+ sourceNetId + " to target: " + targetNetId);

		// build put request url
		String requestURL = buildRequestURL(sourceNetId, targetNetId);

		// create request for Skytap API
		HttpPost hp = SkytapUtils.buildHttpPostRequest(requestURL,
				this.authCredentials);

		// execute request
		String httpRespBody = SkytapUtils.executeHttpRequest(hp);

		// check for empty response
		if (httpRespBody.equals("")) {
			throw new SkytapException(
					"Request Failed. No HTTP response was returned.");
		}

		// check response for skytap errors
		try {
			SkytapUtils.checkResponseForErrors(httpRespBody);
		} catch (SkytapException e) {
			throw e;
		}

	}

	private String buildRequestURL(String sid, String tid) {

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("tunnels?");
		sb.append("source_network_id=");
		sb.append(sid);
		sb.append("&target_network_id=");
		sb.append(tid);

		return sb.toString();
	}

	private String buildCheckTargetNetworkURL(String confId, String netId) {

		JenkinsLogger.log("Building request url ...");

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(confId);
		sb.append("/networks/");
		sb.append(netId);

		JenkinsLogger.log("Request URL: " + sb.toString());
		return sb.toString();
	}

	/**
	 * This method verifies the busy status of the target network. In the event
	 * of a busy status, it enters a wait/retry loop.
	 * 
	 * @param tgtNetId
	 * @return
	 */
	private Boolean checkIsTargetNetworkAvailable(String tgtConfigId,
			String tgtNetId) {

		JenkinsLogger.log("Checking availability of target network with id: "
				+ tgtNetId + " in configuration with id: " + tgtConfigId);

		// build busy check request
		String requestURL = buildCheckTargetNetworkURL(tgtConfigId, tgtNetId);
		HttpGet hg = SkytapUtils.buildHttpGetRequest(requestURL,
				this.authCredentials);

		// repeat request until target network is available
		String httpRespBody = "";
		Boolean networkIsAvailable = false;

		try {
			int pollAttempts = 0;

			while (!networkIsAvailable
					&& (pollAttempts < this.NUMBER_OF_RETRIES)) {

				httpRespBody = SkytapUtils.executeHttpRequest(hg);

				// get json object from response
				JsonParser parser = new JsonParser();
				JsonElement je = parser.parse(httpRespBody);
				JsonObject jo = je.getAsJsonObject();

				// get busy status
				
				if (jo.get("status").getAsString().equals("not_busy")) {
					networkIsAvailable = true;
					JenkinsLogger.log("Target network is available.");
				} else {
					networkIsAvailable = false;
					JenkinsLogger.log("Target network is busy.");

					// wait before trying again
					int sleepTime = this.RETRY_INTERVAL_SECONDS;
					JenkinsLogger
							.log("Sleeping for " + sleepTime + " seconds.");
					Thread.sleep(sleepTime * 1000);
				}

				pollAttempts++;
			}

			return networkIsAvailable;

		} catch (SkytapException ex) {
			JenkinsLogger.error("Request returned an error: " + ex.getError());
			JenkinsLogger.error("Failing build step.");
			return false;
		} catch (InterruptedException e1) {
			JenkinsLogger.error(e1.getMessage());
			return false;
		}

	}

	/**
	 * This method is a final check to ensure that user inputs are legitimate.
	 * Any situation where the user has entered both inputs in an either/or
	 * scenario will fail the build. If the user has left both blank where we
	 * need one, it will also fail.
	 * 
	 * @return Boolean sanityCheckPassed
	 */
	private Boolean preFlightSanityChecks() {

		// check whether user entered both values for conf id/conf file
		if (!this.sourceNetworkConfigurationFile.equals("")
				&& !this.sourceNetworkConfigurationID.equals("")) {
			JenkinsLogger
					.error("Values were provided for both source configuration ID and file. Please provide just one or the other.");
			return false;
		}

		if (!this.targetNetworkConfigurationFile.equals("")
				&& !this.targetNetworkConfigurationID.equals("")) {
			JenkinsLogger
					.error("Values were provided for both target configuration ID and file. Please provide just one or the other.");
			return false;
		}

		// check whether values missing
		if (this.sourceNetworkConfigurationFile.equals("")
				&& this.sourceNetworkConfigurationID.equals("")) {
			JenkinsLogger
					.error("No value was provided for configuration ID or file. Please provide either a valid Skytap configuration ID, or a valid configuration file.");
			return false;
		}

		if (this.targetNetworkConfigurationFile.equals("")
				&& this.targetNetworkConfigurationID.equals("")) {
			JenkinsLogger
					.error("No value was provided for configuration ID or file. Please provide either a valid Skytap configuration ID, or a valid configuration file.");
			return false;
		}

		if (this.targetNetworkName.equals("")) {
			JenkinsLogger
					.error("No value was provided for target network name. Please provide a valid target network name.");
			return false;
		}

		if (this.sourceNetworkName.equals("")) {
			JenkinsLogger
					.error("No value was provided for source network name. Please provide a valid source network name.");
			return false;
		}

		return true;
	}

	public SkytapGlobalVariables getGlobalVars() {
		return globalVars;
	}

	public String getSourceNetworkConfigurationID() {
		return sourceNetworkConfigurationID;
	}

	public String getTargetNetworkConfigurationID() {
		return targetNetworkConfigurationID;
	}

	public String getSourceNetworkConfigurationFile() {
		return sourceNetworkConfigurationFile;
	}

	public String getTargetNetworkConfigurationFile() {
		return targetNetworkConfigurationFile;
	}

	public String getSourceNetworkName() {
		return sourceNetworkName;
	}

	public String getTargetNetworkName() {
		return targetNetworkName;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			NetworkConnectStep.class,
			"Connect to Network in another Configuration (ICNR)");

}
