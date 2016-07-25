package org.jenkinsci.plugins.maven_artifact_choicelistprovider.nexus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;

import org.jenkinsci.plugins.maven_artifact_choicelistprovider.IVersionReader;
import org.jenkinsci.plugins.maven_artifact_choicelistprovider.ValidAndInvalidClassifier;
import org.sonatype.nexus.rest.model.NexusNGArtifact;
import org.sonatype.nexus.rest.model.NexusNGArtifactHit;
import org.sonatype.nexus.rest.model.NexusNGArtifactLink;
import org.sonatype.nexus.rest.model.NexusNGRepositoryDetail;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;

public class NexusLuceneSearchService implements IVersionReader {

	private static final String PACKAGING_ALL = "*";

	private static final String LUCENE_SEARCH_SERVICE_URI = "service/local/lucene/search";

	private static final Logger LOGGER = Logger.getLogger(NexusLuceneSearchService.class.getName());

	private final String mURL;
	private final String mGroupId;
	private final String mArtifactId;
	private final String mPackaging;
	private final ValidAndInvalidClassifier mClassifier;

	private WebResource mInstance;

	public NexusLuceneSearchService(String pURL, String pGroupId, String pArtifactId, String pPackaging) {
		this(pURL, pGroupId, pArtifactId, pPackaging, ValidAndInvalidClassifier.getDefault());
	}

	public NexusLuceneSearchService(String pURL, String pGroupId, String pArtifactId, String pPackaging,
			ValidAndInvalidClassifier pAcceptedClassifier) {
		super();
		this.mURL = pURL;
		this.mGroupId = pGroupId;
		this.mArtifactId = pArtifactId;
		this.mPackaging = pPackaging;
		this.mClassifier = pAcceptedClassifier;
	}

	void init() {
		ClientConfig config = new DefaultClientConfig();
		Client client = Client.create(config);

		client.addFilter(new HTTPBasicAuthFilter("q+LII+/k", "MWjotd7d2MJgHvmWt+OAXYaZhphi5rMZe+3jB2y2mN2r"));

		mInstance = client.resource(UriBuilder.fromUri(getURL()).build());
		mInstance = mInstance.path(LUCENE_SEARCH_SERVICE_URI);
		// String respAsString = service.path("nexus/service/local/lucene/search")
		// .queryParam("g", "com.wincornixdorf.pnc.releases").queryParam("a", "pnc-brass-maven")
		// .accept(MediaType.APPLICATION_XML).get(String.class);
		// System.out.println(respAsString);
		//
	}

	/**
	 * Search in Nexus for the artifact using the Lucene Service.
	 * https://repository.sonatype.org/nexus-indexer-lucene-plugin/default/docs/path__lucene_search.html
	 */
	public List<String> retrieveVersions() {
		if (LOGGER.isLoggable(Level.FINE)) {
			LOGGER.fine("query nexus with arguments: r:" + mURL + ", g:" + getGroupId() + ", a:" + getArtifactId()
					+ ", p:" + getPackaging() + ", c: " + getClassifier().toString());
		}

		MultivaluedMap<String, String> requestParams = new MultivaluedHashMap<String, String>();
		if (getGroupId() != "")
			requestParams.putSingle("g", getGroupId());
		if (getArtifactId() != "")
			requestParams.putSingle("a", getArtifactId());
		if (getPackaging() != "" && !PACKAGING_ALL.equals(getPackaging()))
			requestParams.putSingle("p", getPackaging());
		if (getClassifier() != null) {
			// FIXME: There is of course a better way how to do it...
			final List<String> query = new ArrayList<String>();
			for (String current : getClassifier().getValid())
				query.add(current);

			if (!query.isEmpty())
				requestParams.put("c", query);
		}

		final PatchedSearchNGResponse xmlResult = getInstance().queryParams(requestParams)
				.accept(MediaType.APPLICATION_XML).get(PatchedSearchNGResponse.class);

		// Use a Map instead of a List to filter duplicated entries and also linked to keep the order of XML response
		Set<String> retVal = new LinkedHashSet<String>();

		if (xmlResult == null) {
			LOGGER.info("response from Nexus is NULL.");
		} else if (xmlResult.getTotalCount() == 0) {
			LOGGER.info("response from Nexus does not contain any results.");
		} else {
			final Map<String, String> repoURLs = retrieveRepositoryURLs(xmlResult.getRepoDetails());

			// https://davis.wincor-nixdorf.com/nexus/content/repositories/wn-ps-us-pnc/com/wincornixdorf/pnc/releases/pnc-brass-maven/106/pnc-brass-maven-106.tar.gz
			for (NexusNGArtifact current : xmlResult.getData()) {
				final StringBuilder theBaseDownloadURL = new StringBuilder();
				// theDownloadURL.append(repoURL);
				theBaseDownloadURL.append("/");
				theBaseDownloadURL.append(current.getGroupId().replace(".", "/"));
				theBaseDownloadURL.append("/");
				theBaseDownloadURL.append(current.getArtifactId());
				theBaseDownloadURL.append("/");
				theBaseDownloadURL.append(current.getVersion());

				final StringBuilder theArtifactSuffix = new StringBuilder();
				theArtifactSuffix.append("/");
				theArtifactSuffix.append(current.getArtifactId());
				theArtifactSuffix.append("-");
				theArtifactSuffix.append(current.getVersion());

				for (NexusNGArtifactHit currentHit : current.getArtifactHits()) {
					for (NexusNGArtifactLink currentLink : currentHit.getArtifactLinks()) {
						final String repo = repoURLs.get(currentHit.getRepositoryId());

						boolean addCurrentEntry = true;
						boolean addCurrentyEntryAsFolder = false;

						// if packaging configuration is set but does not match
						if ("".equals(getPackaging())) {
							addCurrentyEntryAsFolder = true;
						} else if (PACKAGING_ALL.equals(getPackaging())) {
							// then always add
						} else if (!getPackaging().equals(currentLink.getExtension())) {
							addCurrentEntry &= false;
						}

						// check the classifier.
						if (!getClassifier().isValid(currentLink.getClassifier())) {
							addCurrentEntry &= false;
						}

						if (addCurrentEntry) {
							final String baseUrl = repo + theBaseDownloadURL.toString();
							final String artifactSuffix = theArtifactSuffix.toString();
							final String classifier = (currentLink.getClassifier() == null ? ""
									: "-" + currentLink.getClassifier());

							if (addCurrentyEntryAsFolder) {
								retVal.add(baseUrl);
							} else {
								retVal.add(baseUrl + artifactSuffix + classifier + "." + currentLink.getExtension());
							}
						}
					}
				}
			}

		}
		return new ArrayList<String>(retVal);
	}

	Map<String, String> retrieveRepositoryURLs(final List<NexusNGRepositoryDetail> pRepoDetails) {
		Map<String, String> retVal = new HashMap<String, String>();

		for (NexusNGRepositoryDetail currentRepo : pRepoDetails) {
			String theURL = currentRepo.getRepositoryURL();

			// FIXME: Repository URL can be retrieved somehow...
			theURL = theURL.replace("service/local", "content");
			retVal.put(currentRepo.getRepositoryId(), theURL);
		}
		return retVal;
	}

	public String getURL() {
		return mURL;
	}

	public String getGroupId() {
		return mGroupId;
	}

	public String getArtifactId() {
		return mArtifactId;
	}

	public String getPackaging() {
		return mPackaging;
	}

	public ValidAndInvalidClassifier getClassifier() {
		return mClassifier;
	}

	WebResource getInstance() {
		if (mInstance == null) {
			init();
		}
		return mInstance;
	}
}
