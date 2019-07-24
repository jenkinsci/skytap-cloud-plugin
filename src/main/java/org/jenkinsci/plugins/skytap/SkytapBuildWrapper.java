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
import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.Secret;

import java.io.IOException;
import java.util.Map;

import org.kohsuke.stapler.DataBoundConstructor;

public class SkytapBuildWrapper extends BuildWrapper {

	@Extension
	public static class DescriptorImpl extends BuildWrapperDescriptor {

		public DescriptorImpl() {
			load();
		}

		@Override
		public String getDisplayName() {
			return "Skytap Cloud Authentication Credentials";
		}

		@Override
		public boolean isApplicable(final AbstractProject<?, ?> item) {
			return true;
		}

	}

	private final String userId;
	private final Secret authKey;

	@DataBoundConstructor
	public SkytapBuildWrapper(final String userId, final Secret authKey) {
		super();
		this.userId = userId;
		this.authKey = authKey;
	}

	public String getUserId() {
		return userId;
	}

	public String getAuthKey() {
		return Secret.toString(authKey);
	}

	@Override
	  public BuildWrapper.Environment setUp(
	      @SuppressWarnings("rawtypes") final AbstractBuild build,
	      final Launcher launcher, final BuildListener listener)
	      throws IOException, InterruptedException
	  {

	       EnvVars env = build.getEnvironment(listener);
	       env.put("userId", userId);
	       env.put("authKey", Secret.toString(authKey));

	    return new Environment()
	    {
	      /* empty implementation */
	    };
	  }

	@Override
	public void makeBuildVariables(AbstractBuild build,
			Map<String, String> variables) {

		variables.put("userId", userId);
		variables.put("authKey", Secret.toString(authKey));

	}

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

}
