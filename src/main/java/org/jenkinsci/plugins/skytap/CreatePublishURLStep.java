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

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import hudson.Extension;
import hudson.model.AbstractBuild;

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

public class CreatePublishURLStep extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;
	private final String urlSaveFilename;
	private final String portalName;
	private final String permissionOption;

	private final Boolean hasPassword;
	private final String urlPassword;

	// these will be initialized when the step is run
	@XStreamOmitField
	private SkytapGlobalVariables globalVars;

	@XStreamOmitField
	private String runtimeConfigurationID;
	
	@XStreamOmitField
	private String authCredentials;

	@DataBoundConstructor
	public CreatePublishURLStep(String configurationID,
			String configurationFile, String urlSaveFilename, String portalName,
			String permissionOption, RequirePasswordBlock hasPassword ) {

		super("Create Published URL");
		this.configurationFile = configurationFile;
		this.configurationID = configurationID;
		this.urlSaveFilename = urlSaveFilename;
		this.permissionOption = permissionOption;

		if (portalName == null) {
			this.portalName = "Default Publish Set";
		} else {
			this.portalName = portalName;
		}

		if (hasPassword == null) {
			this.hasPassword = false;
			this.urlPassword = null;
		} else {
			this.hasPassword = true;
			this.urlPassword = hasPassword.password;
		}
	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Creating Sharing Portal");
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		
		if(preFlightSanityChecks()==false){
			return false;
		}
		
		this.globalVars = globalVars;
		this.authCredentials = SkytapUtils.getAuthCredentials(build);

		// expand environment variables where it makes sense
		String expConfigFile = SkytapUtils.expandEnvVars(build,
				configurationFile);
		
		// if user has provided just a filename with no path, default to
		// place it in their Jenkins workspace

		if (!expConfigFile.equals("")) {
			expConfigFile = SkytapUtils.convertFileNameToFullPath(build,
					expConfigFile);
		}
		
		
		String expUrlFile = SkytapUtils.expandEnvVars(build, urlSaveFilename);

		// get runtime environment id
		try {
			runtimeConfigurationID = SkytapUtils.getRuntimeId(configurationID,
					expConfigFile);
		} catch (FileNotFoundException e) {
			JenkinsLogger.error("Error retrieving environment id: "
					+ e.getMessage());
			return false;
		}

		// get all VM ids for environment
		JenkinsLogger.log("Retrieving VM ids for environment: "
				+ runtimeConfigurationID);

		List<String> vmList = new ArrayList<String>();
		try {
			vmList = getVMIds(runtimeConfigurationID);
		} catch (SkytapException e) {
			JenkinsLogger.error(e.getMessage());
			return false;
		}

		// create publish set
		JenkinsLogger.log("Creating publish set ...");
		String pubSetUrl = "";

		try {
			pubSetUrl = createPublishSet(runtimeConfigurationID, vmList);
		} catch (SkytapException e) {
			JenkinsLogger.error(e.getMessage());
		}

		// get url
		JenkinsLogger.log("Sharing Portal URL: " + pubSetUrl);

		Writer output = null;
		
		// if user has provided just a filename with no path, default to
		// place it in their Jenkins workspace
		expUrlFile = SkytapUtils.convertFileNameToFullPath(build, expUrlFile);
		
		File file = new File(expUrlFile);

		// write url to file
		try {

			output = new BufferedWriter(new FileWriter(file));
			output.write(pubSetUrl);
			output.close();

		} catch (IOException e) {

			JenkinsLogger.error("Skytap Plugin failed to save URL to file: "
					+ expUrlFile);
			return false;
		}

		JenkinsLogger.defaultLogMessage("URL " + pubSetUrl
				+ " successfully created and saved to file: " + expUrlFile);
		
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		return true;

	}

	private String createPublishSet(String confId, List<String> vmList)
			throws SkytapException {

		// build request
		String reqUrl = "https://cloud.skytap.com/configurations/" + confId
				+ "/publish_sets/";

		JenkinsLogger.log("Request URL: " + reqUrl);

		// create request
		HttpPost hp = SkytapUtils.buildHttpPostRequest(reqUrl,
				this.authCredentials);

		// add content to request - vms
		BasicHttpEntity he = new BasicHttpEntity();
		he.setContentEncoding("gzip");
		he.setContentType("application/json");

		// json string for connected attribute
		StringBuilder sb = new StringBuilder("");
		sb.append("{\"publish_set\":{\"publish_set_type\":\"single_url\",");
		sb.append("\"vms\":[");

		// iterate through vm list, adding elements to the array
		Iterator iter = vmList.iterator();

		while (iter.hasNext()) {
			String vmString = "{\"access\":\"" + this.permissionOption
					+ "\",\"vm_ref\":\"" + iter.next().toString() + "\"}";

			if (iter.hasNext()) {
				vmString += ",";
			}

			sb.append(vmString);
		}

		// set password if one was provided
		String passwordString = "";

		if (this.hasPassword) {
			passwordString = "\"" + this.urlPassword + "\"";
		} else {
			passwordString = "null";
		}

		sb.append("],\"password\":" + passwordString + ",");
		sb.append("\"name\":\"" + this.portalName + "\"}}");

		String jsonString = sb.toString();

		JenkinsLogger.log("Request Payload: " + jsonString);
		// {"publish_set":{"publish_set_type":"single_url",
		// "vms":[{"access":"use","vm_ref":"2128250"}],"password":null,"name":"blah"}}

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

		response = SkytapUtils.executeHttpRequest(hp);

		// check response for errors
		SkytapUtils.checkResponseForErrors(response);
		
		// extract url from response
		String respUrl = SkytapUtils.getValueFromJsonResponseBody(response,
				"desktops_url");

		return respUrl;

	}

	private List<String> getVMIds(String confId) throws SkytapException {

		List<String> vmList = new ArrayList<String>();

		// build request
		String reqUrl = "https://cloud.skytap.com/configurations/" + confId;

		HttpGet hg = SkytapUtils.buildHttpGetRequest(reqUrl,
				this.authCredentials);
		
		// extract vm ids
		String response = SkytapUtils.executeHttpRequest(hg);

		// check response for errors
		SkytapUtils.checkResponseForErrors(response);
		
		// get id and name of newly created template
		JsonParser parser = new JsonParser();
		JsonElement je = parser.parse(response);
		JsonArray ja = (JsonArray) je.getAsJsonObject().get("vms");

		Iterator iter = ja.iterator();

		while (iter.hasNext()) {
			JsonElement vmElement = (JsonElement) iter.next();
			String vmElementId = vmElement.getAsJsonObject().get("id")
					.getAsString();

			vmList.add(vmElementId);
			JenkinsLogger.log("VM ID: " + vmElementId);
		}

		return vmList;
	}

	// "requirePassword":{"urlPassword":"qsdqweq"}
	public static class RequirePasswordBlock {
		private String password;

		@DataBoundConstructor
		public RequirePasswordBlock(String urlPassword) {
			this.password = urlPassword;
		}
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
			JenkinsLogger.error("Values were provided for both environment ID and file. Please provide just one or the other.");
			return false;
		}
		
		// check whether we have neither conf id or file
		if(this.configurationFile.equals("") && this.configurationID.equals("")){
			JenkinsLogger.error("No value was provided for environment ID or file. Please provide either a valid Skytap environment ID, or a valid environment file.");
			return false;
		}

		// check whether no savefile name was provided
		if(this.urlSaveFilename.equals("")){
			JenkinsLogger.error("No value was provided for URL save file. Please provide a valid filename.");
			return false;
		}
		
		// should there be a password? was one provided?
		if(this.hasPassword && this.urlPassword.equals("")){
			JenkinsLogger.error("It was indicated the URL should have a password but none was provided. Please provide a password.");
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

	public String getUrlSaveFilename() {
		return urlSaveFilename;
	}

	public String getPermissionOption() {
		return permissionOption;
	}

	public Boolean isHasPassword() {
		return hasPassword;
	}

	public Boolean getHasPassword() {
		return hasPassword;
	}

	public String getUrlPassword() {
		return urlPassword;
	}

	public String getPortalName() {
		return portalName;
	}
	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			CreatePublishURLStep.class, "Create Sharing Portal");

}
