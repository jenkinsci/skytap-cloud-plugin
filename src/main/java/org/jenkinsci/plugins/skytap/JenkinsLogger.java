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

import hudson.model.BuildListener;

public final class JenkinsLogger {

	private static BuildListener listener;
	private static Boolean loggingEnabled;

	public JenkinsLogger(BuildListener listener, Boolean loggingEnabled) {
		JenkinsLogger.listener = listener;
		JenkinsLogger.loggingEnabled = loggingEnabled;
	}

	public static void log(String message) {

		if (loggingEnabled) {
			listener.getLogger().println(message);
		}

	}
	
	/**
	 * These messages get logged no matter what
	 * the 'logging enabled' preference is in global settings
	 * 
	 * @param message
	 */
	public static void defaultLogMessage(String message){
		listener.getLogger().println(message);
	}

	public static void error(String error) {
		listener.getLogger().println(error);
	}

	public static BuildListener getListener() {
		return listener;
	}
}
