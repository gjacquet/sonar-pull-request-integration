package com.contaazul.sonarpullrequestintegration.mojo;

import java.io.File;
import java.util.List;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.eclipse.egit.github.core.CommitFile;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Lists;

public class ComponentConverter {

	private final BiMap<String, String> components = HashBiMap.create();
	private final List<MavenProject> reactorProjects;
	private final String sonarBranch;
	private final Log log;

	public ComponentConverter(String sonarBranch, List<MavenProject> reactorProjects, List<CommitFile> files, Log log) {
		this.sonarBranch = sonarBranch;
		this.reactorProjects = reactorProjects;
		this.log = log;
		for (CommitFile file : files) {
			String path = file.getFilename();
			if (log.isDebugEnabled()) {
				log.debug("Considering file " + path);
			}
			// TODO Add support for multi language projects
			//if (!path.endsWith( ".java" )) {
			//	if (log.isDebugEnabled()) {
			//		log.debug("Removing file " + path);
			//	}
			//	continue;
			//}
			String componentKey = toComponentKey( path );
			if (componentKey != null) {
				if (log.isDebugEnabled()) {
					log.debug("Adding component " + componentKey + " for file " + path);
				}
				components.put(componentKey, path);
			}
		}
	}

	private String toComponentKey(String path) {
		if (path == null) {
			log.debug("Path is null");
			return null;
		}

		BestMatch bestMatch = find( path );
		if (bestMatch == null) {
			if (log.isDebugEnabled()) {
				log.debug("Best match is null for " + path);
			}
			return null;
		}

		MavenProject project = bestMatch.project;
		File file = bestMatch.file;
		String fullPath = file.getAbsolutePath();

		String sources  = project.getBasedir().getAbsolutePath();
		String relativePath = fullPath.substring(fullPath.indexOf(sources) + sources.length() + 1);
		String componentKey = project.getGroupId() + ":" + project.getArtifactId() + ":" + sonarBranch + ":" + relativePath;

		if (log.isDebugEnabled()) {
			log.debug("Component key for " + path + ": " + componentKey);
		}

		return componentKey;
	}

	private class BestMatch {
		private MavenProject project;
		private File file;

		private BestMatch(MavenProject project, File file) {
			this.project = project;
			this.file = file;
		}
	}

	private BestMatch find(String path) {
		int longest = -1;
		MavenProject bestMatch = null;
		File bestMatchFile = null;
		for (MavenProject project : reactorProjects) {
			File baseDir = project.getBasedir().getAbsoluteFile();
			int longestSubstr = longestSubstr( path, baseDir.getAbsolutePath().replace( '\\', '/' ) );

			if (log.isDebugEnabled()) {
				log.debug("Basedir: " + baseDir + " path: " + path);
			}
			if (longestSubstr > longest) {
				File file = new File(project.getBasedir(), path.substring(longestSubstr));
				if (file.exists()) {
					bestMatchFile = file;
					bestMatch = project;
					longest = longestSubstr;
					log.debug("Found new best match");
				}
			}
		}

		if (bestMatch != null && bestMatchFile != null) {
			log.debug("Best match: " + bestMatch.getArtifactId() + " for " + bestMatchFile.getAbsolutePath());
			return new BestMatch(bestMatch, bestMatchFile);
		}

		return null;
	}

	public static int longestSubstr(String first, String second) {
		if (first == null || second == null || first.length() == 0 || second.length() == 0) {
			return 0;
		}

		int maxLen = 0;
		int fl = first.length();
		int sl = second.length();
		int[][] table = new int[fl][sl];

		for (int i = 0; i < fl; i++) {
			for (int j = 0; j < sl; j++) {
				if (first.charAt( i ) == second.charAt( j )) {
					if (i == 0 || j == 0) {
						table[i][j] = 1;
					}
					else {
						table[i][j] = table[i - 1][j - 1] + 1;
					}
					if (table[i][j] > maxLen) {
						maxLen = table[i][j];
					}
				}
			}
		}
		return maxLen;
	}

	public String[] getComponents() {
		return components.keySet().toArray(
				new String[size()]
				);
	}

	public List<String> getPaths() {
		return Lists.newArrayList( components.values() );
	}

	public String pathToComponent(String path) {
		return components.inverse().get( path );
	}

	public int size() {
		return components.size();
	}

	public String componentToPath(String componentKey) {
		return components.get( componentKey );
	}

}
