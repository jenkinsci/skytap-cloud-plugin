package org.jenkinsci.plugins.skytap;

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
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
	public static String encodeAuthCredentials(String unencodedCredential) {

		byte[] encoded = Base64.encodeBase64(unencodedCredential.getBytes());

		String encodedCredential = new String(encoded);

		return encodedCredential;

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

		HttpClient httpclient = new DefaultHttpClient();
		String responseString = "";
		HttpResponse response = null;

		try {

			JenkinsLogger.log("Executing Request: " + hr.getRequestLine());
			response = httpclient.execute(hr);

			JenkinsLogger.log(response.getStatusLine().toString());
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
	 * Utility method used to execute an http delete. Returns the status line
	 * which can be parsed as desired by the caller.
	 * 
	 * @param hd
	 * @return
	 */
	public static String executeHttpDeleteRequest(HttpDelete hd) {

		String responseString = "";

		HttpClient httpclient = new DefaultHttpClient();
		HttpResponse response = null;

		try {
			response = httpclient.execute(hd);
			String statusLine = response.getStatusLine().toString();
			JenkinsLogger.log(statusLine);
			HttpEntity entity = response.getEntity();
			responseString = EntityUtils.toString(entity, "UTF-8");

		} catch (ClientProtocolException e) {
			JenkinsLogger.error("HTTP Error: " + e.getMessage());
		} catch (IOException e) {
			JenkinsLogger
					.error("An error occurred executing the http request: "
							+ e.getMessage());
		}

		return "";
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

		JenkinsLogger.log("Searching configuration's networks for network: " + netName);
		
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
