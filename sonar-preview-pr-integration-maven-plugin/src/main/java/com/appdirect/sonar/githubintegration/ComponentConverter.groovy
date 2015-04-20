package com.appdirect.sonar.githubintegration
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import org.eclipse.egit.github.core.CommitFile

class ComponentConverter {
	private final Map<String, String> sonarComponents
	private final List<MavenProject> reactorProjects
	private final String sonarBranch
	private final Log log

	public ComponentConverter(String sonarBranch, List<MavenProject> reactorProjects, List<CommitFile> files, Log log) {
		this.sonarComponents = [:]
		this.sonarBranch = sonarBranch
		this.reactorProjects = reactorProjects
		this.log = log

		files.each { file ->
			String path = file.filename
			log.debug("Considering file ${path}")

			String componentKey = toComponentKey(path)
			if (componentKey) {
				log.debug("Adding component ${componentKey} for file ${path}")
				this.sonarComponents[componentKey] = path
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

		MavenProject project = bestMatch.project
		File file = bestMatch.file
		String fullPath = file.getAbsolutePath()

		String sources  = project.getBasedir().getAbsolutePath()
		String relativePath = fullPath.substring(fullPath.indexOf(sources) + sources.length() + 1)
		String componentKey = "${project.groupId}:${project.artifactId}${sonarBranch ? ':' + sonarBranch : ''}:${relativePath}"

		log.debug("Component key for ${path}: ${componentKey}")

		return componentKey
	}

	private class BestMatch {
		MavenProject project
		File file
	}

	private BestMatch find(String path) {
		int longest = -1
		MavenProject bestMatch = null
		File bestMatchFile = null

		reactorProjects.each { project ->
			File baseDir = project.getBasedir().getAbsoluteFile()
			int longestSubstr = longestSubstr(path, baseDir.getAbsolutePath().replace( '\\', '/' ) )

			log.debug("Basedir: ${baseDir}, path: ${path}")
			if (longestSubstr > longest) {
				File file = new File(project.getBasedir(), path.substring(longestSubstr))
				if (file.exists()) {
					bestMatchFile = file
					bestMatch = project
					longest = longestSubstr
					log.debug('Found new best match')
				}
			}
		}

		if (bestMatch != null && bestMatchFile != null) {
			log.debug("Best match: ${bestMatch.artifactId} for ${bestMatchFile.absolutePath}")
			return new BestMatch([ project: bestMatch, file: bestMatchFile ])
		}

		return null
	}

	public static int longestSubstr(String first, String second) {
		if (!first || !second) {
			return 0
		}

		Map result = first.toCharArray().inject([ i: 0, j: 0, table: new int[first.length()][second.length()], maxLen: 0 ]) { Map a, l ->
			a = second.toCharArray().inject(a) { Map acc, r ->
				if (l == r) {
					if (acc.i == 0 || acc.j == 0) {
						acc.table[acc.i][acc.j] = 1
					} else {
						acc.table[acc.i][acc.j] = acc.table[acc.i - 1][acc.j - 1] + 1
					}

					if (acc.table[acc.i][acc.j] > acc.maxLen) {
						acc.maxLen = acc.table[acc.i][acc.j]
					}
				}

				acc << [ j: acc.j + 1 ]
			}

			a << [ i: a.i + 1, j: 0 ]
		}

		result.maxLen
	}

	public Set<String> getComponents() {
		sonarComponents.keySet()
	}

	public int size() {
		sonarComponents.size()
	}

	public String componentToPath(String componentKey) {
		sonarComponents.get(componentKey)
	}
}
