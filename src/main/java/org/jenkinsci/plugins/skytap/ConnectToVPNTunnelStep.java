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

import hudson.Extension;
import hudson.model.AbstractBuild;

import org.apache.commons.httpclient.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
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

public class ConnectToVPNTunnelStep extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;
	private final String configurationNetworkName;
	private final String vpnID;

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
	public ConnectToVPNTunnelStep(String configurationID,
			String configurationFile, String configurationNetworkName,
			String vpnID) {
		super("Connect to VPN Tunnel");

		this.configurationID = configurationID;
		this.configurationFile = configurationFile;
		this.configurationNetworkName = configurationNetworkName;
		this.vpnID = vpnID;

	}

	private String buildIsConnectedCheckURL(String confId, String netId,
			String vpnId) {

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(confId);
		sb.append("/networks/");
		sb.append(netId);
		sb.append("/vpns/");
		sb.append(vpnId);

		String checkUrl = sb.toString();

		// https://cloud.skytap.com/configurations/1168708/networks/805882/vpns/vpn-817994

		return checkUrl;
	}

	private Boolean checkIsAlreadyConnected(String confId, String netId,
			String vpnId) throws SkytapException {

		JenkinsLogger.log("Verifying if network " + netId
				+ " is already connected to VPN " + vpnId);

		// assume its not connected
		Boolean isConnected = false;

		// build check url
		String reqURL = buildIsConnectedCheckURL(confId, netId, vpnId);

		// build request
		HttpGet hg = SkytapUtils.buildHttpGetRequest(reqURL, authCredentials);

		// execute request
		String httpRespBody = SkytapUtils.executeHttpRequest(hg);

		// check for error indicating environment is not connected to VPN
		try {
			SkytapUtils.checkResponseForErrors(httpRespBody);
		} catch (SkytapException e) {

			// this is what we expect normally - return isconnected=false
			if (e.getMessage().contains("Environment not attached to VPN")) {
				return isConnected;
			} else {
				throw e;
			}

		}

		// get json object from response
		JsonParser parser = new JsonParser();
		JsonElement je = parser.parse(httpRespBody);
		JsonObject jo = je.getAsJsonObject();

		isConnected = jo.get("connected").getAsBoolean();

		return isConnected;
	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Connecting to VPN Tunnel");
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

		// get runtime environment id
		try {
			runtimeConfigurationID = SkytapUtils.getRuntimeId(configurationID,
					expConfigurationFile);
		} catch (FileNotFoundException e) {
			JenkinsLogger
					.error("Error obtaining runtime id: " + e.getMessage());
			return false;
		}

		// get the network id from the provided network name
		JenkinsLogger.log("Getting the ID for network: "
				+ configurationNetworkName);
		String runtimeNetworkID = "";

		try {
			runtimeNetworkID = SkytapUtils.getNetworkIdFromName(
					runtimeConfigurationID, configurationNetworkName,
					this.authCredentials);
		} catch (SkytapException e1) {
			JenkinsLogger.error(e1.getError());
			return false;
		}

		if (runtimeNetworkID.equals("")) {
			JenkinsLogger.error("Failed to obtain network ID.");
			return false;
		}

		JenkinsLogger.log("Environment ID: " + runtimeConfigurationID);
		JenkinsLogger.log("Environment File: " + expConfigurationFile);
		JenkinsLogger.log("Network ID: " + runtimeNetworkID);
		JenkinsLogger.log("Network Name: " + this.configurationNetworkName);
		JenkinsLogger.log("VPN ID: " + this.vpnID);

		// check whether network is already connected, if it is, output a
		// message and pass the build step
		Boolean alreadyConnected = false;

		try {
			alreadyConnected = checkIsAlreadyConnected(runtimeConfigurationID,
					runtimeNetworkID, vpnID);
		} catch (SkytapException e1) {
			JenkinsLogger.error("Skytap Error: " + e1.getMessage());
			return false;
		}

		if (alreadyConnected) {
			JenkinsLogger.log("Network is already connected to VPN: " + vpnID
					+ ". Passing build step.");
			return true;
		} else {
			JenkinsLogger.log("Network is not currently connected to VPN: "
					+ vpnID);
		}

		// attach the VPN to the environment
		JenkinsLogger.log("Attaching VPN to Environment ...");

		if (executeVPNAttach(runtimeConfigurationID, runtimeNetworkID, vpnID)) {

			JenkinsLogger.log("VPN with ID " + vpnID
					+ " successfully attached to Network with ID "
					+ runtimeNetworkID + ".");

		} else {

			JenkinsLogger.error("VPN attach has failed. Failing build step.");
			return false;

		}

		// connect the environment to the VPN
		JenkinsLogger.log("Connecting VPN to Environment ...");

		if (executeVPNConnect(runtimeConfigurationID, runtimeNetworkID, vpnID)) {

			JenkinsLogger.defaultLogMessage("VPN with ID " + vpnID
					+ " successfully connected to Network with ID "
					+ runtimeNetworkID + ".");
			
		} else {
			JenkinsLogger.error("VPN connect has failed. Failing build step.");
			return false;
		}

		// pause before exiting to allow VPN and environment to settle down
		int sleepTime = this.RETRY_INTERVAL_SECONDS;
		JenkinsLogger.log("Pausing for " + sleepTime + " seconds.");

		try {
			Thread.sleep(sleepTime * 1000);
		} catch (InterruptedException e1) {
			JenkinsLogger.error(e1.getMessage());
		}

		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		return true;
	}

	private Boolean executeVPNConnect(String confId, String netId, String vpnId) {

		String connectResponse = "";
		Boolean vpnConnectedSuccessfully = false;
		int pollAttempts = 0;

		while (!vpnConnectedSuccessfully
				&& pollAttempts < this.NUMBER_OF_RETRIES) {

			connectResponse = this.connectVPNToConfiguration(confId, netId,
					vpnId);

			JenkinsLogger.log("Connect Response: " + connectResponse);

			if (connectResponse.equals("") || connectResponse == null) {
				JenkinsLogger.error("Response was null or empty.");
			}

			// check response for errors
			try {
				SkytapUtils.checkResponseForErrors(connectResponse);

				// no errors.. then connect succeeded.
				vpnConnectedSuccessfully = true;

			} catch (SkytapException e) {
				JenkinsLogger.error("Skytap Error: " + e.getError());

				// wait before trying again
				int sleepTime = this.RETRY_INTERVAL_SECONDS;
				JenkinsLogger.log("Sleeping for " + sleepTime + " seconds.");

				try {
					Thread.sleep(sleepTime * 1000);
				} catch (InterruptedException e1) {
					JenkinsLogger.error(e1.getMessage());
				}
			}

			pollAttempts++;

		}

		return vpnConnectedSuccessfully;
	}

	private Boolean executeVPNAttach(String confId, String netId, String vpnId) {

		Boolean vpnAttachedSuccessfully = false;
		int pollAttempts = 0;
		String attachResponse = "";

		while (!vpnAttachedSuccessfully
				&& pollAttempts < this.NUMBER_OF_RETRIES) {

			attachResponse = this
					.attachVPNToConfiguration(confId, netId, vpnId);

			JenkinsLogger.log("Attach Response: " + attachResponse);

			if (attachResponse.equals("") || attachResponse == null) {
				JenkinsLogger.error("Response was null or empty.");
			} else {

				try {
					SkytapUtils.checkResponseForErrors(attachResponse);

					// if no exception is thrown, attach succeeded
					vpnAttachedSuccessfully = true;

				} catch (SkytapException e) {
					JenkinsLogger.error("Skytap Error: " + e.getError());

					// wait before trying again
					int sleepTime = this.RETRY_INTERVAL_SECONDS;
					JenkinsLogger
							.log("Sleeping for " + sleepTime + " seconds.");

					try {
						Thread.sleep(sleepTime * 1000);
					} catch (InterruptedException e1) {
						JenkinsLogger.error(e1.getMessage());
					}

				}

			}

			pollAttempts++;

		}
		return vpnAttachedSuccessfully;
	}

	private String connectVPNToConfiguration(String confId, String networkId,
			String vpnId) {

		// build url
		String reqUrl = this.buildConnectRequestURL(confId, networkId, vpnId);

		// create request
		HttpPut hp = SkytapUtils.buildHttpPutRequest(reqUrl,
				this.authCredentials);

		// add content to request - vpn identifier
		BasicHttpEntity he = new BasicHttpEntity();
		he.setContentEncoding("gzip");
		he.setContentType("application/json");

		// json string for connected attribute
		String jsonString = "{\"connected\" :true}";

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
		String response = "";

		try {
			response = SkytapUtils.executeHttpRequest(hp);
		} catch (SkytapException e) {
			JenkinsLogger.error("Skytap Exception: " + e.getMessage());
		}

		return response;

	}

	private String buildConnectRequestURL(String confId, String networkId,
			String vpnId) {

		String req = this.buildRequestURL(confId, networkId);
		StringBuilder sb = new StringBuilder(req);
		sb.append("/");
		sb.append(vpnId);
		sb.append("/");

		JenkinsLogger.log(sb.toString());
		return sb.toString();

	}

	private String buildRequestURL(String confId, String networkId) {

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(confId);
		sb.append("/networks/");
		sb.append(networkId);
		sb.append("/vpns");

		String requestUrl = sb.toString();
		return requestUrl;
	}

	private String attachVPNToConfiguration(String confId, String networkId,
			String vpnId) {

		// build url
		String requestUrl = this.buildRequestURL(confId, networkId);

		// create request
		HttpPost hp = SkytapUtils.buildHttpPostRequest(requestUrl,
				this.authCredentials);

		// add content to request - vpn identifier
		BasicHttpEntity he = new BasicHttpEntity();
		he.setContentEncoding("gzip");
		he.setContentType("application/json");

		// json string for vpn id
		String jsonString = "{\"id\":\"" + vpnId + "\"}";

		InputStream stream;
		try {
			stream = new ByteArrayInputStream(jsonString.getBytes("UTF-8"));
			Integer len = jsonString.getBytes("UTF-8").length;
			long llen = len.longValue();

			he.setContent(stream);
			he.setContentLength(llen);

		} catch (UnsupportedEncodingException e) {
			JenkinsLogger.error("Error encoding json string for vpn id: "
					+ e.getMessage());

		}

		hp.setEntity(he);

		JenkinsLogger.log("HTTP POST request: " + hp.toString());

		// execute request
		String httpRespBody = "";

		try {
			httpRespBody = SkytapUtils.executeHttpRequest(hp);
		} catch (SkytapException e) {
			JenkinsLogger.error("Skytap Exception: " + e.getMessage());
		}

		// return response
		return httpRespBody;

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

		// if either VPN ID or network name are blank, fail the build
		if (this.vpnID.equals("")) {
			JenkinsLogger
					.error("No value was provided for the VPN ID. Please provide a valid Skytap VPN ID.");
			return false;
		}

		if (this.configurationNetworkName.equals("")) {
			JenkinsLogger
					.error("No value was provided for the network name. Please provide a valid Skytap Network name.");
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

	public String getConfigurationNetworkName() {
		return configurationNetworkName;
	}

	public String getVpnID() {
		return vpnID;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			ConnectToVPNTunnelStep.class, "Connect to VPN Tunnel");

}
