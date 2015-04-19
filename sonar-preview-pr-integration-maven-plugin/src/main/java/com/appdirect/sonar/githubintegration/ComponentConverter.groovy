package com.appdirect.sonar.githubintegration
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import org.eclipse.egit.github.core.CommitFile

import com.google.common.collect.BiMap
import com.google.common.collect.HashBiMap
import com.google.common.collect.Lists

class ComponentConverter {
	private final BiMap<String, String> sonarComponents
	private final List<MavenProject> reactorProjects
	private final String sonarBranch
	private final Log log

	public ComponentConverter(String sonarBranch, List<MavenProject> reactorProjects, List<CommitFile> files, Log log) {
		this.sonarComponents = HashBiMap.create()
		this.sonarBranch = sonarBranch
		this.reactorProjects = reactorProjects
		this.log = log

		files.each { file ->
			String path = file.filename;
			log.debug("Considering file ${path}")

			String componentKey = toComponentKey(path)
			if (componentKey) {
				log.debug("Adding component ${componentKey} for file ${path}")
				this.sonarComponents.put(componentKey, path)
			}
		}
	}

	private String toComponentKey(String path) {
		if (!path) {
			log.debug('Path is null')
			return null
		}

		BestMatch bestMatch = find(path)
		if (!bestMatch) {
			log.debug("Best match is null for ${path}")
			return null
		}

		MavenProject project = bestMatch.project;
		File file = bestMatch.file;
		String fullPath = file.getAbsolutePath();

		String sources  = project.getBasedir().getAbsolutePath();
		String relativePath = fullPath.substring(fullPath.indexOf(sources) + sources.length() + 1);
		String componentKey = "${project.groupId}:${project.artifactId}${sonarBranch ? ':' + sonarBranch : ''}:${relativePath}"

		log.debug("Component key for ${path}: ${componentKey}");

		return componentKey
	}

	private class BestMatch {
		MavenProject project;
		File file;
	}

	private BestMatch find(String path) {
		int longest = -1;
		MavenProject bestMatch = null;
		File bestMatchFile = null;

		reactorProjects.each { project ->
			File baseDir = project.getBasedir().getAbsoluteFile();
			int longestSubstr = longestSubstr(path, baseDir.getAbsolutePath().replace( '\\', '/' ) );

			log.debug("Basedir: ${baseDir}, path: ${path}");
			if (longestSubstr > longest) {
				File file = new File(project.getBasedir(), path.substring(longestSubstr));
				if (file.exists()) {
					bestMatchFile = file;
					bestMatch = project;
					longest = longestSubstr;
					log.debug('Found new best match');
				}
			}
		}

		if (bestMatch != null && bestMatchFile != null) {
			log.debug("Best match: ${bestMatch.artifactId} for ${bestMatchFile.absolutePath}");
			return new BestMatch([ project: bestMatch, file: bestMatchFile ]);

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
		return sonarComponents.keySet().toArray(
				new String[size()]
		);
	}

	public List<String> getPaths() {
		return Lists.newArrayList( sonarComponents.values() );
	}

	public String pathToComponent(String path) {
		return sonarComponents.inverse().get( path );
	}

	public int size() {
		return sonarComponents.size();
	}

	public String componentToPath(String componentKey) {
		return sonarComponents.get( componentKey );
	}
}
