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
import java.util.ArrayList;
import java.util.Iterator;

import hudson.Extension;
import hudson.model.AbstractBuild;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapAction;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapActionDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

public class DeleteConfigurationStep extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;

	// number of times it will poll Skytap to see if template is busy
	private static final int NUMBER_OF_RETRIES = 18;
	private static final int RETRY_INTERVAL_SECONDS = 10;

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

	@DataBoundConstructor
	public DeleteConfigurationStep(String configurationID,
			String configurationFile) {
		super("Delete Configuration");

		this.configurationID = configurationID;
		this.configurationFile = configurationFile;
	}

	private ArrayList getTunnelList(String configId) throws SkytapException {

		ArrayList tunnelIdList = new ArrayList();

		// build network listing url
		String listNetworksURL = buildNetworkListURL(configId);

		JenkinsLogger.log("Getting network list for environment with id: "
				+ configId);

		// execute http get
		HttpGet hg = SkytapUtils.buildHttpGetRequest(listNetworksURL,
				this.authCredentials);

		// execute request
		String httpRespBody = "";

		try {
			httpRespBody = SkytapUtils.executeHttpRequest(hg);
		} catch (SkytapException e1) {
			throw e1;
		}

		try {
			SkytapUtils.checkResponseForErrors(httpRespBody);
		}catch (SkytapException e2){
			throw e2;
		}
		
		// get JSON Array of networks
		JsonParser parser = new JsonParser();
		JsonElement je = parser.parse(httpRespBody);
		JsonArray ja = je.getAsJsonArray();

		Iterator iter = ja.iterator();

		// iterate through each network and get tunnels
		while (iter.hasNext()) {
			JsonElement networkElement = (JsonElement) iter.next();

			String networkId = networkElement.getAsJsonObject().get("id")
					.getAsString();

			JenkinsLogger.log("Getting tunnels for network with id: "
					+ networkId);

			JsonElement tunnelsElement = networkElement.getAsJsonObject().get(
					"tunnels");
			JsonArray tunnelArray = tunnelsElement.getAsJsonArray();

			// loop through tunnels and retrieve ids
			for (int i = 0; i < tunnelArray.size(); i++) {
				JsonElement tunnelElement = tunnelArray.get(i);
				String id = tunnelElement.getAsJsonObject().get("id")
						.getAsString();
				JenkinsLogger.log("Adding tunnel: " + id + " to list.");

				tunnelIdList.add(id);
			}

		}

		return tunnelIdList;

	}

	private String buildNetworkListURL(String configId) {

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(configId);
		sb.append("/networks");
		// https://cloud.skytap.com/configurations/1155792/networks

		return sb.toString();
	}

	private String buildDisconnectTunnelURL(String tunnelId) {

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");

		sb.append("tunnels/");
		sb.append(tunnelId);
		sb.append("/");

		return sb.toString();

		// https://cloud.skytap.com/tunnels/tunnel-794010-998866/
	}

	/**
	 * Executes API request to disconnect a connection between 2 networks
	 * (tunnel).
	 * 
	 * @param tid
	 * @throws SkytapException
	 */
	private void disconnectTunnel(String tid) throws SkytapException {

		JenkinsLogger.log("Disconnecting tunnel with id: " + tid);

		// build request url
		String reqUrl = buildDisconnectTunnelURL(tid);

		// execute request
		String httpRespBody = "";

		HttpDelete hd = SkytapUtils.buildHttpDeleteRequest(reqUrl,
				authCredentials);

		httpRespBody = SkytapUtils.executeHttpDeleteRequest(hd);

		if (httpRespBody.equals("")) {
			throw new SkytapException(
					"An error occurred while attempting to disconnect " + tid);
		} else {
			JenkinsLogger.log("Tunnel " + tid
					+ " was disconnected successfully.");
		}
	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Delete Environment");
		JenkinsLogger
				.defaultLogMessage("----------------------------------------");

		if (preFlightSanityChecks() == false) {
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
			this.runtimeConfigurationID = SkytapUtils.getRuntimeId(
					build, configurationID, expConfigurationFile);
		} catch (FileNotFoundException e) {
			JenkinsLogger.error("Error retrieving environment id: "
					+ e.getMessage());
			return false;
		}

		// retrieve ids of any tunnels (connected networks) prior to deletion
		JenkinsLogger
				.log("Checking for any connected networks for environment id: "
						+ runtimeConfigurationID);

		ArrayList tunnelIdList = new ArrayList();

		try {
			tunnelIdList = getTunnelList(runtimeConfigurationID);
		} catch (SkytapException e1) {
			JenkinsLogger.error(e1.getMessage());
			return false;
		}

		JenkinsLogger.log("Disconnecting connected networks ...");

		for (int i = 0; i < tunnelIdList.size(); i++) {

			String tunnelId = tunnelIdList.get(i).toString();
			try {
				disconnectTunnel(tunnelId);
			} catch (SkytapException e) {
				JenkinsLogger.error(e.getMessage());
				return false;
			}

		}

		JenkinsLogger.log("Sending delete request for environment id "
				+ this.runtimeConfigurationID);

		// attempt to delete environment - if the resource is busy,
		// and doesn't become available after the environment wait time,
		// fail the build step
		if (attemptDeleteConfiguration(runtimeConfigurationID) == false) {
			JenkinsLogger.error("Environment ID: " + runtimeConfigurationID
					+ " could not be deleted. Failing build step.");
			return false;
		}

		JenkinsLogger.defaultLogMessage("Environment "
				+ runtimeConfigurationID + " was successfully deleted.");
		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		return true;

	}

	private Boolean attemptDeleteConfiguration(String confId) {

		// build delete environment url
		String requestURL = buildRequestURL(confId);

		// create request for Skytap API
		HttpDelete hd = SkytapUtils.buildHttpDeleteRequest(requestURL,
				this.authCredentials);

		// repeat request until environment
		// becomes available and can be deleted
		String httpRespBody = "";
		Boolean configDeletedSuccessfully = false;

		int pollAttempts = 0;

		while (!configDeletedSuccessfully
				&& (pollAttempts < this.NUMBER_OF_RETRIES)) {

			// wait for a time before attempting delete
			int sleepTime = this.RETRY_INTERVAL_SECONDS;
			JenkinsLogger.log("Sleeping for " + sleepTime + " seconds.");
			try {
				Thread.sleep(sleepTime * 1000);
			} catch (InterruptedException e1) {
				JenkinsLogger.error(e1.getMessage());
			}

			httpRespBody = SkytapUtils.executeHttpDeleteRequest(hd);

			if (httpRespBody.equals("")) {
				JenkinsLogger
						.error("An error occurred while attempting to delete "
								+ confId);
				pollAttempts++;

			} else {
				configDeletedSuccessfully = true;
			}

		}

		return configDeletedSuccessfully;
	}

	private String buildRequestURL(String configId) {

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(configId);

		// https://cloud.skytap.com/configurations/1154948

		return sb.toString();
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
		if (!this.configurationID.equals("")
				&& !this.configurationFile.equals("")) {
			JenkinsLogger
					.error("Values were provided for both environment ID and file. Please provide just one or the other.");
			return false;
		}

		// check whether we have neither conf id or file
		if (this.configurationFile.equals("")
				&& this.configurationID.equals("")) {
			JenkinsLogger
					.error("No value was provided for environment ID or file. Please provide either a valid Skytap environment ID, or a valid environment file.");
			return false;
		}

		return true;
	}

	public String getConfigurationID() {
		return configurationID;
	}

	public String getConfigurationFile() {
		return configurationFile;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			DeleteConfigurationStep.class, "Delete Environment");

}
