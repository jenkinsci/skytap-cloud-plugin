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

public class DeleteContainerStep extends SkytapAction {

	private final String containerID;
	private final String containerFile;

	// number of times it will poll Skytap to see if template is busy
	private static final int NUMBER_OF_RETRIES = 18;
	private static final int RETRY_INTERVAL_SECONDS = 10;

	// these vars will be initialized when the step is run

	@XStreamOmitField
	private SkytapGlobalVariables globalVars;

	@XStreamOmitField
	private String authCredentials;

	// the runtime container id will be set one of two ways:
	// either the user has provided just a container id, so we use it,
	// or the user provided a file, in which case we read the file and extract
	// the
	// id from the json element
	@XStreamOmitField
	private String runtimeContainerID;

	@DataBoundConstructor
	public DeleteContainerStep(String containerID,
			String containerFile) {
		super("Delete Container");

		this.containerID = containerID;
		this.containerFile = containerFile;
	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Delete Container");
		JenkinsLogger
				.defaultLogMessage("----------------------------------------");

		if (preFlightSanityChecks() == false) {
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
			this.runtimeContainerID = SkytapUtils.getRuntimeId(
					build, containerID, expContainerFile);
		} catch (FileNotFoundException e) {
			JenkinsLogger.error("Error retrieving container id: "
					+ e.getMessage());
			return false;
		}
		JenkinsLogger.log("Sending delete request for container id "
				+ this.runtimeContainerID);

		// attempt to delete container - if the resource is busy,
		// and doesn't become available after the container wait time,
		// fail the build step
		if (attemptDeleteContainer(runtimeContainerID) == false) {
			JenkinsLogger.error("Container ID: " + runtimeContainerID
					+ " could not be deleted. Failing build step.");
			return false;
		}

		JenkinsLogger.defaultLogMessage("Container "
				+ runtimeContainerID + " was successfully deleted.");
		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		return true;

	}

	private Boolean attemptDeleteContainer(String confId) {

		// build delete container url
		String requestURL = buildRequestURL(confId);

		// create request for Skytap API
		HttpDelete hd = SkytapUtils.buildHttpDeleteRequest(requestURL,
				this.authCredentials);

		// repeat request until container
		// becomes available and can be deleted
		String httpRespBody = "";
		Boolean containerDeletedSuccessfully = false;

		int pollAttempts = 0;

		while (!containerDeletedSuccessfully
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

			if (httpRespBody.contains("error")) {
				JenkinsLogger
						.error("An error occurred while attempting to delete "
								+ confId);
				pollAttempts++;

			} else {
				containerDeletedSuccessfully = true;
			}

		}

		return containerDeletedSuccessfully;
	}

	private String buildRequestURL(String containerId) {

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("/v2/containers/");
		sb.append(containerId);

		// https://cloud.skytap.com/v2/containers/9720

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
		if (!this.containerID.equals("")
				&& !this.containerFile.equals("")) {
			JenkinsLogger
					.error("Values were provided for both container ID and file. Please provide just one or the other.");
			return false;
		}

		// check whether we have neither conf id or file
		if (this.containerFile.equals("")
				&& this.containerID.equals("")) {
			JenkinsLogger
					.error("No value was provided for container ID or file. Please provide either a valid Skytap container ID, or a valid container file.");
			return false;
		}

		return true;
	}

	public String getContainerID() {
		return containerID;
	}

	public String getContainerFile() {
		return containerFile;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			DeleteContainerStep.class, "Delete Container");

}
