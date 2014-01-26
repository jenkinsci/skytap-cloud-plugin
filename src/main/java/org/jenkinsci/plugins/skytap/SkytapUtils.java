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

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.VariableResolver;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.Date;
import java.text.SimpleDateFormat;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FilenameUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
//
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
//
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.util.EntityUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SkytapUtils {

	/**
	 * This method is used to enable Jenkins variable expansion. The user would
	 * include Jenkins variables such as ${BUILD_ID} and these are resolved at
	 * runtime, with the expanded string being used for all plugin actions.
	 * 
	 * @param build
	 * @param targetString
	 * @return
	 */
	public static String expandEnvVars(AbstractBuild build, String targetString) {

		EnvVars env;
		String expandedString = "";

		// if value was null or empty return blank value
		if (targetString == null || targetString.equals("")) {
			return expandedString;
		}

		try {
			env = build.getEnvironment(JenkinsLogger.getListener());
			expandedString = env.expand(targetString);

		} catch (IOException e) {
			JenkinsLogger
					.error("Jenkins Environment variables could not be resolved successfully.");
		} catch (InterruptedException e) {
			JenkinsLogger
					.error("Jenkins Environment variables could not be resolved successfully.");
		} catch (NullPointerException e) {
			JenkinsLogger.error("Error. Submitted value was null or empty.");
		}

		if (!expandedString.equals("")) {
			JenkinsLogger.log("Expanding environment variable ...");
			JenkinsLogger.log(targetString + "=>" + expandedString);
		}

		return expandedString;
	}

	/**
	 * This is a utility method used to get the json object in json file used
	 * for configs, templates, etc.
	 * 
	 * @param filepath
	 * @return
	 */
	public static JsonObject getJsonObjectFromFile(String filepath) {

		JsonObject jo = null;

		try {
			BufferedReader reader = new BufferedReader(new FileReader(filepath));
			String line, results = "";
			while ((line = reader.readLine()) != null) {
				results += line;

			}
			reader.close();

			JsonParser parser = new JsonParser();
			JsonElement je = parser.parse(results);
			jo = je.getAsJsonObject();

		} catch (IOException e) {
			JenkinsLogger.error("Error retrieving JsonObject from file "
					+ filepath + ".");
			JenkinsLogger.error("Error message: " + e.getMessage());
		}

		return jo;

	}

	/**
	 * This is a utility method to help retrieve a json element from the
	 * response body.
	 * 
	 * @param jsonRespBody
	 * @return
	 */
	public static String getValueFromJsonResponseBody(String jsonRespBody,
			String key) {

		JsonParser parser = new JsonParser();
		JsonElement je = parser.parse(jsonRespBody);
		JsonObject jo = je.getAsJsonObject();
		jo = je.getAsJsonObject();

		String jsonValue = jo.get(key).getAsString();
		return jsonValue;

	}

	/**
	 * Takes the Skytap auth credentials and encodes them so they can be used in
	 * requests to the API
	 * 
	 * @param unencodedCredential
	 * @return
	 */
	private static String encodeAuthCredentials(String unencodedCredential) {

		byte[] encoded = Base64.encodeBase64(unencodedCredential.getBytes());

		String encodedCredential = new String(encoded);

		return encodedCredential;

	}

	/**
	 * Retrieves user id and auth key from the project build environment,
	 * encodes and returns encoded credential string to the user.
	 * 
	 * @param AbstractBuild
	 * @return String
	 */
	public static String getAuthCredentials(AbstractBuild build) {

		VariableResolver vr = build.getBuildVariableResolver();
		String uid = vr.resolve("userId").toString();
		String authkey = vr.resolve("authKey").toString();
		String cred = uid + ":" + authkey;

		String encodedCred = encodeAuthCredentials(cred);
		return encodedCred;

	}

	/**
	 * This method packages an http get request object, given a url and the
	 * encoded Skytap authorization token.
	 * 
	 * @param requestUrl
	 * @param AuthToken
	 * @return
	 */
	public static HttpGet buildHttpGetRequest(String requestUrl,
			String AuthToken) {

		HttpGet hg = new HttpGet(requestUrl);
		String authHeaderValue = "Basic " + AuthToken;

		hg.addHeader("Authorization", authHeaderValue);
		hg.addHeader("Accept", "application/json");
		hg.addHeader("Content-Type", "application/json");

		JenkinsLogger.log("HTTP GET Request: " + hg.toString());
		return hg;
	}

	/**
	 * This method packages an http post request object, given a url and the
	 * encoded Skytap authorization token.
	 * 
	 * @param requestUrl
	 * @param AuthToken
	 * @return
	 */
	public static HttpPost buildHttpPostRequest(String requestUrl,
			String AuthToken) {

		HttpPost hp = new HttpPost(requestUrl);
		String authHeaderValue = "Basic " + AuthToken;

		hp.addHeader("Authorization", authHeaderValue);
		hp.addHeader("Accept", "application/json");
		hp.addHeader("Content-Type", "application/json");

		JenkinsLogger.log("HTTP POST Request: " + hp.toString());

		return hp;
	}

	/**
	 * This method returns an http put request object, given a url and the
	 * encoded Skytap authorization token.
	 * 
	 * @param requestUrl
	 * @param AuthToken
	 * @return
	 */
	public static HttpPut buildHttpPutRequest(String requestUrl,
			String AuthToken) {

		HttpPut httpput = new HttpPut(requestUrl);
		String authHeaderValue = "Basic " + AuthToken;

		httpput.addHeader("Authorization", authHeaderValue);
		httpput.addHeader("Accept", "application/json");
		httpput.addHeader("Content-Type", "application/json");

		JenkinsLogger.log("HTTP PUT Request: " + httpput.toString());

		return httpput;
	}

	/**
	 * This method returns an http delete request object, given a url and the
	 * encoded Skytap authorization token.
	 * 
	 * @param requestUrl
	 * @param AuthToken
	 * @return
	 */
	public static HttpDelete buildHttpDeleteRequest(String requestUrl,
			String AuthToken) {

		HttpDelete hd = new HttpDelete(requestUrl);
		String authHeaderValue = "Basic " + AuthToken;

		hd.addHeader("Authorization", authHeaderValue);
		hd.addHeader("Accept", "application/json");
		hd.addHeader("Content-Type", "application/json");

		JenkinsLogger.log("HTTP DELETE Request: " + hd.toString());

		return hd;
	}

	/**
	 * Utility method to execute any type of http request (except delete), to
	 * catch any exceptions thrown and return the response string.
	 * 
	 * @param hr
	 * @return
	 * @throws SkytapException
	 * @throws IOException
	 * @throws ParseException
	 */
	public static String executeHttpRequest(HttpRequestBase hr)
			throws SkytapException {

		boolean retryHttpRequest = true;
		int retryCount = 1;
		String responseString = "";
		while (retryHttpRequest == true) {
			HttpClient httpclient = new DefaultHttpClient();
			//
			// Set timeouts for httpclient requests to 60 seconds
			//
			HttpConnectionParams.setConnectionTimeout(httpclient.getParams(),60000);
			HttpConnectionParams.setSoTimeout(httpclient.getParams(),60000);
			//
			responseString = "";
			HttpResponse response = null;
			try {
				Date myDate = new Date();
				SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd:HH-mm-ss");
				String myDateString = sdf.format(myDate);

				JenkinsLogger.log(myDateString + "\n" + "Executing Request: " + hr.getRequestLine());
				response = httpclient.execute(hr);

				String responseStatusLine = response.getStatusLine().toString();
				if (responseStatusLine.contains("423 Locked")) {
					retryCount = retryCount + 1;
					if (retryCount > 5) {
						retryHttpRequest = false;
						JenkinsLogger.error("Object busy too long - giving up.");
					} else {
						JenkinsLogger.log("Object busy - Retrying...");
						try {
							Thread.sleep(15000);
						} catch (InterruptedException e1) {
							JenkinsLogger.error(e1.getMessage());
						}
					}
				} else if (responseStatusLine.contains("409 Conflict")){
				
				throw new SkytapException(responseStatusLine);
				
				}else {
			
					JenkinsLogger.log(response.getStatusLine().toString());
					HttpEntity entity = response.getEntity();
					responseString = EntityUtils.toString(entity, "UTF-8");
					retryHttpRequest = false;
				}

			} catch (HttpResponseException e) {
				retryHttpRequest = false;
				JenkinsLogger.error("HTTP Response Code: " + e.getStatusCode());

			} catch (ParseException e) {
				retryHttpRequest = false;
				JenkinsLogger.error(e.getMessage());

			} catch (InterruptedIOException e) {
				Date myDate = new Date();
				SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd:HH-mm-ss");
				String myDateString = sdf.format(myDate);

				retryCount = retryCount + 1;
				if (retryCount > 5) {
					retryHttpRequest = false;
					JenkinsLogger.error("API Timeout - giving up. " + e.getMessage());
				} else {
					JenkinsLogger.log(myDateString + "\n" + e.getMessage() + "\n" + "API Timeout - Retrying...");
				}
			} catch (IOException e) {
				retryHttpRequest = false;
				JenkinsLogger.error(e.getMessage());
			} finally {
				if (response != null) {
					// response will be null if this is a timeout retry
					HttpEntity entity = response.getEntity();
					try {
						responseString = EntityUtils.toString(entity, "UTF-8");
					} catch (IOException e) {
						// JenkinsLogger.error(e.getMessage());
					}
				}

				httpclient.getConnectionManager().shutdown();
			}
		}

		return responseString;

	}

	/**
	 * Utility method used to execute an http delete. Returns the status line
	 * which can be parsed as desired by the caller.
	 * 
	 * @param hd
	 * @return
	 * @throws SkytapException 
	 */
	public static String executeHttpDeleteRequest(HttpDelete hd) {

		String responseString = "";

		HttpClient httpclient = new DefaultHttpClient();
		HttpResponse response = null;
		
		JenkinsLogger.log("Executing Request: " + hd.getRequestLine());
		
		try {
			
			response = httpclient.execute(hd);
			String statusLine = response.getStatusLine().toString();
			JenkinsLogger.log(statusLine);
			HttpEntity entity = response.getEntity();
			responseString = EntityUtils.toString(entity, "UTF-8");
			
		} catch (HttpResponseException e) {

			JenkinsLogger.error("HTTP Response Code: " + e.getStatusCode());

		} catch (ParseException e) {
			JenkinsLogger.error(e.getMessage());
		} catch (IOException e) {
			JenkinsLogger.error(e.getMessage());
		} finally {

			HttpEntity entity = response.getEntity();
			try {
				responseString = EntityUtils.toString(entity, "UTF-8");
			} catch (IOException e) {
				// JenkinsLogger.error(e.getMessage());
			}

			httpclient.getConnectionManager().shutdown();
		}

		return responseString;
	}

	/**
	 * Utility method to extract errors, if any, from the Skytap json response,
	 * and throw an exception which can be handled by the caller.
	 * 
	 * @param response
	 * @throws SkytapException
	 */
	public static void checkResponseForErrors(String response)
			throws SkytapException {

		// check skytap response body for errors
		JsonParser parser = new JsonParser();
		JsonElement je = parser.parse(response);
		JsonObject jo = null;

		if (je.isJsonNull()) {
			return;
		} else if(je.isJsonArray()) {
			return;
		} else {

			jo = je.getAsJsonObject();
		}

		je = parser.parse(response);
		jo = je.getAsJsonObject();

		if (!(jo.has("error") || jo.has("errors"))) {
			return;
		}
		
		// handle case where skytap returns an array of errors
		if (jo.has("errors")) {

			String errorString = "";

			JsonArray skytapErrors = (JsonArray) je.getAsJsonObject().get(
					"errors");

			Iterator itr = skytapErrors.iterator();
			while (itr.hasNext()) {
				JsonElement errorElem = (JsonElement) itr.next();
				String errMsg = errorElem.toString();

				errorString += errMsg + "\n";

			}

			throw new SkytapException(errorString);

		}

		if (jo.has("error")) {

			// handle case where 'error' element is null value
			if (jo.get("error").isJsonNull()) {
				return;
			}
			
			// handle case where 'error' element is a boolean
			if (jo.get("error").isJsonPrimitive()){
				
				Boolean hasError = jo.get("error").getAsBoolean();
				
				// if its false, no error, all is good
				if(!hasError){
					return;
				}
				
			}

			// handle case where 'error' element is a quoted string
			String error = jo.get("error").getAsString();

			if (!error.equals("")) {
				throw new SkytapException(error);
			}

		}

	}

	/**
	 * This method is used to obtain an id. If a file was provided, it extracts
	 * the id from there. Otherwise it just uses the id provided by the user.
	 * 
	 * @param confId
	 * @return runtimeConfigurationId
	 */
	public static String getRuntimeId(String usersId, String usersFile)
			throws FileNotFoundException {

		String runtimeID = "";

		if (!usersFile.equals("")) {

			JenkinsLogger.log("User provided file: " + usersFile);

			// read the id from the config file
			JsonObject jo = SkytapUtils.getJsonObjectFromFile(usersFile);

			// if object returned was null, reading the file has failed, so fail
			// the build step
			if (jo == null) {
				JenkinsLogger.error("Unable to read file: " + usersFile);
				throw new FileNotFoundException("Unable to read file: "
						+ usersFile);
			}

			runtimeID = jo.get("id").getAsString();

		} else {
			runtimeID = usersId;
		}

		return runtimeID;
	}
	
	/**
	 * Makes call to skytap to retrieve the id
	 * of a named project.
	 * 
	 * @param projName
	 * @param authCredentials
	 * @return projId
	 */
	public static String getProjectID(String projName, String authCredentials) {
		
		// build url
		StringBuilder sb = new StringBuilder("https://cloud.skytap.com");
		sb.append("/projects");
		
		// create http get
		HttpGet hg = SkytapUtils.buildHttpGetRequest(sb.toString(), authCredentials);
		
		// execute request
		String response;
		try {
			response = SkytapUtils.executeHttpRequest(hg);
		} catch (SkytapException e) {
			JenkinsLogger.error("Skytap Exception: " + e.getMessage());
			return "";
		}
		
		// response string will be a json array of all projects		
		JsonParser parser = new JsonParser();
		JsonElement je = parser.parse(response);
		JsonArray ja = je.getAsJsonArray();

	     Iterator itr = ja.iterator();
	      while(itr.hasNext()) {
	         JsonElement projElement = (JsonElement) itr.next();
	         String projElementName = projElement.getAsJsonObject().get("name").getAsString();
	         
	         if(projElementName.equals(projName)){
	        	 String projElementId = projElement.getAsJsonObject().get("id").getAsString();
	        	 return projElementId;
	         }

	      }
		
	    JenkinsLogger.error("No project matching name \"" + projName + "\"" + " was found.");
		return "";
	}


	/**
	 * Prepends the workspace path to a save file name as a default if user has
	 * not provided a full path.
	 * 
	 * @param build
	 * @param savefile
	 * 
	 * @return fullpath
	 * 
	 */
	public static String convertFileNameToFullPath(AbstractBuild build,
			String savefile) {

		FilenameUtils fu = new FilenameUtils();

		// if its just a filename with no path, prepend workspace dir

		if (fu.getPath(savefile).equals("")) {
			JenkinsLogger
					.log("File: " + savefile + " was specified without a path. Defaulting path to Jenkins workspace.");
			String workspacePath = SkytapUtils.expandEnvVars(build,
					"${WORKSPACE}");

			savefile = workspacePath + "/" + savefile;
			savefile = fu.separatorsToSystem(savefile);
			return savefile;

		} else {
			return fu.separatorsToSystem(savefile);
		}

	}

	/**
	 * Executes a Skytap API call in order to get the network id of the network
	 * whose name was provided by the user.
	 * 
	 * @param confId
	 * @param netName
	 * @return
	 */
	public static String getNetworkIdFromName(String confId, String netName,
			String authCredential) throws SkytapException {

		// build request url to get config info
		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(confId);
		String reqUrl = sb.toString();

		// create request
		HttpGet hg = SkytapUtils.buildHttpGetRequest(reqUrl, authCredential);

		// execute request
		String httpRespBody = "";
		httpRespBody = SkytapUtils.executeHttpRequest(hg);
		SkytapUtils.checkResponseForErrors(httpRespBody);

		// parse the response, first get the array of networks
		JsonParser parser = new JsonParser();
		JsonElement je = parser.parse(httpRespBody);
		JsonArray networkArray = (JsonArray) je.getAsJsonObject().get(
				"networks");

		JenkinsLogger.log("Searching configuration's networks for network: "
				+ netName);

		Iterator itr = networkArray.iterator();
		while (itr.hasNext()) {
			JsonElement networkElement = (JsonElement) itr.next();
			String networkElementName = networkElement.getAsJsonObject()
					.get("name").getAsString();

			JenkinsLogger.log("Network Name: " + networkElementName);

			if (networkElementName.equals(netName)) {
				String networkElementId = networkElement.getAsJsonObject()
						.get("id").getAsString();

				JenkinsLogger.log("Network Name Matched.");
				JenkinsLogger.log("Network ID: " + networkElementId);
				return networkElementId;
			}

		}

		throw new SkytapException("No network matching name \"" + netName
				+ "\"" + " is associated with configuration id " + confId + ".");
	}

}
