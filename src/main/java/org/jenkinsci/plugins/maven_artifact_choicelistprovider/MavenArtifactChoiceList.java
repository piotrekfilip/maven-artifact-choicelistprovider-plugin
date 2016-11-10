package org.jenkinsci.plugins.maven_artifact_choicelistprovider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.maven_artifact_choicelistprovider.nexus.NexusLuceneSearchService;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;

import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jp.ikedam.jenkins.plugins.extensible_choice_parameter.ChoiceListProvider;
import jp.ikedam.jenkins.plugins.extensible_choice_parameter.ExtensibleChoiceParameterDefinition;

public class MavenArtifactChoiceList extends ChoiceListProvider implements ExtensionPoint {

	private static final Logger LOGGER = Logger.getLogger(MavenArtifactChoiceList.class.getName());

	private String url;
	private String groupId;
	private String artifactId;
	private String packaging;
	private String classifier;
	private boolean reverseOrder;
	private String credentialsId;

	private Map<String, String> mChoices;

	@DataBoundConstructor
	public MavenArtifactChoiceList(String url, String artifactId) {
		super();
		this.setArtifactId(artifactId);
		this.setUrl(url);
	}

	/**
	 * FIXME: CHANGE-1: Needs to be implemented. But currently i dont know how to update the environment variable to use
	 * the new value.
	 */
	@Override
	public void onBuildTriggeredWithValue(AbstractProject<?, ?> pJob, ExtensibleChoiceParameterDefinition pDef,
			String pOldValue) {
		String newValue = pOldValue;
		if (mChoices != null) {
			LOGGER.log(Level.INFO, "get full url for item:" + pOldValue);
			if (mChoices.containsKey(pOldValue)) {
				newValue = mChoices.get(pOldValue);
			}
		}
		LOGGER.log(Level.INFO, "target value is: " + newValue);
		// FIXME: CHANGE-1: How to update the build env variables to replace the current parameter? I dont know...
	}

	@Override
	public List<String> getChoiceList() {
		if (mChoices == null) {
			mChoices = readURL(getUrl(), getCredentialsId(), getGroupId(), getArtifactId(), getPackaging(),
					getClassifier(), getReverseOrder());
		}
		// FIXME: CHANGE-1: Return only the keys, that are shorter then the values
		// return new ArrayList<String>(mChoices.keySet());
		return new ArrayList<String>(mChoices.values());
	}

	static Map<String, String> readURL(final String pURL, final String pCredentialsId, final String pGroupId,
			final String pArtifactId, final String pPackaging, String pClassifier, final boolean pReverseOrder) {
		Map<String, String> retVal = new LinkedHashMap<String, String>();
		try {
			ValidAndInvalidClassifier classifierBox = ValidAndInvalidClassifier.fromString(pClassifier);

			IVersionReader mService = new NexusLuceneSearchService(pURL, pGroupId, pArtifactId, pPackaging,
					classifierBox);

			// If configured, set User Credentials
			final UsernamePasswordCredentialsImpl c = getCredentials(pCredentialsId);
			if (c != null) {
				mService.setCredentials(c.getUsername(), c.getPassword().getPlainText());
			}

			List<String> choices = mService.retrieveVersions();

			if (pReverseOrder)
				Collections.reverse(choices);

			retVal = toMap(choices);
		} catch (VersionReaderException e) {
			LOGGER.log(Level.INFO, "failed to retrieve versions from nexus for r:" + pURL + ", g:" + pGroupId + ", a:"
					+ pArtifactId + ", p:" + pPackaging + ", c:" + pClassifier, e);
			retVal.put("error", e.getMessage());
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, "failed to retrieve versions from nexus for r:" + pURL + ", g:" + pGroupId
					+ ", a:" + pArtifactId + ", p:" + pPackaging + ", c:" + pClassifier, e);
			retVal.put("error", "Unexpected Error: " + e.getMessage());
		}
		return retVal;
	}

	/**
	 * 
	 * @param pCredentialId
	 * @return the credentials for the ID or NULL
	 */
	static UsernamePasswordCredentialsImpl getCredentials(final String pCredentialId) {
		return CredentialsMatchers
				.firstOrNull(
						CredentialsProvider.lookupCredentials(UsernamePasswordCredentialsImpl.class,
								Jenkins.getInstance(), ACL.SYSTEM),
						CredentialsMatchers.allOf(CredentialsMatchers.withId(pCredentialId)));
	}

	@Extension
	public static class DescriptorImpl extends Descriptor<ChoiceListProvider> {

		/**
		 * the display name shown in the dropdown to select a choice provider.
		 * 
		 * @return display name
		 * @see hudson.model.Descriptor#getDisplayName()
		 */
		@Override
		public String getDisplayName() {
			return "Maven Artifact Choice Parameter";
		}

		public ListBoxModel doFillCredentialsIdItems() {
			return new StandardListBoxModel().withEmptySelection().withMatching(
					CredentialsMatchers
							.anyOf(CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)),
					CredentialsProvider.lookupCredentials(StandardCredentials.class, Jenkins.getInstance(),
							ACL.SYSTEM));
		}

		public FormValidation doCheckUrl(@QueryParameter String url) {
			if (StringUtils.isBlank(url)) {
				return FormValidation.error("The server URL cannot be empty");
			}

			return FormValidation.ok();
		}

		public FormValidation doCheckArtifactId(@QueryParameter String artifactId) {
			if (StringUtils.isBlank(artifactId)) {
				return FormValidation.error("The artifactId cannot be empty");
			}

			return FormValidation.ok();
		}

		public FormValidation doCheckPackaging(@QueryParameter String packaging) {
			if (!StringUtils.isBlank(packaging) && packaging.startsWith(".")) {
				return FormValidation.error("packaging must not start with a .");
			}

			return FormValidation.ok();
		}

		public FormValidation doCheckClassifier(@QueryParameter String classifier) {
			if (StringUtils.isBlank(classifier)) {
				FormValidation.ok("OK, will not filter for any classifier");
			}
			return FormValidation.ok();
		}

		/**
		 * Perfom testing of the parameter.
		 * 
		 * @param url
		 *            the URL
		 * @param credentialsId
		 *            the choosen credentials
		 * @param groupId
		 *            the groupId to search for
		 * @param artifactId
		 *            the artifactId to search for
		 * @param packaging
		 *            results will be limited to that packaging, i.E. tar.gz
		 * @param classifier
		 *            results will be limited to that classifier, i.E. sources
		 * @param reverseOrder
		 *            results in reversed order.
		 * @return {@link FormValidation#ok()} if validation went fine.
		 */
		public FormValidation doTest(@QueryParameter String url, @QueryParameter String credentialsId,
				@QueryParameter String groupId, @QueryParameter String artifactId, @QueryParameter String packaging,
				@QueryParameter String classifier, @QueryParameter boolean reverseOrder) {
			if (StringUtils.isEmpty(packaging) && !StringUtils.isEmpty(classifier)) {
				return FormValidation.error(
						"You have choosen an empty Packaging configuration but have configured a Classifier. Please either define a Packaging value or remove the Classifier");
			}

			try {
				final Map<String, String> entriesFromURL = readURL(url, credentialsId, groupId, artifactId, packaging,
						classifier, reverseOrder);

				if (entriesFromURL.isEmpty()) {
					return FormValidation.ok("(Working, but no Entries found)");
				}
				return FormValidation.ok(StringUtils.join(entriesFromURL.keySet(), '\n'));
			} catch (Exception e) {
				return FormValidation.error("error reading versions from url:" + e.getMessage());
			}
		}
	}

	/**
	 * Cuts of the first parts of the URL and only returns a smaller set of items.
	 * 
	 * @param pChoices
	 *            the list which is transformed to a map
	 * @return the map containing the short url as Key and the long url as value.
	 */
	public static Map<String, String> toMap(List<String> pChoices) {
		Map<String, String> retVal = new LinkedHashMap<String, String>();
		for (String current : pChoices) {
			retVal.put(current.substring(current.lastIndexOf("/") + 1), current);
		}
		return retVal;
	}

	@DataBoundSetter
	public void setUrl(String url) {
		this.url = StringUtils.trim(url);
	}

	@DataBoundSetter
	public void setGroupId(String groupId) {
		this.groupId = StringUtils.trim(groupId);
	}

	@DataBoundSetter
	public void setArtifactId(String artifactId) {
		this.artifactId = StringUtils.trim(artifactId);
	}

	@DataBoundSetter
	public void setPackaging(String packaging) {
		this.packaging = StringUtils.trim(packaging);
	}

	@DataBoundSetter
	public void setClassifier(String classifier) {
		this.classifier = StringUtils.trim(classifier);
	}

	@DataBoundSetter
	public void setReverseOrder(boolean reverseOrder) {
		this.reverseOrder = reverseOrder;
	}

	@DataBoundSetter
	public void setCredentialsId(String credentialsId) {
		this.credentialsId = credentialsId;
	}

	public String getUrl() {
		return url;
	}

	public String getGroupId() {
		return groupId;
	}

	public String getArtifactId() {
		return artifactId;
	}

	public String getPackaging() {
		return packaging;
	}

	public String getClassifier() {
		return classifier;
	}

	public boolean getReverseOrder() {
		return reverseOrder;
	}

	public String getCredentialsId() {
		return credentialsId;
	}

}